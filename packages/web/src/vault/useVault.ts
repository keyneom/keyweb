import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  createClock,
  datasetOf,
  emptyVault,
  type ItemField,
  type ItemRecord,
  type RemoteVaultStore,
  RemoteUnavailableError,
  type SyncStatus,
  type VaultOp,
  type VaultState,
  VaultSync,
  itemsOf,
  keyringLabel,
  keyringsOf,
  liveKeyrings,
  visibleItems,
} from "@keyweb/vault-core";
import { IndexedDbVaultStorage, peekSealedState, type VaultCipher } from "@keyweb/vault-idb";
import { createRecoveryCipher, passkeySupported, unlockVault } from "./crypto";
import {
  formatRecoveryCode,
  generateRecoverySecret,
  InvalidRecoveryCode,
  parseRecoveryCode,
} from "./recovery";
import {
  type AccountContents,
  type BackupFile,
  chooseBackupFile,
  GoogleDriveRemote,
} from "./drive";
import {
  createKeywebSharingController,
  createSharingIdentity,
  KeywebSharing,
  type BlockedJoin,
  IndexRemote,
  SharedKeyringRemote,
  type SharingIdentityLike,
  type Member,
  type PendingInvite,
  type ShareRole,
} from "./sharing";
import { grantSharedFiles } from "./sharing/picker";

export type { Member, PendingInvite, ShareRole } from "./sharing";
import type { SharingDatasetFileV1, SharingPublicKeyResponseV1 } from "@keyneom/sync-kit/sharing";
import type { SharingInvitationV1 } from "@keyneom/sync-kit/sharing";
import { importOperations } from "./keepass";
import type { AccountPlan } from "./account-plan";
import { prepareFile } from "./attachments";
import type { ImportPreview, UngroupedDestination } from "./keepass";

/**
 * A remote for when encrypted backup has not been configured for this build.
 * The app stays fully usable local-first and says so plainly rather than
 * pretending to back anything up.
 */
class UnconfiguredRemote implements RemoteVaultStore {
  async read(): Promise<never> {
    throw new RemoteUnavailableError("Encrypted backup is not set up yet.");
  }
  async write(): Promise<never> {
    throw new RemoteUnavailableError("Encrypted backup is not set up yet.");
  }
}

const CLIENT_ID = import.meta.env["VITE_GOOGLE_WEB_CLIENT_ID"] ?? "";
const BACKUP_CONFIGURED = Boolean(CLIENT_ID);

/** A stable per-device id, so causal ordering survives a reload. */
function deviceNode(): string {
  const key = "keyweb:device-node";
  const existing = localStorage.getItem(key);
  if (existing) return existing;
  const created = crypto.randomUUID().slice(0, 8);
  localStorage.setItem(key, created);
  return created;
}

export type VaultPhase = "checking" | "locked" | "unlocking" | "ready" | "unsupported";

export type VaultApi = {
  phase: VaultPhase;
  /** True when this device has no vault yet, so unlocking means setting up. */
  firstRun: boolean;
  error: string | null;
  state: VaultState;
  status: SyncStatus;
  backupConfigured: boolean;
  items: ItemRecord[];
  /**
   * Keyrings somebody shared with this person as a reader.
   *
   * Empty unless something has actually been shared, so nothing about the
   * ordinary app changes. The screens use it to stop an edit that would be
   * accepted here and refused at the file — which would look exactly like
   * saving, and never arrive.
   */
  readOnlyKeyrings: ReadonlySet<string>;
  /** Keyrings someone else can read. Not the same as having a file: they all do. */
  sharedKeyrings: ReadonlySet<string>;
  unlock(options?: { quiet?: boolean }): Promise<void>;
  /** Open a vault that already exists in Drive, onto a device that has none. */
  restore(): Promise<void>;
  /** Open a backup with the printed code, when the passkey is gone. */
  restoreWithCode(code: string): Promise<void>;
  /**
   * The printed recovery code, surfaced once just after setup so it can be
   * written down. Null at every other time: it is never re-derivable for
   * display, because holding it ready would defeat the point of printing it.
   */
  newRecoveryCode: string | null;
  dismissRecoveryCode(): void;
  /**
   * True when this backup is already protected by a code this device does not
   * hold — a second device, or a reinstall. Until the code is supplied, this
   * device cannot keep the recovery copy current, so the copy in Drive stays
   * frozen at whatever the code-holding device last published.
   */
  recoveryNeedsCode: boolean;
  /** Prove the existing code, then keep the recovery copy current from here. */
  adoptRecoveryCode(code: string): Promise<void>;
  /**
   * What the signed-in Google account holds, without opening any of it.
   *
   * Null until something asks. It exists so a screen that cannot show the
   * vault can still answer "is my data gone?", which is the only question
   * somebody actually has when a password manager comes up empty.
   */
  accountContents: AccountContents | null;
  /** One line describing the backup file, for comparing against the phone. */
  describeBackupFile(): Promise<string>;
  /**
   * Every vault file in the account, when there is more than one to choose
   * between. Empty until something has looked.
   */
  backupFiles: BackupFile[];
  /** Look again at what is in the account. */
  refreshBackupFiles(): Promise<void>;
  /** Throw one away. Never the one in use; Drive keeps it in the bin. */
  deleteBackupFile(fileId: string): Promise<void>;
  /** Say which of them is the real vault, and carry on with it. */
  chooseBackupFile(fileId: string): Promise<void>;
  lock(): void;
  syncNow(): Promise<void>;
  /**
   * Save one password. Throws rather than failing quietly, and reports the
   * keyring it ended up on, which is not always the one that was asked for —
   * see the implementation.
   */
  saveItem(input: {
    itemId?: string;
    keyringId: string;
    fields: Partial<Record<ItemField, string>>;
  }): Promise<{ itemId: string; keyringId: string; keyringName: string }>;
  deleteItem(itemId: string): Promise<void>;
  /** Write scanned second-factor codes where their plans say they go. */
  addScannedCodes(
    plans: AccountPlan[],
    destination: { keyringId: string } | { newKeyringName: string },
  ): Promise<void>;
  moveItem(itemId: string, keyringId: string): Promise<void>;
  deleteItems(itemIds: string[]): Promise<void>;
  moveItems(itemIds: string[], keyringId: string): Promise<void>;
  /** Deletes the keyring *and* the passwords in it. */
  deleteKeyring(keyringId: string): Promise<void>;
  addKeyring(name: string): Promise<string>;
  /** Attach a file to a password. Throws `FileTooBig` past the size ceiling. */
  attachFile(itemId: string, file: File): Promise<void>;
  /** Take a file off, and out of the vault if nothing else references it. */
  removeAttachment(itemId: string, blobId: string): Promise<void>;
  /** Copy a parsed KeePass file in. Returns how many entries landed. */
  importKeePass(preview: ImportPreview, ungrouped: UngroupedDestination): Promise<number>;
  /**
   * Sharing, or null when this build has no Google client and so no Drive.
   *
   * Null rather than a stubbed object that throws: a keyring can only be shared
   * through a Drive file, so a build without one cannot share at all, and the
   * screens should not offer a button that apologises when pressed.
   */
  sharing: SharingApi | null;
  /** Shares that cannot use the keyring id in the file. */
  blockedJoins: BlockedJoin[];
  /** File one of those under a fresh id, leaving the private keyring alone. */
  adoptBlockedJoin(datasetId: string): Promise<void>;
};

/** What the sharing screens need, with the identity and controller already wired. */
export type SharingApi = {
  myFingerprint(): Promise<string>;
  shareKeyring(input: {
    keyringId: string;
    email: string;
    role: ShareRole;
  }): Promise<{ link: string; exchangeId: string }>;
  /** One invitation, one link, every keyring named. */
  shareKeyrings(input: {
    keyringIds: string[];
    email: string;
    role: ShareRole;
  }): Promise<{ link: string; exchangeId: string }>;
  joinFromLink(input: {
    invitation: SharingInvitationV1;
    files: SharingDatasetFileV1[];
    label: string | null;
  }): Promise<{ link: string }>;
  previewResponse(response: SharingPublicKeyResponseV1): Promise<{
    label: string;
    email: string;
    fingerprint: string;
  }>;
  acceptResponse(response: SharingPublicKeyResponseV1): Promise<{
    label: string;
    email: string;
    fingerprint: string;
  }>;
  members(datasetId: string): Promise<Member[]>;
  setRole(input: { datasetId: string; keyId: string; role: ShareRole }): Promise<void>;
  revoke(input: { datasetId: string; keyId: string }): Promise<void>;
  /** Hand a keyring to somebody already on it. Returns the link to send them. */
  proposeOwnership(input: { datasetId: string; keyId: string; email: string }): Promise<string>;
  /** Take a keyring over, from a link somebody sent. */
  acceptOwnership(payload: unknown): Promise<void>;
  pendingInvites(keyringId?: string): Promise<PendingInvite[]>;
  cancelInvite(exchangeId: string): Promise<void>;
  /** Stop sharing a keyring of your own and bring its passwords home. */
  stopSharing(keyringId: string): Promise<void>;
  /** Stop carrying a keyring somebody else shared. */
  leave(keyringId: string): Promise<void>;
};

/**
 * Moves sealed envelopes around without decrypting them.
 *
 * The probe paths need the raw envelope — to read its salt, or to hand it to a
 * recovery cipher — and must not hold a key capable of opening it.
 */
const passthroughCipher: VaultCipher = {
  sealState: async (value) => value,
  openState: async (value) => value as VaultState,
  sealOp: async (value) => value,
  openOp: async (value) => value as never,
};

/** Does the backup already carry a recovery copy someone may have printed? */
async function backupAlreadyHasRecovery(): Promise<boolean> {
  try {
    const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
    return (await probe.fetchRecoverySealed()) !== null;
  } catch {
    // Unreachable Drive must not be read as "no code exists", because that
    // would mint a new one and retire the printed sheet.
    return true;
  }
}

export function useVault(): VaultApi {
  const [phase, setPhase] = useState<VaultPhase>("checking");
  const [firstRun, setFirstRun] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accountContents, setAccountContents] = useState<AccountContents | null>(null);
  const [backupFiles, setBackupFiles] = useState<BackupFile[]>([]);
  const [state, setState] = useState<VaultState>(emptyVault);
  const [status, setStatus] = useState<SyncStatus>({
    pending: 0,
    lastPublishedAt: null,
    lastError: null,
    syncing: false,
  });

  const [sharedKeyrings, setSharedKeyrings] = useState<ReadonlySet<string>>(
    () => new Set<string>(),
  );
  const [readOnlyKeyrings, setReadOnlyKeyrings] = useState<ReadonlySet<string>>(
    () => new Set<string>(),
  );
  const [newRecoveryCode, setNewRecoveryCode] = useState<string | null>(null);
  /** This backup already has a recovery code, and it is not on this device. */
  const [recoveryNeedsCode, setRecoveryNeedsCode] = useState(false);
  const syncRef = useRef<VaultSync | null>(null);
  const lockRef = useRef<(() => void) | null>(null);
  const storageRef = useRef<IndexedDbVaultStorage | null>(null);
  const cipherRef = useRef<VaultCipher | null>(null);
  const remoteRef = useRef<GoogleDriveRemote | null>(null);
  const sharingRef = useRef<KeywebSharing | null>(null);
  const identityRef = useRef<SharingIdentityLike | null>(null);
  const [sharing, setSharing] = useState<SharingApi | null>(null);
  const [blockedJoins, setBlockedJoins] = useState<BlockedJoin[]>([]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      if (!passkeySupported()) {
        if (!cancelled) setPhase("unsupported");
        return;
      }
      const sealed = await peekSealedState();
      if (cancelled) return;
      setFirstRun(sealed === null);
      setPhase("locked");
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /** Re-read everything after a sharing change moved passwords between files. */
  const refreshFromEngine = useCallback(async () => {
    const sync = syncRef.current;
    const engine = sharingRef.current;
    if (!sync) return;
    const next = await sync.state();
    setState(next);
    setStatus({ ...sync.status() });
    if (engine) {
      try {
        setReadOnlyKeyrings(await engine.readOnlyKeyrings(next));
        setSharedKeyrings(await engine.sharedKeyrings(next));
      } catch {
        // Leave the last answer rather than guessing a more permissive one.
      }
    }
  }, []);

  const refresh = useCallback((next: VaultState) => {
    setState(next);
    const sync = syncRef.current;
    if (sync) setStatus({ ...sync.status() });
  }, []);

  /**
   * Pick up a shared keyring that has become readable.
   *
   * Run after a sync rather than on a timer, because "readable" changes only
   * when Drive says so — and by then there is already a token in hand, so this
   * never causes a sign-in popup nobody asked for.
   */
  const adoptShared = useCallback(async () => {
    const engine = sharingRef.current;
    const sync = syncRef.current;
    if (!engine || !sync) return false;
    let adopted = false;
    try {
      adopted = (await engine.adoptJoinedKeyrings()).length > 0;
      setBlockedJoins(engine.blockedJoins());
    } catch {
      // A share that is not ready yet is the normal case, not an error worth
      // showing. It will be ready on some later sync.
    }
    try {
      const current = await sync.state();
      setReadOnlyKeyrings(await engine.readOnlyKeyrings(current));
      setSharedKeyrings(await engine.sharedKeyrings(current));
    } catch {
      // Not knowing must not make an editable keyring look read-only.
    }
    return adopted;
  }, []);

  /** Publish in the background; the UI already showed "Saved" from the commit. */
  const backgroundSync = useCallback(() => {
    const sync = syncRef.current;
    if (!sync || !BACKUP_CONFIGURED) return;
    void sync.sync().then(
      async () => {
        let changed = await adoptShared();
        // Every keyring into a file of its own, after the sync rather than
        // before it, so a keyring another device already moved is seen as
        // moved rather than moved a second time. Quiet on failure: it is
        // idempotent and simply runs again on the next sync.
        try {
          if ((await sharingRef.current?.ensureOwnFiles())?.length) changed = true;
        } catch {
          // Offline, or the sharing key is not unlocked yet.
        }
        if (changed) setState(await sync.state());
        setStatus({ ...sync.status() });
      },
      () => setStatus({ ...sync.status() }),
    );
  }, [adoptShared]);

  /** Shared tail of unlock and restore: open storage and start the engine. */
  const start = useCallback(
    async (cipher: VaultCipher, lock: () => void, seedFromRemote: boolean) => {
      lockRef.current = lock;
      cipherRef.current = cipher;
      const storage = await IndexedDbVaultStorage.open({ cipher });
      storageRef.current = storage;
      const clock = createClock({ node: deviceNode(), resume: await storage.readClock() });

      // The recovery secret is kept sealed under the vault key, so every
      // publish can reseal the recovery copy and it never drifts out of date.
      // It is minted once, shown once, and after that only ever used, never
      // displayed again.
      /*
       * No recovery copy to maintain any more.
       *
       * The vault root now lives in a file of the same kind as every keyring —
       * encrypted once, its key wrapped to you — so there is no second copy
       * sealed to the printed code for this browser to keep current, and
       * nothing to ask for the code in order to do it. The code locks your
       * identity instead, and it is the device that minted it that writes
       * that lock.
       *
       * A code this browser already holds is still loaded, because it can
       * open the old file's recovery copy during the one-time move off it.
       */
      let recoveryCipher: VaultCipher | undefined;
      if (BACKUP_CONFIGURED) {
        const storedSecret = await storage.readMeta("recovery-secret");
        if (storedSecret) {
          const raw = await cipher.openOp(storedSecret);
          recoveryCipher = await createRecoveryCipher(
            Uint8Array.from(raw as unknown as number[]),
            await storage.readMeta("recovery-envelope"),
          );
        }
      }

      const legacy = BACKUP_CONFIGURED
        ? new GoogleDriveRemote({
            clientId: CLIENT_ID,
            cipher,
            ...(recoveryCipher ? { recoveryCipher } : {}),
          })
        : null;
      remoteRef.current = legacy;

      // Sharing is wired in before the engine starts, because the engine asks
      // for a dataset's remote the first time it syncs one — and a keyring
      // bound on another device can already be waiting in the vault it is
      // about to pull down.
      //
      // One identity object, not one per user of it. It holds the unlocked
      // keypair for the session and serialises the passkey prompt; two of them
      // would mean two prompts for one operation, and the second would arrive
      // while the first was still on screen.
      identityRef.current = null;
      /*
       * One identity per person, on every device they own.
       *
       * Wrapped with the passkey and kept in the Google account's app-data
       * folder, which is what makes it follow the account rather than the
       * device. sync-kit ships both halves and easy-bc has used them from the
       * start; Keyweb had the folder and wrapped with the printed recovery
       * code instead, so a browser that had never been handed that code could
       * read every password and still not touch a shared keyring.
       */
      const identity = BACKUP_CONFIGURED ? createSharingIdentity() : null;
      identityRef.current = identity;
      const controller = identity ? createKeywebSharingController(identity) : null;
      const datasetRemotes = new Map<string, SharedKeyringRemote>();
      // The root publishes to the index file, reading the old one once if no
      // device has moved off it yet. Without sharing there is no identity to
      // wrap an index to, so it stays where it was.
      const remote: RemoteVaultStore = controller
        ? new IndexRemote(controller, legacy)
        : (legacy ?? new UnconfiguredRemote());
      const sync = new VaultSync({
        storage,
        remote,
        clock,
        remoteFor: (documentId) => {
          if (!controller || documentId === "") return documentId === "" ? remote : null;
          const existing = datasetRemotes.get(documentId);
          if (existing) return existing;
          // Held rather than rebuilt, so the "is this file there" answer is
          // not re-derived on every sync of every shared keyring.
          const created = new SharedKeyringRemote(controller, documentId);
          datasetRemotes.set(documentId, created);
          return created;
        },
      });
      syncRef.current = sync;

      if (controller && identity) {
        const engine = new KeywebSharing({ sync, controller, identity, store: storage });
        sharingRef.current = engine;
        setSharing(sharingApi(engine, sync, refreshFromEngine));
      }

      let current = await sync.state();
      if (seedFromRemote) {
        // Restoring: pull the backup down before deciding the vault is empty.
        await sync.sync();
        current = await sync.state();
      }
      // First run: give people somewhere to put things rather than an empty
      // screen with no obvious next step.
      // Live ones. Counting records meant a vault whose only keyring had been
      // deleted started with nowhere to put anything, and every save from then
      // on aimed at a keyring that was not there.
      if (liveKeyrings(current).length === 0) {
        current = await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
      }
      setState(current);
      setStatus({ ...sync.status() });
      setPhase("ready");
      setFirstRun(false);
      if (!seedFromRemote) backgroundSync();
    },
    [backgroundSync],
  );

  /**
   * Adopt the recovery code that already protects this backup.
   *
   * Verified by actually opening the remote recovery envelope with it, not by
   * shape: accepting an unverified code would leave the device believing it can
   * keep the recovery copy current when it cannot.
   */
  const adoptRecoveryCode = useCallback(
    async (code: string) => {
      const sync = syncRef.current;
      if (!sync) throw new Error("Unlock Keyweb first.");
      const secret = parseRecoveryCode(code);
      const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
      const sealed = await probe.fetchRecoverySealed();
      if (!sealed) throw new InvalidRecoveryCode("This backup has no recovery copy yet.");
      const viaCode = await createRecoveryCipher(secret, sealed);
      // Throws if the code is wrong, which is the whole point of the check.
      await viaCode.openState(sealed);

      const storage = storageRef.current;
      const cipher = cipherRef.current;
      if (storage && cipher) {
        await storage.writeMeta("recovery-secret", await cipher.sealOp([...secret] as never));
        await storage.writeMeta("recovery-envelope", sealed);
      }
      setRecoveryNeedsCode(false);
      // Reseal the recovery copy now rather than at the next unlock, so the
      // window where it is stale closes immediately.
      remoteRef.current?.setRecoveryCipher(viaCode);
      await sync.sync();
    },
    [],
  );

  const describe = useCallback((cause: unknown): string => {
    // A cancelled passkey prompt is the common case and is not an error worth
    // alarming anyone about.
    if (cause instanceof DOMException && cause.name === "NotAllowedError") {
      return "That was cancelled. Your vault is still locked.";
    }
    return cause instanceof Error ? cause.message : "Keyweb couldn't open your vault.";
  }, []);

  /**
   * `quiet` is for the attempt nobody asked for.
   *
   * A browser may refuse a passkey prompt that did not come from a tap —
   * Safari requires one — and it refuses with the same `NotAllowedError` a
   * person gets for dismissing the prompt themselves, so the two cannot be
   * told apart. Reporting either would mean greeting Safari users with an
   * error they did nothing to cause. The quiet attempt therefore fails into
   * the ordinary locked screen, where the button says what to do next.
   */
  const unlock = useCallback(
    async ({ quiet = false }: { quiet?: boolean } = {}) => {
      setPhase("unlocking");
      setError(null);
      try {
        const sealed = await peekSealedState();
        const { cipher, lock } = await unlockVault(sealed);
        await start(cipher, lock, false);
      } catch (cause) {
        if (!quiet) setError(describe(cause));
        setPhase("locked");
        // Whatever went wrong, show what the account holds and which files are
        // in it. Both answer questions somebody has at exactly this moment,
        // and neither needs a key.
        if (!quiet) {
          void describeAccount();
          void listBackupFiles();
        }
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [describe, start],
  );

  /**
   * Restore onto a device that has no vault of its own.
   *
   * The key is derived from the passkey *and the salt recorded in the backup
   * envelope*, so the envelope has to be fetched before unlocking. Minting a
   * fresh key locally would produce a different key that could never read the
   * backup — which is exactly how a "restore" silently produces an empty vault.
   */
  const restore = useCallback(async () => {
    setPhase("unlocking");
    setError(null);
    try {
      // Only the raw envelope is needed here, and fetching it never decrypts.
      const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
      // Whatever happens next, say what is in the account. An empty screen and
      // an empty account are opposite situations that look identical.
      void probe
        .describeContents()
        .then(setAccountContents)
        .catch(() => undefined);
      void listBackupFiles();

      const sealed = await probe.fetchSealedState();
      if (!sealed) {
        /*
         * A backup with no browser key in it is not the same as no backup.
         *
         * A vault made on a phone that has no passkey for the backup holds
         * only the recovery envelope. Telling that person "there's no backup
         * in that account" is false and sends them looking for the wrong
         * problem, so the two cases are separated here
         * even though it costs a second round trip on a path nobody takes
         * twice.
         */
        const onlyRecovery = await probe.fetchRecoverySealed();
        setError(
          onlyRecovery
            ? "This backup was made on your phone, which can't create a key for this " +
              "browser. Use your recovery code below — just this once. After that this " +
              "browser unlocks with your face, fingerprint or PIN like the phone does."
            : "There's no Keyweb backup in that Google account yet.",
        );
        setPhase("locked");
        return;
      }
      const { cipher, lock } = await unlockVault(sealed);
      await start(cipher, lock, true);
    } catch (cause) {
      setError(describe(cause));
      setPhase("locked");
    }
  }, [describe, start]);

  /**
   * Last resort: open the backup using the printed code.
   *
   * This path exists precisely because the passkey is unavailable, so it must
   * not require one. It rebuilds the vault locally under a fresh passkey once
   * the code has proved itself.
   */
  const restoreWithCode = useCallback(
    async (code: string) => {
      setPhase("unlocking");
      setError(null);
      try {
        const secret = parseRecoveryCode(code);
        const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
        const sealed = await probe.fetchRecoverySealed();
        if (!sealed) {
          setError("That Google account has no Keyweb backup that a code can open.");
          setPhase("locked");
          return;
        }
        const viaCode = await createRecoveryCipher(secret, sealed);
        const recovered = await viaCode.openState(sealed);

        /*
         * Reuse this browser's passkey when it has one.
         *
         * This always passed null, which mints a *new* credential — fine when
         * the path could only be reached on a first run, and wrong now that
         * somebody whose browser already has a vault can reach it. A second
         * credential for the same vault leaves the first one orphaned, and the
         * envelope it sealed unopenable by the browser that wrote it.
         */
        const { cipher, lock } = await unlockVault(await peekSealedState());
        const storage = await IndexedDbVaultStorage.open({ cipher });
        await storage.applyRemote(recovered);
        /*
         * Keep the code, so this browser can rewrite the recovery copy too.
         *
         * Without it the browser can only ever seal the passkey envelope and
         * the phone can only ever seal the recovery one, so whichever device
         * wrote last leaves the other's copy stale — and the stale one is read
         * as current, which is worse than an error. The code was just proved
         * against the live envelope a few lines above, so this is storing
         * something already verified rather than something believed.
         */
        await storage.writeMeta("recovery-secret", await cipher.sealOp([...secret] as never));
        // The envelope too, not just the code. The key is derived from the
        // code *and the salt recorded in the envelope*, so without this the
        // next unlock mints a fresh salt, derives a key that opens nothing,
        // and this browser silently loses the ability to reseal the copy it
        // just proved it could open.
        await storage.writeMeta("recovery-envelope", sealed);
        await start(cipher, lock, false);
      } catch (cause) {
        setError(describe(cause));
        setPhase("locked");
      }
    },
    [describe, start],
  );

  /**
   * What this browser is looking at, in one line a person can read back.
   *
   * Exists for the same reason the phone's does: "both say they are synced and
   * show different things" is unanswerable from either side and obvious from
   * the two read-outs side by side.
   */
  /**
   * Offer the choice rather than only refusing to make it.
   *
   * Listed whenever reaching the backup failed, because that is the only
   * moment somebody needs it — and refusing without showing them what the
   * alternatives are leaves them with a true statement they can do nothing
   * with.
   */
  const describeAccount = useCallback(async () => {
    if (!BACKUP_CONFIGURED) return;
    const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
    await probe.describeContents().then(setAccountContents).catch(() => undefined);
  }, []);

  const listBackupFiles = useCallback(async () => {
    if (!BACKUP_CONFIGURED) return;
    const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
    setBackupFiles(await probe.listBackupFiles().catch(() => []));
  }, []);

  /**
   * Look at what is in this Google account, whenever somebody asks.
   *
   * The list used to appear only when something had already gone wrong and
   * Keyweb refused to choose between two files. That is the worst moment to
   * meet it and the only one — so a person who suspected they had a stray file
   * had no way to look, and no way to be rid of it.
   */
  const refreshBackupFiles = useCallback(async () => {
    await listBackupFiles();
  }, [listBackupFiles]);

  const deleteBackupFile = useCallback<VaultApi["deleteBackupFile"]>(
    async (fileId) => {
      const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
      await probe.deleteBackupFile(fileId);
      await listBackupFiles();
    },
    [listBackupFiles],
  );

  const chooseBackupFileAndRetry = useCallback<VaultApi["chooseBackupFile"]>(
    async (fileId) => {
      chooseBackupFile(fileId);
      setBackupFiles([]);
      setError(null);
      // Straight back into the flow that failed, rather than asking somebody
      // who just answered a question to also work out what to press next.
      await unlock({ quiet: true }).catch(() => undefined);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const describeBackupFile = useCallback(async () => {
    if (!BACKUP_CONFIGURED) return "Encrypted backup is not set up in this build.";
    const probe = new GoogleDriveRemote({ clientId: CLIENT_ID, cipher: passthroughCipher });
    const items = visibleItems(state).length;
    try {
      return `${items} here · ${await probe.describeFile()}`;
    } catch (cause) {
      return cause instanceof Error ? cause.message : "Couldn't read the backup file.";
    }
  }, [state]);

  const lock = useCallback(() => {
    lockRef.current?.();
    lockRef.current = null;
    syncRef.current = null;
    cipherRef.current = null;
    storageRef.current = null;
    remoteRef.current = null;
    sharingRef.current = null;
    identityRef.current?.clear();
    identityRef.current = null;
    setSharing(null);
    setBlockedJoins([]);
    setState(emptyVault());
    setPhase("locked");
  }, []);

  const adoptBlockedJoin = useCallback(async (datasetId: string) => {
    const engine = sharingRef.current;
    const sync = syncRef.current;
    if (!engine || !sync) throw new Error("Keyweb is locked.");
    await engine.adoptAsNewKeyring(datasetId);
    setBlockedJoins(engine.blockedJoins());
    setState(await sync.state());
    setStatus({ ...sync.status() });
    void name;
  }, []);

  const syncNow = useCallback(async () => {
    const sync = syncRef.current;
    if (!sync) return;
    await sync.sync();
    await adoptShared();
    setStatus({ ...sync.status() });
    setState(await sync.state());
  }, [adoptShared]);

  /**
   * Save one password, and be sure it is actually in the vault afterwards.
   *
   * Three things here exist because each of them, on its own, was enough to
   * make a password vanish while the screen said "Saved on this device".
   *
   * A locked vault used to return quietly. The caller had no way to tell that
   * apart from a save, so it showed the success it had already decided on and
   * went back to a list that would never contain the password. Throwing is the
   * whole fix: nothing downstream has to remember to check.
   *
   * A keyring id that matches no keyring used to be written anyway. The item
   * was real, synced and backed up, and no screen on either platform would
   * show it. The list no longer hides such an item, but the better answer is
   * not to make one, so a save aimed at a keyring that is gone lands in a
   * keyring that is not — the first one there is, or a new one if this vault
   * somehow has none — and the caller is told where it went.
   *
   * And the result is read back. A save that reports success without the
   * password being in the state it returns is a bug somewhere further down,
   * and the person in front of it should hear about it at the moment it
   * happens rather than the next time they go looking for that password.
   */
  const saveItem = useCallback<VaultApi["saveItem"]>(
    async (input) => {
      const sync = syncRef.current;
      if (!sync) throw new Error("Keyweb is locked. Unlock it and try saving again.");

      const before = await sync.state();
      const wanted = keyringsOf(before)[input.keyringId];
      const itemId = input.itemId ?? crypto.randomUUID();
      let keyringId = input.keyringId;
      let next: VaultState;

      if (wanted !== undefined && !wanted.deleted.value) {
        next = await sync.putItem({ ...input, itemId });
      } else {
        const ops: VaultOp[] = [];
        // Never a keyring shared with this browser to look at: a save rescued
        // into one of those is a save the owner never accepts.
        const fallback = liveKeyrings(before).find((ring) => !readOnlyKeyrings.has(ring.id));
        keyringId = fallback?.id ?? crypto.randomUUID();
        if (!fallback) {
          const { opId, ts } = sync.stamp();
          ops.push({ kind: "keyring.put", opId, ts, keyringId, name: "Just mine" });
        }
        const { opId, ts } = sync.stamp();
        ops.push({ kind: "item.put", opId, ts, itemId, keyringId, fields: input.fields });
        next = await sync.commitAll(ops);
      }

      refresh(next);
      backgroundSync();

      if (itemsOf(next)[itemId] === undefined) {
        throw new Error("Keyweb could not save that password. Nothing was changed.");
      }
      return { itemId, keyringId, keyringName: keyringLabel(next, keyringId) };
    },
    [refresh, backgroundSync, readOnlyKeyrings],
  );

  /**
   * Write scanned second-factor codes where their plans say they go.
   *
   * The check is not belt and braces. An item holds one `otp` field, so two
   * puts on one item resolve to the later one and lose the other — exactly the
   * failure the planner exists to prevent — and this is the last place before
   * the writes where it can still be stopped.
   */
  const addScannedCodes = useCallback<VaultApi["addScannedCodes"]>(
    async (plans, destination) => {
      const sync = syncRef.current;
      if (!sync) return;

      const targets = plans.map((plan) => plan.existingItemId).filter((id) => id !== null);
      if (new Set(targets).size !== targets.length) {
        throw new Error("Two codes were pointed at the same password.");
      }

      const ops: VaultOp[] = [];
      let keyringId: string;
      if ("keyringId" in destination) {
        keyringId = destination.keyringId;
      } else {
        const name = destination.newKeyringName.trim();
        const existing = Object.values(state.keyrings).find(
          (ring) => !ring.deleted.value && ring.name.value === name,
        );
        if (existing) {
          keyringId = existing.id;
        } else {
          keyringId = crypto.randomUUID();
          const { opId, ts } = sync.stamp();
          ops.push({ kind: "keyring.put", opId, ts, keyringId, name });
        }
      }

      for (const plan of plans) {
        const { opId, ts } = sync.stamp();
        if (plan.existingItemId !== null) {
          // Only the code. Nothing else about the password they already have
          // is this QR code's business.
          ops.push({
            kind: "item.put",
            opId,
            ts,
            itemId: plan.existingItemId,
            keyringId: state.items[plan.existingItemId]?.keyring.value ?? keyringId,
            fields: { otp: plan.account.otp },
          });
        } else {
          ops.push({
            kind: "item.put",
            opId,
            ts,
            itemId: crypto.randomUUID(),
            keyringId,
            fields: {
              title: plan.account.issuer || plan.account.name || "Second-factor code",
              ...(plan.account.name ? { username: plan.account.name } : {}),
              otp: plan.account.otp,
            },
          });
        }
      }

      refresh(await sync.commitAll(ops));
      backgroundSync();
    },
    [state.keyrings, state.items, refresh, backgroundSync],
  );

  const deleteItem = useCallback(
    async (itemId: string) => {
      const sync = syncRef.current;
      if (!sync) return;
      refresh(await sync.deleteItem(itemId));
      backgroundSync();
    },
    [refresh, backgroundSync],
  );

  const moveItem = useCallback(
    async (itemId: string, keyringId: string) => {
      const sync = syncRef.current;
      if (!sync) return;
      refresh(await sync.moveItem(itemId, keyringId));
      backgroundSync();
    },
    [refresh, backgroundSync],
  );

  /**
   * Delete several passwords in one gesture.
   *
   * One write, not one per password. Each op is still stamped by the live
   * clock and queued individually, so the outbox and the merge behave exactly
   * as they always did — but the vault is read, re-encrypted and stored once.
   */
  const deleteItems = useCallback(
    async (itemIds: string[]) => {
      const sync = syncRef.current;
      if (!sync || itemIds.length === 0) return;
      refresh(await sync.deleteItems(itemIds));
      backgroundSync();
    },
    [refresh, backgroundSync],
  );

  /** Move several passwords to one keyring. */
  const moveItems = useCallback(
    async (itemIds: string[], keyringId: string) => {
      const sync = syncRef.current;
      if (!sync || itemIds.length === 0) return;
      refresh(await sync.moveItems(itemIds, keyringId));
      backgroundSync();
    },
    [refresh, backgroundSync],
  );

  /**
   * Delete a keyring and the passwords in it.
   *
   * The passwords go too, deliberately. Deleting only the keyring leaves them
   * in storage and in the Drive backup forever — invisible, because
   * `visibleItems` hides anything whose keyring is gone, but still there. A
   * password manager should not keep passwords a person believes they deleted.
   *
   * One atomic write, so the vault is never left holding half of it — which
   * also removes the question of what an interrupted bulk delete leaves
   * behind, since it can no longer be interrupted partway.
   */
  const deleteKeyring = useCallback(
    async (keyringId: string) => {
      const sync = syncRef.current;
      if (!sync) return;
      refresh(await sync.deleteKeyringWithItems(keyringId));
      backgroundSync();
    },
    [refresh, backgroundSync],
  );

  /**
   * Attach a file to a password.
   *
   * The keyring comes from the item rather than the caller, so a file always
   * lands in the same document as the password it belongs to — which is what
   * makes it travel when the keyring is shared.
   */
  const attachFile = useCallback(
    async (itemId: string, file: File) => {
      const sync = syncRef.current;
      if (!sync) return;
      const prepared = await prepareFile(file);
      const keyringId = state.items[itemId]?.keyring.value;
      if (!keyringId) return;
      refresh(
        await sync.attachFile({
          itemId,
          keyringId,
          blobId: prepared.blobId,
          name: prepared.name,
          type: prepared.type,
          data: prepared.data,
          bytes: prepared.bytes,
        }),
      );
      backgroundSync();
    },
    [state.items, refresh, backgroundSync],
  );

  const removeAttachment = useCallback(
    async (itemId: string, blobId: string) => {
      const sync = syncRef.current;
      if (!sync) return;
      refresh(await sync.removeAttachment(itemId, blobId));
      backgroundSync();
    },
    [refresh, backgroundSync],
  );

  const addKeyring = useCallback(
    async (name: string) => {
      const sync = syncRef.current;
      if (!sync) return "";
      const keyringId = crypto.randomUUID();
      refresh(await sync.putKeyring({ keyringId, name }));
      backgroundSync();
      return keyringId;
    },
    [refresh, backgroundSync],
  );

  /**
   * Copy a parsed KeePass file in.
   *
   * Each top-level group becomes a keyring, and entries keep their KeePass
   * UUIDs, so importing the same file again updates rather than duplicates.
   *
   * One write, not one per password. Importing a file of two hundred used to
   * commit two hundred times, each re-encrypting the whole vault and re-reading
   * a growing outbox — the same cost that made deleting a large keyring slow,
   * and on the path people meet first. Every operation is still stamped
   * individually by the live clock, so a re-import still beats edits made in
   * between and the outbox still carries each change to other devices.
   */
  const importKeePass = useCallback(
    async (preview: ImportPreview, ungrouped: UngroupedDestination) => {
      const sync = syncRef.current;
      if (!sync) return 0;

      const existing = Object.values(state.keyrings).filter((ring) => !ring.deleted.value);
      const keyringOps: VaultOp[] = [];
      const keyringIds: Record<string, string> = {};
      // Remembers what it has already queued, not just what the vault already
      // had. Two callers can ask for the same name in one import — the
      // ungrouped keyring defaults to the file's name, which may well match a
      // group in it — and without this they would each mint an id and the
      // import would end with two keyrings wearing the same name.
      const minted = new Map<string, string>();
      const keyringFor = (name: string) => {
        const seen = minted.get(name);
        if (seen !== undefined) return seen;
        const already = existing.find((ring) => ring.name.value === name);
        const id = already?.id ?? crypto.randomUUID();
        if (!already) {
          const { opId, ts } = sync.stamp();
          keyringOps.push({ kind: "keyring.put", opId, ts, keyringId: id, name });
        }
        minted.set(name, id);
        return id;
      };

      for (const name of preview.keyringNames) {
        keyringIds[name] = keyringFor(name);
      }

      // Where the entries in no group go. Asked of the user rather than
      // guessed, and resolved before any entry is written so a half-finished
      // import cannot leave them somewhere arbitrary.
      const ungroupedKeyringId =
        preview.ungrouped === 0
          ? ""
          : ungrouped.kind === "existing"
            ? ungrouped.keyringId
            : keyringFor(ungrouped.name);

      // The same op builder the import tests exercise, rather than a second
      // copy of the mapping inline here. It throws on a keyring it was not
      // given, which is the behaviour those tests pin down.
      // The vault as it stands, so an entry that is already here can have the
      // names an *earlier* build's import gave its custom fields retired
      // rather than left beside the ones this one writes.
      const itemOps = importOperations(
        preview,
        keyringIds,
        ungroupedKeyringId,
        () => sync.stamp(),
        await sync.state(),
      );

      // Keyrings first: an item op naming a keyring that does not exist yet
      // would be replayed in that order by another device.
      refresh(await sync.commitAll([...keyringOps, ...itemOps]));
      backgroundSync();
      return preview.entries.length;
    },
    [state.keyrings, refresh, backgroundSync],
  );

  const items = useMemo(() => visibleItems(state), [state]);

  return {
    phase,
    firstRun,
    error,
    state,
    status,
    backupConfigured: BACKUP_CONFIGURED,
    items,
    unlock,
    restore,
    restoreWithCode,
    newRecoveryCode,
    recoveryNeedsCode,
    adoptRecoveryCode,
    accountContents,
    describeBackupFile,
    backupFiles,
    refreshBackupFiles,
    deleteBackupFile,
    chooseBackupFile: chooseBackupFileAndRetry,
    dismissRecoveryCode: () => setNewRecoveryCode(null),
    lock,
    syncNow,
    saveItem,
    addScannedCodes,
    deleteItem,
    moveItem,
    deleteItems,
    moveItems,
    deleteKeyring,
    addKeyring,
    importKeePass,
    attachFile,
    removeAttachment,
    readOnlyKeyrings,
    sharedKeyrings,
    sharing,
    blockedJoins,
    adoptBlockedJoin,
  };
}

/**
 * The sharing engine, wrapped so every operation that can change what is in
 * the vault refreshes the screen afterwards.
 *
 * Sharing a keyring moves its passwords into another file, and leaving one
 * takes it off the list. Both are invisible to the caller otherwise, because
 * neither goes through the ordinary edit path.
 */
function sharingApi(
  engine: KeywebSharing,
  sync: VaultSync,
  refresh: () => Promise<void>,
): SharingApi {
  return {
    myFingerprint: () => engine.myFingerprint(),
    async shareKeyring(input) {
      const result = await engine.shareKeyring(input);
      await refresh();
      return result;
    },
    async shareKeyrings(input) {
      const result = await engine.shareKeyrings(input);
      await refresh();
      return result;
    },
    joinFromLink: (input) =>
      engine.joinFromLink({ ...input, grantAccess: grantSharedFiles }),
    previewResponse: (response) => engine.previewResponse(response),
    async acceptResponse(response) {
      const result = await engine.acceptResponse(response);
      await refresh();
      return result;
    },
    members: (datasetId) => engine.members(datasetId),
    async setRole(input) {
      await engine.setRole(input);
    },
    async proposeOwnership(input) {
      return engine.proposeOwnership(input);
    },
    async acceptOwnership(payload) {
      await engine.acceptOwnership(payload);
      refresh();
    },
    async revoke(input) {
      await engine.revoke(input);
    },
    pendingInvites: (keyringId) => engine.pendingInvites(keyringId),
    cancelInvite: (exchangeId) => engine.cancelInvite(exchangeId),
    async stopSharing(keyringId) {
      const datasetId = datasetOf((await sync.state()).keyrings[keyringId]);
      if (datasetId) {
        let members;
        try {
          members = await engine.members(datasetId);
        } catch {
          throw new Error(
            "Keyweb couldn't see who has this keyring, so it is still shared.",
          );
        }
        for (const member of members) {
          if (member.you) continue;
          try {
            await engine.revoke({ datasetId, keyId: member.keyId });
          } catch {
            const who = member.email ?? "someone";
            throw new Error(
              `Keyweb couldn't remove ${who}. The keyring is still shared.`,
            );
          }
        }
      }
      // The keyring keeps its own file. Every keyring has one now, shared or
      // not, so "private again" means nobody else holds its key — which the
      // revocations above just made true — not that it moves anywhere.
      await engine.forgetShared(datasetId);
      await refresh();
    },
    async leave(keyringId) {
      await sync.leaveKeyring(keyringId);
      await refresh();
    },
  };
}

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  createClock,
  emptyVault,
  type ItemField,
  type ItemRecord,
  type RemoteVaultStore,
  RemoteUnavailableError,
  type SyncStatus,
  type VaultOp,
  type VaultState,
  VaultSync,
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
import { GoogleDriveRemote } from "./drive";
import {
  createKeywebSharingController,
  createSharingIdentity,
  KeywebSharing,
  SharedKeyringRemote,
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
  lock(): void;
  syncNow(): Promise<void>;
  saveItem(input: {
    itemId?: string;
    keyringId: string;
    fields: Partial<Record<ItemField, string>>;
  }): Promise<void>;
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
};

/** What the sharing screens need, with the identity and controller already wired. */
export type SharingApi = {
  myFingerprint(): Promise<string>;
  shareKeyring(input: {
    keyringId: string;
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
  const [state, setState] = useState<VaultState>(emptyVault);
  const [status, setStatus] = useState<SyncStatus>({
    pending: 0,
    lastPublishedAt: null,
    lastError: null,
    syncing: false,
  });

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
  const [sharing, setSharing] = useState<SharingApi | null>(null);

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
    } catch {
      // A share that is not ready yet is the normal case, not an error worth
      // showing. It will be ready on some later sync.
    }
    try {
      setReadOnlyKeyrings(await engine.readOnlyKeyrings(await sync.state()));
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
        if (await adoptShared()) setState(await sync.state());
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
      let recoveryCipher: VaultCipher | undefined;
      if (BACKUP_CONFIGURED) {
        const storedSecret = await storage.readMeta("recovery-secret");
        if (storedSecret) {
          const raw = await cipher.openOp(storedSecret);
          recoveryCipher = await createRecoveryCipher(
            Uint8Array.from(raw as unknown as number[]),
            await storage.readMeta("recovery-envelope"),
          );
        } else if (await backupAlreadyHasRecovery()) {
          // A second device, or a reinstall. There is already a code written
          // down somewhere, and this device cannot derive it.
          //
          // Minting a fresh one here would look harmless — a new sheet to
          // print — but it would reseal the backup under a different key and
          // silently retire the sheet already in someone's filing cabinet.
          // They would discover that only on the day they needed it. So this
          // device leaves the recovery copy alone and asks for the existing
          // code instead.
          setRecoveryNeedsCode(true);
        } else {
          const secret = generateRecoverySecret();
          recoveryCipher = await createRecoveryCipher(secret);
          await storage.writeMeta(
            "recovery-secret",
            await cipher.sealOp([...secret] as never),
          );
          setNewRecoveryCode(formatRecoveryCode(secret));
        }
      }

      const remote = BACKUP_CONFIGURED
        ? new GoogleDriveRemote({
            clientId: CLIENT_ID,
            cipher,
            ...(recoveryCipher ? { recoveryCipher } : {}),
          })
        : new UnconfiguredRemote();
      remoteRef.current = remote instanceof GoogleDriveRemote ? remote : null;

      // Sharing is wired in before the engine starts, because the engine asks
      // for a dataset's remote the first time it syncs one — and a keyring
      // bound on another device can already be waiting in the vault it is
      // about to pull down.
      //
      // One identity object, not one per user of it. It holds the unlocked
      // keypair for the session and serialises the passkey prompt; two of them
      // would mean two prompts for one operation, and the second would arrive
      // while the first was still on screen.
      const identity = BACKUP_CONFIGURED
        ? createSharingIdentity(async () => {
            const stored = await storage.readMeta("recovery-secret");
            if (!stored) {
              throw new Error(
                "This browser needs your recovery code before it can share a keyring. Enter it in Settings.",
              );
            }
            return Uint8Array.from((await cipher.openOp(stored)) as unknown as number[]);
          })
        : null;
      const controller = identity ? createKeywebSharingController(identity) : null;
      const datasetRemotes = new Map<string, SharedKeyringRemote>();
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
      if (Object.keys(current.keyrings).length === 0) {
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
      }
    },
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
      const sealed = await probe.fetchSealedState();
      if (!sealed) {
        /*
         * A backup with no browser key in it is not the same as no backup.
         *
         * A vault made entirely on a phone has only the recovery envelope,
         * because Keyweb's Android app does not derive the passkey key.
         * Telling that
         * person "there's no backup in that account" is false and sends them
         * looking for the wrong problem, so the two cases are separated here
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

        // Re-establish this device with a new passkey, then seed it with what
        // the code just opened.
        const { cipher, lock } = await unlockVault(null);
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
        await start(cipher, lock, false);
      } catch (cause) {
        setError(describe(cause));
        setPhase("locked");
      }
    },
    [describe, start],
  );

  const lock = useCallback(() => {
    lockRef.current?.();
    lockRef.current = null;
    syncRef.current = null;
    setState(emptyVault());
    setPhase("locked");
  }, []);

  const syncNow = useCallback(async () => {
    const sync = syncRef.current;
    if (!sync) return;
    await sync.sync();
    await adoptShared();
    setStatus({ ...sync.status() });
    setState(await sync.state());
  }, [adoptShared]);

  const saveItem = useCallback<VaultApi["saveItem"]>(
    async (input) => {
      const sync = syncRef.current;
      if (!sync) return;
      refresh(await sync.putItem(input));
      backgroundSync();
    },
    [refresh, backgroundSync],
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
    sharing,
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
      await sync.unbindKeyring(keyringId);
      await refresh();
    },
    async leave(keyringId) {
      await sync.leaveKeyring(keyringId);
      await refresh();
    },
  };
}

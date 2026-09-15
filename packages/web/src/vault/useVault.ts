import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  createClock,
  emptyVault,
  type ItemField,
  type ItemRecord,
  type RemoteVaultStore,
  RemoteUnavailableError,
  type SyncStatus,
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
  unlock(): Promise<void>;
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
  moveItem(itemId: string, keyringId: string): Promise<void>;
  addKeyring(name: string): Promise<string>;
  /** Copy a parsed KeePass file in. Returns how many entries landed. */
  importKeePass(preview: ImportPreview, ungrouped: UngroupedDestination): Promise<number>;
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

  const [newRecoveryCode, setNewRecoveryCode] = useState<string | null>(null);
  /** This backup already has a recovery code, and it is not on this device. */
  const [recoveryNeedsCode, setRecoveryNeedsCode] = useState(false);
  const syncRef = useRef<VaultSync | null>(null);
  const lockRef = useRef<(() => void) | null>(null);
  const storageRef = useRef<IndexedDbVaultStorage | null>(null);
  const cipherRef = useRef<VaultCipher | null>(null);
  const remoteRef = useRef<GoogleDriveRemote | null>(null);

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

  const refresh = useCallback((next: VaultState) => {
    setState(next);
    const sync = syncRef.current;
    if (sync) setStatus({ ...sync.status() });
  }, []);

  /** Publish in the background; the UI already showed "Saved" from the commit. */
  const backgroundSync = useCallback(() => {
    const sync = syncRef.current;
    if (!sync || !BACKUP_CONFIGURED) return;
    void sync.sync().then(
      () => setStatus({ ...sync.status() }),
      () => setStatus({ ...sync.status() }),
    );
  }, []);

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
      const sync = new VaultSync({ storage, remote, clock });
      syncRef.current = sync;

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

  const unlock = useCallback(async () => {
    setPhase("unlocking");
    setError(null);
    try {
      const sealed = await peekSealedState();
      const { cipher, lock } = await unlockVault(sealed);
      await start(cipher, lock, false);
    } catch (cause) {
      setError(describe(cause));
      setPhase("locked");
    }
  }, [describe, start]);

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
        setError("There's no Keyweb backup in that Google account yet.");
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
    setStatus({ ...sync.status() });
    setState(await sync.state());
  }, []);

  const saveItem = useCallback<VaultApi["saveItem"]>(
    async (input) => {
      const sync = syncRef.current;
      if (!sync) return;
      refresh(await sync.putItem(input));
      backgroundSync();
    },
    [refresh, backgroundSync],
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
   * Every operation goes through the normal commit path, so an import is as
   * durable and as recoverable as anything typed by hand.
   */
  const importKeePass = useCallback(
    async (preview: ImportPreview, ungrouped: UngroupedDestination) => {
      const sync = syncRef.current;
      if (!sync) return 0;

      const existing = Object.values(state.keyrings).filter((ring) => !ring.deleted.value);
      const keyringIds: Record<string, string> = {};
      const keyringFor = async (name: string) => {
        const already = existing.find((ring) => ring.name.value === name);
        if (already) return already.id;
        const id = crypto.randomUUID();
        await sync.putKeyring({ keyringId: id, name });
        return id;
      };

      for (const name of preview.keyringNames) {
        keyringIds[name] = await keyringFor(name);
      }

      // Where the entries in no group go. Asked of the user rather than
      // guessed, and resolved before any entry is written so a half-finished
      // import cannot leave them somewhere arbitrary.
      const ungroupedKeyringId =
        preview.ungrouped === 0
          ? ""
          : ungrouped.kind === "existing"
            ? ungrouped.keyringId
            : await keyringFor(ungrouped.name);

      // Committed one at a time through the normal path, so each entry is
      // stamped by the live clock and lands in the outbox like any other edit.
      // That ordering is what lets a re-import beat edits made in Keyweb
      // since the last one, and what makes a half-finished import durable
      // rather than lost.
      let next = await sync.state();
      for (const entry of preview.entries) {
        next = await sync.putItem({
          itemId: entry.itemId,
          keyringId:
            entry.keyringName === null ? ungroupedKeyringId : keyringIds[entry.keyringName]!,
          fields: entry.fields,
        });
      }
      refresh(next);
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
    deleteItem,
    moveItem,
    addKeyring,
    importKeePass,
  };
}

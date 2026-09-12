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
import { passkeySupported, unlockVault } from "./crypto";
import { GoogleDriveRemote } from "./drive";

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
};

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

  const syncRef = useRef<VaultSync | null>(null);
  const lockRef = useRef<(() => void) | null>(null);

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
      const storage = await IndexedDbVaultStorage.open({ cipher });
      const clock = createClock({ node: deviceNode(), resume: await storage.readClock() });
      const remote = BACKUP_CONFIGURED
        ? new GoogleDriveRemote({ clientId: CLIENT_ID, cipher })
        : new UnconfiguredRemote();
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
      const probe = new GoogleDriveRemote({
        clientId: CLIENT_ID,
        // Only the raw envelope is needed here, and fetching it never decrypts.
        cipher: {
          sealState: async (value) => value,
          openState: async (value) => value as VaultState,
          sealOp: async (value) => value,
          openOp: async (value) => value as never,
        },
      });
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
    lock,
    syncNow,
    saveItem,
    deleteItem,
    moveItem,
    addKeyring,
  };
}

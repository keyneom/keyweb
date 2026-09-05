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
import { IndexedDbVaultStorage, peekSealedState } from "@keyweb/vault-idb";
import { passkeySupported, unlockVault } from "./crypto";

/**
 * A remote that is not configured yet. Encrypted Drive backup needs a Google
 * OAuth client id; until one is supplied the app is fully usable local-first
 * and says so plainly rather than pretending to back anything up.
 */
class UnconfiguredRemote implements RemoteVaultStore {
  async read(): Promise<never> {
    throw new RemoteUnavailableError("Encrypted backup is not set up yet.");
  }
  async write(): Promise<never> {
    throw new RemoteUnavailableError("Encrypted backup is not set up yet.");
  }
}

const BACKUP_CONFIGURED = Boolean(import.meta.env["VITE_GOOGLE_WEB_CLIENT_ID"]);

/** A stable per-device id, so causal ordering survives a reload. */
function deviceNode(): string {
  const key = "keyweb:device-node";
  const existing = localStorage.getItem(key);
  if (existing) return existing;
  const created = crypto.randomUUID().slice(0, 8);
  localStorage.setItem(key, created);
  return created;
}

export type VaultPhase =
  /** Working out whether a vault already exists on this device. */
  | "checking"
  /** A vault exists and needs the passkey, or none exists and needs creating. */
  | "locked"
  | "unlocking"
  | "ready"
  /** No passkey support, or an insecure origin. The vault cannot be opened. */
  | "unsupported";

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
  lock(): void;
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

  const unlock = useCallback(async () => {
    setPhase("unlocking");
    setError(null);
    try {
      const sealed = await peekSealedState();
      const { cipher, lock } = await unlockVault(sealed);
      lockRef.current = lock;

      const storage = await IndexedDbVaultStorage.open({ cipher });
      const clock = createClock({
        node: deviceNode(),
        resume: await storage.readClock(),
      });
      const sync = new VaultSync({ storage, remote: new UnconfiguredRemote(), clock });
      syncRef.current = sync;

      let current = await sync.state();
      // First run: give people somewhere to put things rather than an empty
      // screen with no obvious next step.
      if (Object.keys(current.keyrings).length === 0) {
        current = await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
      }
      setState(current);
      setStatus(sync.status());
      setPhase("ready");
      setFirstRun(false);
    } catch (cause) {
      // A cancelled passkey prompt is the common case and is not an error worth
      // alarming anyone about.
      const message =
        cause instanceof DOMException && cause.name === "NotAllowedError"
          ? "That was cancelled. Your vault is still locked."
          : cause instanceof Error
            ? cause.message
            : "Keyweb couldn't open your vault.";
      setError(message);
      setPhase("locked");
    }
  }, []);

  const lock = useCallback(() => {
    lockRef.current?.();
    lockRef.current = null;
    syncRef.current = null;
    setState(emptyVault());
    setPhase("locked");
  }, []);

  const after = useCallback((next: VaultState) => {
    setState(next);
    const sync = syncRef.current;
    if (sync) setStatus({ ...sync.status() });
  }, []);

  const saveItem = useCallback<VaultApi["saveItem"]>(
    async (input) => {
      const sync = syncRef.current;
      if (!sync) return;
      after(await sync.putItem(input));
    },
    [after],
  );

  const deleteItem = useCallback(
    async (itemId: string) => {
      const sync = syncRef.current;
      if (!sync) return;
      after(await sync.deleteItem(itemId));
    },
    [after],
  );

  const moveItem = useCallback(
    async (itemId: string, keyringId: string) => {
      const sync = syncRef.current;
      if (!sync) return;
      after(await sync.moveItem(itemId, keyringId));
    },
    [after],
  );

  const addKeyring = useCallback(
    async (name: string) => {
      const sync = syncRef.current;
      if (!sync) return "";
      const keyringId = crypto.randomUUID();
      after(await sync.putKeyring({ keyringId, name }));
      return keyringId;
    },
    [after],
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
    lock,
    saveItem,
    deleteItem,
    moveItem,
    addKeyring,
  };
}

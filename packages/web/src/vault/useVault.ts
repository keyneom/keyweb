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
import { IndexedDbVaultStorage } from "@keyweb/vault-idb";

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

export type VaultApi = {
  ready: boolean;
  state: VaultState;
  status: SyncStatus;
  backupConfigured: boolean;
  items: ItemRecord[];
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
  const [ready, setReady] = useState(false);
  const [state, setState] = useState<VaultState>(emptyVault);
  const [status, setStatus] = useState<SyncStatus>({
    pending: 0,
    lastPublishedAt: null,
    lastError: null,
    syncing: false,
  });
  const syncRef = useRef<VaultSync | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const storage = await IndexedDbVaultStorage.open();
      const clock = createClock({ node: deviceNode(), resume: await storage.readClock() });
      const sync = new VaultSync({
        storage,
        remote: new UnconfiguredRemote(),
        clock,
      });
      if (cancelled) return;
      syncRef.current = sync;

      let current = await sync.state();
      // First run: give people somewhere to put things rather than an empty
      // screen with no obvious next step.
      if (Object.keys(current.keyrings).length === 0) {
        current = await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
      }
      setState(current);
      setStatus(sync.status());
      setReady(true);
    })();
    return () => {
      cancelled = true;
    };
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
    ready,
    state,
    status,
    backupConfigured: BACKUP_CONFIGURED,
    items,
    saveItem,
    deleteItem,
    moveItem,
    addKeyring,
  };
}

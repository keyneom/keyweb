import type { Hlc, VaultOp, VaultState, VaultStorage } from "@keyweb/vault-core";
import { emptyVault, mergeVaults } from "@keyweb/vault-core";

/**
 * IndexedDB implementation of the vault storage contract.
 *
 * The whole no-data-loss guarantee rests on two operations being atomic, so
 * both are performed inside a single IndexedDB transaction here rather than as
 * a sequence of awaited puts:
 *
 *  - `commit` writes the new state and appends to the outbox together. A tab
 *    closed between the two would otherwise leave an edit that the device
 *    shows but never uploads.
 *
 *  - `applyRemote` reads, joins, and writes inside one transaction, so an edit
 *    committed concurrently cannot be read-then-overwritten.
 *
 * IndexedDB gives us this: a readwrite transaction spanning both stores is
 * serialised against every other readwrite transaction on those stores, and
 * aborts as a unit.
 */

const DB_NAME = "keyweb";
const DB_VERSION = 1;
const META = "meta";
const OUTBOX = "outbox";
const STATE_KEY = "state";
const CLOCK_KEY = "clock";

type OutboxRow = { seq: number; op: VaultOp };

function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("IndexedDB request failed."));
  });
}

/** Resolve only when the transaction has actually committed to disk. */
function committed(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? new Error("IndexedDB transaction aborted."));
    tx.onerror = () => reject(tx.error ?? new Error("IndexedDB transaction failed."));
  });
}

export function openVaultDb(
  factory: IDBFactory = indexedDB,
  name: string = DB_NAME,
): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = factory.open(name, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(META)) db.createObjectStore(META);
      if (!db.objectStoreNames.contains(OUTBOX)) {
        db.createObjectStore(OUTBOX, { keyPath: "seq", autoIncrement: true });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("Could not open the vault database."));
  });
}

export class IndexedDbVaultStorage implements VaultStorage {
  readonly #db: IDBDatabase;

  constructor(db: IDBDatabase) {
    this.#db = db;
  }

  static async open(factory?: IDBFactory, name?: string): Promise<IndexedDbVaultStorage> {
    return new IndexedDbVaultStorage(await openVaultDb(factory, name));
  }

  async readState(): Promise<VaultState> {
    const tx = this.#db.transaction(META, "readonly");
    const stored = await request<VaultState | undefined>(tx.objectStore(META).get(STATE_KEY));
    return stored ?? emptyVault();
  }

  async commit(op: VaultOp, nextState: VaultState): Promise<void> {
    const tx = this.#db.transaction([META, OUTBOX], "readwrite");
    tx.objectStore(META).put(nextState, STATE_KEY);
    tx.objectStore(OUTBOX).add({ op } satisfies Omit<OutboxRow, "seq">);
    await committed(tx);
  }

  async applyRemote(incoming: VaultState): Promise<VaultState> {
    const tx = this.#db.transaction(META, "readwrite");
    const store = tx.objectStore(META);
    const current = (await request<VaultState | undefined>(store.get(STATE_KEY))) ?? emptyVault();
    const joined = mergeVaults(current, incoming);
    store.put(joined, STATE_KEY);
    await committed(tx);
    return joined;
  }

  async pending(): Promise<VaultOp[]> {
    const tx = this.#db.transaction(OUTBOX, "readonly");
    const rows = await request<OutboxRow[]>(tx.objectStore(OUTBOX).getAll());
    return rows.sort((a, b) => a.seq - b.seq).map((row) => row.op);
  }

  async ack(opIds: readonly string[]): Promise<void> {
    const drop = new Set(opIds);
    const tx = this.#db.transaction(OUTBOX, "readwrite");
    const store = tx.objectStore(OUTBOX);
    const rows = await request<OutboxRow[]>(store.getAll());
    for (const row of rows) if (drop.has(row.op.opId)) store.delete(row.seq);
    await committed(tx);
  }

  async readClock(): Promise<Hlc | undefined> {
    const tx = this.#db.transaction(META, "readonly");
    return request<Hlc | undefined>(tx.objectStore(META).get(CLOCK_KEY));
  }

  async writeClock(value: Hlc): Promise<void> {
    const tx = this.#db.transaction(META, "readwrite");
    tx.objectStore(META).put(value, CLOCK_KEY);
    await committed(tx);
  }

  close(): void {
    this.#db.close();
  }
}

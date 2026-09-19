import type { Hlc, VaultOp, VaultState, VaultStorage } from "@keyweb/vault-core";
import { applyOp, emptyVault, mergeVaults, VAULT_DOCUMENT } from "@keyweb/vault-core";

/**
 * IndexedDB implementation of the vault storage contract.
 *
 * Two problems have to be solved together here, and the way they interact is
 * the reason this file is not just a set of puts.
 *
 * **Atomicity.** `commit` must write the state and append to the outbox as one
 * unit, and `applyRemote` must read-join-write as one unit; otherwise an edit
 * made during a sync can be read-then-overwritten, which is how a saved
 * password silently disappears.
 *
 * **Encryption at rest.** The vault must not sit in IndexedDB as plaintext. On
 * a shared origin — GitHub Pages project sites all share one — any other page
 * on that origin can open this database.
 *
 * These fight each other: an IndexedDB transaction auto-closes as soon as the
 * event loop turns without a pending request, so `await crypto.subtle.decrypt`
 * inside a transaction kills it. So crypto happens *outside* transactions, and
 * the atomicity is restored two ways:
 *
 *  - every mutation runs inside a Web Lock, which serialises across tabs of the
 *    same origin, not merely within one page;
 *  - the state row carries a revision, and writes are compare-and-set, so a
 *    write derived from a stale read is rejected and retried rather than
 *    clobbering a concurrent one.
 */

const DB_NAME = "keyweb";
const DB_VERSION = 1;
const META = "meta";
const OUTBOX = "outbox";
const STATE_KEY = "state";

/**
 * Where a document's state is kept.
 *
 * The vault keeps the bare key it has always had, so an existing database is
 * read by this code unchanged; a dataset gets a key of its own.
 */
function stateKey(documentId: string): string {
  return documentId ? `${STATE_KEY}:${documentId}` : STATE_KEY;
}
const CLOCK_KEY = "clock";
const LOCK_NAME = "keyweb-vault-write";

/** How the vault is transformed on its way to and from disk. */
export interface VaultCipher {
  sealState(state: VaultState): Promise<unknown>;
  /**
   * Seal at a time the caller chooses, rather than at the time of the call.
   *
   * For a writer putting the same vault into two envelopes in one request.
   * Sealing stamps each envelope with the moment it was sealed, so two calls a
   * millisecond apart produced two copies of an identical vault with different
   * times on them — and a device holding the key to only one of them has
   * nothing to compare but those times. It read the gap as the other copy
   * having moved on without it.
   *
   * Optional because a cipher that does not stamp anything (the plaintext one,
   * the passthrough probes) has nothing to choose.
   */
  sealStateAt?(state: VaultState, updatedAt: string): Promise<unknown>;
  openState(stored: unknown): Promise<VaultState>;
  /** Operations carry passwords too, so the outbox is encrypted as well. */
  sealOp(op: VaultOp): Promise<unknown>;
  openOp(stored: unknown): Promise<VaultOp>;
}

/**
 * Stores the vault as-is. Only appropriate where the origin is not shared and
 * the threat model does not include local disclosure — in practice, tests.
 */
export const plaintextCipher: VaultCipher = {
  async sealState(state) {
    return state;
  },
  async openState(stored) {
    return stored as VaultState;
  },
  async sealOp(op) {
    return op;
  },
  async openOp(stored) {
    return stored as VaultOp;
  },
};

type StateRow = { revision: number; payload: unknown };
/**
 * `document` is absent on rows written before keyrings could live in their own
 * documents, and absent means the vault — so old outboxes keep working and
 * keep meaning what they meant.
 */
type OutboxRow = { seq?: number; opId: string; payload: unknown; document?: string };

function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("IndexedDB request failed."));
  });
}

/** Resolve only when the transaction has actually committed. */
function committed(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? new Error("IndexedDB transaction aborted."));
    tx.onerror = () => reject(tx.error ?? new Error("IndexedDB transaction failed."));
  });
}

/**
 * Serialise a critical section across every tab on this origin.
 *
 * Falls back to a promise chain where Web Locks is unavailable, which still
 * covers the common single-tab case rather than failing outright.
 */
function createLock(): (fn: () => Promise<unknown>) => Promise<unknown> {
  const locks = (globalThis as { navigator?: { locks?: LockManager } }).navigator?.locks;
  if (locks) {
    return (fn) => locks.request(LOCK_NAME, () => fn());
  }
  let tail: Promise<unknown> = Promise.resolve();
  return (fn) => {
    const run = tail.then(fn, fn);
    tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  };
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
  readonly #cipher: VaultCipher;
  readonly #withLock: (fn: () => Promise<unknown>) => Promise<unknown>;

  constructor(db: IDBDatabase, cipher: VaultCipher = plaintextCipher) {
    this.#db = db;
    this.#cipher = cipher;
    this.#withLock = createLock();
  }

  static async open(options?: {
    factory?: IDBFactory;
    name?: string;
    cipher?: VaultCipher;
  }): Promise<IndexedDbVaultStorage> {
    const db = await openVaultDb(options?.factory, options?.name);
    return new IndexedDbVaultStorage(db, options?.cipher);
  }

  /** Read the stored row without decrypting, so callers can compare revisions. */
  async #readRow(documentId: string = VAULT_DOCUMENT): Promise<StateRow> {
    const tx = this.#db.transaction(META, "readonly");
    const row = await request<StateRow | undefined>(
      tx.objectStore(META).get(stateKey(documentId)),
    );
    return row ?? { revision: 0, payload: null };
  }

  async #decode(row: StateRow): Promise<VaultState> {
    if (row.payload === null) return emptyVault();
    return this.#cipher.openState(row.payload);
  }

  async readState(documentId: string = VAULT_DOCUMENT): Promise<VaultState> {
    return this.#decode(await this.#readRow(documentId));
  }

  /**
   * Documents other than the vault, from the keys actually present.
   *
   * Read from storage rather than from the vault's bindings, because the two
   * legitimately disagree: a keyring bound on another device is in the vault
   * before its document has ever been fetched.
   */
  async knownDocuments(): Promise<string[]> {
    const tx = this.#db.transaction(META, "readonly");
    const keys = await request<IDBValidKey[]>(tx.objectStore(META).getAllKeys());
    return keys
      .filter((key): key is string => typeof key === "string")
      .filter((key) => key.startsWith(`${STATE_KEY}:`))
      .map((key) => key.slice(STATE_KEY.length + 1))
      .sort();
  }

  /**
   * Remove a document entirely: its state row and everything queued for it.
   *
   * For leaving a keyring somebody else shared. "Remove it from my vault" has
   * to mean the passwords go, not merely that they stop being listed.
   *
   * One transaction, and the outbox is cleared first within it, so there is no
   * moment where queued operations name a document whose state has gone.
   */
  async forgetDocument(documentId: string): Promise<void> {
    if (documentId === VAULT_DOCUMENT) {
      throw new Error("The vault itself cannot be forgotten.");
    }
    await this.#withLock(async () => {
      const tx = this.#db.transaction([META, OUTBOX], "readwrite");
      const outbox = tx.objectStore(OUTBOX);
      const rows = await request<Required<OutboxRow>[]>(outbox.getAll());
      for (const row of rows) {
        if ((row.document ?? VAULT_DOCUMENT) === documentId && row.seq !== undefined) {
          outbox.delete(row.seq);
        }
      }
      tx.objectStore(META).delete(stateKey(documentId));
      await committed(tx);
    });
  }

  /**
   * Write the state and append the operation together, but only if the state
   * has not moved since `expectedRevision`. Returns false so the caller can
   * re-derive and retry rather than overwrite a concurrent write.
   *
   * No `await` sits between the read and the writes, so the transaction cannot
   * close underneath us.
   */
  async #casCommit(
    expectedRevision: number,
    statePayload: unknown,
    outbox: readonly OutboxRow[],
    documentId: string = VAULT_DOCUMENT,
  ): Promise<boolean> {
    const stores = outbox.length > 0 ? [META, OUTBOX] : [META];
    const tx = this.#db.transaction(stores, "readwrite");
    const meta = tx.objectStore(META);
    const key = stateKey(documentId);
    const current = await request<StateRow | undefined>(meta.get(key));
    if ((current?.revision ?? 0) !== expectedRevision) {
      tx.abort();
      return false;
    }
    meta.put({ revision: expectedRevision + 1, payload: statePayload }, key);
    // One transaction however many rows: a bulk change must not be able to
    // land half-written, and paying the state re-encryption once is the whole
    // reason the batch exists.
    if (outbox.length > 0) {
      const store = tx.objectStore(OUTBOX);
      for (const row of outbox) store.add(row);
    }
    await committed(tx);
    return true;
  }

  commit(op: VaultOp, nextState: VaultState): Promise<void> {
    return this.commitAll([op], nextState);
  }

  async commitAll(
    ops: readonly VaultOp[],
    nextState: VaultState,
    documentId: string = VAULT_DOCUMENT,
  ): Promise<void> {
    if (ops.length === 0) return;
    await this.#withLock(async () => {
      const row = await this.#readRow(documentId);
      const sealedState = await this.#cipher.sealState(nextState);
      const sealedOps = await Promise.all(
        ops.map(async (op) => ({
          opId: op.opId,
          payload: await this.#cipher.sealOp(op),
          document: documentId,
        })),
      );
      const ok = await this.#casCommit(row.revision, sealedState, sealedOps, documentId);
      if (!ok) {
        // Another tab wrote between our read and our write. Re-derive the
        // state by joining the operations onto whatever is there now, so
        // neither side's work is lost, and try again.
        const current = await this.readState(documentId);
        let rejoined = current;
        for (const op of ops) rejoined = applyOp(rejoined, op);
        const row2 = await this.#readRow(documentId);
        const resealed = await this.#cipher.sealState(rejoined);
        const retried = await this.#casCommit(row2.revision, resealed, sealedOps, documentId);
        if (!retried) throw new Error("The vault was being written by another tab. Try again.");
      }
    });
  }

  async applyRemote(
    incoming: VaultState,
    documentId: string = VAULT_DOCUMENT,
  ): Promise<VaultState> {
    return (await this.#withLock(async () => {
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const row = await this.#readRow(documentId);
        const current = await this.#decode(row);
        const joined = mergeVaults(current, incoming);
        const sealed = await this.#cipher.sealState(joined);
        if (await this.#casCommit(row.revision, sealed, [], documentId)) return joined;
      }
      throw new Error("The vault kept changing while we merged. Try again.");
    })) as VaultState;
  }

  async pending(documentId: string = VAULT_DOCUMENT): Promise<VaultOp[]> {
    const tx = this.#db.transaction(OUTBOX, "readonly");
    const rows = await request<Required<OutboxRow>[]>(tx.objectStore(OUTBOX).getAll());
    const ordered = rows
      // A row with no document predates datasets and belongs to the vault,
      // which is what it meant when it was written.
      .filter((row) => (row.document ?? VAULT_DOCUMENT) === documentId)
      .sort((a, b) => a.seq - b.seq);
    return Promise.all(ordered.map((row) => this.#cipher.openOp(row.payload)));
  }

  async ack(opIds: readonly string[], _documentId: string = VAULT_DOCUMENT): Promise<void> {
    if (opIds.length === 0) return;
    await this.#withLock(async () => {
      const drop = new Set(opIds);
      const tx = this.#db.transaction(OUTBOX, "readwrite");
      const store = tx.objectStore(OUTBOX);
      // Operation ids are random and carry nothing secret, so they are stored
      // in the clear and an acknowledgement needs no key.
      const rows = await request<Required<OutboxRow>[]>(store.getAll());
      for (const row of rows) if (drop.has(row.opId)) store.delete(row.seq);
      await committed(tx);
    });
  }

  /**
   * Small named values that sit beside the vault.
   *
   * Used for the sealed recovery secret: it must survive restarts so every
   * publish can reseal the recovery copy, and it is stored already encrypted
   * under the vault key, so this store never holds it in the clear.
   */
  async readMeta(key: string): Promise<unknown | undefined> {
    const tx = this.#db.transaction(META, "readonly");
    return request<unknown | undefined>(tx.objectStore(META).get(`meta:${key}`));
  }

  async writeMeta(key: string, value: unknown): Promise<void> {
    const tx = this.#db.transaction(META, "readwrite");
    tx.objectStore(META).put(value, `meta:${key}`);
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

/**
 * Read the stored state payload without decrypting it.
 *
 * Unlocking is a chicken-and-egg problem: the cipher needs a key, and the key
 * is named by the envelope that the cipher would otherwise be needed to read.
 * The envelope is self-describing, so this returns it raw — null on a first
 * run, when there is nothing to unlock and a new key must be created instead.
 */
export async function peekSealedState(
  factory: IDBFactory = indexedDB,
  name: string = DB_NAME,
): Promise<unknown | null> {
  const db = await openVaultDb(factory, name);
  try {
    const tx = db.transaction(META, "readonly");
    const row = await request<StateRow | undefined>(tx.objectStore(META).get(STATE_KEY));
    return row?.payload ?? null;
  } finally {
    db.close();
  }
}

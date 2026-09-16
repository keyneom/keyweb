import type { Hlc } from "./hlc.js";
import { mergeVaults } from "./merge.js";
import type { VaultState } from "./model.js";
import { emptyVault } from "./model.js";
import type { VaultOp } from "./ops.js";
import { applyOp } from "./ops.js";

/**
 * Durable local storage.
 *
 * Two of these methods carry an atomicity requirement that the whole
 * no-data-loss guarantee rests on. A platform implementation (IndexedDB on
 * web, Room on Android) MUST perform each inside a single write transaction:
 *
 *  - `commit` writes the new state AND appends the operation to the outbox.
 *    If the state landed but the outbox append did not, a crash before the
 *    next sync loses the edit from the cloud forever.
 *
 *  - `applyRemote` reads the current state, joins the incoming one into it,
 *    and writes the result. It must never be implemented as a blind
 *    assignment: an edit committed while a sync was in flight lives only in
 *    local state, and overwriting instead of joining is exactly how a saved
 *    password silently disappears.
 */
/**
 * Which document an operation concerns.
 *
 * The empty string is the vault itself, which is why it is the default
 * everywhere: every existing call site means the vault, and adding a parameter
 * must not change what any of them do. A non-empty id is a keyring that lives
 * in its own document — see `docs/keyring-sharing.md` for why it has to.
 */
export const VAULT_DOCUMENT = "";

export interface VaultStorage {
  readState(documentId?: string): Promise<VaultState>;
  /** Atomic: persist `nextState` and append `op` to the outbox together. */
  commit(op: VaultOp, nextState: VaultState): Promise<void>;
  /** Atomic: join `incoming` into the current state and return the result. */
  /**
   * Atomic: persist [nextState] and append every op in [ops] together.
   *
   * The whole point is that it is one write. Committing a bulk change one op
   * at a time re-encrypts the entire vault per op, which is what made deleting
   * a keyring of 200 passwords take the better part of a minute.
   */
  commitAll(
    ops: readonly VaultOp[],
    nextState: VaultState,
    documentId?: string,
  ): Promise<void>;
  applyRemote(incoming: VaultState, documentId?: string): Promise<VaultState>;
  /** Operations not yet proven present in a published revision, oldest first. */
  pending(documentId?: string): Promise<VaultOp[]>;
  /** Drop operations now known to be in a published revision. */
  ack(opIds: readonly string[], documentId?: string): Promise<void>;
  /**
   * Every document this device holds, the vault excluded.
   *
   * Asked rather than remembered, because the vault's own list of bound
   * keyrings and the documents actually on disk can disagree — a keyring
   * bound on another device arrives before its document does.
   */
  knownDocuments(): Promise<string[]>;
  /**
   * Remove a document entirely: its state and anything queued for it.
   *
   * For leaving a keyring somebody else shared. "Remove it from my vault" has
   * to mean the passwords go, not merely that they stop being listed — the
   * same reason `item.purge` blanks fields rather than only tombstoning. It is
   * a genuine deletion rather than a tombstone because nothing has to converge
   * on it: the document belongs to somebody else, and this device is simply no
   * longer carrying a copy.
   *
   * Refuses the vault. Nothing should be able to ask for that by passing an
   * empty string it did not mean to pass.
   */
  forgetDocument(documentId: string): Promise<void>;
  /** Persisted causal time, so a restart cannot rewind the clock. */
  readClock(): Promise<Hlc | undefined>;
  writeClock(value: Hlc): Promise<void>;
}

export class VersionConflictError extends Error {
  constructor(message = "The remote vault moved since it was read.") {
    super(message);
    this.name = "VersionConflictError";
  }
}

export type RemoteRevision = { state: VaultState; version: string };

/**
 * The encrypted remote. In production this wraps sync-kit's dataset transport;
 * `version` is the Drive change token captured at read time.
 */
export interface RemoteVaultStore {
  read(): Promise<RemoteRevision | null>;
  /**
   * Publish a revision. MUST reject with `VersionConflictError` when
   * `expectedVersion` no longer matches the remote head, so a concurrent
   * writer's revision is never silently clobbered.
   */
  write(state: VaultState, expectedVersion: string | null): Promise<string>;
}

type OutboxEntry = { op: VaultOp; seq: number };

/**
 * In-memory storage. Real on Android and web only in tests, but the semantics
 * here are the contract every platform implementation must reproduce.
 */
export class MemoryVaultStorage implements VaultStorage {
  /** One entry per document; the vault is the one keyed by VAULT_DOCUMENT. */
  #states = new Map<string, VaultState>();
  #outboxes = new Map<string, OutboxEntry[]>();
  #seq = 0;
  #clock: Hlc | undefined;

  async readState(documentId: string = VAULT_DOCUMENT): Promise<VaultState> {
    return this.#states.get(documentId) ?? emptyVault();
  }

  async commit(op: VaultOp, nextState: VaultState): Promise<void> {
    this.#states.set(VAULT_DOCUMENT, nextState);
    this.#outbox(VAULT_DOCUMENT).push({ op, seq: this.#seq++ });
  }

  async commitAll(
    ops: readonly VaultOp[],
    nextState: VaultState,
    documentId: string = VAULT_DOCUMENT,
  ): Promise<void> {
    this.#states.set(documentId, nextState);
    const outbox = this.#outbox(documentId);
    for (const op of ops) outbox.push({ op, seq: this.#seq++ });
  }

  async applyRemote(
    incoming: VaultState,
    documentId: string = VAULT_DOCUMENT,
  ): Promise<VaultState> {
    const joined = mergeVaults(await this.readState(documentId), incoming);
    this.#states.set(documentId, joined);
    return joined;
  }

  async pending(documentId: string = VAULT_DOCUMENT): Promise<VaultOp[]> {
    return this.#outbox(documentId).map((entry) => entry.op);
  }

  async ack(opIds: readonly string[], documentId: string = VAULT_DOCUMENT): Promise<void> {
    const drop = new Set(opIds);
    this.#outboxes.set(
      documentId,
      this.#outbox(documentId).filter((entry) => !drop.has(entry.op.opId)),
    );
  }

  /**
   * Documents other than the vault.
   *
   * A document counts as known once it has state or queued work, so a keyring
   * bound locally is visible before its first sync has ever run.
   */
  async knownDocuments(): Promise<string[]> {
    const ids = new Set([...this.#states.keys(), ...this.#outboxes.keys()]);
    ids.delete(VAULT_DOCUMENT);
    return [...ids].sort();
  }

  async forgetDocument(documentId: string): Promise<void> {
    if (documentId === VAULT_DOCUMENT) {
      throw new Error("The vault itself cannot be forgotten.");
    }
    this.#states.delete(documentId);
    this.#outboxes.delete(documentId);
  }

  #outbox(documentId: string): OutboxEntry[] {
    const existing = this.#outboxes.get(documentId);
    if (existing) return existing;
    const created: OutboxEntry[] = [];
    this.#outboxes.set(documentId, created);
    return created;
  }

  async readClock(): Promise<Hlc | undefined> {
    return this.#clock;
  }

  async writeClock(value: Hlc): Promise<void> {
    this.#clock = value;
  }

  /** Test helper: simulate a process restart with durable state intact. */
  fork(): MemoryVaultStorage {
    const next = new MemoryVaultStorage();
    // Every document, not just the vault: a restart that forgot the shared
    // keyrings would look exactly like them never having been bound.
    next.#states = new Map(this.#states);
    next.#outboxes = new Map(
      [...this.#outboxes].map(([id, entries]) => [id, [...entries]]),
    );
    next.#seq = this.#seq;
    next.#clock = this.#clock;
    return next;
  }
}

/** Rebuild state from scratch by replaying the outbox onto a base. */
export function replay(base: VaultState, ops: readonly VaultOp[]): VaultState {
  return ops.reduce(applyOp, base);
}

/**
 * The remote could not be reached. Distinct from a conflict: the vault is
 * intact, the user is simply offline, and pending work stays queued.
 */
export class RemoteUnavailableError extends Error {
  constructor(message = "The encrypted backup could not be reached.") {
    super(message);
    this.name = "RemoteUnavailableError";
  }
}

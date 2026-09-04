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
export interface VaultStorage {
  readState(): Promise<VaultState>;
  /** Atomic: persist `nextState` and append `op` to the outbox together. */
  commit(op: VaultOp, nextState: VaultState): Promise<void>;
  /** Atomic: join `incoming` into the current state and return the result. */
  applyRemote(incoming: VaultState): Promise<VaultState>;
  /** Operations not yet proven present in a published revision, oldest first. */
  pending(): Promise<VaultOp[]>;
  /** Drop operations now known to be in a published revision. */
  ack(opIds: readonly string[]): Promise<void>;
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
  #state: VaultState = emptyVault();
  #outbox: OutboxEntry[] = [];
  #seq = 0;
  #clock: Hlc | undefined;

  async readState(): Promise<VaultState> {
    return this.#state;
  }

  async commit(op: VaultOp, nextState: VaultState): Promise<void> {
    this.#state = nextState;
    this.#outbox.push({ op, seq: this.#seq++ });
  }

  async applyRemote(incoming: VaultState): Promise<VaultState> {
    this.#state = mergeVaults(this.#state, incoming);
    return this.#state;
  }

  async pending(): Promise<VaultOp[]> {
    return this.#outbox.map((entry) => entry.op);
  }

  async ack(opIds: readonly string[]): Promise<void> {
    const drop = new Set(opIds);
    this.#outbox = this.#outbox.filter((entry) => !drop.has(entry.op.opId));
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
    next.#state = this.#state;
    next.#outbox = [...this.#outbox];
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

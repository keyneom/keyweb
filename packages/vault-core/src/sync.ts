import type { Clock, Hlc } from "./hlc.js";
import { HLC_ZERO } from "./hlc.js";
import { mergeVaults } from "./merge.js";
import type { ItemField, VaultState } from "./model.js";
import { emptyVault, fingerprint } from "./model.js";
import type { VaultOp } from "./ops.js";
import { applyOp, applyOps } from "./ops.js";
import type { RemoteVaultStore, VaultStorage } from "./storage.js";
import { RemoteUnavailableError, VersionConflictError } from "./storage.js";

export type SyncOutcome =
  | { status: "published"; version: string; pending: number }
  | { status: "unchanged"; pending: number }
  | { status: "offline"; pending: number; reason: string }
  | { status: "conflict-exhausted"; pending: number };

export type SyncStatus = {
  /** Edits saved locally but not yet proven present in a published revision. */
  pending: number;
  lastPublishedAt: string | null;
  lastError: string | null;
  syncing: boolean;
};

/** The highest causal timestamp anywhere in a state. */
export function maxHlc(state: VaultState): Hlc {
  let max = HLC_ZERO;
  const bump = (ts: Hlc) => {
    if (ts > max) max = ts;
  };
  for (const item of Object.values(state.items)) {
    bump(item.keyring.ts);
    bump(item.deleted.ts);
    for (const field of Object.values(item.fields)) bump(field.ts);
  }
  for (const ring of Object.values(state.keyrings)) {
    bump(ring.name.ts);
    bump(ring.deleted.ts);
  }
  return max;
}

export type VaultSyncOptions = {
  storage: VaultStorage;
  remote: RemoteVaultStore;
  clock: Clock;
  /** How many times to re-merge and retry when another device wins the race. */
  maxConflictRetries?: number;
  newId?: () => string;
  now?: () => string;
};

/**
 * Orchestrates local edits and encrypted backup.
 *
 * The guarantee: once `commit` resolves, the edit is durable locally and will
 * reach the published revision, whatever happens next - a sync already in
 * flight, a lost connection, a competing device, or the process being killed.
 *
 * Three rules produce it, and all three matter:
 *
 *  1. An edit is written to durable storage and the outbox *before* commit
 *     resolves. The UI's "Saved" state is derived from that write and never
 *     from a sync result.
 *
 *  2. A sync re-applies every pending operation on top of the merged remote
 *     immediately before publishing. So an edit made *during* a sync is not
 *     merely preserved, it is actively folded into the next publish.
 *
 *  3. An operation leaves the outbox only after a revision containing it was
 *     accepted by the remote. A crash between publish and acknowledgement
 *     replays it, which is harmless because the merge is idempotent.
 */
export class VaultSync {
  readonly #storage: VaultStorage;
  readonly #remote: RemoteVaultStore;
  readonly #clock: Clock;
  readonly #maxConflictRetries: number;
  readonly #newId: () => string;
  readonly #now: () => string;

  #tail: Promise<unknown> = Promise.resolve();
  #syncing = false;
  #lastPublishedAt: string | null = null;
  #lastError: string | null = null;
  #pendingCount = 0;

  constructor(options: VaultSyncOptions) {
    this.#storage = options.storage;
    this.#remote = options.remote;
    this.#clock = options.clock;
    this.#maxConflictRetries = options.maxConflictRetries ?? 5;
    this.#newId = options.newId ?? (() => crypto.randomUUID());
    this.#now = options.now ?? (() => new Date().toISOString());
  }

  status(): SyncStatus {
    return {
      pending: this.#pendingCount,
      lastPublishedAt: this.#lastPublishedAt,
      lastError: this.#lastError,
      syncing: this.#syncing,
    };
  }

  /**
   * True only when every saved edit is in a published revision. The vault UI
   * must never claim "backed up" on anything weaker than this.
   */
  async isFullyBackedUp(): Promise<boolean> {
    const pending = await this.#storage.pending();
    this.#pendingCount = pending.length;
    return pending.length === 0 && this.#lastPublishedAt !== null;
  }

  async state(): Promise<VaultState> {
    return this.#storage.readState();
  }

  /**
   * Save one edit. Resolves only once the edit is durable, so a caller may
   * show "Saved" the moment this returns - and must not show it before.
   */
  async commit(op: VaultOp): Promise<VaultState> {
    const current = await this.#storage.readState();
    const next = applyOp(current, op);
    await this.#storage.commit(op, next);
    await this.#storage.writeClock(this.#clock.snapshot());
    const pending = await this.#storage.pending();
    this.#pendingCount = pending.length;
    return next;
  }

  // ---- Edit helpers: build a stamped operation and commit it. ----

  putItem(input: {
    itemId?: string;
    keyringId: string;
    fields: Partial<Record<ItemField, string>>;
  }): Promise<VaultState> {
    return this.commit({
      kind: "item.put",
      opId: this.#newId(),
      ts: this.#clock.now(),
      itemId: input.itemId ?? this.#newId(),
      keyringId: input.keyringId,
      fields: input.fields,
    });
  }

  deleteItem(itemId: string): Promise<VaultState> {
    return this.commit({
      kind: "item.delete",
      opId: this.#newId(),
      ts: this.#clock.now(),
      itemId,
    });
  }

  restoreItem(itemId: string): Promise<VaultState> {
    return this.commit({
      kind: "item.restore",
      opId: this.#newId(),
      ts: this.#clock.now(),
      itemId,
    });
  }

  moveItem(itemId: string, keyringId: string): Promise<VaultState> {
    return this.commit({
      kind: "item.move",
      opId: this.#newId(),
      ts: this.#clock.now(),
      itemId,
      keyringId,
    });
  }

  putKeyring(input: { keyringId?: string; name: string }): Promise<VaultState> {
    return this.commit({
      kind: "keyring.put",
      opId: this.#newId(),
      ts: this.#clock.now(),
      keyringId: input.keyringId ?? this.#newId(),
      name: input.name,
    });
  }

  deleteKeyring(keyringId: string): Promise<VaultState> {
    return this.commit({
      kind: "keyring.delete",
      opId: this.#newId(),
      ts: this.#clock.now(),
      keyringId,
    });
  }

  /** Serialize syncs so two never publish from the same base. */
  sync(): Promise<SyncOutcome> {
    const run = this.#tail.then(
      () => this.#syncNow(),
      () => this.#syncNow(),
    );
    this.#tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  async #syncNow(): Promise<SyncOutcome> {
    this.#syncing = true;
    try {
      for (let attempt = 0; attempt <= this.#maxConflictRetries; attempt += 1) {
        let remote: Awaited<ReturnType<RemoteVaultStore["read"]>>;
        try {
          remote = await this.#remote.read();
        } catch (error) {
          return this.#offline(error);
        }

        // Adopt remote causal time so our next local write sorts after it,
        // even if this device's wall clock is behind.
        if (remote) {
          this.#clock.observe(maxHlc(remote.state));
          await this.#storage.writeClock(this.#clock.snapshot());
        }

        const local = await this.#storage.readState();
        const base = remote ? remote.state : emptyVault();

        // Capture pending *before* the write. Anything committed after this
        // point stays queued for the next sync rather than being falsely
        // acknowledged.
        const pending = await this.#storage.pending();
        const pendingIds = pending.map((op) => op.opId);

        // Rule 2: fold every unacknowledged edit into what we are about to
        // publish. Idempotent, so re-applying already-published work is free.
        const merged = applyOps(mergeVaults(base, local), pending);

        if (remote && fingerprint(merged) === fingerprint(remote.state)) {
          await this.#storage.applyRemote(merged);
          await this.#storage.ack(pendingIds);
          this.#lastError = null;
          if (this.#lastPublishedAt === null) this.#lastPublishedAt = this.#now();
          return { status: "unchanged", pending: await this.#refreshPending() };
        }

        let version: string;
        try {
          version = await this.#remote.write(merged, remote ? remote.version : null);
        } catch (error) {
          if (error instanceof VersionConflictError) continue; // re-read, re-merge
          return this.#offline(error);
        }

        await this.#storage.applyRemote(merged);

        // Rule 3: acknowledge only work we have *seen* in a published
        // revision, never work we merely uploaded. Google Drive offers no
        // compare-and-set, so a competing device can land a revision between
        // our freshness check and our upload. Acking on our own write alone
        // would let the loser of that race drop edits it had already marked
        // safe -- silent data loss, the exact failure this engine exists to
        // prevent. One extra read closes it.
        if (await this.#published(pending)) {
          await this.#storage.ack(pendingIds);
        }
        this.#lastPublishedAt = this.#now();
        this.#lastError = null;
        return { status: "published", version, pending: await this.#refreshPending() };
      }

      this.#lastError = "Another device kept changing the vault while we were saving.";
      return { status: "conflict-exhausted", pending: await this.#refreshPending() };
    } finally {
      this.#syncing = false;
    }
  }

  /**
   * Is every pending operation reflected in what the remote now holds?
   *
   * Uses the CRDT's idempotence: re-applying operations that are already
   * present cannot change the state, so an unchanged fingerprint proves they
   * survived. A failed read is treated as unproven, which keeps the work
   * queued rather than risking its loss.
   */
  async #published(pending: readonly VaultOp[]): Promise<boolean> {
    if (pending.length === 0) return true;
    try {
      const confirmed = await this.#remote.read();
      if (!confirmed) return false;
      return (
        fingerprint(applyOps(confirmed.state, pending)) === fingerprint(confirmed.state)
      );
    } catch {
      return false;
    }
  }

  async #offline(error: unknown): Promise<SyncOutcome> {
    if (!(error instanceof RemoteUnavailableError)) throw error;
    this.#lastError = error.message;
    return {
      status: "offline",
      pending: await this.#refreshPending(),
      reason: error.message,
    };
  }

  async #refreshPending(): Promise<number> {
    const pending = await this.#storage.pending();
    this.#pendingCount = pending.length;
    return this.#pendingCount;
  }
}

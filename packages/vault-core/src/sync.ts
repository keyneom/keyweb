import type { Clock, Hlc } from "./hlc.js";
import { HLC_ZERO } from "./hlc.js";
import { mergeVaults } from "./merge.js";
import type { ItemField, ItemRecord, VaultState } from "./model.js";
import { datasetOf, emptyVault, fingerprint, visibleItems } from "./model.js";
import type { VaultOp } from "./ops.js";
import { applyOp, applyOps } from "./ops.js";
import type { RemoteVaultStore, VaultStorage } from "./storage.js";
import { VAULT_DOCUMENT } from "./storage.js";
import {
  boundDatasets,
  composeVault,
  datasetForItem,
  extractDataset,
  withoutDatasetItems,
} from "./datasets.js";
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
    // Where a keyring lives is causal time like any other write. Skipping it
    // would let a device that has just learned of a binding stamp its next
    // edit *before* that binding, and lose to it.
    bump(ring.dataset.ts);
  }
  return max;
}

export type VaultSyncOptions = {
  storage: VaultStorage;
  remote: RemoteVaultStore;
  /**
   * The remote for a keyring that lives in its own document.
   *
   * Absent, or returning null, means that document has nowhere to publish
   * yet — which is the normal state for a keyring bound locally but whose
   * Drive file has not been made. Its edits stay queued in its own outbox
   * and go up when a remote appears, exactly as an offline vault's do.
   */
  remoteFor?: (documentId: string) => RemoteVaultStore | null;
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
  readonly #remoteFor: (documentId: string) => RemoteVaultStore | null;
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
    this.#remoteFor =
      options.remoteFor ??
      ((documentId) => (documentId === VAULT_DOCUMENT ? options.remote : null));
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

  /**
   * The vault as a person sees it: its own items plus every dataset's.
   *
   * Composed on every read rather than cached, because a cache here would be
   * one more thing that can disagree with storage, and the merge is cheap
   * beside the decryption that already happened to get the documents.
   */
  async state(): Promise<VaultState> {
    const vault = await this.#storage.readState();
    const datasets = await this.#readDatasets(vault);
    return datasets.size === 0 ? vault : composeVault(vault, datasets);
  }

  /**
   * One document's own state, without the rest of the vault composed in.
   *
   * For a caller that has to publish a document as its own thing — sharing a
   * keyring is the one — where composing would hand over every other keyring
   * as well.
   */
  documentState(documentId: string): Promise<VaultState> {
    return this.#storage.readState(documentId);
  }

  /** Every bound document this device actually holds. */
  async #readDatasets(vault: VaultState): Promise<Map<string, VaultState>> {
    const wanted = new Set(boundDatasets(vault).map((binding) => binding.datasetId));
    if (wanted.size === 0) return new Map();

    const held = new Set(await this.#storage.knownDocuments());
    const datasets = new Map<string, VaultState>();
    for (const datasetId of wanted) {
      // A keyring bound on another device arrives before its document does.
      // Skipping it shows the keyring empty rather than failing the read.
      if (held.has(datasetId)) {
        datasets.set(datasetId, await this.#storage.readState(datasetId));
      }
    }
    return datasets;
  }

  /**
   * Save one edit. Resolves only once the edit is durable, so a caller may
   * show "Saved" the moment this returns - and must not show it before.
   */
  async commit(op: VaultOp): Promise<VaultState> {
    return this.commitAll([op]);
  }

  /**
   * Save several edits as one durable write.
   *
   * Not a convenience wrapper around `commit`. Committing N edits separately
   * costs N reads and N writes of the *whole* vault — and since each write
   * re-encrypts everything and `pending()` re-reads the growing outbox, the
   * cost of deleting a keyring grew with the square of its size. Deleting 200
   * passwords meant 201 whole-vault encryptions and sixty thousand operation
   * decryptions, which is why it appeared to hang.
   *
   * The ops are folded in memory and persisted once, in one transaction, so
   * the vault is never left holding half a bulk change.
   */
  async commitAll(ops: readonly VaultOp[]): Promise<VaultState> {
    if (ops.length === 0) return this.state();

    // Every document is read once, here, and the result is composed from what
    // this produces rather than read back afterwards. Each read is a decrypt
    // of a whole document, so re-reading to answer "what does it look like
    // now" would double the cost of every edit.
    const vault = await this.#storage.readState();
    const datasets = await this.#readDatasets(vault);

    // Routed against the composed view, because deciding where an edit goes
    // needs to know which keyring an item is currently on, and for an item in
    // a shared keyring that fact lives in the dataset rather than the vault.
    const composed = datasets.size === 0 ? vault : composeVault(vault, datasets);
    const byDocument = new Map<string, VaultOp[]>();
    for (const op of ops) {
      const documentId = this.#documentFor(op, composed);
      const group = byDocument.get(documentId);
      if (group) group.push(op);
      else byDocument.set(documentId, [op]);
    }

    // The vault is written **last**, and that order is the only thing standing
    // between a crash and a lost password.
    //
    // An edit that moves a password between documents is a tombstone in the
    // one it left and a copy in the one it arrived at. Writing the tombstone
    // first and then failing would destroy the only copy. Writing the copy
    // first and then failing leaves the password in both places, which the
    // merge resolves the moment either document is read again.
    //
    // The cost of this order is that a dataset can briefly be a document the
    // vault does not yet point at. That is recoverable by repeating the
    // operation; the other way round is not recoverable by anything.
    let nextVault = vault;
    const order = [...byDocument.keys()].sort().reverse();
    for (const documentId of order) {
      const group = byDocument.get(documentId)!;
      const isVault = documentId === VAULT_DOCUMENT;
      let after = isVault ? vault : (datasets.get(documentId) ?? emptyVault());
      for (const op of group) after = applyOp(after, op);

      if (group.length === 1 && isVault) {
        await this.#storage.commit(group[0]!, after);
      } else {
        await this.#storage.commitAll(group, after, documentId);
      }

      if (isVault) nextVault = after;
      else datasets.set(documentId, after);
    }

    await this.#storage.writeClock(this.#clock.snapshot());
    await this.#refreshPending();
    return datasets.size === 0 ? nextVault : composeVault(nextVault, datasets);
  }

  /**
   * A fresh operation id and causal timestamp from this engine's clock.
   *
   * For callers that build their own operations — the KeePass import is the
   * one — so they can hand a whole batch to `commitAll`. The stamp has to come
   * from here rather than from the caller: the clock is what orders an import
   * against edits made in between, and a clock of the caller's own would not
   * be the one the engine persists.
   */
  stamp(): { opId: string; ts: Hlc } {
    return { opId: this.#newId(), ts: this.#clock.now() };
  }

  /** Stamp and delete several items in one write. */
  deleteItems(itemIds: readonly string[]): Promise<VaultState> {
    return this.commitAll(
      itemIds.map((itemId) => ({
        kind: "item.delete" as const,
        opId: this.#newId(),
        ts: this.#clock.now(),
        itemId,
      })),
    );
  }

  /**
   * Stamp and move several items in one write.
   *
   * A move within one document is just a change of keyring. A move that leaves
   * one document for another cannot be, for the reason `#relocation` explains:
   * the item would stay behind in the document it left. That matters most in
   * the direction people care about — dragging a password *out* of a shared
   * keyring has to actually take it away from the people it was shared with,
   * not merely stop showing it here.
   */
  async moveItems(itemIds: readonly string[], keyringId: string): Promise<VaultState> {
    const vault = await this.#storage.readState();
    const datasets = await this.#readDatasets(vault);
    const composed = datasets.size === 0 ? vault : composeVault(vault, datasets);
    const destination = datasetForItem(composed, keyringId) ?? VAULT_DOCUMENT;

    const ops: VaultOp[] = [];
    for (const itemId of itemIds) {
      const item = composed.items[itemId];
      if (!item) continue;
      const source = datasetForItem(composed, item.keyring.value) ?? VAULT_DOCUMENT;
      if (source === destination) {
        ops.push({
          kind: "item.move",
          opId: this.#newId(),
          ts: this.#clock.now(),
          itemId,
          keyringId,
        });
      } else {
        ops.push(...this.#relocation(item, keyringId));
      }
    }
    return this.commitAll(ops);
  }

  /**
   * Delete a keyring and the passwords in it, as one write.
   *
   * The items go first in the op order, so a reader replaying the outbox sees
   * them deleted in their own right rather than merely orphaned by a keyring
   * that vanished.
   */
  async deleteKeyringWithItems(keyringId: string): Promise<VaultState> {
    const current = await this.#storage.readState();
    const doomed = visibleItems(current).filter((item) => item.keyring.value === keyringId);
    return this.commitAll([
      ...doomed.map((item) => ({
        kind: "item.delete" as const,
        opId: this.#newId(),
        ts: this.#clock.now(),
        itemId: item.id,
      })),
      {
        kind: "keyring.delete" as const,
        opId: this.#newId(),
        ts: this.#clock.now(),
        keyringId,
      },
    ]);
  }

  /**
   * The two operations that carry one password from one document to another.
   *
   * A CRDT cannot forget. Merging never removes anything, so an item simply
   * left out of a document's next state comes straight back the moment that
   * document is joined with a revision that still has it — which is what made
   * the first attempt at this silently republish every password it had just
   * moved out. The only way to say "not here any more" is to say it in the
   * language the merge understands: a tombstone.
   *
   * So a relocation is a tombstone in the document it leaves and a full copy
   * in the document it arrives in, and the copy is stamped **after** the
   * tombstone. That ordering is what makes the pair survive being merged in
   * either order on any device: wherever the two meet, the live copy is the
   * later write and wins.
   *
   * The undo history does not travel. It is a local window onto values this
   * document once held, and carrying it into a keyring somebody else can read
   * would hand them superseded passwords they were never shown.
   */
  #relocation(item: ItemRecord, keyringId: string): VaultOp[] {
    const fields: Record<ItemField, string | undefined> = {};
    for (const [field, value] of Object.entries(item.fields)) fields[field] = value.value;
    return [
      { kind: "item.purge", opId: this.#newId(), ts: this.#clock.now(), itemId: item.id },
      {
        kind: "item.put",
        opId: this.#newId(),
        ts: this.#clock.now(),
        itemId: item.id,
        keyringId,
        fields,
      },
    ];
  }

  /** The live passwords currently on a keyring, wherever they are stored. */
  #itemsOn(composed: VaultState, keyringId: string): ItemRecord[] {
    return visibleItems(composed).filter((item) => item.keyring.value === keyringId);
  }

  /**
   * Move a keyring's passwords into their own document, so it can be shared.
   *
   * Two writes, and the dataset is written first. In between, the passwords
   * exist in both documents — harmless, because the tombstone that removes
   * them from the vault is the *second* write, and until it lands the vault
   * still holds the originals. The other order would delete them from the
   * vault before anything else held them.
   *
   * Nothing here encrypts, uploads, or talks to anyone. Binding is the local
   * rearrangement only; a bound keyring with no remote yet simply queues, and
   * sharing it with a person is a separate step.
   */
  async bindKeyring(keyringId: string, datasetId: string): Promise<VaultState> {
    if (!datasetId) throw new Error("A shared keyring needs a document to live in.");
    const vault = await this.#storage.readState();
    const datasets = await this.#readDatasets(vault);
    const composed = datasets.size === 0 ? vault : composeVault(vault, datasets);

    const keyring = composed.keyrings[keyringId];
    if (!keyring) throw new Error("That keyring doesn't exist.");
    const already = datasetOf(keyring);
    if (already === datasetId) return composed;
    if (already) throw new Error("That keyring already lives in its own document.");

    const moving = this.#itemsOn(composed, keyringId);
    const relocations = moving.map((item) => this.#relocation(item, keyringId));

    // Read rather than taken from the composed map, which only holds documents
    // that are *already* bound. This one is not, and a document that has just
    // been pulled down — a keyring somebody else shared — would otherwise be
    // overwritten with an empty one.
    const base = await this.#storage.readState(datasetId);

    // The name is written into the dataset only when the dataset does not
    // already agree with it. Writing it unconditionally would queue an
    // operation a *reader* has no right to publish, and their status line
    // would say "1 change still to back up" for as long as they kept the
    // keyring.
    const renames: VaultOp[] =
      base.keyrings[keyringId]?.name.value === keyring.name.value
        ? []
        : [
            {
              kind: "keyring.put",
              opId: this.#newId(),
              ts: this.#clock.now(),
              keyringId,
              name: keyring.name.value,
            },
          ];
    const datasetOps: VaultOp[] = [...renames, ...relocations.map(([, put]) => put!)];
    const dataset = applyOps(base, datasetOps);
    if (datasetOps.length > 0) {
      await this.#storage.commitAll(datasetOps, dataset, datasetId);
    }

    const vaultOps: VaultOp[] = [
      ...relocations.map(([tombstone]) => tombstone!),
      {
        kind: "keyring.bind",
        opId: this.#newId(),
        ts: this.#clock.now(),
        keyringId,
        datasetId,
      },
    ];
    const remaining = applyOps(vault, vaultOps);
    await this.#storage.commitAll(vaultOps, remaining, VAULT_DOCUMENT);

    datasets.set(datasetId, dataset);
    await this.#storage.writeClock(this.#clock.snapshot());
    await this.#refreshPending();
    return composeVault(remaining, datasets);
  }

  /**
   * Take a document's remote state in, for one that has just become readable.
   *
   * A keyring somebody shared exists in Drive before it exists here. This is
   * how its contents arrive before there is any binding pointing at them — a
   * join, in other words, not an assignment, so a document already holding
   * something keeps it.
   */
  adoptDocument(documentId: string, state: VaultState): Promise<VaultState> {
    return this.#storage.applyRemote(state, documentId);
  }

  /**
   * Bring a keyring's passwords home and stop treating it as shared.
   *
   * For the owner un-sharing something of their own. Deliberately *not*
   * symmetric with binding: the vault takes copies, and the shared document is
   * left exactly as it is rather than tombstoned. Tombstoning it would empty
   * the file out from under anyone still holding a grant, and un-sharing is
   * meant to stop new reading, not to reach into what somebody already has.
   *
   * Because the document keeps its contents, re-sharing this keyring must mint
   * a fresh dataset id. Re-binding to the old one would resurrect whatever it
   * still holds — including passwords deleted in the meantime.
   */
  async unbindKeyring(keyringId: string): Promise<VaultState> {
    const vault = await this.#storage.readState();
    const datasets = await this.#readDatasets(vault);
    const composed = datasets.size === 0 ? vault : composeVault(vault, datasets);

    const datasetId = datasetOf(composed.keyrings[keyringId]);
    if (!datasetId) return composed;

    const returning = this.#itemsOn(composed, keyringId);
    const ops: VaultOp[] = [
      ...returning.map((item) => this.#relocation(item, keyringId)[1]!),
      {
        kind: "keyring.put",
        opId: this.#newId(),
        ts: this.#clock.now(),
        keyringId,
        name: composed.keyrings[keyringId]?.name.value ?? "",
      },
      {
        kind: "keyring.bind",
        opId: this.#newId(),
        ts: this.#clock.now(),
        keyringId,
        datasetId: "",
      },
    ];

    const returned = applyOps(vault, ops);
    await this.#storage.commitAll(ops, returned, VAULT_DOCUMENT);

    datasets.delete(datasetId);
    await this.#storage.writeClock(this.#clock.snapshot());
    await this.#refreshPending();
    return datasets.size === 0 ? returned : composeVault(returned, datasets);
  }

  /**
   * Stop carrying a keyring somebody else shared.
   *
   * Deliberately not `deleteKeyringWithItems`. The passwords belong to the
   * person who shared them, and deleting them here would delete them for
   * everybody — the tombstones would publish straight back into the shared
   * document. So the keyring is tombstoned in *this* vault only, which the
   * routing rules guarantee stays local, and the binding is cleared so the
   * document stops syncing.
   *
   * What it cannot do is take back what was already read. Leaving is the app
   * forgetting a keyring, not the passwords becoming unseen.
   */
  async leaveKeyring(keyringId: string): Promise<VaultState> {
    const vault = await this.#storage.readState();

    const ops: VaultOp[] = [
      {
        kind: "keyring.bind",
        opId: this.#newId(),
        ts: this.#clock.now(),
        keyringId,
        datasetId: "",
      },
      {
        kind: "keyring.delete",
        opId: this.#newId(),
        ts: this.#clock.now(),
        keyringId,
      },
    ];

    await this.#storage.commitAll(ops, applyOps(vault, ops), VAULT_DOCUMENT);
    await this.#storage.writeClock(this.#clock.snapshot());
    await this.#refreshPending();
    return this.state();
  }

  // ---- Edit helpers: build a stamped operation and commit it. ----

  putItem(input: {
    itemId?: string;
    keyringId: string;
    fields: Record<ItemField, string | undefined>;
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

  /** One password to another keyring, crossing documents if it has to. */
  moveItem(itemId: string, keyringId: string): Promise<VaultState> {
    return this.moveItems([itemId], keyringId);
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

  /**
   * Sync the vault, then every keyring that lives in its own document.
   *
   * The vault goes first and its result is what the caller sees, because the
   * vault is what says which datasets exist — syncing them first would use
   * this device's idea of the bindings rather than the agreed one.
   *
   * A dataset that fails does not fail the whole sync. One shared keyring
   * whose file has been revoked or moved must not stop the rest of someone's
   * passwords from backing up; the failure is reported through the status
   * line, which already knows how to say that something is not backed up.
   */
  async #syncNow(): Promise<SyncOutcome> {
    const outcome = await this.#syncDocument(VAULT_DOCUMENT, this.#remote);

    const vault = await this.#storage.readState();
    for (const { datasetId } of boundDatasets(vault)) {
      const remote = this.#remoteFor(datasetId);
      if (!remote) continue;
      try {
        await this.#syncDocument(datasetId, remote);
      } catch {
        // Already recorded in #lastError by the document's own run.
      }
    }
    return { ...outcome, pending: await this.#refreshPending() };
  }

  async #syncDocument(
    documentId: string,
    store: RemoteVaultStore,
  ): Promise<SyncOutcome> {
    this.#syncing = true;
    try {
      for (let attempt = 0; attempt <= this.#maxConflictRetries; attempt += 1) {
        let remote: Awaited<ReturnType<RemoteVaultStore["read"]>>;
        try {
          remote = await store.read();
        } catch (error) {
          return this.#offline(error);
        }

        // Adopt remote causal time so our next local write sorts after it,
        // even if this device's wall clock is behind.
        if (remote) {
          this.#clock.observe(maxHlc(remote.state));
          await this.#storage.writeClock(this.#clock.snapshot());
        }

        const local = await this.#storage.readState(documentId);
        const base = remote ? remote.state : emptyVault();

        // Capture pending *before* the write. Anything committed after this
        // point stays queued for the next sync rather than being falsely
        // acknowledged.
        const pending = await this.#storage.pending(documentId);
        const pendingIds = pending.map((op) => op.opId);

        // Rule 2: fold every unacknowledged edit into what we are about to
        // publish. Idempotent, so re-applying already-published work is free.
        const merged = applyOps(mergeVaults(base, local), pending);

        if (remote && fingerprint(merged) === fingerprint(remote.state)) {
          await this.#storage.applyRemote(merged, documentId);
          await this.#storage.ack(pendingIds, documentId);
          this.#lastError = null;
          if (this.#lastPublishedAt === null) this.#lastPublishedAt = this.#now();
          return { status: "unchanged", pending: await this.#refreshPending() };
        }

        let version: string;
        try {
          version = await store.write(merged, remote ? remote.version : null);
        } catch (error) {
          if (error instanceof VersionConflictError) continue; // re-read, re-merge
          return this.#offline(error);
        }

        await this.#storage.applyRemote(merged, documentId);

        // Rule 3: acknowledge only work we have *seen* in a published
        // revision, never work we merely uploaded. Google Drive offers no
        // compare-and-set, so a competing device can land a revision between
        // our freshness check and our upload. Acking on our own write alone
        // would let the loser of that race drop edits it had already marked
        // safe -- silent data loss, the exact failure this engine exists to
        // prevent. One extra read closes it.
        if (await this.#published(pending, store)) {
          await this.#storage.ack(pendingIds, documentId);
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
  async #published(
    pending: readonly VaultOp[],
    store: RemoteVaultStore,
  ): Promise<boolean> {
    if (pending.length === 0) return true;
    try {
      const confirmed = await store.read();
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

  /**
   * Work still queued, across every document.
   *
   * Counted over all of them because the status line speaks for the whole
   * vault: "3 changes still to back up" must not omit the ones waiting in a
   * shared keyring, or someone is told they are safe when they are not.
   */
  async #refreshPending(): Promise<number> {
    let total = (await this.#storage.pending()).length;
    for (const documentId of await this.#storage.knownDocuments()) {
      total += (await this.#storage.pending(documentId)).length;
    }
    this.#pendingCount = total;
    return this.#pendingCount;
  }

  /**
   * Which document an operation belongs in.
   *
   * Item operations follow their keyring. An edit to a password in a shared
   * keyring belongs in that keyring's document; putting it in the vault
   * instead would leave this device looking correct while the other person
   * never saw the change.
   *
   * A rename follows the keyring too, once it is shared. The name is part of
   * what was shared — two people looking at the same keyring should not be
   * looking at differently-named things — so it belongs beside the items
   * rather than in a vault only one of them can read.
   *
   * Binding and deleting stay in the vault whatever happens. `keyring.bind`
   * is this device's record of where the items went, and writing it into the
   * document it is describing would be circular. `keyring.delete` is the
   * subtler one: for a keyring somebody else shared, "delete" means *leave*,
   * and a tombstone published into the shared document would delete it out
   * from under everyone else instead.
   */
  #documentFor(op: VaultOp, composed: VaultState): string {
    switch (op.kind) {
      case "keyring.delete":
      case "keyring.bind":
        return VAULT_DOCUMENT;
      case "keyring.put":
        return datasetOf(composed.keyrings[op.keyringId]) ?? VAULT_DOCUMENT;
      case "item.put":
        return datasetForItem(composed, op.keyringId) ?? VAULT_DOCUMENT;
      case "item.move":
        return datasetForItem(composed, op.keyringId) ?? VAULT_DOCUMENT;
      default:
        return (
          datasetForItem(composed, composed.items[op.itemId]?.keyring.value) ?? VAULT_DOCUMENT
        );
    }
  }
}

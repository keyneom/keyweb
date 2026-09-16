import type { Hlc } from "./hlc.js";
import type { HistoryEntry, ItemField, Reg, VaultState } from "./model.js";
import { HISTORY_LIMIT, newItem, newKeyring, pickReg, reg } from "./model.js";

/**
 * Every user edit is an operation before it is a state change. Operations are
 * durable, replayable, and idempotent: applying the same op twice produces the
 * same state, which is what makes crash-then-replay and sync retries safe.
 */
export type VaultOp =
  | {
      kind: "item.put";
      opId: string;
      ts: Hlc;
      itemId: string;
      keyringId: string;
      /**
       * Open-keyed, so a client can write a field this version has never heard
       * of. `undefined` is possible on an index and is skipped rather than
       * stored, since a register holding undefined is not a value.
       */
      fields: Record<ItemField, string | undefined>;
    }
  | { kind: "item.delete"; opId: string; ts: Hlc; itemId: string }
  /**
   * Tombstone an item *and* take its values with it.
   *
   * `item.delete` hides a password but leaves its value in the document, which
   * is what the undo window is built on. That is the wrong trade when the
   * document is one somebody else can read: dragging a password out of a
   * shared keyring has to actually take it away from the people it was shared
   * with, not merely stop showing it to them.
   *
   * So this blanks every field and drops the history as well. Still a set of
   * register writes rather than a removal, so it merges like everything else —
   * an edit made after the purge, on another device, still wins and brings the
   * value back, which is correct: that edit happened later.
   */
  | { kind: "item.purge"; opId: string; ts: Hlc; itemId: string }
  | { kind: "item.restore"; opId: string; ts: Hlc; itemId: string }
  | { kind: "item.move"; opId: string; ts: Hlc; itemId: string; keyringId: string }
  | { kind: "keyring.put"; opId: string; ts: Hlc; keyringId: string; name: string }
  | { kind: "keyring.delete"; opId: string; ts: Hlc; keyringId: string }
  /**
   * Move a keyring's items into their own document, or back into the vault.
   *
   * An empty `datasetId` means the vault. The operation records *where* the
   * items belong; actually moving them is the caller's job, because it spans
   * two documents and only the storage layer can do that atomically.
   */
  | { kind: "keyring.bind"; opId: string; ts: Hlc; keyringId: string; datasetId: string };

function pushHistory(history: HistoryEntry[], entry: HistoryEntry): HistoryEntry[] {
  const deduped = history.filter((h) => !(h.field === entry.field && h.ts === entry.ts));
  return [entry, ...deduped]
    .sort((a, b) => (a.ts > b.ts ? -1 : a.ts < b.ts ? 1 : 0))
    .slice(0, HISTORY_LIMIT);
}

/**
 * Apply one operation. Pure: returns a new state and never mutates the input.
 *
 * Idempotent by construction - every write goes through a register whose HLC
 * decides the winner, so replaying an op that is already reflected is a no-op.
 */
export function applyOp(state: VaultState, op: VaultOp): VaultState {
  switch (op.kind) {
    case "keyring.put": {
      const existing = state.keyrings[op.keyringId] ?? newKeyring(op.keyringId, op.name, op.ts);
      const next = {
        ...existing,
        name: pickReg(existing.name, reg(op.name, op.ts)) ?? reg(op.name, op.ts),
      };
      return { ...state, keyrings: { ...state.keyrings, [op.keyringId]: next } };
    }
    case "keyring.bind": {
      const existing = state.keyrings[op.keyringId] ?? newKeyring(op.keyringId, "", op.ts);
      const next = {
        ...existing,
        dataset: pickReg(existing.dataset, reg(op.datasetId, op.ts)) ?? reg(op.datasetId, op.ts),
      };
      return { ...state, keyrings: { ...state.keyrings, [op.keyringId]: next } };
    }
    case "keyring.delete": {
      const existing = state.keyrings[op.keyringId] ?? newKeyring(op.keyringId, "", op.ts);
      const next = {
        ...existing,
        deleted: pickReg(existing.deleted, reg(true, op.ts)) ?? reg(true, op.ts),
      };
      return { ...state, keyrings: { ...state.keyrings, [op.keyringId]: next } };
    }
    case "item.put": {
      const existing = state.items[op.itemId] ?? newItem(op.itemId, op.keyringId, op.ts);
      const fields = { ...existing.fields };
      let history = existing.history;
      for (const [field, value] of Object.entries(op.fields)) {
        if (value === undefined) continue;
        const incoming = reg(value, op.ts);
        const current = fields[field];
        const winner = pickReg(current, incoming) ?? incoming;
        // Retain a superseded value so an accidental overwrite is recoverable.
        if (current && winner.ts !== current.ts && current.value !== winner.value) {
          history = pushHistory(history, { field, value: current.value, ts: current.ts });
        }
        fields[field] = winner;
      }
      const next = {
        ...existing,
        keyring: pickReg(existing.keyring, reg(op.keyringId, op.ts)) ?? reg(op.keyringId, op.ts),
        // Saving an item revives it if it was deleted earlier. Losing a save is
        // the worse failure, so an edit that happened after a delete wins and
        // the item comes back; an edit from before the delete still loses.
        deleted: pickReg(existing.deleted, reg(false, op.ts)) ?? reg(false, op.ts),
        fields,
        history,
      };
      return { ...state, items: { ...state.items, [op.itemId]: next } };
    }
    case "item.delete":
    case "item.restore": {
      const wanted = op.kind === "item.delete";
      const existing = state.items[op.itemId] ?? newItem(op.itemId, "", op.ts);
      const next = {
        ...existing,
        deleted: pickReg(existing.deleted, reg(wanted, op.ts)) ?? reg(wanted, op.ts),
      };
      return { ...state, items: { ...state.items, [op.itemId]: next } };
    }
    case "item.purge": {
      const existing = state.items[op.itemId] ?? newItem(op.itemId, "", op.ts);
      const fields: Record<ItemField, Reg<string>> = {};
      // Only the fields this document actually holds can be blanked. A field
      // written by a newer client and not yet merged here is not in the record
      // to blank, and will be carried through untouched — the same limitation
      // every field-level write has.
      for (const [field, value] of Object.entries(existing.fields)) {
        fields[field] = pickReg(value, reg("", op.ts)) ?? reg("", op.ts);
      }
      const next = {
        ...existing,
        deleted: pickReg(existing.deleted, reg(true, op.ts)) ?? reg(true, op.ts),
        fields,
        history: [],
      };
      return { ...state, items: { ...state.items, [op.itemId]: next } };
    }
    case "item.move": {
      const existing = state.items[op.itemId] ?? newItem(op.itemId, op.keyringId, op.ts);
      const next = {
        ...existing,
        keyring: pickReg(existing.keyring, reg(op.keyringId, op.ts)) ?? reg(op.keyringId, op.ts),
      };
      return { ...state, items: { ...state.items, [op.itemId]: next } };
    }
  }
}

export function applyOps(state: VaultState, ops: readonly VaultOp[]): VaultState {
  return ops.reduce(applyOp, state);
}

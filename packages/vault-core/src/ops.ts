import type { Hlc } from "./hlc.js";
import type { HistoryEntry, ItemField, VaultState } from "./model.js";
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
      fields: Partial<Record<ItemField, string>>;
    }
  | { kind: "item.delete"; opId: string; ts: Hlc; itemId: string }
  | { kind: "item.restore"; opId: string; ts: Hlc; itemId: string }
  | { kind: "item.move"; opId: string; ts: Hlc; itemId: string; keyringId: string }
  | { kind: "keyring.put"; opId: string; ts: Hlc; keyringId: string; name: string }
  | { kind: "keyring.delete"; opId: string; ts: Hlc; keyringId: string };

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
      for (const [name, value] of Object.entries(op.fields)) {
        const field = name as ItemField;
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

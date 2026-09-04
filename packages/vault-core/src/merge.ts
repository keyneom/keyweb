import type {
  HistoryEntry,
  ItemField,
  ItemRecord,
  KeyringRecord,
  Reg,
  VaultState,
} from "./model.js";
import { HISTORY_LIMIT, pickReg } from "./model.js";

function mergeHistory(a: HistoryEntry[], b: HistoryEntry[]): HistoryEntry[] {
  const seen = new Set<string>();
  const all: HistoryEntry[] = [];
  for (const entry of [...a, ...b]) {
    const key = `${entry.field} ${entry.ts}`;
    if (seen.has(key)) continue;
    seen.add(key);
    all.push(entry);
  }
  return all.sort((x, y) => (x.ts > y.ts ? -1 : x.ts < y.ts ? 1 : 0)).slice(0, HISTORY_LIMIT);
}

function mergeItem(a: ItemRecord, b: ItemRecord): ItemRecord {
  const fields: Partial<Record<ItemField, Reg<string>>> = {};
  const names = new Set([...Object.keys(a.fields), ...Object.keys(b.fields)] as ItemField[]);
  for (const name of names) {
    const winner = pickReg(a.fields[name], b.fields[name]);
    if (winner) fields[name] = winner;
  }
  return {
    id: a.id,
    keyring: pickReg(a.keyring, b.keyring) ?? a.keyring,
    deleted: pickReg(a.deleted, b.deleted) ?? a.deleted,
    fields,
    history: mergeHistory(a.history, b.history),
  };
}

function mergeKeyring(a: KeyringRecord, b: KeyringRecord): KeyringRecord {
  return {
    id: a.id,
    name: pickReg(a.name, b.name) ?? a.name,
    deleted: pickReg(a.deleted, b.deleted) ?? a.deleted,
  };
}

/**
 * Join two vault states.
 *
 * This is a CRDT merge, so it is commutative (merge(a,b) equals merge(b,a)),
 * associative, and idempotent (merge(a,a) equals a). Those three properties are
 * the reason the sync engine can retry a failed publish, re-apply an operation
 * it already published, or process the same remote revision twice without ever
 * losing or duplicating a change.
 */
export function mergeVaults(a: VaultState, b: VaultState): VaultState {
  const items: Record<string, ItemRecord> = {};
  for (const id of new Set([...Object.keys(a.items), ...Object.keys(b.items)])) {
    const left = a.items[id];
    const right = b.items[id];
    if (left && right) items[id] = mergeItem(left, right);
    else if (left) items[id] = left;
    else if (right) items[id] = right;
  }
  const keyrings: Record<string, KeyringRecord> = {};
  for (const id of new Set([...Object.keys(a.keyrings), ...Object.keys(b.keyrings)])) {
    const left = a.keyrings[id];
    const right = b.keyrings[id];
    if (left && right) keyrings[id] = mergeKeyring(left, right);
    else if (left) keyrings[id] = left;
    else if (right) keyrings[id] = right;
  }
  return { items, keyrings };
}

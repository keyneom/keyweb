import type {
  HistoryEntry,
  ItemField,
  ItemRecord,
  KeyringRecord,
  Reg,
  VaultState,
} from "./model.js";
import {
  fieldsOf,
  historyOf,
  HISTORY_LIMIT,
  itemsOf,
  keyringsOf,
  pickReg,
  reg,
} from "./model.js";
import { HLC_ZERO } from "./hlc.js";

/**
 * Superseded values from both sides.
 *
 * Tolerates a missing list because Kotlin omits a property still holding its
 * default: an item a phone wrote that has never been overwritten arrives with
 * no `history` key at all, and merging one used to produce an item whose
 * `history` was `undefined` — which then threw wherever anything read it.
 */
function mergeHistory(a: HistoryEntry[] | undefined, b: HistoryEntry[] | undefined): HistoryEntry[] {
  const seen = new Set<string>();
  const all: HistoryEntry[] = [];
  for (const entry of [...(a ?? []), ...(b ?? [])]) {
    const key = `${entry.field} ${entry.ts}`;
    if (seen.has(key)) continue;
    seen.add(key);
    all.push(entry);
  }
  return all.sort((x, y) => (x.ts > y.ts ? -1 : x.ts < y.ts ? 1 : 0)).slice(0, HISTORY_LIMIT);
}

function mergeItem(a: ItemRecord, b: ItemRecord): ItemRecord {
  const fields: Record<ItemField, Reg<string>> = {};
  // Both read defensively: Kotlin omits a property still holding its default,
  // so an item a phone wrote with no fields arrives with no `fields` key.
  const aFields = fieldsOf(a);
  const bFields = fieldsOf(b);
  const names = new Set([...Object.keys(aFields), ...Object.keys(bFields)] as ItemField[]);
  for (const name of names) {
    const winner = pickReg(aFields[name], bFields[name]);
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
    // A register like the rest, so two devices that move the same keyring into
    // its own document at the same moment converge on one dataset instead of
    // each keeping their own and silently splitting the passwords in two.
    //
    // Falling back to an empty register rather than to `a.dataset`: a state
    // written before this field existed carries no register at all, and a
    // merge must not fault on one. Empty reads as "in the vault", which is
    // exactly what those states meant.
    dataset: pickReg(a.dataset, b.dataset) ?? reg("", HLC_ZERO),
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
/** The shape every reader expects, from the shape the wire is allowed to send. */
function normalise(item: ItemRecord): ItemRecord {
  if (item.fields !== undefined && item.history !== undefined) return item;
  return { ...item, fields: fieldsOf(item), history: historyOf(item) };
}

export function mergeVaults(a: VaultState, b: VaultState): VaultState {
  const items: Record<string, ItemRecord> = {};
  // Through the accessors: both maps are absent-meaning-empty on anything a
  // phone wrote, and a merge is the first thing that touches a remote read.
  const aItems = itemsOf(a);
  const bItems = itemsOf(b);
  for (const id of new Set([...Object.keys(aItems), ...Object.keys(bItems)])) {
    const left = aItems[id];
    const right = bItems[id];
    if (left && right) items[id] = mergeItem(left, right);
    // Normalised on the way through, not merely tolerated on the way in. An
    // item that only one side has is copied rather than merged, so without
    // this the absences a phone's wire format leaves out would travel onward
    // into the next thing that reads them.
    else if (left) items[id] = normalise(left);
    else if (right) items[id] = normalise(right);
  }
  const keyrings: Record<string, KeyringRecord> = {};
  const aRings = keyringsOf(a);
  const bRings = keyringsOf(b);
  for (const id of new Set([...Object.keys(aRings), ...Object.keys(bRings)])) {
    const left = aRings[id];
    const right = bRings[id];
    if (left && right) keyrings[id] = mergeKeyring(left, right);
    else if (left) keyrings[id] = left;
    else if (right) keyrings[id] = right;
  }
  return { items, keyrings };
}

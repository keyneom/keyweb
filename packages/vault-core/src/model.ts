import type { Hlc } from "./hlc.js";
import { HLC_ZERO } from "./hlc.js";

/**
 * The vault state is a CRDT: a map of registers, each carrying the HLC of the
 * write that produced it. Merging two states is a join — commutative,
 * associative, and idempotent — which is what lets a sync retry, a duplicate
 * delivery, or a crashed-then-replayed operation all be harmless.
 *
 * Resolution is per *field*, not per item, so two people editing the same
 * login at once keep both edits when they touched different fields.
 */

/** A single last-write-wins register. */
export type Reg<T> = { value: T; ts: Hlc };

export function reg<T>(value: T, ts: Hlc): Reg<T> {
  return { value, ts };
}

/** Take the later of two registers. Ties break on the encoded HLC, which
 *  embeds the node id, so every replica picks the same winner. */
export function pickReg<T>(a: Reg<T> | undefined, b: Reg<T> | undefined): Reg<T> | undefined {
  if (!a) return b;
  if (!b) return a;
  if (a.ts === b.ts) return a;
  return a.ts > b.ts ? a : b;
}

/** Fields we store on a login. Free-form so a schema change needs no migration. */
export const ITEM_FIELDS = ["title", "username", "password", "url", "note"] as const;
export type ItemField = (typeof ITEM_FIELDS)[number];

export type HistoryEntry = {
  field: ItemField;
  value: string;
  /** HLC of the write that is being superseded. */
  ts: Hlc;
};

/** How many superseded values to retain per item for the undo window. */
export const HISTORY_LIMIT = 12;

export type ItemRecord = {
  id: string;
  /** Which keyring the item lives on. Moving is itself a LWW register. */
  keyring: Reg<string>;
  fields: Partial<Record<ItemField, Reg<string>>>;
  /** Tombstone. Deletion is a register, not a removal, so a delete can lose
   *  to a later edit and an edit can lose to a later delete — deterministically. */
  deleted: Reg<boolean>;
  /** Superseded field values, newest first, capped at HISTORY_LIMIT. */
  history: HistoryEntry[];
};

export type KeyringRecord = {
  id: string;
  name: Reg<string>;
  deleted: Reg<boolean>;
};

export type VaultState = {
  items: Record<string, ItemRecord>;
  keyrings: Record<string, KeyringRecord>;
};

export function emptyVault(): VaultState {
  return { items: {}, keyrings: {} };
}

export function newItem(id: string, keyringId: string, ts: Hlc): ItemRecord {
  return {
    id,
    keyring: reg(keyringId, ts),
    fields: {},
    deleted: reg(false, HLC_ZERO),
    history: [],
  };
}

export function newKeyring(id: string, name: string, ts: Hlc): KeyringRecord {
  return { id, name: reg(name, ts), deleted: reg(false, HLC_ZERO) };
}

/** Items a person can actually see: not deleted, on a keyring that exists. */
export function visibleItems(state: VaultState): ItemRecord[] {
  return Object.values(state.items)
    .filter((item) => !item.deleted.value)
    .filter((item) => {
      const ring = state.keyrings[item.keyring.value];
      return ring !== undefined && !ring.deleted.value;
    });
}

export function itemField(item: ItemRecord, field: ItemField): string | undefined {
  return item.fields[field]?.value;
}

/**
 * A stable content fingerprint. Two states that would present identically to
 * the user produce the same string, so we can skip a pointless upload — but it
 * deliberately covers timestamps too, because a state whose registers advanced
 * is genuinely different and must be published for other devices to converge.
 */
export function fingerprint(state: VaultState): string {
  const items = Object.keys(state.items)
    .sort()
    .map((id) => {
      const item = state.items[id];
      if (!item) return "";
      const fields = Object.keys(item.fields)
        .sort()
        .map((name) => {
          const value = item.fields[name as ItemField];
          return value ? `${name}=${value.ts}:${value.value}` : "";
        })
        .join(",");
      return `${id}|${item.keyring.ts}:${item.keyring.value}|${item.deleted.ts}:${item.deleted.value}|${fields}`;
    })
    .join(";");
  const keyrings = Object.keys(state.keyrings)
    .sort()
    .map((id) => {
      const ring = state.keyrings[id];
      if (!ring) return "";
      return `${id}|${ring.name.ts}:${ring.name.value}|${ring.deleted.ts}:${ring.deleted.value}`;
    })
    .join(";");
  return `v1:${items}#${keyrings}`;
}

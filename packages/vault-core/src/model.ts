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
/**
 * `folder` and `tags` exist to preserve structure brought in from elsewhere.
 * KeePass and KeeWeb organise entries into nested groups and tag them, and
 * dropping that on import would turn an organised vault into an undifferentiated
 * list. They are plain fields so they inherit the same per-field merge as
 * everything else, with no CRDT machinery of their own.
 */
export const ITEM_FIELDS = [
  "title",
  "username",
  "password",
  "url",
  "note",
  /**
   * The TOTP shared secret, base32 as the site issues it.
   *
   * A secret like the password, stored and sealed identically. Keeping it on
   * the item rather than in a separate store is what lets one unlock produce
   * both halves of a sign-in; the security argument for that is in
   * `docs/two-factor.md`.
   */
  "otp",
  "folder",
  "tags",
] as const;
/**
 * A field key.
 *
 * Deliberately open rather than a union of [ITEM_FIELDS]. Those are the keys
 * Keyweb knows how to present nicely; they are not the only keys that may
 * appear. A newer client — or a different item kind, or a custom field someone
 * added — will write keys this version has never heard of, and the merge must
 * carry them through untouched.
 *
 * Closing this type was a forward-compatibility hazard rather than a safety
 * feature: the Kotlin port enforced it at parse time, so a single unrecognised
 * key made the *entire vault* unreadable on an older device rather than merely
 * hiding one field.
 */
export type ItemField = string;

/** The keys Keyweb gives special presentation to. */
export type KnownItemField = (typeof ITEM_FIELDS)[number];

/** True for a field whose value must never be shown without asking. */
export function isSecretField(field: ItemField): boolean {
  return (
    field === "password" ||
    field === "otp" ||
    field === "seedPhrase" ||
    field === "privateKey" ||
    field === "pin" ||
    field.startsWith("secret:")
  );
}

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
  /**
   * Open-keyed: an index yields `Reg<string> | undefined` under
   * `noUncheckedIndexedAccess`, so absence is already in the type and `Partial`
   * would only make it doubly optional.
   */
  fields: Record<ItemField, Reg<string>>;
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

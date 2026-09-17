import type { Hlc } from "./hlc.js";
import { decodeHlc, HLC_ZERO } from "./hlc.js";

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
  /**
   * Which template presents this item: "login", "card", "wallet", and so on.
   *
   * A field rather than a column, so an item whose kind this version does not
   * recognise still merges and still shows every value it carries.
   */
  "kind",
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

/**
 * Key shapes Keyweb gives its own meaning to, which a field name must not wear.
 *
 * `secret:` masks a value, `custom:` keeps an imported name from acting like
 * one of Keyweb's own, and `file:` points a password at an attached file.
 */
const RESERVED_PREFIXES = ["secret:", "custom:", "file:"];

/**
 * The key a field is stored under, given the name its owner gave it.
 *
 * One rule, in one place, because three callers need to agree on it exactly:
 * the KeePass import on each platform, and the editor where somebody types a
 * field name themselves. A field the phone stores as `secret:Answer` and the
 * browser stores as `Answer` is one field that has silently become two.
 *
 * A name colliding with one of Keyweb's own keys, or wearing one of its own
 * prefixes, is pushed under `custom:` rather than allowed to act like the real
 * thing — a field called "folder" must not move the entry, and one called
 * `file:x` must not appear in the files list as an attachment that will never
 * arrive.
 */
export function storedFieldName(name: string, secret: boolean): ItemField {
  const collides =
    (ITEM_FIELDS as readonly string[]).includes(name.toLowerCase()) ||
    RESERVED_PREFIXES.some((prefix) => name.startsWith(prefix));
  const safe = collides ? `custom:${name}` : name;
  return secret ? `secret:${safe}` : safe;
}

/**
 * What to call a field on screen.
 *
 * `secret:` and `custom:` are how a field is *stored* — one says the value is
 * masked, the other keeps an imported name from colliding with one of Keyweb's
 * own keys. Neither is the name its owner gave it, and both were being stripped
 * by hand in four places that could drift apart.
 *
 * Also what makes two keys comparable: a field the old import stored as
 * `secret:Account number` and the same field stored today as `Account number`
 * have one label between them, which is how the import recognises its own
 * earlier mistake.
 */
export function fieldLabel(field: ItemField): string {
  return field.replace(/^secret:/, "").replace(/^custom:/, "");
}

/**
 * A value this item used to hold, for showing somebody what changed.
 *
 * The vault has kept superseded values per field since the CRDT was written —
 * that is what `HistoryEntry` is — and nothing on either platform ever showed
 * them. The KeePass import then started replaying years of somebody's earlier
 * passwords into the same place, which made an invisible feature into an
 * invisible *import result*: carried across, synced, backed up, and impossible
 * to look at.
 */
export type PastValue = {
  field: ItemField;
  /** The name to show, without the prefixes that say how it is stored. */
  label: string;
  value: string;
  /** Masked until asked for, on the same rule as the live field. */
  secret: boolean;
  /** When this value was replaced, from the HLC of the write it lost to. */
  atMs: number;
};

/**
 * What this item used to hold, newest first.
 *
 * Blank values are left out. A field that was empty and then filled in leaves
 * an empty entry behind, and "this used to be nothing" is not a thing anybody
 * came here to read.
 */
export function pastValues(item: ItemRecord): PastValue[] {
  return item.history
    .filter((entry) => entry.value !== "")
    .map((entry) => ({
      field: entry.field,
      label: fieldLabel(entry.field),
      value: entry.value,
      secret: isSecretField(entry.field),
      atMs: decodeHlc(entry.ts).wall,
    }))
    .sort((a, b) => b.atMs - a.atMs);
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
  /**
   * Where this keyring's items live, when it is not the vault.
   *
   * Empty means local: the items sit in the vault document alongside every
   * other keyring's, which is how every keyring works until someone shares
   * one. A dataset id means they live in their own document, with their own
   * key, in their own Drive file.
   *
   * That split exists because Drive shares *files*, not parts of files. A
   * recipient needs access to the file the keyring is in, so a shared keyring
   * that stayed in the vault document would hand them every other keyring's
   * ciphertext as well — unreadable today, held indefinitely, and one cipher
   * weakness away from being readable. The reasoning in full is in
   * `docs/keyring-sharing.md`.
   *
   * A register like everything else, so two devices deciding to share the same
   * keyring at once converge rather than producing two datasets.
   */
  dataset: Reg<string>;
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
  return {
    id,
    name: reg(name, ts),
    deleted: reg(false, HLC_ZERO),
    dataset: reg("", HLC_ZERO),
  };
}

/** Where a keyring's items are kept, or null when they are in the vault. */
export function datasetOf(keyring: KeyringRecord | undefined): string | null {
  const id = keyring?.dataset?.value;
  return id ? id : null;
}

/** Items a person can actually see: not deleted, on a keyring that exists. */
/**
 * A file attached to a password, stored as an item of its own.
 *
 * Deliberately an ordinary item rather than a new kind of record. It then
 * inherits every property the vault already guarantees without a line of new
 * machinery: it is encrypted by the same envelope, merged by the same CRDT,
 * carried into a shared keyring's document by the same extraction, tombstoned
 * by the same delete and scrubbed by the same purge. A parallel blob store
 * would have had to re-earn all of that, and would have got some of it wrong.
 *
 * It lives on the same keyring as the password it belongs to, which is what
 * makes sharing work for nothing: sharing a keyring moves its items, and the
 * files are items.
 */
export const BLOB_KIND = "blob";

/** Items that are files rather than passwords. */
export function isBlobItem(item: ItemRecord): boolean {
  return item.fields["kind"]?.value === BLOB_KIND;
}

/**
 * The field on a password that points at one of its files.
 *
 * One field per file rather than a list in a single field, so attaching and
 * removing are ordinary per-field writes and two devices doing both at once
 * merge instead of overwriting each other's list.
 */
export function attachmentField(blobId: string): ItemField {
  return `file:${blobId}`;
}

/** Every file attached to an item: the blob's id, and the name to show. */
export function attachmentsOf(item: ItemRecord): { blobId: string; name: string }[] {
  return Object.entries(item.fields)
    .filter(([field, value]) => field.startsWith("file:") && value.value !== "")
    .map(([field, value]) => ({ blobId: field.slice("file:".length), name: value.value }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Items a person can actually see: not deleted, on a keyring that exists, and
 * not one of the files hanging off another item.
 *
 * Files are excluded here rather than stored somewhere separate because this
 * is the only question they are the wrong answer to — every other part of the
 * vault should and does treat them as the ordinary items they are.
 */
export function visibleItems(state: VaultState): ItemRecord[] {
  return itemsOnKeyrings(state).filter((item) => !isBlobItem(item));
}

/**
 * Everything live on a keyring that exists, files included.
 *
 * What moves when a keyring moves. Using `visibleItems` here would leave a
 * password's files behind in the document it came from — present, orphaned,
 * and readable by whoever still has that document.
 */
export function itemsOnKeyrings(state: VaultState): ItemRecord[] {
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
      const base = `${id}|${ring.name.ts}:${ring.name.value}|${ring.deleted.ts}:${ring.deleted.value}`;
      // Appended only once the register has actually been written, so a vault
      // with no shared keyrings fingerprints byte-for-byte as it always did and
      // the cross-platform fixtures stay valid. Omitting it entirely was a bug:
      // binding a keyring to a dataset changes nothing else in the vault, so
      // the sync saw an unchanged fingerprint, skipped the upload, and the
      // other devices never learned where the keyring's items had moved to.
      // A state written before this field existed carries no register at all,
      // and a fingerprint must not fault on one — the cross-platform fixtures
      // are exactly such states.
      const dataset = ring.dataset;
      return !dataset || dataset.ts === HLC_ZERO
        ? base
        : `${base}|${dataset.ts}:${dataset.value}`;
    })
    .join(";");
  return `v1:${items}#${keyrings}`;
}

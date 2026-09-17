import { decodeHlc } from "./hlc.js";
import { itemField, type ItemRecord, type KeyringRecord } from "./model.js";

/**
 * How a list is ordered, as one choice rather than two.
 *
 * Deliberately not a field plus an ascending/descending arrow. That is two
 * controls to understand and a state ("descending") that means nothing until
 * you also know what it applies to — is descending by name A–Z or Z–A? Naming
 * both ends of each ordering says exactly what will happen before it happens,
 * which is the whole point of offering the control.
 *
 * Shared between platforms so a vault sorted on a phone and the same vault
 * sorted in a browser put the same thing at the top.
 */
export type ItemSort = "name-az" | "name-za" | "newest" | "oldest";

export const ITEM_SORTS: { value: ItemSort; label: string }[] = [
  { value: "name-az", label: "Name (A–Z)" },
  { value: "name-za", label: "Name (Z–A)" },
  { value: "newest", label: "Changed most recently" },
  { value: "oldest", label: "Unchanged the longest" },
];

export type KeyringSort = "name-az" | "name-za" | "most" | "fewest";

export const KEYRING_SORTS: { value: KeyringSort; label: string }[] = [
  { value: "name-az", label: "Name (A–Z)" },
  { value: "name-za", label: "Name (Z–A)" },
  { value: "most", label: "Most passwords first" },
  { value: "fewest", label: "Fewest passwords first" },
];

/**
 * When this item last changed, from the writes it carries.
 *
 * Read off the CRDT rather than stored as a field. Every register already
 * knows the HLC of the write that produced it, so the newest of them is the
 * moment the item last changed — and it stays correct through a merge, because
 * the merge is what picks those registers in the first place. A separate
 * `updatedAt` field would be a second source of truth that two devices could
 * disagree about.
 *
 * `folder`, `tags` and the file pointers count too: moving a password into a
 * folder is a change to it, and so is attaching a scan.
 */
export function lastChangedAt(item: ItemRecord): number {
  let newest = decodeHlc(item.keyring.ts).wall;
  for (const register of Object.values(item.fields)) {
    const wall = decodeHlc(register.ts).wall;
    if (wall > newest) newest = wall;
  }
  return newest;
}

/** A stable order: ties break on the name, then the id, so it never shuffles. */
function byName(a: ItemRecord, b: ItemRecord): number {
  const an = (itemField(a, "title") ?? "").toLowerCase();
  const bn = (itemField(b, "title") ?? "").toLowerCase();
  return an.localeCompare(bn) || a.id.localeCompare(b.id);
}

export function sortItems(items: ItemRecord[], sort: ItemSort): ItemRecord[] {
  const out = [...items];
  switch (sort) {
    case "name-az":
      return out.sort(byName);
    case "name-za":
      return out.sort((a, b) => -byName(a, b));
    case "newest":
      return out.sort((a, b) => lastChangedAt(b) - lastChangedAt(a) || byName(a, b));
    case "oldest":
      return out.sort((a, b) => lastChangedAt(a) - lastChangedAt(b) || byName(a, b));
  }
}

export function sortKeyrings(
  keyrings: KeyringRecord[],
  /** How many passwords each one holds, by keyring id. */
  counts: Record<string, number>,
  sort: KeyringSort,
): KeyringRecord[] {
  const out = [...keyrings];
  const name = (a: KeyringRecord, b: KeyringRecord) =>
    a.name.value.toLowerCase().localeCompare(b.name.value.toLowerCase()) ||
    a.id.localeCompare(b.id);
  const size = (ring: KeyringRecord) => counts[ring.id] ?? 0;

  switch (sort) {
    case "name-az":
      return out.sort(name);
    case "name-za":
      return out.sort((a, b) => -name(a, b));
    case "most":
      return out.sort((a, b) => size(b) - size(a) || name(a, b));
    case "fewest":
      return out.sort((a, b) => size(a) - size(b) || name(a, b));
  }
}

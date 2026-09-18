import { describe, expect, it } from "vitest";
import {
  attachmentsOf,
  fingerprint,
  isBlobItem,
  itemField,
  mergeVaults,
  pastValues,
  visibleItems,
  type ItemRecord,
  type VaultState,
} from "../src/index.js";

/**
 * An item exactly as a phone puts it on the wire.
 *
 * Kotlin omits a property that still holds its default, so `fields` is absent
 * when an item has none and `history` is absent when it has never been
 * overwritten — which is almost every password, because most are written once
 * and never edited. Both absences mean "empty" and the Kotlin decoder puts the
 * default back, so they are valid; the web threw on them.
 *
 * That was not theoretical. The detail screen reads `history` on every item it
 * shows, so a browser restoring a phone's vault would have opened the first
 * password it was offered and rendered nothing at all.
 */
const fromPhone = {
  id: "a",
  keyring: { value: "ring", ts: "001700000000000-00000-phone" },
  deleted: { value: false, ts: "000000000000000-00000-" },
} as unknown as ItemRecord;

const phoneVault = {
  items: { a: fromPhone },
  keyrings: {
    ring: {
      id: "ring",
      name: { value: "Home", ts: "001700000000000-00000-phone" },
      deleted: { value: false, ts: "000000000000000-00000-" },
      dataset: { value: "", ts: "000000000000000-00000-" },
    },
  },
} as unknown as VaultState;

describe("reading what a phone actually writes", () => {
  it("does not throw on an item with no fields and no history", () => {
    expect(itemField(fromPhone, "title")).toBeUndefined();
    expect(pastValues(fromPhone)).toEqual([]);
    expect(attachmentsOf(fromPhone)).toEqual([]);
    expect(isBlobItem(fromPhone)).toBe(false);
  });

  it("shows it in the list rather than crashing the screen", () => {
    expect(visibleItems(phoneVault)).toHaveLength(1);
  });

  it("fingerprints it, so a pointless upload is still skipped", () => {
    expect(() => fingerprint(phoneVault)).not.toThrow();
  });

  /**
   * And merging fills the absences in, so an item that has been through a
   * merge is the normal shape again rather than carrying `undefined` forward
   * into the next thing that reads it.
   */
  it("normalises when merged", () => {
    const merged = mergeVaults({ items: {}, keyrings: {} }, phoneVault);
    expect(merged.items.a!.history).toEqual([]);
    expect(merged.items.a!.fields).toEqual({});
  });
});

import { describe, expect, it } from "vitest";
import {
  applyOps,
  attachmentsOf,
  csvOmissions,
  exportVault,
  fingerprint,
  isBlobItem,
  itemField,
  maxHlc,
  mergeVaults,
  pastValues,
  sortItems,
  sortKeyrings,
  visibleItems,
  type VaultState,
} from "../src/index.js";

/**
 * The whole wire shape a phone is allowed to send, through every reader.
 *
 * Kotlin omits a property that still holds its default, so five members are
 * absent-meaning-empty on anything a phone wrote: `items`, `keyrings`,
 * `fields`, `history` and `dataset`. Each one has now cost a crash or a silent
 * wrong answer on the web, and each was found alone, in production, by
 * somebody using the app.
 *
 * `dataset` was the expensive one. `maxHlc` read `.ts` straight off it on the
 * result of every single read, so "Cannot read properties of undefined
 * (reading 'ts')" fired before anything else could go right.
 *
 * So this stops testing them one at a time. The state below is what the wire
 * legitimately carries at its most minimal, and every reader has to survive
 * it.
 */
const asMinimalAsItGets = {
  keyrings: {
    ring: {
      id: "ring",
      name: { value: "Home", ts: "001700000000000-00000-phone" },
      deleted: { value: false, ts: "000000000000000-00000-" },
      // no `dataset`
    },
  },
  items: {
    one: {
      id: "one",
      keyring: { value: "ring", ts: "001700000000000-00000-phone" },
      deleted: { value: false, ts: "000000000000000-00000-" },
      // no `fields`, no `history`
    },
  },
} as unknown as VaultState;

/** A vault with nothing in it at all: both maps absent, not empty. */
const emptyOnTheWire = {} as unknown as VaultState;

describe("every reader survives what a phone actually sends", () => {
  it("does not throw reading causal time, which runs after every read", () => {
    expect(() => maxHlc(asMinimalAsItGets)).not.toThrow();
    expect(() => maxHlc(emptyOnTheWire)).not.toThrow();
  });

  it("fingerprints it, so a pointless upload is still skipped", () => {
    expect(() => fingerprint(asMinimalAsItGets)).not.toThrow();
    expect(() => fingerprint(emptyOnTheWire)).not.toThrow();
  });

  it("lists, sorts and browses it", () => {
    const items = visibleItems(asMinimalAsItGets);
    expect(items).toHaveLength(1);
    expect(() => sortItems(items, "newest")).not.toThrow();
    expect(() => sortItems(items, "name-az")).not.toThrow();
    expect(() =>
      sortKeyrings(Object.values(asMinimalAsItGets.keyrings), { ring: 1 }, "most"),
    ).not.toThrow();
    expect(visibleItems(emptyOnTheWire)).toEqual([]);
  });

  it("reads every field of an item that has none", () => {
    const item = asMinimalAsItGets.items.one!;
    expect(itemField(item, "title")).toBeUndefined();
    expect(pastValues(item)).toEqual([]);
    expect(attachmentsOf(item)).toEqual([]);
    expect(isBlobItem(item)).toBe(false);
  });

  it("merges it, and fills the absences in on the way through", () => {
    const merged = mergeVaults(emptyOnTheWire, asMinimalAsItGets);
    expect(merged.items.one!.fields).toEqual({});
    expect(merged.items.one!.history).toEqual([]);
    expect(() => maxHlc(merged)).not.toThrow();
  });

  it("applies an operation on top of it", () => {
    const after = applyOps(asMinimalAsItGets, [
      {
        kind: "item.put",
        opId: "op",
        ts: "001700000000001-00000-web",
        itemId: "one",
        keyringId: "ring",
        fields: { title: "Bank" },
      },
    ]);
    expect(itemField(after.items.one!, "title")).toBe("Bank");
  });

  it("exports it", () => {
    expect(() => exportVault(asMinimalAsItGets)).not.toThrow();
    expect(() => csvOmissions(exportVault(asMinimalAsItGets))).not.toThrow();
  });
});

import { describe, expect, it } from "vitest";
import {
  applyOps,
  emptyVault,
  encodeHlc,
  itemField,
  lastChangedAt,
  sortItems,
  sortKeyrings,
  type ItemRecord,
} from "../src/index.js";

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "test" });
}

function vault() {
  return applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k1", ts: at(0), keyringId: "home", name: "Home" },
    { kind: "keyring.put", opId: "k2", ts: at(0), keyringId: "work", name: "work" },
    { kind: "keyring.put", opId: "k3", ts: at(0), keyringId: "old", name: "Archive" },
    { kind: "item.put", opId: "a", ts: at(1), itemId: "a", keyringId: "home", fields: { title: "Zebra" } },
    { kind: "item.put", opId: "b", ts: at(2), itemId: "b", keyringId: "home", fields: { title: "apple" } },
    { kind: "item.put", opId: "c", ts: at(3), itemId: "c", keyringId: "work", fields: { title: "Mango" } },
  ]);
}

const items = (): ItemRecord[] => Object.values(vault().items);
const titles = (list: ItemRecord[]) => list.map((i) => itemField(i, "title"));

describe("finding things fast", () => {
  /** Case must not split the alphabet, or "apple" lands after "Zebra". */
  it("orders by name in both directions, ignoring case", () => {
    expect(titles(sortItems(items(), "name-az"))).toEqual(["apple", "Mango", "Zebra"]);
    expect(titles(sortItems(items(), "name-za"))).toEqual(["Zebra", "Mango", "apple"]);
  });

  it("orders by when something last changed", () => {
    expect(titles(sortItems(items(), "newest"))).toEqual(["Mango", "apple", "Zebra"]);
    expect(titles(sortItems(items(), "oldest"))).toEqual(["Zebra", "apple", "Mango"]);
  });

  /**
   * Read off the CRDT rather than a stored field, so it stays right through a
   * merge — and so a later edit to *any* field counts, including moving the
   * item to another keyring or attaching a file to it.
   */
  it("takes the newest write on the item, whichever field it was", () => {
    const state = applyOps(vault(), [
      { kind: "item.put", opId: "z", ts: at(99), itemId: "a", keyringId: "home", fields: { note: "later" } },
    ]);
    expect(lastChangedAt(state.items.a!)).toBe(1_700_000_000_099);
    expect(titles(sortItems(Object.values(state.items), "newest"))[0]).toBe("Zebra");
  });

  it("orders keyrings by name or by how much is in them", () => {
    const rings = Object.values(vault().keyrings);
    const counts = { home: 2, work: 1, old: 0 };
    const names = (list: typeof rings) => list.map((r) => r.name.value);

    expect(names(sortKeyrings(rings, counts, "name-az"))).toEqual(["Archive", "Home", "work"]);
    expect(names(sortKeyrings(rings, counts, "name-za"))).toEqual(["work", "Home", "Archive"]);
    expect(names(sortKeyrings(rings, counts, "most"))).toEqual(["Home", "work", "Archive"]);
    expect(names(sortKeyrings(rings, counts, "fewest"))).toEqual(["Archive", "work", "Home"]);
  });

  /**
   * A list that reshuffles between renders is a list you cannot point at. Two
   * keyrings with the same count must come out in the same order every time.
   */
  it("never leaves a tie to chance", () => {
    const rings = Object.values(vault().keyrings);
    const counts = { home: 1, work: 1, old: 1 };
    const once = sortKeyrings(rings, counts, "most").map((r) => r.id);
    const again = sortKeyrings([...rings].reverse(), counts, "most").map((r) => r.id);
    expect(again).toEqual(once);
  });
});

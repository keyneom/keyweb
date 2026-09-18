import { describe, expect, it } from "vitest";
import {
  applyOps,
  allFolders,
  browseFolders,
  emptyVault,
  encodeHlc,
  folderPath,
  itemField,
  visibleItems,
} from "../src/index.js";

/**
 * The folders an import has been preserving and nothing ever showed.
 *
 * The `folder` field has always held the full nested path, so two folders
 * called "Banks" under two different top-level groups have never collided.
 * What was missing is that nothing read it, so an organised KeePass database
 * arrived looking like one flat list.
 */

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "test" });
}

function put(n: number, id: string, keyringId: string, title: string, folder: string) {
  return {
    kind: "item.put",
    opId: `op-${n}`,
    ts: at(n),
    itemId: id,
    keyringId,
    fields: { title, folder },
  } as const;
}

/** Two top-level groups, each with a "Banks" inside it. The user's question. */
function vault() {
  return applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k1", ts: at(0), keyringId: "leslie", name: "Leslie" },
    { kind: "keyring.put", opId: "k2", ts: at(0), keyringId: "mika", name: "Mika" },
    put(1, "l-chase", "leslie", "Chase", "Leslie / Banks"),
    put(2, "l-amex", "leslie", "Amex", "Leslie / Banks / Cards"),
    put(3, "l-gas", "leslie", "Gas", "Leslie / Utilities"),
    put(4, "l-loose", "leslie", "Loose", "Leslie"),
    put(5, "m-wells", "mika", "Wells Fargo", "Mika / Banks"),
  ]);
}

describe("finding things by folder", () => {
  /**
   * The bug the earlier tests here walked straight past.
   *
   * Every case below filtered to one keyring before browsing, which is what
   * the screen does only when a keyring chip is selected. With "All" selected
   * the list holds every keyring at once, each path was computed relative to
   * its *own* keyring, and Leslie's "Banks" and Mika's "Banks" became one
   * folder holding both. The data never collided; the view invented the
   * collision, which is worse — the two passwords looked like they were in
   * one place.
   */
  it("does not merge two keyrings' folders when showing all of them", () => {
    const state = vault();
    const all = visibleItems(state);

    const top = browseFolders(all, state, [], null);
    // The keyrings, not their insides: at this level "Banks" is ambiguous and
    // the honest answer is to say which one first.
    expect(top.folders.map((f) => f.name)).toEqual(["Leslie", "Mika"]);
    expect(top.folders.every((f) => f.kind === "keyring")).toBe(true);

    const leslie = browseFolders(all, state, ["Leslie"], null);
    expect(leslie.folders.map((f) => f.name)).toEqual(["Banks", "Utilities"]);
    expect(leslie.folders.every((f) => f.kind === "folder")).toBe(true);

    expect(
      browseFolders(all, state, ["Leslie", "Banks"], null).items.map((i) => itemField(i, "title")),
    ).toEqual(["Chase"]);
    expect(
      browseFolders(all, state, ["Mika", "Banks"], null).items.map((i) => itemField(i, "title")),
    ).toEqual(["Wells Fargo"]);
  });

  /** The question that prompted this: do the two "Banks" collide? */
  it("keeps two folders of the same name under different keyrings apart", () => {
    const state = vault();
    const items = visibleItems(state);

    const leslie = items.filter((item) => item.keyring.value === "leslie");
    const mika = items.filter((item) => item.keyring.value === "mika");

    expect(browseFolders(leslie, state, ["Banks"], "leslie").items.map((i) => itemField(i, "title"))).toEqual([
      "Chase",
    ]);
    expect(browseFolders(mika, state, ["Banks"], "mika").items.map((i) => itemField(i, "title"))).toEqual([
      "Wells Fargo",
    ]);
  });

  /**
   * The keyring's own name is the first segment of every path in it, and
   * showing it would open every vault onto a folder that contains everything
   * and is named after the thing you just opened.
   */
  it("does not repeat the keyring as a folder inside itself", () => {
    const state = vault();
    expect(folderPath(state.items["l-chase"]!, state)).toEqual(["Banks"]);
    expect(folderPath(state.items["l-loose"]!, state)).toEqual([]);
  });

  it("shows the folders and the loose items at one level", () => {
    const state = vault();
    const leslie = visibleItems(state).filter((i) => i.keyring.value === "leslie");

    const top = browseFolders(leslie, state, [], "leslie");
    expect(top.folders.map((f) => f.name)).toEqual(["Banks", "Utilities"]);
    expect(top.items.map((i) => itemField(i, "title"))).toEqual(["Loose"]);
  });

  /**
   * Counted all the way down. A folder showing "0" that opens onto three
   * subfolders full of passwords is a folder nobody opens.
   */
  it("counts everything beneath a folder, not only what is directly in it", () => {
    const state = vault();
    const leslie = visibleItems(state).filter((i) => i.keyring.value === "leslie");
    const banks = browseFolders(leslie, state, [], "leslie").folders.find((f) => f.name === "Banks")!;
    expect(banks.count).toBe(2);
  });

  it("descends", () => {
    const state = vault();
    const leslie = visibleItems(state).filter((i) => i.keyring.value === "leslie");
    const banks = browseFolders(leslie, state, ["Banks"], "leslie");
    expect(banks.folders.map((f) => f.name)).toEqual(["Cards"]);
    expect(banks.items.map((i) => itemField(i, "title"))).toEqual(["Chase"]);
    expect(
      browseFolders(leslie, state, ["Banks", "Cards"], "leslie").items.map((i) => itemField(i, "title")),
    ).toEqual(["Amex"]);
  });

  it("lists every folder that exists, for moving something into one", () => {
    const state = vault();
    const leslie = visibleItems(state).filter((i) => i.keyring.value === "leslie");
    expect(allFolders(leslie, state)).toEqual([
      ["Banks"],
      ["Banks", "Cards"],
      ["Utilities"],
    ]);
  });
});

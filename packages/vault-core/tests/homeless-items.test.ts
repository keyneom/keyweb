import { describe, expect, it } from "vitest";
import {
  applyOps,
  browseFolders,
  emptyVault,
  encodeHlc,
  exportVault,
  itemField,
  itemsWithoutKeyring,
  keyringLabel,
  liveKeyrings,
  NO_KEYRING,
  visibleItems,
} from "../src/index.js";

/**
 * A password saved against a keyring that is not there.
 *
 * This is the shape of a real report: a password added in the browser, saved
 * successfully, and then absent from the list on that browser *and* on the
 * phone it synced to. Nothing had gone wrong with the sync — the item was in
 * the vault the whole time. Every screen filtered it out, because the list was
 * built from "items on a keyring that exists" and its keyring did not.
 *
 * The rule now is narrower and much harder to lose something under: an item
 * that is not deleted is shown. Where it lives is a question about how to
 * label it, never about whether it is real.
 */

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "test" });
}

/** A vault with one real keyring, and three ways of ending up without one. */
function vault() {
  return applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k1", ts: at(0), keyringId: "mine", name: "Just mine" },
    { kind: "keyring.put", opId: "k2", ts: at(1), keyringId: "old", name: "Old" },
    {
      kind: "item.put",
      opId: "i1",
      ts: at(2),
      itemId: "kept",
      keyringId: "mine",
      fields: { title: "Bank" },
    },
    // The one the report is about: a stale default id that names no keyring.
    {
      kind: "item.put",
      opId: "i2",
      ts: at(3),
      itemId: "stray",
      keyringId: "personal",
      fields: { title: "New password" },
    },
    // Saved on another device onto a keyring this one had already deleted.
    {
      kind: "item.put",
      opId: "i3",
      ts: at(4),
      itemId: "late",
      keyringId: "old",
      fields: { title: "Late arrival" },
    },
    { kind: "keyring.delete", opId: "k3", ts: at(5), keyringId: "old" },
  ]);
}

describe("a password whose keyring is missing", () => {
  it("is in the list rather than filtered out of existence", () => {
    const titles = visibleItems(vault()).map((item) => itemField(item, "title"));
    expect(titles).toContain("New password");
  });

  it("is named as one that needs a keyring, not left blank", () => {
    const state = vault();
    expect(keyringLabel(state, "personal")).toBe(NO_KEYRING);
    expect(keyringLabel(state, "mine")).toBe("Just mine");
  });

  it("counts as one somebody should be offered a keyring for", () => {
    const ids = itemsWithoutKeyring(vault()).map((item) => item.id);
    expect(ids.sort()).toEqual(["late", "stray"]);
  });

  it("does not drag a deleted keyring's name back onto the screen", () => {
    // "Old" was deleted; an item that outlived that delete must not claim to
    // be somewhere its owner threw away.
    expect(keyringLabel(vault(), "old")).toBe(NO_KEYRING);
    expect(liveKeyrings(vault()).map((ring) => ring.id)).toEqual(["mine"]);
  });

  it("has a place of its own in the folder tree", () => {
    const state = vault();
    const top = browseFolders(visibleItems(state), state, []);
    expect(top.folders.map((child) => child.name).sort()).toEqual([NO_KEYRING, "Just mine"].sort());
    const inside = browseFolders(visibleItems(state), state, [NO_KEYRING]);
    expect(inside.items.map((item) => item.id).sort()).toEqual(["late", "stray"]);
  });

  it("comes out in an export instead of being silently left behind", () => {
    const exported = exportVault(vault());
    const stray = exported.items.find((item) => item.id === "stray");
    expect(stray?.keyringName).toBe(NO_KEYRING);
  });

  it("stops being homeless once it is moved", () => {
    const moved = applyOps(vault(), [
      { kind: "item.move", opId: "m1", ts: at(6), itemId: "stray", keyringId: "mine" },
    ]);
    expect(itemsWithoutKeyring(moved).map((item) => item.id)).toEqual(["late"]);
  });

  it("still hides a password that was actually deleted", () => {
    const deleted = applyOps(vault(), [
      { kind: "item.delete", opId: "d1", ts: at(6), itemId: "stray" },
    ]);
    expect(visibleItems(deleted).map((item) => item.id)).not.toContain("stray");
    expect(itemsWithoutKeyring(deleted).map((item) => item.id)).toEqual(["late"]);
  });
});

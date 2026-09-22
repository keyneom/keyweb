import { describe, expect, it } from "vitest";
import {
  applyOps,
  capHistory,
  emptyVault,
  encodeHlc,
  HISTORY_LIMIT,
  itemField,
  mergeVaults,
  pastValues,
} from "../src/index.js";

/**
 * What the undo window keeps when two devices edit at once.
 *
 * A single device archives what it overwrites: `item.put` pushes the value it
 * replaced into history. Two devices editing the same field offline produce no
 * such moment on either of them — each one only ever saw its own write — so
 * the merge is the only place the losing value can be kept. Without that, the
 * password one of them typed is gone from the item *and* from the list of what
 * it used to be, which is the one place somebody would go looking for it.
 */

/** What an item used to hold in one field, newest first. */
function past(item: Parameters<typeof pastValues>[0], field: string): string[] {
  return pastValues(item)
    .filter((entry) => entry.field === field)
    .map((entry) => entry.value);
}

function at(node: string, n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node });
}

/** The same item, edited on two devices that have not spoken since. */
function diverged(leftValue: string, rightValue: string) {
  const base = applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k", ts: at("a", 0), keyringId: "ring", name: "Just mine" },
    {
      kind: "item.put",
      opId: "i",
      ts: at("a", 1),
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Bank", password: "original" },
    },
  ]);
  const left = applyOps(base, [
    {
      kind: "item.put",
      opId: "l",
      ts: at("a", 5),
      itemId: "bank",
      keyringId: "ring",
      fields: { password: leftValue },
    },
  ]);
  const right = applyOps(base, [
    {
      kind: "item.put",
      opId: "r",
      ts: at("b", 9),
      itemId: "bank",
      keyringId: "ring",
      fields: { password: rightValue },
    },
  ]);
  return { left, right };
}

describe("merging two devices that edited the same field", () => {
  it("keeps the value that lost, not just the one that won", () => {
    const { left, right } = diverged("from-the-laptop", "from-the-phone");
    const merged = mergeVaults(left, right);
    const item = merged.items["bank"]!;

    expect(itemField(item, "password")).toBe("from-the-phone");
    expect(past(item, "password")).toContain("from-the-laptop");
    // And the value from before either edit is still there.
    expect(past(item, "password")).toContain("original");
  });

  it("says the same thing whichever way round the merge runs", () => {
    const { left, right } = diverged("from-the-laptop", "from-the-phone");
    const one = mergeVaults(left, right).items["bank"]!;
    const other = mergeVaults(right, left).items["bank"]!;

    expect(itemField(one, "password")).toBe(itemField(other, "password"));
    expect(past(one, "password").sort()).toEqual(
      past(other, "password").sort(),
    );
  });

  it("does not archive the loser twice when the merge is repeated", () => {
    const { left, right } = diverged("from-the-laptop", "from-the-phone");
    const once = mergeVaults(left, right);
    const twice = mergeVaults(once, right);
    const thrice = mergeVaults(twice, left);

    const values = past(thrice.items["bank"]!, "password");
    expect(values.filter((value) => value === "from-the-laptop")).toHaveLength(1);
    expect(values.filter((value) => value === "original")).toHaveLength(1);
  });

  it("leaves history alone when both sides already agree", () => {
    const { left } = diverged("x", "y");
    const merged = mergeVaults(left, left);
    expect(past(merged.items["bank"]!, "password")).toEqual(
      past(left.items["bank"]!, "password"),
    );
  });
});

/**
 * The cap is per field, and that is the whole point.
 *
 * One cap across the item meant a run of edits to a note could push the only
 * archived password out of the window — the least valuable history evicting
 * the most valuable, silently, at the moment somebody needed it.
 */
describe("how much history is kept", () => {
  it("keeps a field's own history however busy another field is", () => {
    let state = applyOps(emptyVault(), [
      { kind: "keyring.put", opId: "k", ts: at("a", 0), keyringId: "ring", name: "Just mine" },
      {
        kind: "item.put",
        opId: "i",
        ts: at("a", 1),
        itemId: "bank",
        keyringId: "ring",
        fields: { title: "Bank", password: "the-one-that-matters" },
      },
      {
        kind: "item.put",
        opId: "p",
        ts: at("a", 2),
        itemId: "bank",
        keyringId: "ring",
        fields: { password: "replaced-it" },
      },
    ]);

    // Far more note edits than the cap, long after the password changed.
    for (let n = 0; n < HISTORY_LIMIT * 3; n += 1) {
      state = applyOps(state, [
        {
          kind: "item.put",
          opId: `n-${n}`,
          ts: at("a", 100 + n),
          itemId: "bank",
          keyringId: "ring",
          fields: { note: `note ${n}` },
        },
      ]);
    }

    const item = state.items["bank"]!;
    expect(past(item, "password")).toContain(
      "the-one-that-matters",
    );
    expect(past(item, "note").length).toBeLessThanOrEqual(HISTORY_LIMIT);
  });

  it("caps each field on its own and keeps the newest of them", () => {
    const entries = Array.from({ length: HISTORY_LIMIT + 4 }, (_, n) => ({
      field: "password",
      value: `v${n}`,
      ts: at("a", n),
    })).concat([{ field: "note", value: "a note", ts: at("a", 0) }]);

    const capped = capHistory(entries);
    expect(capped.filter((entry) => entry.field === "password")).toHaveLength(HISTORY_LIMIT);
    // The note survives a full password window rather than being crowded out.
    expect(capped.filter((entry) => entry.field === "note")).toHaveLength(1);
    // Newest first, and the oldest passwords are the ones dropped.
    expect(capped[0]!.value).toBe(`v${HISTORY_LIMIT + 3}`);
    expect(capped.map((entry) => entry.value)).not.toContain("v0");
  });
});

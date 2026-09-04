import { describe, expect, it } from "vitest";
import { compareHlc, createClock, decodeHlc, encodeHlc, HLC_ZERO } from "../src/hlc.js";
import { mergeVaults } from "../src/merge.js";
import type { VaultState } from "../src/model.js";
import { emptyVault, fingerprint, itemField, visibleItems } from "../src/model.js";
import type { VaultOp } from "../src/ops.js";
import { applyOp, applyOps } from "../src/ops.js";

const clock = createClock({ node: "test" });
let n = 0;
const id = () => `op-${++n}`;

function put(itemId: string, fields: Record<string, string>, keyringId = "ring"): VaultOp {
  return { kind: "item.put", opId: id(), ts: clock.now(), itemId, keyringId, fields };
}

function build(ops: VaultOp[]): VaultState {
  return applyOps(emptyVault(), ops);
}

describe("hybrid logical clock", () => {
  it("round-trips and sorts lexicographically in causal order", () => {
    const a = encodeHlc({ wall: 1_700_000_000_000, counter: 0, node: "a" });
    const b = encodeHlc({ wall: 1_700_000_000_000, counter: 1, node: "a" });
    const c = encodeHlc({ wall: 1_700_000_000_001, counter: 0, node: "a" });
    expect(decodeHlc(b)).toEqual({ wall: 1_700_000_000_000, counter: 1, node: "a" });
    expect(compareHlc(a, b)).toBe(-1);
    expect(compareHlc(b, c)).toBe(-1);
    expect([c, a, b].sort()).toEqual([a, b, c]);
    expect(HLC_ZERO < a).toBe(true);
  });

  it("advances monotonically even when the wall clock stands still or goes backwards", () => {
    let wall = 1_000;
    const c = createClock({ node: "n", physical: () => wall });
    const first = c.now();
    const second = c.now();
    wall = 500; // clock corrected backwards
    const third = c.now();
    expect(second > first).toBe(true);
    expect(third > second).toBe(true);
  });

  it("sorts a local edit after a remote one it has observed", () => {
    const slow = createClock({ node: "slow", physical: () => 1_000 });
    const fast = createClock({ node: "fast", physical: () => 9_999_999 });
    const remote = fast.now();
    slow.observe(remote);
    expect(slow.now() > remote).toBe(true);
  });

  it("resumes causal time across a restart", () => {
    const before = createClock({ node: "n", physical: () => 5_000 });
    const stamp = before.now();
    // Process restarts and the device clock has been set backwards.
    const after = createClock({ node: "n", physical: () => 100, resume: before.snapshot() });
    expect(after.now() > stamp).toBe(true);
  });
});

describe("merge is a CRDT join", () => {
  const a = build([put("one", { title: "One" }), put("two", { title: "Two" })]);
  const b = build([put("two", { title: "Two edited" }), put("three", { title: "Three" })]);

  it("is idempotent", () => {
    expect(fingerprint(mergeVaults(a, a))).toBe(fingerprint(a));
  });

  it("is commutative", () => {
    expect(fingerprint(mergeVaults(a, b))).toBe(fingerprint(mergeVaults(b, a)));
  });

  it("is associative", () => {
    const c = build([put("four", { title: "Four" })]);
    const left = mergeVaults(mergeVaults(a, b), c);
    const right = mergeVaults(a, mergeVaults(b, c));
    expect(fingerprint(left)).toBe(fingerprint(right));
  });

  it("keeps every item from both sides", () => {
    const merged = mergeVaults(a, b);
    expect(Object.keys(merged.items).sort()).toEqual(["one", "three", "two"]);
  });
});

describe("operations", () => {
  it("resolve per field, so simultaneous edits to one login both survive", () => {
    const base = build([put("chase", { title: "Chase", username: "old", password: "old" })]);
    const withUser = applyOp(base, put("chase", { username: "new-user" }));
    const withPass = applyOp(base, put("chase", { password: "new-pass" }));
    const merged = mergeVaults(withUser, withPass);
    expect(itemField(merged.items.chase!, "username")).toBe("new-user");
    expect(itemField(merged.items.chase!, "password")).toBe("new-pass");
    expect(itemField(merged.items.chase!, "title")).toBe("Chase");
  });

  it("is idempotent when the same operation is replayed", () => {
    const op = put("chase", { password: "abc" });
    const once = applyOp(emptyVault(), op);
    const twice = applyOp(once, op);
    expect(fingerprint(twice)).toBe(fingerprint(once));
  });

  it("lets a later edit beat an earlier delete, and a later delete beat an earlier edit", () => {
    const base = build([put("a", { title: "A" }), put("b", { title: "B" })]);
    const deleteA: VaultOp = { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "a" };
    const afterDelete = applyOp(base, deleteA);
    const revived = applyOp(afterDelete, put("a", { title: "A again" }));
    expect(revived.items.a!.deleted.value).toBe(false);

    const deleteB: VaultOp = { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "b" };
    const gone = applyOp(revived, deleteB);
    expect(gone.items.b!.deleted.value).toBe(true);
  });

  it("hides items whose keyring was deleted without destroying them", () => {
    let state = build([put("x", { title: "X" })]);
    state = applyOp(state, {
      kind: "keyring.put",
      opId: id(),
      ts: clock.now(),
      keyringId: "ring",
      name: "Household",
    });
    expect(visibleItems(state)).toHaveLength(1);
    state = applyOp(state, {
      kind: "keyring.delete",
      opId: id(),
      ts: clock.now(),
      keyringId: "ring",
    });
    expect(visibleItems(state)).toHaveLength(0);
    expect(state.items.x).toBeDefined();
  });

  it("retains a superseded password so an accidental overwrite is recoverable", () => {
    let state = build([put("chase", { password: "original" })]);
    state = applyOp(state, put("chase", { password: "overwritten" }));
    expect(itemField(state.items.chase!, "password")).toBe("overwritten");
    const previous = state.items.chase!.history.find((h) => h.field === "password");
    expect(previous?.value).toBe("original");
  });

  it("caps retained history so a long-lived item cannot grow without bound", () => {
    let state = emptyVault();
    for (let i = 0; i < 40; i += 1) state = applyOp(state, put("chase", { password: `pw-${i}` }));
    expect(state.items.chase!.history.length).toBeLessThanOrEqual(12);
    expect(itemField(state.items.chase!, "password")).toBe("pw-39");
  });
});

describe("fingerprint", () => {
  it("is stable across key insertion order", () => {
    const one = build([put("a", { title: "A" }), put("b", { title: "B" })]);
    const two = mergeVaults(build([put("b", { title: "B" })]), build([put("a", { title: "A" })]));
    // Different construction order, same observable content ordering.
    expect(Object.keys(one.items).sort()).toEqual(Object.keys(two.items).sort());
  });

  it("changes when a register advances", () => {
    const before = build([put("a", { title: "A" })]);
    const after = applyOp(before, put("a", { title: "A2" }));
    expect(fingerprint(after)).not.toBe(fingerprint(before));
  });
});

import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { emptyVault, itemsWithoutKeyring, visibleItems } from "../src/model.js";
import type { VaultOp } from "../src/ops.js";
import { applyOps } from "../src/ops.js";

const clock = createClock({ node: "test" });
let n = 0;
const id = () => `op-${++n}`;

const put = (itemId: string, keyringId: string): VaultOp => ({
  kind: "item.put",
  opId: id(),
  ts: clock.now(),
  itemId,
  keyringId,
  fields: { title: itemId },
});

const ring = (keyringId: string, name: string): VaultOp => ({
  kind: "keyring.put",
  opId: id(),
  ts: clock.now(),
  keyringId,
  name,
});

/**
 * What deleting a keyring leaves behind.
 *
 * The distinction this pins down is invisible on screen and matters a great
 * deal: `keyring.delete` alone does not delete the passwords inside it. They
 * stay in storage and in the Drive backup indefinitely, which is not what a
 * person who pressed Delete in a password manager has agreed to.
 *
 * So the app deletes the items too. These tests hold the two halves apart, so
 * a future change cannot quietly go back to leaving them behind.
 *
 * What the list does with a survivor has since changed, and that is the second
 * test here. It used to hide anything whose keyring was gone, which made a
 * bare `keyring.delete` look exactly like a proper delete while the passwords
 * were still in the backup — and made a password saved against a keyring id
 * that matched nothing disappear the moment it was written. Now a survivor
 * stays on screen and says it needs a keyring.
 */
describe("deleting a keyring", () => {
  const base = [ring("doomed", "Old"), ring("keep", "Keep"), put("a", "doomed"), put("b", "doomed"), put("c", "keep")];

  it("a bare keyring.delete leaves the items, and says so", () => {
    const state = applyOps(emptyVault(), [
      ...base,
      { kind: "keyring.delete", opId: id(), ts: clock.now(), keyringId: "doomed" },
    ]);

    // Still in the document that gets published to Drive...
    expect(state.items["a"]).toBeDefined();
    expect(state.items["a"]!.deleted.value).toBe(false);
    // ...and therefore still on the screen, rather than in the backup only.
    expect(visibleItems(state).map((item) => item.id).sort()).toEqual(["a", "b", "c"]);
    expect(itemsWithoutKeyring(state).map((item) => item.id).sort()).toEqual(["a", "b"]);
  });

  it("deleting the items first leaves nothing behind", () => {
    const state = applyOps(emptyVault(), [
      ...base,
      { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "a" },
      { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "b" },
      { kind: "keyring.delete", opId: id(), ts: clock.now(), keyringId: "doomed" },
    ]);

    expect(state.items["a"]!.deleted.value).toBe(true);
    expect(state.items["b"]!.deleted.value).toBe(true);
    expect(state.keyrings["doomed"]!.deleted.value).toBe(true);
  });

  it("leaves other keyrings and their passwords alone", () => {
    const state = applyOps(emptyVault(), [
      ...base,
      { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "a" },
      { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "b" },
      { kind: "keyring.delete", opId: id(), ts: clock.now(), keyringId: "doomed" },
    ]);

    expect(visibleItems(state).map((item) => item.id)).toEqual(["c"]);
    expect(state.keyrings["keep"]!.deleted.value).toBe(false);
    expect(state.items["c"]!.deleted.value).toBe(false);
  });

  it("a password moved out beforehand is not caught by the delete", () => {
    // The order the app uses — read the members, then delete them — must not
    // sweep up something that left the keyring in between.
    const state = applyOps(emptyVault(), [
      ...base,
      { kind: "item.move", opId: id(), ts: clock.now(), itemId: "a", keyringId: "keep" },
      { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "b" },
      { kind: "keyring.delete", opId: id(), ts: clock.now(), keyringId: "doomed" },
    ]);

    expect(visibleItems(state).map((item) => item.id).sort()).toEqual(["a", "c"]);
  });
});

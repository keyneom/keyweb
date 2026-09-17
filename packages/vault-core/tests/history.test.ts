import { describe, expect, it } from "vitest";
import { applyOps, emptyVault, encodeHlc, itemField, pastValues } from "../src/index.js";

/**
 * What an item used to hold has been kept since the CRDT was written and shown
 * nowhere, on either platform.
 *
 * That was survivable while it was only a Keyweb edit somebody had just made.
 * It stopped being survivable when the KeePass import started replaying years
 * of earlier passwords into the same place: carried across, synced, backed up,
 * and impossible to look at — which is data loss with extra steps, because the
 * person deletes the original file believing they have it.
 */
const ring = { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "ring", name: "Home" } as const;

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "test" });
}

function put(n: number, fields: Record<string, string>) {
  return {
    kind: "item.put",
    opId: `op-${n}`,
    ts: at(n),
    itemId: "bank",
    keyringId: "ring",
    fields,
  } as const;
}

describe("what an item used to hold", () => {
  it("reports each superseded value, newest first", () => {
    const state = applyOps(emptyVault(), [
      ring,
      put(1, { title: "Bank", password: "first" }),
      put(2, { password: "second" }),
      put(3, { password: "third" }),
    ]);

    const past = pastValues(state.items.bank!);
    expect(past.map((p) => p.value)).toEqual(["second", "first"]);
    expect(itemField(state.items.bank!, "password")).toBe("third");
    // Masked on the same rule as the live field, so an old password is not
    // printed on screen just because it is old.
    expect(past.every((p) => p.secret)).toBe(true);
    expect(past[0]!.label).toBe("password");
  });

  /** The name its owner gave it, not the key it is stored under. */
  it("uses the field's own name", () => {
    const state = applyOps(emptyVault(), [
      ring,
      put(1, { title: "Bank", "secret:Backup PIN": "1111" }),
      put(2, { "secret:Backup PIN": "2222" }),
    ]);
    expect(pastValues(state.items.bank!)[0]).toMatchObject({
      label: "Backup PIN",
      value: "1111",
      secret: true,
    });
  });

  /**
   * "This used to be nothing" is not something anybody came here to read, and
   * a removed field writes a blank on purpose — so the blank is the *new*
   * value and the real one is what lands in history.
   */
  it("leaves out values that were blank", () => {
    const state = applyOps(emptyVault(), [
      ring,
      put(1, { title: "Bank", "Account number": "" }),
      put(2, { "Account number": "00112233" }),
      put(3, { "Account number": "" }),
    ]);
    expect(pastValues(state.items.bank!).map((p) => p.value)).toEqual(["00112233"]);
  });

  /** Restoring is an ordinary write, so the way back is never a one-way door. */
  it("keeps a way back after a restore", () => {
    let state = applyOps(emptyVault(), [
      ring,
      put(1, { title: "Bank", password: "old" }),
      put(2, { password: "new" }),
    ]);
    state = applyOps(state, [put(3, { password: "old" })]);

    expect(itemField(state.items.bank!, "password")).toBe("old");
    expect(pastValues(state.items.bank!).map((p) => p.value)).toEqual(["new", "old"]);
  });
});

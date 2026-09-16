import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import {
  boundDatasets,
  composeVault,
  datasetForItem,
  extractDataset,
  withoutDatasetItems,
} from "../src/datasets.js";
import { mergeVaults } from "../src/merge.js";
import { datasetOf, emptyVault, itemField, visibleItems } from "../src/model.js";
import type { VaultOp } from "../src/ops.js";
import { applyOps } from "../src/ops.js";

const clock = createClock({ node: "test" });
let n = 0;
const id = () => `op-${++n}`;

const ring = (keyringId: string, name: string): VaultOp => ({
  kind: "keyring.put", opId: id(), ts: clock.now(), keyringId, name,
});
const put = (itemId: string, keyringId: string, title = itemId): VaultOp => ({
  kind: "item.put", opId: id(), ts: clock.now(), itemId, keyringId, fields: { title },
});
const bind = (keyringId: string, datasetId: string): VaultOp => ({
  kind: "keyring.bind", opId: id(), ts: clock.now(), keyringId, datasetId,
});

function vault(): ReturnType<typeof applyOps> {
  return applyOps(emptyVault(), [
    ring("personal", "Just mine"),
    ring("house", "Household"),
    put("bank", "personal"),
    put("wifi", "house"),
    put("power", "house"),
  ]);
}

describe("moving a keyring into its own document", () => {
  it("is off until something says otherwise", () => {
    // The change must be invisible to a vault that shares nothing, which is
    // every vault until someone presses a button.
    const state = vault();
    expect(datasetOf(state.keyrings["house"])).toBeNull();
    expect(boundDatasets(state)).toEqual([]);
    expect(datasetForItem(state, "house")).toBeNull();
  });

  it("records where the items belong", () => {
    const state = applyOps(vault(), [bind("house", "ds-house")]);
    expect(datasetOf(state.keyrings["house"])).toBe("ds-house");
    expect(boundDatasets(state)).toEqual([{ keyringId: "house", datasetId: "ds-house" }]);
    expect(datasetForItem(state, "house")).toBe("ds-house");
    // Other keyrings are untouched.
    expect(datasetForItem(state, "personal")).toBeNull();
  });

  it("extracts exactly that keyring and its items", () => {
    const state = applyOps(vault(), [bind("house", "ds-house")]);
    const dataset = extractDataset(state, "house");

    expect(Object.keys(dataset.keyrings)).toEqual(["house"]);
    expect(Object.keys(dataset.items).sort()).toEqual(["power", "wifi"]);
    // Nothing of the other keyring leaks into the document that will be shared.
    expect(dataset.items["bank"]).toBeUndefined();
    expect(dataset.keyrings["personal"]).toBeUndefined();
  });

  it("leaves the keyring behind in the vault, without its items", () => {
    const state = applyOps(vault(), [bind("house", "ds-house")]);
    const rest = withoutDatasetItems(state, "house");

    // The keyring still exists here: it is what names the thing and says
    // where its items went.
    expect(rest.keyrings["house"]).toBeDefined();
    expect(Object.keys(rest.items)).toEqual(["bank"]);
  });

  it("puts back together into exactly what it was", () => {
    const before = applyOps(vault(), [bind("house", "ds-house")]);
    const dataset = extractDataset(before, "house");
    const rest = withoutDatasetItems(before, "house");

    const after = composeVault(rest, new Map([["ds-house", dataset]]));
    expect(visibleItems(after).map((i) => i.id).sort()).toEqual(
      visibleItems(before).map((i) => i.id).sort(),
    );
    expect(itemField(after.items["wifi"]!, "title")).toBe("wifi");
  });

  it("carries tombstones into the dataset", () => {
    // A deleted password left behind in the vault would come back the next
    // time the documents were joined, because the dataset would have no
    // record that it ever existed.
    const state = applyOps(vault(), [
      bind("house", "ds-house"),
      { kind: "item.delete", opId: id(), ts: clock.now(), itemId: "power" },
    ]);
    const dataset = extractDataset(state, "house");
    expect(dataset.items["power"]!.deleted.value).toBe(true);

    const rejoined = composeVault(withoutDatasetItems(state, "house"), new Map([["ds-house", dataset]]));
    expect(visibleItems(rejoined).map((i) => i.id).sort()).toEqual(["bank", "wifi"]);
  });

  it("composes the same state whatever order the documents arrive in", () => {
    // Two devices assembling the same documents must agree byte for byte, or
    // the sync engine's fingerprint says they differ and they publish forever.
    const state = applyOps(vault(), [bind("house", "ds-house"), bind("personal", "ds-personal")]);
    const house = extractDataset(state, "house");
    const personal = extractDataset(state, "personal");
    const rest = withoutDatasetItems(withoutDatasetItems(state, "house"), "personal");

    const one = composeVault(rest, new Map([["ds-house", house], ["ds-personal", personal]]));
    const two = composeVault(rest, new Map([["ds-personal", personal], ["ds-house", house]]));
    expect(JSON.stringify(one)).toBe(JSON.stringify(two));
  });

  it("two devices binding the same keyring at once agree on one dataset", () => {
    // Otherwise each keeps its own and the passwords quietly split in two.
    const base = vault();
    const a = applyOps(base, [bind("house", "ds-from-phone")]);
    const b = applyOps(base, [bind("house", "ds-from-laptop")]);

    const ab = mergeVaults(a, b);
    const ba = mergeVaults(b, a);
    expect(datasetOf(ab.keyrings["house"])).toBe(datasetOf(ba.keyrings["house"]));
  });

  it("can be put back in the vault", () => {
    // Unsharing has to be possible, or sharing once is a one-way door.
    const state = applyOps(vault(), [bind("house", "ds-house"), bind("house", "")]);
    expect(datasetOf(state.keyrings["house"])).toBeNull();
    expect(boundDatasets(state)).toEqual([]);
  });

  it("a vault written before datasets existed still merges", () => {
    // The field is new. Every stored vault predates it, and a merge that
    // faulted on a missing register would be a vault nobody could open.
    const legacy = {
      items: {},
      keyrings: {
        house: {
          id: "house",
          name: { value: "Household", ts: "001700000000000-00000-a" },
          deleted: { value: false, ts: "000000000000000-00000-" },
        },
      },
    } as unknown as ReturnType<typeof emptyVault>;

    const merged = mergeVaults(legacy, vault());
    expect(datasetOf(merged.keyrings["house"])).toBeNull();
    expect(merged.keyrings["house"]!.name.value).toBeTruthy();
  });

  it("a deleted keyring stops being a document to look for", () => {
    const state = applyOps(vault(), [
      bind("house", "ds-house"),
      { kind: "keyring.delete", opId: id(), ts: clock.now(), keyringId: "house" },
    ]);
    expect(boundDatasets(state)).toEqual([]);
  });

  it("does not let a shared document contribute another keyring", () => {
    // The attack: share a folder, and in that file publish a later `personal`
    // keyring that rebinds private writes into the shared Drive file.
    const before = applyOps(vault(), [bind("house", "ds-house")]);
    const rest = withoutDatasetItems(before, "house");
    const house = extractDataset(before, "house");
    const later = "999999999999999-00000-evil";
    const poisoned = {
      items: {
        ...house.items,
        bank: {
          id: "bank",
          keyring: { value: "personal", ts: later },
          fields: { password: { value: "s3cret", ts: later } },
          deleted: { value: false, ts: "000000000000000-00000-" },
          history: [],
        },
      },
      keyrings: {
        ...house.keyrings,
        personal: {
          id: "personal",
          name: { value: "Stolen", ts: later },
          deleted: { value: false, ts: later },
          dataset: { value: "ds-house", ts: later },
        },
        house: {
          ...house.keyrings["house"]!,
          dataset: { value: "ds-evil", ts: later },
        },
      },
    };

    const after = composeVault(rest, new Map([["ds-house", poisoned]]));

    expect(after.keyrings["personal"]?.name.value).toBe("Just mine");
    expect(datasetOf(after.keyrings["personal"])).toBeNull();
    expect(datasetOf(after.keyrings["house"])).toBe("ds-house");
    expect(datasetForItem(after, "personal")).toBeNull();
    expect(itemField(after.items["bank"]!, "password")).toBeUndefined();
    expect(itemField(after.items["wifi"]!, "title")).toBe("wifi");
  });
});

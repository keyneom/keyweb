import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { MemoryVaultStorage, VAULT_DOCUMENT } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote } from "../src/testing.js";
import { itemField, visibleItems } from "../src/model.js";
import { applyOps } from "../src/ops.js";

/**
 * A keyring that lives in its own document.
 *
 * Stage one of sharing: no second person is involved yet, so everything here
 * is about one device keeping two files straight. The failures this guards
 * against are all quiet — an edit written to the wrong document does not
 * throw, it just never reaches the other side.
 */
function engine() {
  const storage = new MemoryVaultStorage();
  const vaultRemote = new FakeRemote();
  const houseRemote = new FakeRemote();
  const sync = new VaultSync({
    storage,
    remote: vaultRemote,
    remoteFor: (documentId) =>
      documentId === VAULT_DOCUMENT ? vaultRemote : documentId === "ds-house" ? houseRemote : null,
    clock: createClock({ node: "test" }),
  });
  return { storage, sync, vaultRemote, houseRemote };
}

async function withHouseBound() {
  const parts = engine();
  await parts.sync.putKeyring({ keyringId: "personal", name: "Just mine" });
  await parts.sync.putKeyring({ keyringId: "house", name: "Household" });
  await parts.sync.putItem({ itemId: "bank", keyringId: "personal", fields: { title: "Bank" } });
  await parts.sync.commitAll([
    { kind: "keyring.bind", ...parts.sync.stamp(), keyringId: "house", datasetId: "ds-house" },
  ]);
  return parts;
}

describe("a keyring in its own document", () => {
  it("puts its items in that document, not the vault", async () => {
    const { storage, sync } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });

    const vault = await storage.readState(VAULT_DOCUMENT);
    const dataset = await storage.readState("ds-house");

    expect(vault.items["wifi"]).toBeUndefined();
    expect(dataset.items["wifi"]).toBeDefined();
    // And the vault still holds the one that is not in a bound keyring.
    expect(vault.items["bank"]).toBeDefined();
  });

  it("still reads as one vault", async () => {
    // Whatever the storage does, a person sees their passwords in one list.
    const { sync } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });

    const state = await sync.state();
    expect(visibleItems(state).map((item) => item.id).sort()).toEqual(["bank", "wifi"]);
    expect(itemField(state.items["wifi"]!, "title")).toBe("Wifi");
  });

  it("publishes each document to its own remote", async () => {
    const { sync, vaultRemote, houseRemote } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });
    await sync.sync();

    // The shared keyring's file holds its passwords...
    expect(houseRemote.snapshot().items["wifi"]).toBeDefined();
    // ...and the vault's file does not. This is the whole point: the file
    // that gets shared must not carry everything else.
    expect(vaultRemote.snapshot().items["wifi"]).toBeUndefined();
    expect(vaultRemote.snapshot().items["bank"]).toBeDefined();
  });

  it("counts work queued in every document as still to back up", async () => {
    // "3 changes still to back up" must not omit the shared keyring, or
    // someone is told they are safe when they are not.
    const { sync } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });
    expect(sync.status().pending).toBeGreaterThan(0);

    await sync.sync();
    expect(sync.status().pending).toBe(0);
  });

  it("edits a password in the shared keyring in place", async () => {
    const { sync, houseRemote } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { password: "hunter2" } });
    await sync.sync();

    expect(itemField(houseRemote.snapshot().items["wifi"]!, "password")).toBe("hunter2");
    const state = await sync.state();
    expect(itemField(state.items["wifi"]!, "password")).toBe("hunter2");
  });

  it("deleting a password in a shared keyring reaches that document", async () => {
    const { sync, houseRemote } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });
    await sync.sync();
    await sync.deleteItem("wifi");
    await sync.sync();

    // The tombstone has to land where the item is, or the other side keeps it.
    expect(houseRemote.snapshot().items["wifi"]!.deleted.value).toBe(true);
    expect(visibleItems(await sync.state()).map((i) => i.id)).toEqual(["bank"]);
  });

  it("takes in what another device put in the shared keyring", async () => {
    const { sync, houseRemote, vaultRemote } = await withHouseBound();
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });
    await sync.sync();

    // Another device publishes to the shared keyring's file, on top of what
    // is already there. Written through the remote's real interface, so a
    // version mismatch would fail this test rather than be swallowed.
    const current = await houseRemote.read();
    expect(current).not.toBeNull();
    const theirs = applyOps(current!.state, [
      { kind: "item.put", opId: "their-1", ts: "001800000000000-00000-them", itemId: "power", keyringId: "house", fields: { title: "Power" } },
    ]);
    await houseRemote.write(theirs, current!.version);

    await sync.sync();

    // It arrives, and lands in the shared keyring rather than the vault.
    const state = await sync.state();
    expect(itemField(state.items["power"]!, "title")).toBe("Power");
    expect(vaultRemote.snapshot().items["power"]).toBeUndefined();
    expect(houseRemote.snapshot().items["power"]).toBeDefined();
  });

  it("a keyring with nowhere to publish keeps its edits queued", async () => {
    // Bound locally but no Drive file yet: the edits wait, exactly as an
    // offline vault's do, rather than being lost or landing in the vault.
    const storage = new MemoryVaultStorage();
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      remoteFor: (documentId) => (documentId === VAULT_DOCUMENT ? new FakeRemote() : null),
      clock: createClock({ node: "test" }),
    });
    await sync.putKeyring({ keyringId: "house", name: "Household" });
    await sync.commitAll([
      { kind: "keyring.bind", ...sync.stamp(), keyringId: "house", datasetId: "ds-house" },
    ]);
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });

    await sync.sync();
    expect((await storage.pending("ds-house")).length).toBeGreaterThan(0);
    // And it is still visible locally meanwhile.
    expect(visibleItems(await sync.state()).map((i) => i.id)).toEqual(["wifi"]);
  });

  it("a vault that shares nothing touches one document", async () => {
    // The change has to be invisible to everyone who has not shared anything.
    const { storage, sync } = engine();
    await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
    await sync.putItem({ itemId: "bank", keyringId: "personal", fields: { title: "Bank" } });
    await sync.sync();

    expect(await storage.knownDocuments()).toEqual([]);
  });

  it("does not route private edits into a poisoned shared document", async () => {
    const { sync, storage } = await withHouseBound();
    const house = await storage.readState("ds-house");
    const later = "999999999999999-00000-evil";
    await sync.adoptDocument("ds-house", {
      items: house.items,
      keyrings: {
        ...house.keyrings,
        personal: {
          id: "personal",
          name: { value: "Stolen", ts: later },
          deleted: { value: false, ts: later },
          dataset: { value: "ds-house", ts: later },
        },
      },
    });

    await sync.putItem({
      itemId: "bank",
      keyringId: "personal",
      fields: { password: "s3cret" },
    });

    expect(itemField((await storage.readState(VAULT_DOCUMENT)).items["bank"]!, "password")).toBe(
      "s3cret",
    );
    expect((await storage.readState("ds-house")).items["bank"]).toBeUndefined();
  });

  it("does not pin the vault clock from a shared document", async () => {
    let now = 1_700_000_000_000;
    const storage = new MemoryVaultStorage();
    const vaultRemote = new FakeRemote();
    const houseRemote = new FakeRemote();
    const sync = new VaultSync({
      storage,
      remote: vaultRemote,
      remoteFor: (documentId) =>
        documentId === VAULT_DOCUMENT ? vaultRemote : documentId === "ds-house" ? houseRemote : null,
      clock: createClock({ node: "test", physical: () => now }),
    });
    await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
    await sync.putKeyring({ keyringId: "house", name: "Household" });
    await sync.putItem({ itemId: "bank", keyringId: "personal", fields: { title: "Bank" } });
    await sync.commitAll([
      { kind: "keyring.bind", ...sync.stamp(), keyringId: "house", datasetId: "ds-house" },
    ]);
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { title: "Wifi" } });
    await sync.sync();

    const current = await houseRemote.read();
    expect(current).not.toBeNull();
    const theirs = applyOps(current!.state, [
      {
        kind: "item.put",
        opId: "evil-1",
        ts: "999999999999999-00000-evil",
        itemId: "power",
        keyringId: "house",
        fields: { title: "Power" },
      },
    ]);
    await houseRemote.write(theirs, current!.version);
    await sync.sync();

    now += 1;
    await sync.putItem({ itemId: "bank", keyringId: "personal", fields: { password: "s3cret" } });
    const ts = (await sync.state()).items["bank"]!.fields.password!.ts;
    expect(ts.startsWith("999999999999999")).toBe(false);
  });
});

import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { MemoryVaultStorage, VAULT_DOCUMENT } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote } from "../src/testing.js";
import { datasetOf, fingerprint, itemField, visibleItems } from "../src/model.js";

/**
 * Moving a keyring into its own document, and back out of it.
 *
 * This is the step that turns a private keyring into a shareable one, and it
 * is the only place in the app where passwords are deliberately taken out of
 * one file and put into another. Every failure here is a lost password, so
 * the tests care mostly about what is true *between* the two writes.
 */
function engine() {
  const storage = new MemoryVaultStorage();
  const vaultRemote = new FakeRemote();
  const datasetRemote = new FakeRemote();
  const sync = new VaultSync({
    storage,
    remote: vaultRemote,
    remoteFor: (documentId) =>
      documentId === VAULT_DOCUMENT
        ? vaultRemote
        : documentId === "ds-house"
          ? datasetRemote
          : null,
    clock: createClock({ node: "test" }),
  });
  return { storage, sync, vaultRemote, datasetRemote };
}

async function withHousehold() {
  const parts = engine();
  await parts.sync.putKeyring({ keyringId: "personal", name: "Just mine" });
  await parts.sync.putKeyring({ keyringId: "house", name: "Household" });
  await parts.sync.putItem({ itemId: "bank", keyringId: "personal", fields: { title: "Bank" } });
  await parts.sync.putItem({
    itemId: "wifi",
    keyringId: "house",
    fields: { title: "Wifi", password: "hunter2" },
  });
  await parts.sync.putItem({ itemId: "gas", keyringId: "house", fields: { title: "Gas" } });
  return parts;
}

describe("binding a keyring to its own document", () => {
  it("moves its passwords out of the vault and nothing else", async () => {
    const { storage, sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");

    const vault = await storage.readState(VAULT_DOCUMENT);
    const dataset = await storage.readState("ds-house");

    expect(Object.keys(dataset.items).sort()).toEqual(["gas", "wifi"]);
    expect(itemField(dataset.items["wifi"]!, "password")).toBe("hunter2");

    // What stays behind in the vault is a scrubbed tombstone, not the
    // password. A record has to stay — a CRDT has no way to say "gone" except
    // by saying it — but it carries no value any more.
    expect(vault.items["wifi"]?.deleted.value).toBe(true);
    expect(itemField(vault.items["wifi"]!, "password")).toBe("");
    expect(vault.items["wifi"]?.history).toEqual([]);
    expect(vault.items["gas"]?.deleted.value).toBe(true);

    // The keyring that was not shared is untouched, in the file it was in.
    expect(vault.items["bank"]?.deleted.value).toBe(false);
  });

  it("keeps the vault able to name what it no longer holds", async () => {
    const { storage, sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");

    const vault = await storage.readState(VAULT_DOCUMENT);
    expect(vault.keyrings["house"]?.name.value).toBe("Household");
    expect(datasetOf(vault.keyrings["house"])).toBe("ds-house");
  });

  it("changes nothing a person can see", async () => {
    const { sync } = await withHousehold();
    const before = visibleItems(await sync.state())
      .map((item) => item.id)
      .sort();

    await sync.bindKeyring("house", "ds-house");

    const after = visibleItems(await sync.state())
      .map((item) => item.id)
      .sort();
    expect(after).toEqual(before);
    const wifi = (await sync.state()).items["wifi"];
    expect(itemField(wifi!, "password")).toBe("hunter2");
  });

  /**
   * The crash-safety property the ordering exists for. If the process dies
   * between the two writes, the items must still be somewhere — and the only
   * way to guarantee that is to write the copy before removing the original.
   */
  it("never has the passwords in neither document", async () => {
    const { storage, sync } = await withHousehold();

    const seen: string[] = [];
    const original = storage.commitAll.bind(storage);
    let interrupted = false;
    storage.commitAll = async (ops, next, documentId = VAULT_DOCUMENT) => {
      // Refuse the second write, exactly as a crash would.
      if (interrupted) throw new Error("crashed");
      seen.push(documentId);
      if (seen.length === 1) {
        await original(ops, next, documentId);
        interrupted = true;
        return;
      }
      await original(ops, next, documentId);
    };

    await expect(sync.bindKeyring("house", "ds-house")).rejects.toThrow("crashed");

    // The dataset was written first, so the passwords survived.
    expect(seen[0]).toBe("ds-house");
    const dataset = await storage.readState("ds-house");
    expect(Object.keys(dataset.items).sort()).toEqual(["gas", "wifi"]);
    // And the vault still has them too, because the removal never happened.
    const vault = await storage.readState(VAULT_DOCUMENT);
    expect(vault.items["wifi"]).toBeDefined();
  });

  it("refuses to bind a keyring twice", async () => {
    const { sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await expect(sync.bindKeyring("house", "ds-other")).rejects.toThrow(/already lives/);
  });

  it("is a no-op when asked for the binding it already has", async () => {
    const { sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await expect(sync.bindKeyring("house", "ds-house")).resolves.toBeDefined();
  });

  /**
   * The bug this caught: binding changes nothing else in the vault, so a
   * fingerprint that ignored the binding reported "unchanged" and the sync
   * skipped the upload. Other devices would then never learn the keyring had
   * moved, and would keep looking for its passwords in the vault.
   */
  it("changes the vault's fingerprint, so it gets published", async () => {
    const { storage, sync, vaultRemote } = await withHousehold();
    await sync.sync();
    const before = fingerprint(await storage.readState(VAULT_DOCUMENT));

    await sync.bindKeyring("house", "ds-house");
    const after = fingerprint(await storage.readState(VAULT_DOCUMENT));
    expect(after).not.toBe(before);

    const writes = vaultRemote.writes;
    await sync.sync();
    expect(vaultRemote.writes).toBeGreaterThan(writes);
    expect(datasetOf(vaultRemote.snapshot().keyrings["house"])).toBe("ds-house");
  });

  it("publishes the keyring's passwords to its own remote and no other", async () => {
    const { sync, vaultRemote, datasetRemote } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.sync();

    expect(Object.keys(datasetRemote.snapshot().items).sort()).toEqual(["gas", "wifi"]);
    expect(itemField(datasetRemote.snapshot().items["wifi"]!, "password")).toBe("hunter2");
    // The backup of the private vault keeps no copy of the shared password.
    expect(itemField(vaultRemote.snapshot().items["wifi"]!, "password")).toBe("");
    expect(vaultRemote.snapshot().items["bank"]?.deleted.value).toBe(false);
  });

  /** A rename is part of what was shared, so it has to travel with the items. */
  it("sends a later rename to the shared document", async () => {
    const { storage, sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.putKeyring({ keyringId: "house", name: "Ours" });

    const dataset = await storage.readState("ds-house");
    expect(dataset.keyrings["house"]?.name.value).toBe("Ours");
    // And it is what the person sees, whichever document it came from.
    expect((await sync.state()).keyrings["house"]?.name.value).toBe("Ours");
  });
});

describe("unbinding a keyring", () => {
  it("brings the passwords back into the vault", async () => {
    const { storage, sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.unbindKeyring("house");

    const vault = await storage.readState(VAULT_DOCUMENT);
    expect(Object.keys(vault.items).sort()).toEqual(["bank", "gas", "wifi"]);
    expect(datasetOf(vault.keyrings["house"])).toBeNull();
  });

  it("keeps edits made while it was shared", async () => {
    const { sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.putItem({ itemId: "wifi", keyringId: "house", fields: { password: "rotated" } });
    await sync.unbindKeyring("house");

    const wifi = (await sync.state()).items["wifi"];
    expect(itemField(wifi!, "password")).toBe("rotated");
  });

  it("stops syncing the document it no longer uses", async () => {
    const { sync, datasetRemote } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.sync();
    const writes = datasetRemote.writes;

    await sync.unbindKeyring("house");
    await sync.putItem({ itemId: "gas", keyringId: "house", fields: { title: "Gas co" } });
    await sync.sync();

    expect(datasetRemote.writes).toBe(writes);
  });
});

describe("leaving a keyring someone else shared", () => {
  it("hides it here without deleting anyone else's passwords", async () => {
    const { storage, sync, datasetRemote } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.sync();
    const theirs = datasetRemote.snapshot();

    await sync.leaveKeyring("house");

    // Gone from this vault...
    expect(visibleItems(await sync.state()).map((item) => item.id)).toEqual(["bank"]);
    // ...and the shared document is untouched, tombstone-free.
    await sync.sync();
    expect(Object.keys(datasetRemote.snapshot().items).sort()).toEqual(["gas", "wifi"]);
    expect(datasetRemote.snapshot().items["wifi"]?.deleted.value).toBe(false);
    expect(theirs.items["wifi"]?.deleted.value).toBe(false);

    // The tombstone is local: it lives in the vault, not in what was shared.
    const vault = await storage.readState(VAULT_DOCUMENT);
    expect(vault.keyrings["house"]?.deleted.value).toBe(true);
    expect(datasetRemote.snapshot().keyrings["house"]?.deleted.value).toBe(false);
  });
});

describe("moving a password across documents", () => {
  /**
   * The property the purge exists for. "I took that one out of the shared
   * folder" has to be true of the file the other person reads, not just of
   * this screen.
   */
  it("takes it away from the shared document", async () => {
    const { sync, datasetRemote } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.sync();
    expect(itemField(datasetRemote.snapshot().items["wifi"]!, "password")).toBe("hunter2");

    await sync.moveItem("wifi", "personal");
    await sync.sync();

    const shared = datasetRemote.snapshot().items["wifi"];
    expect(shared?.deleted.value).toBe(true);
    expect(itemField(shared!, "password")).toBe("");
    expect(shared?.history).toEqual([]);
  });

  it("keeps it readable in the keyring it moved to", async () => {
    const { sync } = await withHousehold();
    await sync.bindKeyring("house", "ds-house");
    await sync.moveItem("wifi", "personal");

    const state = await sync.state();
    expect(state.items["wifi"]?.deleted.value).toBe(false);
    expect(state.items["wifi"]?.keyring.value).toBe("personal");
    expect(itemField(state.items["wifi"]!, "password")).toBe("hunter2");
    expect(visibleItems(state).map((item) => item.id).sort()).toEqual(["bank", "gas", "wifi"]);
  });

  it("is an ordinary move when both keyrings are in the same document", async () => {
    const { storage, sync } = await withHousehold();
    await sync.putKeyring({ keyringId: "work", name: "Work" });
    await sync.moveItem("bank", "work");

    const vault = await storage.readState(VAULT_DOCUMENT);
    // Not relocated: the record keeps its history and its values in place.
    expect(vault.items["bank"]?.keyring.value).toBe("work");
    expect(vault.items["bank"]?.deleted.value).toBe(false);
  });
});

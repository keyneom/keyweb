import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { MemoryVaultStorage, VAULT_DOCUMENT } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote } from "../src/testing.js";
import { attachmentsOf, isBlobItem, itemField, visibleItems } from "../src/model.js";

/**
 * Files attached to a password.
 *
 * Stored as ordinary items so they inherit the vault's guarantees rather than
 * re-earning them. These tests are about the places that treat them
 * differently, which is where a bug would put somebody's scanned passport
 * somewhere they did not expect it to be — or leave it behind after they
 * believed they had deleted it.
 */
function engine() {
  const storage = new MemoryVaultStorage();
  const vaultRemote = new FakeRemote();
  const datasetRemote = new FakeRemote();
  const sync = new VaultSync({
    storage,
    remote: vaultRemote,
    remoteFor: (id) =>
      id === VAULT_DOCUMENT ? vaultRemote : id === "ds-house" ? datasetRemote : null,
    clock: createClock({ node: "test" }),
  });
  return { storage, sync, vaultRemote, datasetRemote };
}

async function withScan() {
  const parts = engine();
  await parts.sync.putKeyring({ keyringId: "house", name: "Household" });
  await parts.sync.putItem({ itemId: "passport", keyringId: "house", fields: { title: "Passport" } });
  await parts.sync.attachFile({
    itemId: "passport",
    keyringId: "house",
    blobId: "blob:abc123",
    name: "scan.jpg",
    type: "image/jpeg",
    data: "PRETEND-JPEG-BYTES",
    bytes: 18,
  });
  return parts;
}

describe("attaching a file", () => {
  it("shows up on the password it belongs to", async () => {
    const { sync } = await withScan();
    const state = await sync.state();
    expect(attachmentsOf(state.items["passport"]!)).toEqual([
      { blobId: "blob:abc123", name: "scan.jpg" },
    ]);
  });

  /** A file is an item, but it is not a password and must not look like one. */
  it("does not appear in the password list", async () => {
    const { sync } = await withScan();
    const state = await sync.state();
    expect(visibleItems(state).map((item) => item.id)).toEqual(["passport"]);
    expect(isBlobItem(state.items["blob:abc123"]!)).toBe(true);
  });

  it("keeps the bytes under a name that stays masked", async () => {
    const { sync } = await withScan();
    const blob = (await sync.state()).items["blob:abc123"]!;
    expect(itemField(blob, "secret:data")).toBe("PRETEND-JPEG-BYTES");
    expect(itemField(blob, "name")).toBe("scan.jpg");
    expect(itemField(blob, "type")).toBe("image/jpeg");
  });

  /** Content-addressed, so the same file on two passwords costs one copy. */
  it("stores one copy of a file attached twice", async () => {
    const { sync } = await withScan();
    await sync.putItem({ itemId: "visa", keyringId: "house", fields: { title: "Visa" } });
    await sync.attachFile({
      itemId: "visa",
      keyringId: "house",
      blobId: "blob:abc123",
      name: "scan.jpg",
      type: "image/jpeg",
      data: "PRETEND-JPEG-BYTES",
      bytes: 18,
    });

    const state = await sync.state();
    expect(Object.keys(state.items).filter((id) => id.startsWith("blob:"))).toEqual([
      "blob:abc123",
    ]);
  });
});

describe("removing a file", () => {
  it("takes the bytes with it", async () => {
    const { sync } = await withScan();
    await sync.removeAttachment("passport", "blob:abc123");

    const state = await sync.state();
    expect(attachmentsOf(state.items["passport"]!)).toEqual([]);
    expect(itemField(state.items["blob:abc123"]!, "secret:data")).toBe("");
    expect(state.items["blob:abc123"]?.deleted.value).toBe(true);
  });

  it("keeps the bytes while another password still wants them", async () => {
    const { sync } = await withScan();
    await sync.putItem({ itemId: "visa", keyringId: "house", fields: { title: "Visa" } });
    await sync.attachFile({
      itemId: "visa",
      keyringId: "house",
      blobId: "blob:abc123",
      name: "scan.jpg",
      type: "image/jpeg",
      data: "PRETEND-JPEG-BYTES",
      bytes: 18,
    });

    await sync.removeAttachment("passport", "blob:abc123");
    const state = await sync.state();
    expect(itemField(state.items["blob:abc123"]!, "secret:data")).toBe("PRETEND-JPEG-BYTES");
    expect(attachmentsOf(state.items["visa"]!)).toHaveLength(1);
  });
});

describe("deleting the password a file hangs off", () => {
  /**
   * The file has to go too. Left behind it is somebody's scanned passport,
   * still in the vault and still in the backup, with nothing on any screen to
   * remind them it is there.
   */
  it("takes the file with it", async () => {
    const { sync } = await withScan();
    await sync.deleteItem("passport");

    const state = await sync.state();
    expect(itemField(state.items["blob:abc123"]!, "secret:data")).toBe("");
  });

  it("takes the files when the whole keyring goes", async () => {
    const { sync } = await withScan();
    await sync.deleteKeyringWithItems("house");

    const state = await sync.state();
    expect(itemField(state.items["blob:abc123"]!, "secret:data")).toBe("");
  });
});

describe("a file on a shared keyring", () => {
  /**
   * Sharing a keyring has to share its files. They are items on the keyring,
   * so this works for nothing — but it is exactly the thing that would have
   * needed rebuilding had files been stored anywhere else.
   */
  it("moves into the shared document with its password", async () => {
    const { storage, sync } = await withScan();
    await sync.bindKeyring("house", "ds-house");

    const dataset = await storage.readState("ds-house");
    expect(itemField(dataset.items["blob:abc123"]!, "secret:data")).toBe("PRETEND-JPEG-BYTES");

    // And the private vault keeps a scrubbed tombstone rather than the scan.
    const vault = await storage.readState(VAULT_DOCUMENT);
    expect(itemField(vault.items["blob:abc123"]!, "secret:data")).toBe("");
  });

  it("is published to the shared file, not the private one", async () => {
    const { sync, vaultRemote, datasetRemote } = await withScan();
    await sync.bindKeyring("house", "ds-house");
    await sync.sync();

    expect(itemField(datasetRemote.snapshot().items["blob:abc123"]!, "secret:data")).toBe(
      "PRETEND-JPEG-BYTES",
    );
    expect(itemField(vaultRemote.snapshot().items["blob:abc123"]!, "secret:data")).toBe("");
  });

  /** Moving a password out of a shared keyring must take its files too. */
  it("comes back out when its password is moved to a private keyring", async () => {
    const { storage, sync, datasetRemote } = await withScan();
    await sync.bindKeyring("house", "ds-house");
    await sync.sync();

    await sync.putKeyring({ keyringId: "mine", name: "Just mine" });
    await sync.moveItems(["passport", "blob:abc123"], "mine");
    await sync.sync();

    const state = await sync.state();
    expect(itemField(state.items["blob:abc123"]!, "secret:data")).toBe("PRETEND-JPEG-BYTES");
    // Gone from the file the other person reads.
    expect(itemField(datasetRemote.snapshot().items["blob:abc123"]!, "secret:data")).toBe("");
    expect(await storage.readState(VAULT_DOCUMENT)).toBeDefined();
  });
});

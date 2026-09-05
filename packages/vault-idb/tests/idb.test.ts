import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { beforeEach, describe, expect, it } from "vitest";
import {
  createClock,
  FakeRemote,
  itemField,
  MemoryVaultStorage,
  seqIds,
  VaultSync,
} from "@keyweb/vault-core";
import { IndexedDbVaultStorage, type VaultCipher } from "../src/index.js";

let factory: IDBFactory;
let dbName: string;
let counter = 0;

beforeEach(() => {
  factory = new IDBFactory();
  dbName = `keyweb-test-${++counter}`;
});

async function openStorage(cipher?: VaultCipher): Promise<IndexedDbVaultStorage> {
  return IndexedDbVaultStorage.open({ factory, name: dbName, ...(cipher ? { cipher } : {}) });
}

describe("IndexedDB storage", () => {
  it("persists state and the outbox across a reopen", async () => {
    const storage = await openStorage();
    const clock = createClock({ node: "A" });
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "s3cret" } });
    storage.close();

    // Reopen as a fresh process would.
    const reopened = await openStorage();
    const state = await reopened.readState();
    expect(itemField(state.items.bank!, "password")).toBe("s3cret");
    expect(await reopened.pending()).toHaveLength(2);
    expect(await reopened.readClock()).toBeDefined();
  });

  it("joins on applyRemote instead of replacing", async () => {
    const storage = await openStorage();
    const clock = createClock({ node: "A" });
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "local", keyringId: "ring", fields: { password: "keep-me" } });

    const joined = await storage.applyRemote({ items: {}, keyrings: {} });
    expect(itemField(joined.items.local!, "password")).toBe("keep-me");

    const reread = await storage.readState();
    expect(itemField(reread.items.local!, "password")).toBe("keep-me");
  });

  it("acknowledges only the operations it is given", async () => {
    const storage = await openStorage();
    const clock = createClock({ node: "A" });
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "one", keyringId: "ring", fields: { password: "a" } });
    await sync.putItem({ itemId: "two", keyringId: "ring", fields: { password: "b" } });

    const pending = await storage.pending();
    expect(pending).toHaveLength(3);
    await storage.ack([pending[0]!.opId, pending[2]!.opId]);

    const left = await storage.pending();
    expect(left).toHaveLength(1);
    expect(left[0]!.opId).toBe(pending[1]!.opId);
  });

  it("keeps outbox order stable", async () => {
    const storage = await openStorage();
    const clock = createClock({ node: "A" });
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    for (let i = 0; i < 30; i += 1) {
      await sync.putItem({ itemId: `item-${i}`, keyringId: "ring", fields: { password: `p${i}` } });
    }
    const pending = await storage.pending();
    expect(pending.map((op) => op.opId)).toEqual(
      Array.from({ length: 30 }, (_, i) => `A-${i + 1}`),
    );
  });
});

describe("the full engine on IndexedDB", () => {
  it("does not lose an edit committed during an in-flight sync", async () => {
    const storage = await openStorage();
    const clock = createClock({ node: "A" });
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.sync();

    remote.writeDelayMs = 20;
    let injected = false;
    remote.onBeforeWrite = async () => {
      if (injected) return;
      injected = true;
      await sync.putItem({
        itemId: "bank",
        keyringId: "ring",
        fields: { password: "the-one-that-matters" },
      });
    };

    await sync.putItem({ itemId: "chase", keyringId: "ring", fields: { password: "rotated" } });
    await sync.sync();

    expect(itemField((await storage.readState()).items.bank!, "password")).toBe(
      "the-one-that-matters",
    );
    expect(await sync.isFullyBackedUp()).toBe(false);

    await sync.sync();
    expect(itemField(remote.snapshot().items.bank!, "password")).toBe("the-one-that-matters");
    expect(await sync.isFullyBackedUp()).toBe(true);
  });

  it("behaves identically to the in-memory reference implementation", async () => {
    const scenario = async (storage: MemoryVaultStorage | IndexedDbVaultStorage) => {
      const remote = new FakeRemote();
      const clock = createClock({ node: "A", physical: () => 1_700_000_000_000 });
      const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });
      await sync.putKeyring({ keyringId: "ring", name: "Household" });
      await sync.putItem({ itemId: "a", keyringId: "ring", fields: { password: "1" } });
      await sync.sync();
      remote.offline = true;
      await sync.putItem({ itemId: "b", keyringId: "ring", fields: { password: "2" } });
      await sync.sync();
      remote.offline = false;
      await sync.sync();
      await sync.deleteItem("a");
      await sync.sync();
      return remote.snapshot();
    };

    const memory = await scenario(new MemoryVaultStorage());
    const idb = await scenario(await openStorage());

    expect(Object.keys(idb.items).sort()).toEqual(Object.keys(memory.items).sort());
    expect(idb.items.a!.deleted.value).toBe(memory.items.a!.deleted.value);
    expect(itemField(idb.items.b!, "password")).toBe(itemField(memory.items.b!, "password"));
  });
});

/**
 * A real AES-GCM cipher, so the at-rest tests exercise the same shape the app
 * uses rather than a stand-in that happens to satisfy the interface.
 */
async function testCipher(): Promise<VaultCipher> {
  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, [
    "encrypt",
    "decrypt",
  ]);
  const seal = async (value: unknown) => {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const plaintext = new TextEncoder().encode(JSON.stringify(value));
    const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plaintext);
    return { iv: [...iv], ct: [...new Uint8Array(ciphertext)] };
  };
  const open = async (stored: unknown) => {
    const { iv, ct } = stored as { iv: number[]; ct: number[] };
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: new Uint8Array(iv) },
      key,
      new Uint8Array(ct),
    );
    return JSON.parse(new TextDecoder().decode(plaintext));
  };
  return {
    sealState: seal,
    openState: open,
    sealOp: seal,
    openOp: open,
  };
}

/** Read every stored byte without going through the cipher. */
async function rawDump(name: string, factory: IDBFactory): Promise<string> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const req = factory.open(name);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  const read = (store: string) =>
    new Promise<unknown[]>((resolve, reject) => {
      const req = db.transaction(store, "readonly").objectStore(store).getAll();
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  const dump = JSON.stringify({ meta: await read("meta"), outbox: await read("outbox") });
  db.close();
  return dump;
}

describe("encryption at rest", () => {
  it("writes no plaintext to disk, not in the state and not in the outbox", async () => {
    const storage = await openStorage(await testCipher());
    const clock = createClock({ node: "A" });
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      clock,
      newId: seqIds("A"),
    });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Credit Union", password: "correct-horse-battery" },
    });

    const dump = await rawDump(dbName, factory);
    // The password must not be recoverable by reading the database, and neither
    // must the queued operation that carries it.
    expect(dump).not.toContain("correct-horse-battery");
    expect(dump).not.toContain("Credit Union");
    expect(dump).not.toContain("Household");
    // The operation id stays clear so an acknowledgement needs no key.
    expect(dump).toContain("A-1");
  });

  it("reads its own ciphertext back correctly", async () => {
    const cipher = await testCipher();
    const storage = await openStorage(cipher);
    const clock = createClock({ node: "A" });
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      clock,
      newId: seqIds("A"),
    });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "s3cret" } });

    const reopened = await openStorage(cipher);
    const state = await reopened.readState();
    expect(itemField(state.items.bank!, "password")).toBe("s3cret");
    expect(await reopened.pending()).toHaveLength(2);
    expect((await reopened.pending())[1]!.opId).toBe("A-2");
  });

  it("still joins rather than replaces when encrypted", async () => {
    const cipher = await testCipher();
    const storage = await openStorage(cipher);
    const clock = createClock({ node: "A" });
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      clock,
      newId: seqIds("A"),
    });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "local", keyringId: "ring", fields: { password: "keep-me" } });

    const joined = await storage.applyRemote({ items: {}, keyrings: {} });
    expect(itemField(joined.items.local!, "password")).toBe("keep-me");
  });

  it("cannot be read with the wrong key", async () => {
    const storage = await openStorage(await testCipher());
    const clock = createClock({ node: "A" });
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      clock,
      newId: seqIds("A"),
    });
    await sync.putKeyring({ keyringId: "ring", name: "Household" });

    // A different session, a different key: the bytes must be useless.
    const wrongKey = await openStorage(await testCipher());
    await expect(wrongKey.readState()).rejects.toThrow();
  });
});

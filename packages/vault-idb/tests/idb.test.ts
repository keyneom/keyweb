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
import { IndexedDbVaultStorage } from "../src/index.js";

let factory: IDBFactory;
let dbName: string;
let counter = 0;

beforeEach(() => {
  factory = new IDBFactory();
  dbName = `keyweb-test-${++counter}`;
});

async function openStorage(): Promise<IndexedDbVaultStorage> {
  return IndexedDbVaultStorage.open(factory, dbName);
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

import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { itemField, visibleItems } from "../src/model.js";
import type { VaultOp } from "../src/ops.js";
import { MemoryVaultStorage } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote, seqIds } from "../src/testing.js";

function makeVault(node: string, remote: FakeRemote, physical?: () => number) {
  const storage = new MemoryVaultStorage();
  const clock = createClock(physical ? { node, physical } : { node });
  const sync = new VaultSync({ storage, remote, clock, newId: seqIds(node) });
  return { storage, clock, sync };
}

async function seed(sync: VaultSync) {
  await sync.putKeyring({ keyringId: "ring", name: "Household" });
  await sync.putItem({
    itemId: "chase",
    keyringId: "ring",
    fields: { title: "Chase Bank", password: "old-password" },
  });
  await sync.sync();
}

describe("an edit made while a sync is in flight", () => {
  it("survives locally and reaches the cloud - the easy-bc data-loss bug", async () => {
    const remote = new FakeRemote();
    const { storage, sync } = makeVault("A", remote);
    await seed(sync);

    // The user opens the app, an automatic sync starts, and while the upload
    // is in flight they save a new bank password and are shown "Saved".
    remote.writeDelayMs = 20;
    let injected = false;
    remote.onBeforeWrite = async () => {
      if (injected) return;
      injected = true;
      await sync.putItem({
        itemId: "bank",
        keyringId: "ring",
        fields: { title: "Credit Union", password: "the-one-that-matters" },
      });
    };

    await sync.putItem({ itemId: "chase", keyringId: "ring", fields: { password: "rotated" } });
    await sync.sync();

    // It must still be on the device.
    const local = await sync.state();
    expect(local.items.bank).toBeDefined();
    expect(itemField(local.items.bank!, "password")).toBe("the-one-that-matters");

    // It must NOT have been acknowledged, because it was not in that revision.
    expect(await storage.pending()).toHaveLength(1);
    expect(await sync.isFullyBackedUp()).toBe(false);

    // And the very next sync must publish it.
    await sync.sync();
    expect(remote.snapshot().items.bank).toBeDefined();
    expect(itemField(remote.snapshot().items.bank!, "password")).toBe("the-one-that-matters");
    expect(await sync.isFullyBackedUp()).toBe(true);
  });

  it("keeps every one of many edits made during a single slow sync", async () => {
    const remote = new FakeRemote();
    const { sync } = makeVault("A", remote);
    await seed(sync);

    remote.writeDelayMs = 30;
    let injected = false;
    remote.onBeforeWrite = async () => {
      if (injected) return;
      injected = true;
      for (let i = 0; i < 25; i += 1) {
        await sync.putItem({
          itemId: `site-${i}`,
          keyringId: "ring",
          fields: { title: `Site ${i}`, password: `pw-${i}` },
        });
      }
    };

    await sync.putItem({ itemId: "chase", keyringId: "ring", fields: { password: "rotated" } });
    await sync.sync();
    await sync.sync();

    const published = remote.snapshot();
    for (let i = 0; i < 25; i += 1) {
      expect(itemField(published.items[`site-${i}`]!, "password")).toBe(`pw-${i}`);
    }
    expect(await sync.isFullyBackedUp()).toBe(true);
  });
});

describe("offline behaviour", () => {
  it("reports offline, keeps the queue, and publishes everything on reconnect", async () => {
    const remote = new FakeRemote();
    const { storage, sync } = makeVault("A", remote);
    await seed(sync);

    remote.offline = true;
    await sync.putItem({
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Credit Union", password: "offline-save" },
    });
    const outcome = await sync.sync();
    expect(outcome.status).toBe("offline");
    expect(outcome.pending).toBe(1);
    expect(await sync.isFullyBackedUp()).toBe(false);

    // The edit is durable on the device even though the cloud never saw it.
    expect(itemField((await sync.state()).items.bank!, "password")).toBe("offline-save");

    remote.offline = false;
    const reconnected = await sync.sync();
    expect(reconnected.status).toBe("published");
    expect(await storage.pending()).toHaveLength(0);
    expect(itemField(remote.snapshot().items.bank!, "password")).toBe("offline-save");
  });
});

describe("two devices", () => {
  it("merges both sides when a competing revision lands mid-flight", async () => {
    const remote = new FakeRemote();
    const a = makeVault("A", remote);
    const b = makeVault("B", remote);

    await seed(a.sync);
    await b.sync.sync(); // B adopts the baseline

    await a.sync.putItem({
      itemId: "netflix",
      keyringId: "ring",
      fields: { title: "Netflix", password: "from-A" },
    });
    await b.sync.putItem({
      itemId: "costco",
      keyringId: "ring",
      fields: { title: "Costco", password: "from-B" },
    });

    // B publishes during A's read-to-write window, so A's write is rejected
    // and must re-merge rather than clobber.
    let raced = false;
    remote.onBeforeWrite = async () => {
      if (raced) return;
      raced = true;
      await b.sync.sync();
    };

    const outcome = await a.sync.sync();
    expect(outcome.status).toBe("published");
    expect(remote.rejectedWrites).toBeGreaterThan(0);

    const published = remote.snapshot();
    expect(itemField(published.items.netflix!, "password")).toBe("from-A");
    expect(itemField(published.items.costco!, "password")).toBe("from-B");
  });

  it("keeps both edits when each device changes a different field of one login", async () => {
    const remote = new FakeRemote();
    const a = makeVault("A", remote);
    const b = makeVault("B", remote);

    await seed(a.sync);
    await b.sync.sync();

    await a.sync.putItem({ itemId: "chase", keyringId: "ring", fields: { username: "maria@x.com" } });
    await b.sync.putItem({ itemId: "chase", keyringId: "ring", fields: { password: "rotated-by-B" } });

    await a.sync.sync();
    await b.sync.sync();
    await a.sync.sync();

    const item = remote.snapshot().items.chase!;
    expect(itemField(item, "username")).toBe("maria@x.com");
    expect(itemField(item, "password")).toBe("rotated-by-B");
  });

  it("gives up cleanly rather than looping forever under permanent contention", async () => {
    const remote = new FakeRemote();
    const { sync } = makeVault("A", remote);
    await seed(sync);

    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "x" } });
    remote.forceConflicts = 99;
    const outcome = await sync.sync();
    expect(outcome.status).toBe("conflict-exhausted");
    // The edit is still queued - nothing was thrown away.
    expect(outcome.pending).toBe(1);
  });
});

describe("crash safety", () => {
  it("replays an operation published but not acknowledged, without duplicating it", async () => {
    const remote = new FakeRemote();
    const storage = new MemoryVaultStorage();
    const clock = createClock({ node: "A" });

    // Storage whose acknowledgement fails once, simulating a kill between the
    // successful upload and the local bookkeeping that follows it.
    let failAck = true;
    const brittle = {
      readState: () => storage.readState(),
      commit: (op: VaultOp, next: Parameters<typeof storage.commit>[1]) =>
        storage.commit(op, next),
      applyRemote: (incoming: Parameters<typeof storage.applyRemote>[0]) =>
        storage.applyRemote(incoming),
      pending: () => storage.pending(),
      ack: async (ids: readonly string[]) => {
        if (failAck) {
          failAck = false;
          throw new Error("process killed");
        }
        await storage.ack(ids);
      },
      commitAll: (...args: Parameters<typeof storage.commitAll>) => storage.commitAll(...args),
      knownDocuments: () => storage.knownDocuments(),
      readClock: () => storage.readClock(),
      writeClock: (value: string) => storage.writeClock(value),
    };

    const sync = new VaultSync({ storage: brittle, remote, clock, newId: seqIds("A") });
    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "s3cret" } });

    await expect(sync.sync()).rejects.toThrow("process killed");

    // The upload did land, and the operations are still queued.
    expect(remote.snapshot().items.bank).toBeDefined();
    expect(await storage.pending()).toHaveLength(2);

    // Replaying is harmless and settles the queue.
    const outcome = await sync.sync();
    expect(outcome.status).toBe("unchanged");
    expect(await storage.pending()).toHaveLength(0);
    expect(visibleItems(remote.snapshot())).toHaveLength(1);
    expect(itemField(remote.snapshot().items.bank!, "password")).toBe("s3cret");
  });

  it("rebuilds correctly after a restart with a non-empty queue", async () => {
    const remote = new FakeRemote();
    const storage = new MemoryVaultStorage();
    const clock = createClock({ node: "A" });
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    remote.offline = true;
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "s3cret" } });
    await sync.sync();

    // Restart: durable state and queue survive, engine and clock are new.
    const restarted = storage.fork();
    const resumed = createClock({ node: "A", resume: await restarted.readClock() });
    const sync2 = new VaultSync({
      storage: restarted,
      remote,
      clock: resumed,
      newId: seqIds("A2"),
    });

    remote.offline = false;
    const outcome = await sync2.sync();
    expect(outcome.status).toBe("published");
    expect(itemField(remote.snapshot().items.bank!, "password")).toBe("s3cret");
    expect(await restarted.pending()).toHaveLength(0);
  });
});

describe("clock skew", () => {
  it("lets a device with a slow clock still win for its later edit", async () => {
    const remote = new FakeRemote();
    // B's clock is an hour behind A's - common on old hardware.
    const a = makeVault("A", remote, () => 1_700_000_000_000);
    const b = makeVault("B", remote, () => 1_700_000_000_000 - 3_600_000);

    await a.sync.putKeyring({ keyringId: "ring", name: "Household" });
    await a.sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "from-A" } });
    await a.sync.sync();

    // B pulls A's revision, then makes a genuinely later edit.
    await b.sync.sync();
    await b.sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "from-B" } });
    await b.sync.sync();

    // With naive wall-clock LWW, B's edit would lose and vanish.
    expect(itemField(remote.snapshot().items.bank!, "password")).toBe("from-B");

    await a.sync.sync();
    expect(itemField((await a.sync.state()).items.bank!, "password")).toBe("from-B");
  });
});

describe("backed-up reporting", () => {
  it("never claims backed up while anything is queued", async () => {
    const remote = new FakeRemote();
    const { sync } = makeVault("A", remote);

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    expect(await sync.isFullyBackedUp()).toBe(false);

    await sync.sync();
    expect(await sync.isFullyBackedUp()).toBe(true);

    remote.offline = true;
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "x" } });
    expect(await sync.isFullyBackedUp()).toBe(false);
    await sync.sync();
    expect(await sync.isFullyBackedUp()).toBe(false);

    remote.offline = false;
    await sync.sync();
    expect(await sync.isFullyBackedUp()).toBe(true);
  });
});

describe("storage contract", () => {
  it("applyRemote joins rather than replaces", async () => {
    const storage = new MemoryVaultStorage();
    const clock = createClock({ node: "A" });
    const remote = new FakeRemote();
    const sync = new VaultSync({ storage, remote, clock, newId: seqIds("A") });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "local-only", keyringId: "ring", fields: { password: "keep-me" } });

    // An incoming revision that knows nothing about the local item must not
    // erase it. A blind assignment here is the easy-bc bug.
    const joined = await storage.applyRemote({ items: {}, keyrings: {} });
    expect(joined.items["local-only"]).toBeDefined();
    expect(itemField(joined.items["local-only"]!, "password")).toBe("keep-me");
  });
});

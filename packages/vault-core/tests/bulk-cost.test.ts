import { describe, expect, it } from "vitest";
import { createClock } from "../src/hlc.js";
import { MemoryVaultStorage } from "../src/storage.js";
import { VaultSync } from "../src/sync.js";
import { FakeRemote } from "../src/testing.js";
import type { VaultOp } from "../src/ops.js";
import type { VaultState } from "../src/model.js";

/**
 * Counts what a bulk change costs the storage layer.
 *
 * The counts are the point, not the wall clock. In memory nothing is encrypted
 * so everything is instant; on a phone each `commit` re-encrypts the whole
 * vault and each `pending` decrypts every queued operation, so a count that
 * grows with the number of passwords is a delay the user sits through.
 */
class CountingStorage extends MemoryVaultStorage {
  readStates = 0;
  commits = 0;
  batches = 0;
  clockWrites = 0;
  pendingCalls = 0;
  /** Total ops decrypted across every pending() call. */
  opsScanned = 0;

  override async readState(): Promise<VaultState> {
    this.readStates += 1;
    return super.readState();
  }

  override async commit(op: VaultOp, next: VaultState): Promise<void> {
    this.commits += 1;
    return super.commit(op, next);
  }

  override async commitAll(ops: readonly VaultOp[], next: VaultState): Promise<void> {
    this.batches += 1;
    return super.commitAll(ops, next);
  }

  override async writeClock(value: string): Promise<void> {
    this.clockWrites += 1;
    return super.writeClock(value);
  }

  override async pending(): Promise<VaultOp[]> {
    this.pendingCalls += 1;
    const out = await super.pending();
    this.opsScanned += out.length;
    return out;
  }
}

async function vaultOf(size: number) {
  const storage = new CountingStorage();
  const sync = new VaultSync({
    storage,
    remote: new FakeRemote(),
    clock: createClock({ node: "bench" }),
  });
  await sync.putKeyring({ keyringId: "doomed", name: "Old" });
  for (let i = 0; i < size; i += 1) {
    await sync.putItem({ itemId: `item-${i}`, keyringId: "doomed", fields: { title: `t${i}` } });
  }
  // Only the bulk change that follows should be counted.
  storage.readStates = 0;
  storage.commits = 0;
  storage.batches = 0;
  storage.clockWrites = 0;
  storage.opsScanned = 0;
  storage.pendingCalls = 0;
  return { storage, sync };
}

describe("cost of a bulk change", () => {
  it("deletes a 200-password keyring in a single write", async () => {
    const { storage, sync } = await vaultOf(200);
    const after = await sync.deleteKeyringWithItems("doomed");

    expect(Object.values(after.items).every((item) => item.deleted.value)).toBe(true);
    expect(after.keyrings["doomed"]!.deleted.value).toBe(true);

    // One read to find the members, one to fold from. One write. One clock.
    expect(storage.batches).toBe(1);
    expect(storage.commits).toBe(0);
    expect(storage.clockWrites).toBe(1);
    expect(storage.readStates).toBeLessThanOrEqual(2);
  });

  it("costs the same whether the keyring holds 10 passwords or 500", async () => {
    // The regression this guards: any per-item read, write or clock write
    // reappearing turns a keyring delete back into a wait.
    const small = await vaultOf(10);
    await small.sync.deleteKeyringWithItems("doomed");
    const large = await vaultOf(500);
    await large.sync.deleteKeyringWithItems("doomed");

    expect(large.storage.batches).toBe(small.storage.batches);
    expect(large.storage.clockWrites).toBe(small.storage.clockWrites);
    expect(large.storage.readStates).toBe(small.storage.readStates);
    // The outbox is read once, not once per password. It is the repeated
    // scanning that made the old cost grow with the square of the size: each
    // commit re-read an outbox that each commit had just made longer.
    expect(large.storage.pendingCalls).toBe(1);
    expect(small.storage.pendingCalls).toBe(1);
    // One scan of an outbox holding the setup ops plus the 501 new ones.
    expect(large.storage.opsScanned).toBe(1002);
    expect(small.storage.opsScanned).toBe(22);
  });

  it("queues every operation, so the remote still learns about each one", async () => {
    // Batching is a storage optimisation and must not become a semantic one:
    // each deletion still needs its own op, or a merge on another device would
    // not know the passwords went.
    const { storage, sync } = await vaultOf(20);
    await sync.deleteKeyringWithItems("doomed");
    const queued = await storage.pending();
    expect(queued.filter((op) => op.kind === "item.delete")).toHaveLength(20);
    expect(queued.filter((op) => op.kind === "keyring.delete")).toHaveLength(1);
  });

  it("deletes and moves in bulk in one write each", async () => {
    const { storage, sync } = await vaultOf(50);
    await sync.putKeyring({ keyringId: "other", name: "Other" });
    const ids = Array.from({ length: 50 }, (_, i) => `item-${i}`);

    await sync.moveItems(ids, "other");
    await sync.deleteItems(ids.slice(0, 25));

    // One batch each, plus the single commit for putKeyring above.
    expect(storage.batches).toBe(2);
    expect(storage.commits).toBe(1);
  });
});

describe("cost of an import", () => {
  it("writes a 200-password import once, whatever the keyring count", async () => {
    const storage = new CountingStorage();
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      clock: createClock({ node: "bench" }),
    });

    // What importKeePass / confirmImport build: a keyring op per new group,
    // then an item op per entry, handed over as one batch.
    const ops: VaultOp[] = [];
    for (const name of ["Banking", "Shopping", "Work", "Email", "Misc"]) {
      const { opId, ts } = sync.stamp();
      ops.push({ kind: "keyring.put", opId, ts, keyringId: `r-${name}`, name });
    }
    for (let i = 0; i < 200; i += 1) {
      const { opId, ts } = sync.stamp();
      ops.push({
        kind: "item.put",
        opId,
        ts,
        itemId: `kdbx:${i}`,
        keyringId: "r-Banking",
        fields: { title: `t${i}` },
      });
    }

    const after = await sync.commitAll(ops);

    expect(Object.keys(after.items)).toHaveLength(200);
    expect(Object.keys(after.keyrings)).toHaveLength(5);
    expect(storage.batches).toBe(1);
    expect(storage.commits).toBe(0);
    expect(storage.clockWrites).toBe(1);
  });

  it("stamps every operation separately, so a re-import still merges", async () => {
    // The tempting shortcut is one op for the whole import. It would break
    // re-importing: each entry needs its own causal timestamp to win or lose
    // against an edit made in Keyweb since the last import.
    const storage = new CountingStorage();
    const sync = new VaultSync({
      storage,
      remote: new FakeRemote(),
      clock: createClock({ node: "bench" }),
    });

    const stamps = Array.from({ length: 10 }, () => sync.stamp());
    expect(new Set(stamps.map((s) => s.opId)).size).toBe(10);
    expect(new Set(stamps.map((s) => s.ts)).size).toBe(10);

    await sync.commitAll(
      stamps.map((s, i) => ({
        kind: "item.put" as const,
        opId: s.opId,
        ts: s.ts,
        itemId: `i${i}`,
        keyringId: "r",
        fields: { title: `t${i}` },
      })),
    );
    expect(await storage.pending()).toHaveLength(10);
  });
});

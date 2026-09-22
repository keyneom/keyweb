import { describe, expect, it } from "vitest";
import {
  applyOps,
  createClock,
  emptyVault,
  encodeHlc,
  itemField,
  MemoryVaultStorage,
  VaultSync,
  type RemoteRevision,
  type RemoteVaultStore,
  type VaultState,
} from "@keyweb/vault-core";
import { INDEX_DATASET_ID, IndexRemote } from "../src/vault/sharing/controller";

/**
 * The vault's root, moved into a file of the same kind as every keyring.
 *
 * The root used to live in a file sealed twice — once to a passkey key, once
 * to the printed code — so a device holding only one of them could refresh one
 * copy and leave the other stale. The index is encrypted once with its key
 * wrapped to you, like every keyring, so there is no second copy at all.
 *
 * The move off the old file is the part that must not lose anything: whatever
 * that file held has to arrive in the index, and the old file must never be
 * written again once it has been left.
 */

class NotFound extends Error {
  readonly code = "not-found";
}

/** Just enough of sync-kit's controller to hold one dataset. */
class FakeController {
  datasets = new Map<string, { value: VaultState; revision: number }>();
  created: string[] = [];

  async loadDataset(datasetId: string) {
    const found = this.datasets.get(datasetId);
    if (!found) throw new NotFound(`Dataset ${datasetId} was not found.`);
    return { value: found.value, revisionId: `rev-${found.revision}` };
  }

  async adoptDataset(datasetId: string) {
    return this.loadDataset(datasetId);
  }

  async createDataset(datasetId: string, value: VaultState) {
    if (this.datasets.has(datasetId)) throw new Error("already exists");
    this.created.push(datasetId);
    this.datasets.set(datasetId, { value, revision: 1 });
    return { value, revisionId: "rev-1" };
  }

  async syncDataset(
    datasetId: string,
    mutator: { read: () => VaultState; apply: (merged: VaultState) => VaultState },
  ) {
    const found = this.datasets.get(datasetId)!;
    const next = mutator.apply(mutator.read());
    this.datasets.set(datasetId, { value: next, revision: found.revision + 1 });
    return { value: next, revisionId: `rev-${found.revision + 1}` };
  }
}

/** The old double-sealed file, counting writes so "never again" can be checked. */
class LegacyFile implements RemoteVaultStore {
  writes = 0;
  constructor(private held: VaultState | null) {}
  async read(): Promise<RemoteRevision | null> {
    return this.held ? { state: this.held, version: "legacy-1" } : null;
  }
  async write(): Promise<string> {
    this.writes += 1;
    return "legacy-2";
  }
}

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "phone" });
}

/** What the old file held: a keyring and a password saved on the phone. */
function phoneVault(): VaultState {
  return applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "personal", name: "Just mine" },
    {
      kind: "item.put",
      opId: "i",
      ts: at(1),
      itemId: "bank",
      keyringId: "personal",
      fields: { title: "Credit Union", password: "saved-on-the-phone" },
    },
  ]);
}

function device(controller: FakeController, legacy: LegacyFile | null) {
  return new VaultSync({
    storage: new MemoryVaultStorage(),
    remote: new IndexRemote(controller as never, legacy),
    clock: createClock({ node: "web" }),
  });
}

describe("moving the vault root into a file of its own kind", () => {
  it("carries what the old file held into the index", async () => {
    const controller = new FakeController();
    const legacy = new LegacyFile(phoneVault());
    const sync = device(controller, legacy);

    await sync.sync();

    expect(controller.created).toEqual([INDEX_DATASET_ID]);
    const index = controller.datasets.get(INDEX_DATASET_ID)!.value;
    expect(itemField(index.items["bank"]!, "password")).toBe("saved-on-the-phone");
    // And the device shows it.
    const state = await sync.state();
    expect(itemField(state.items["bank"]!, "title")).toBe("Credit Union");
  });

  it("never writes the old file", async () => {
    const controller = new FakeController();
    const legacy = new LegacyFile(phoneVault());
    const sync = device(controller, legacy);

    await sync.sync();
    await sync.putItem({ itemId: "new", keyringId: "personal", fields: { title: "Added" } });
    await sync.sync();

    expect(legacy.writes).toBe(0);
    expect(controller.datasets.get(INDEX_DATASET_ID)!.value.items["new"]).toBeDefined();
  });

  it("reads the index, not the old file, once another device has moved", async () => {
    const controller = new FakeController();
    await device(controller, new LegacyFile(phoneVault())).sync();

    // A second device whose old file is stale — or unreadable to it — never
    // needs to look at it: the index is already there.
    const stale = new LegacyFile(
      applyOps(emptyVault(), [
        { kind: "keyring.put", opId: "k0", ts: at(0), keyringId: "personal", name: "Just mine" },
      ]),
    );
    const second = device(controller, stale);
    await second.sync();

    const state = await second.state();
    expect(itemField(state.items["bank"]!, "password")).toBe("saved-on-the-phone");
    expect(controller.created).toEqual([INDEX_DATASET_ID]);
    expect(stale.writes).toBe(0);
  });

  it("starts an index from nothing for a vault that never had the old file", async () => {
    const controller = new FakeController();
    const sync = device(controller, null);
    await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
    await sync.sync();

    expect(controller.created).toEqual([INDEX_DATASET_ID]);
  });
});

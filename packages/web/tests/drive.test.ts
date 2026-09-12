import { describe, expect, it } from "vitest";
import {
  createClock,
  MemoryVaultStorage,
  VaultSync,
  emptyVault,
  itemField,
  seqIds,
  VersionConflictError,
} from "@keyweb/vault-core";
import type { GoogleDriveFileStore } from "@keyneom/sync-kit/stores/google-drive";
import { GoogleDriveRemote } from "../src/vault/drive";
import { unlockVault } from "../src/vault/crypto";

/**
 * A stand-in for Drive that behaves the way Drive actually behaves: files are
 * addressed by id, every write bumps a revision, and another device can land a
 * revision between our read and our write.
 */
class FakeDrive {
  files = new Map<
    string,
    { name: string; content: string; revision: number; appProperties: Record<string, string> }
  >();
  private nextId = 1;
  offline = false;
  /** Runs between the preflight check and the upload, to inject a race. */
  onBeforeWrite: (() => Promise<void> | void) | null = null;

  private guard() {
    if (this.offline) throw new Error("network unreachable");
  }

  async list(_auth: unknown, options: { appProperties?: Record<string, string> } = {}) {
    this.guard();
    const wanted = Object.entries(options.appProperties ?? {});
    const files = [...this.files.entries()]
      .filter(([, file]) => wanted.every(([k, v]) => file.appProperties[k] === v))
      .map(([fileId, file]) => ({ fileId, name: file.name }));
    return { files };
  }

  async readText(fileId: string) {
    this.guard();
    return this.files.get(fileId)?.content ?? "";
  }

  async getV2WriteHead(fileId: string) {
    this.guard();
    const file = this.files.get(fileId);
    if (!file) throw new Error("not found");
    return { etag: `etag-${file.revision}`, headRevisionId: String(file.revision) };
  }

  async create(
    name: string,
    content: string,
    _auth: unknown,
    options: { appProperties?: Record<string, string> } = {},
  ) {
    this.guard();
    const fileId = `file-${this.nextId++}`;
    this.files.set(fileId, {
      name,
      content,
      revision: 1,
      appProperties: options.appProperties ?? {},
    });
    return fileId;
  }

  async createFolder(
    name: string,
    _auth: unknown,
    options: { appProperties?: Record<string, string> } = {},
  ) {
    this.guard();
    const fileId = `folder-${this.nextId++}`;
    this.files.set(fileId, {
      name,
      content: "",
      revision: 1,
      appProperties: options.appProperties ?? {},
    });
    return fileId;
  }

  async write(fileId: string, content: string) {
    this.guard();
    if (this.onBeforeWrite) await this.onBeforeWrite();
    const file = this.files.get(fileId);
    if (!file) throw new Error("not found");
    file.content = content;
    file.revision += 1;
    return { fileId };
  }

  /** Simulate another device publishing behind our back. */
  landForeignRevision(fileId: string, content: string) {
    const file = this.files.get(fileId);
    if (!file) throw new Error("not found");
    file.content = content;
    file.revision += 1;
  }

  vaultFile() {
    return [...this.files.values()].find((f) => f.appProperties["keyweb"] === "vault-v1");
  }

  asStore(): GoogleDriveFileStore {
    return this as unknown as GoogleDriveFileStore;
  }
}

function fakeAuthenticator(seed: number) {
  const secret = new Uint8Array(32).fill(seed);
  const rawId = new Uint8Array(16).fill(seed + 100);
  const credential = {
    rawId: rawId.buffer.slice(0),
    response: {},
    getClientExtensionResults: () => ({ prf: { results: { first: secret.buffer.slice(0) } } }),
  };
  return {
    credentials: { create: async () => credential, get: async () => credential },
  } as unknown as Navigator;
}

async function cipherFor(seed: number) {
  const { cipher } = await unlockVault(null, {
    rpId: "localhost",
    navigator: fakeAuthenticator(seed),
    secureContext: () => true,
  });
  return cipher;
}

async function makeDevice(
  drive: FakeDrive,
  node: string,
  seed: number,
  sealed: unknown | null = null,
) {
  const { cipher } = await unlockVault(sealed, {
    rpId: "localhost",
    navigator: fakeAuthenticator(seed),
    secureContext: () => true,
  });
  const remote = new GoogleDriveRemote({
    clientId: "test",
    cipher,
    store: drive.asStore(),
    authorize: async () => ({ accessToken: "token" }) as never,
  });
  const storage = new MemoryVaultStorage();
  const sync = new VaultSync({
    storage,
    remote,
    clock: createClock({ node }),
    newId: seqIds(node),
  });
  return { sync, storage, remote };
}

describe("encrypted Drive backup", () => {
  it("uploads ciphertext, never the passwords", async () => {
    const drive = new FakeDrive();
    const { sync } = await makeDevice(drive, "A", 1);

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Credit Union", password: "correct-horse-battery" },
    });
    const outcome = await sync.sync();
    expect(outcome.status).toBe("published");

    const stored = drive.vaultFile();
    expect(stored).toBeDefined();
    expect(stored!.content).not.toContain("correct-horse-battery");
    expect(stored!.content).not.toContain("Credit Union");
    expect(stored!.content).not.toContain("Household");
    expect(await sync.isFullyBackedUp()).toBe(true);
  });

  it("restores a vault onto a new device, which is the lost-phone case", async () => {
    const drive = new FakeDrive();
    const first = await makeDevice(drive, "A", 7);
    await first.sync.putKeyring({ keyringId: "ring", name: "Household" });
    await first.sync.putItem({
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Credit Union", password: "the-one-that-matters" },
    });
    await first.sync.sync();

    // A replacement phone: empty storage. It must seed its key from the backup
    // envelope, because the key depends on the salt recorded inside it.
    const sealed = await first.remote.fetchSealedState();
    expect(sealed).not.toBeNull();
    const replacement = await makeDevice(drive, "B", 7, sealed);
    expect(Object.keys((await replacement.sync.state()).items)).toHaveLength(0);

    await replacement.sync.sync();
    const restored = await replacement.sync.state();
    expect(itemField(restored.items.bank!, "password")).toBe("the-one-that-matters");
    expect(restored.keyrings.ring!.name.value).toBe("Household");
  });

  it("cannot be restored by someone with the file but not the passkey", async () => {
    const drive = new FakeDrive();
    const owner = await makeDevice(drive, "A", 11);
    await owner.sync.putKeyring({ keyringId: "ring", name: "Household" });
    await owner.sync.sync();

    // Same Drive file, different passkey: the bytes are useless.
    const thief = await makeDevice(drive, "Z", 12);
    const outcome = await thief.sync.sync();
    expect(outcome.status).toBe("offline");
  });

  it("refuses a write when another device published first", async () => {
    const drive = new FakeDrive();
    const a = await makeDevice(drive, "A", 21);
    await a.sync.putKeyring({ keyringId: "ring", name: "Household" });
    await a.sync.sync();

    const fileId = [...drive.files.entries()].find(
      ([, f]) => f.appProperties["keyweb"] === "vault-v1",
    )![0];
    const stale = await drive.getV2WriteHead(fileId);
    drive.landForeignRevision(fileId, "something else entirely");

    await expect(
      a.remote.write(emptyVault(), stale.headRevisionId),
    ).rejects.toBeInstanceOf(VersionConflictError);
  });

  it("keeps both devices' work when they race", async () => {
    const drive = new FakeDrive();
    const a = await makeDevice(drive, "A", 31);
    await a.sync.putKeyring({ keyringId: "ring", name: "Household" });
    await a.sync.sync();

    // The second device joins from the published envelope, as a real one would.
    const b = await makeDevice(drive, "B", 31, await a.remote.fetchSealedState());
    await b.sync.sync();

    await a.sync.putItem({ itemId: "netflix", keyringId: "ring", fields: { password: "from-A" } });
    await b.sync.putItem({ itemId: "costco", keyringId: "ring", fields: { password: "from-B" } });

    // B publishes inside A's read-to-write window.
    let raced = false;
    drive.onBeforeWrite = async () => {
      if (raced) return;
      raced = true;
      await b.sync.sync();
    };

    await a.sync.sync();

    // Drive has no compare-and-set, so B's revision can be overwritten in the
    // narrow window between A's freshness check and A's upload. What must hold
    // is that nothing is *lost*: B's device still holds costco, and the CRDT
    // join republishes it on B's next sync. Convergence, not a perfect write.
    drive.onBeforeWrite = null;
    await b.sync.sync();
    await a.sync.sync();

    const settled = await a.sync.state();
    expect(itemField(settled.items.netflix!, "password")).toBe("from-A");
    expect(itemField(settled.items.costco!, "password")).toBe("from-B");

    // And both devices agree.
    const onB = await b.sync.state();
    expect(itemField(onB.items.netflix!, "password")).toBe("from-A");
    expect(itemField(onB.items.costco!, "password")).toBe("from-B");
  });

  it("queues rather than loses work while Drive is unreachable", async () => {
    const drive = new FakeDrive();
    const { sync, storage } = await makeDevice(drive, "A", 41);

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.sync();

    drive.offline = true;
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { password: "offline" } });
    const offline = await sync.sync();
    expect(offline.status).toBe("offline");
    expect(await storage.pending()).not.toHaveLength(0);

    drive.offline = false;
    await sync.sync();
    expect(await storage.pending()).toHaveLength(0);
    expect(await sync.isFullyBackedUp()).toBe(true);
  });
});

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
import { BackupBehindError, GoogleDriveRemote, TooManyBackupsError } from "../src/vault/drive";
import { unlockVault } from "../src/vault/crypto";
import { FakeDrive, fakeAuthenticator } from "./helpers";

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

/**
 * A copy this browser can open, with a newer one beside it that it cannot.
 *
 * Reachable in one move: a phone with no passkey rewrites only the recovery
 * copy and leaves the passkey copy frozen. A browser holding the passkey but
 * not the code then opens the frozen one, and every timestamp in the file says
 * it is not the newest.
 *
 * Returning it would be the browser reporting "synced" over a vault the phone
 * has moved on from, and the next write would stamp that stale state as the
 * newest thing in the file. The phone refuses this case; so does this.
 */
describe("a browser that can only open the older copy", () => {
  function fileWith(passkeyAt: string, recoveryAt: string, passkeyCopy: unknown) {
    return JSON.stringify({
      v: 1,
      passkey: passkeyCopy,
      recovery: {
        schemaVersion: 1,
        algorithm: "AES-GCM-256",
        compression: "gzip",
        credentialId: "recovery",
        rpId: "keyweb",
        prfInput: "x",
        kdfSalt: "y",
        nonce: "z",
        ciphertext: "sealed-with-a-code-this-browser-has-not-got",
        updatedAt: recoveryAt,
      },
      // Written last so the passkey member keeps the timestamp under test.
      ...(passkeyAt ? {} : {}),
    });
  }

  it("refuses, rather than calling the stale copy a sync", async () => {
    const drive = new FakeDrive();
    const { sync, remote } = await makeDevice(drive, "web", 7);
    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.sync();

    // The passkey copy this browser wrote, now with a newer recovery copy
    // beside it that only the phone's code opens.
    const written = JSON.parse(drive.vaultFile()!.content);
    drive.vaultFile()!.content = fileWith(
      written.passkey.updatedAt,
      "2099-01-01T00:00:00.000Z",
      written.passkey,
    );

    await expect(remote.read()).rejects.toThrow(BackupBehindError);
  });

  it("still reads when the copy it can open is the newest", async () => {
    const drive = new FakeDrive();
    const { sync, remote } = await makeDevice(drive, "web", 7);
    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.sync();

    const written = JSON.parse(drive.vaultFile()!.content);
    drive.vaultFile()!.content = fileWith(
      written.passkey.updatedAt,
      "2000-01-01T00:00:00.000Z",
      written.passkey,
    );

    const revision = await remote.read();
    expect(revision).not.toBeNull();
    expect(revision!.state.keyrings["ring"]!.name.value).toBe("Household");
  });
});

/**
 * A backup that a phone made, opened in a browser.
 *
 * This is the shape nobody had run: Keyweb's Android app does not derive the
 * passkey key, so it writes `{ v, recovery }` with no passkey member. (Not a
 * platform limit — sync-kit-android ships `AndroidPasskeyKeyProvider`.) The wrapper was being mistaken for the bare pre-wrapper envelope,
 * which broke restoring *and* would have deleted the recovery envelope on the
 * next write — the only thing the phone itself can open.
 */
describe("a backup written by a phone", () => {
  const phoneWritten = JSON.stringify({
    v: 1,
    recovery: {
      schemaVersion: 1,
      algorithm: "AES-GCM-256",
      compression: "gzip",
      credentialId: "recovery",
      rpId: "keyweb",
      prfInput: "x",
      kdfSalt: "y",
      nonce: "z",
      ciphertext: "sealed-on-the-phone",
      updatedAt: "2026-09-17T00:00:00.000Z",
    },
  });

  async function driveHolding(content: string) {
    const drive = new FakeDrive();
    drive.files.set("file-1", {
      name: "keyweb-vault-v1.json",
      content,
      revision: 1,
      appProperties: { keyweb: "vault-v1" },
    });
    return drive;
  }

  it("is not mistaken for a browser-written one", async () => {
    const drive = await driveHolding(phoneWritten);
    const { remote } = await makeDevice(drive, "web", 1);

    // Nothing for a passkey to open — which is different from "no backup".
    expect(await remote.fetchSealedState()).toBeNull();
    // And the thing a recovery code *can* open is found.
    expect(await remote.fetchRecoverySealed()).toMatchObject({
      ciphertext: "sealed-on-the-phone",
    });
  });

  /**
   * This test used to assert that the browser publishes here, adding its own
   * passkey envelope on the way — and that was the bug, written down as a
   * requirement. Publishing over a backup you could not read is how a phone's
   * vault got replaced by an empty one.
   *
   * What must happen is that the sync stops and the file is untouched. The
   * browser gets in by being given the recovery code, not by writing to a
   * backup it cannot open.
   */
  it("does not publish over a backup it cannot read", async () => {
    const drive = await driveHolding(phoneWritten);
    const { sync } = await makeDevice(drive, "web", 1);

    await sync.putKeyring({ keyringId: "personal", name: "Just mine" });
    await sync.sync();

    // Byte for byte what the phone left there.
    expect(drive.files.get("file-1")!.content).toBe(phoneWritten);
    // And the local edit is still queued rather than silently considered done.
    expect(sync.status().pending).toBeGreaterThan(0);
  });
});

/**
 * Two vault files in one account is a silent partition, not a choice.
 *
 * Both platforms took the first match from a list Drive returns in no promised
 * order. Two devices can land on two different files and each be perfectly
 * consistent: both sync, both succeed, both report themselves backed up, and
 * they hold different vaults. No error appears anywhere, because from inside
 * either one nothing is wrong — which is exactly what "both say they are
 * synced and show different things" looks like.
 */
describe("more than one backup file", () => {
  it("refuses to pick, rather than picking silently", async () => {
    const drive = new FakeDrive();
    for (const id of ["file-1", "file-2"]) {
      drive.files.set(id, {
        name: "keyweb-vault-v1.json",
        content: "{}",
        revision: 1,
        appProperties: { keyweb: "vault-v1" },
      });
    }

    const { remote } = await makeDevice(drive, "web", 1);
    await expect(remote.read()).rejects.toThrow(TooManyBackupsError);
    // And says how many, because the next step is looking at them in Drive.
    await expect(remote.read()).rejects.toThrow(/2 Keyweb backup files/);
  });

  /** Writing must not be the thing that discovers this. */
  it("refuses to write into the ambiguity", async () => {
    const drive = new FakeDrive();
    for (const id of ["file-1", "file-2"]) {
      drive.files.set(id, {
        name: "keyweb-vault-v1.json",
        content: "{}",
        revision: 1,
        appProperties: { keyweb: "vault-v1" },
      });
    }
    const { remote } = await makeDevice(drive, "web", 1);
    await expect(remote.write({ items: {}, keyrings: {} }, null)).rejects.toThrow();
    // Neither file was touched on the way to finding out.
    expect(drive.files.get("file-1")!.content).toBe("{}");
    expect(drive.files.get("file-2")!.content).toBe("{}");
  });
});

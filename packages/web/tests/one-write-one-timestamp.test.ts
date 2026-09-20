import { describe, expect, it } from "vitest";
import { createClock, MemoryVaultStorage, VaultSync, seqIds } from "@keyweb/vault-core";
import { createRecoveryCipher, unlockVault } from "../src/vault/crypto";
import { generateRecoverySecret } from "../src/vault/recovery";
import { GoogleDriveRemote } from "../src/vault/drive";
import { FakeDrive, fakeAuthenticator } from "./helpers";
import { writeFileSync, mkdirSync } from "node:fs";
import { formatRecoveryCode } from "../src/vault/recovery";

/**
 * Two copies written in one request must carry one timestamp.
 *
 * The phone has no key to the browser's passkey envelope, so the only evidence
 * it has about which copy is newer is the `updatedAt` each one carries. It
 * refuses the recovery copy when that copy is older than the passkey copy,
 * because opening a copy that has fallen behind would make it report itself
 * synced while showing a vault the browser had moved on from.
 *
 * Sealing stamps the time of the *call*, so sealing twice in a row put a few
 * milliseconds between two copies of the identical vault. That is
 * indistinguishable, in the file, from a browser that rewrote only the passkey
 * copy and left the recovery copy behind — so after every single write from a
 * browser, the phone declared the backup unreadable and offered to replace it
 * with its own.
 */
async function browser(drive: FakeDrive, secret: Uint8Array) {
  const { cipher } = await unlockVault(null, {
    rpId: "localhost",
    navigator: fakeAuthenticator(3),
    secureContext: () => true,
  });
  const recoveryCipher = await createRecoveryCipher(secret, undefined, "localhost");
  const remote = new GoogleDriveRemote({
    clientId: "test",
    cipher,
    recoveryCipher,
    store: drive.asStore(),
    authorize: async () => ({ accessToken: "token" }) as never,
  });
  const sync = new VaultSync({
    storage: new MemoryVaultStorage(),
    remote,
    clock: createClock({ node: "web" }),
    newId: seqIds("web"),
  });
  return { remote, sync };
}

function stamps(drive: FakeDrive): { passkey: string; recovery: string } {
  const payload = JSON.parse(drive.vaultFile()!.content);
  return { passkey: payload.passkey.updatedAt, recovery: payload.recovery.updatedAt };
}

describe("a browser writing both copies at once", () => {
  it("stamps them with the same time, so neither looks stale to the phone", async () => {
    const drive = new FakeDrive();
    const { sync } = await browser(drive, generateRecoverySecret());
    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({ itemId: "bank", keyringId: "ring", fields: { title: "Bank" } });
    await sync.sync();

    const { passkey, recovery } = stamps(drive);
    expect(recovery).toBe(passkey);
  });

  it("keeps stamping them together on later writes", async () => {
    const drive = new FakeDrive();
    const { sync } = await browser(drive, generateRecoverySecret());
    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.sync();
    const first = stamps(drive);

    await sync.putItem({ itemId: "new", keyringId: "ring", fields: { title: "Added later" } });
    await sync.sync();
    const second = stamps(drive);

    expect(second.recovery).toBe(second.passkey);
    // And the write really happened, rather than the test comparing one file
    // with itself.
    expect(second.passkey).not.toBe(first.passkey);
  });
});

/**
 * The bytes a browser actually writes, for the phone's suite to read.
 *
 * The mirror of `fixtures/drive-phone-only-v1.json`, which goes the other way.
 * Written by the shipping `GoogleDriveRemote` rather than assembled by hand,
 * because the failure being pinned here was not in the format — every field
 * was correct — but in two timestamps a millisecond apart, which no hand-built
 * approximation would ever have reproduced.
 */
describe("what a browser leaves in Drive", () => {
  it("is written out for the phone's suite to read back", async () => {
    const secret = generateRecoverySecret();
    const drive = new FakeDrive();
    const { sync } = await browser(drive, secret);
    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Credit Union", password: "browser-written-password" },
    });
    await sync.sync();
    // A second write, because that is the one that carries an existing
    // envelope forward rather than creating the file.
    await sync.putItem({ itemId: "later", keyringId: "ring", fields: { title: "Added later" } });
    await sync.sync();

    const content = drive.vaultFile()!.content;
    const payload = JSON.parse(content);
    expect(payload.passkey).toBeDefined();
    expect(payload.recovery).toBeDefined();
    expect(payload.recovery.updatedAt).toBe(payload.passkey.updatedAt);

    const dir = new URL("../../../fixtures/", import.meta.url);
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      new URL("drive-browser-written-v1.json", dir),
      JSON.stringify(
        {
          note: "Written by the web's GoogleDriveRemote. Do not edit.",
          recoveryCode: formatRecoveryCode(secret),
          password: "browser-written-password",
          content,
        },
        null,
        4,
      ),
    );
  });
});

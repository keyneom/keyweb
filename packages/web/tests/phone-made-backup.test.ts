import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { itemField, RemoteUnavailableError } from "@keyweb/vault-core";
import {
  BackupBehindError,
  BackupNeedsRecoveryCodeError,
  GoogleDriveRemote,
} from "../src/vault/drive";
import { createRecoveryCipher, unlockVault } from "../src/vault/crypto";
import {
  formatRecoveryCode,
  generateRecoverySecret,
  parseRecoveryCode,
} from "../src/vault/recovery";
import { FakeDrive, fakeAuthenticator } from "./helpers";

/**
 * Can a browser open a backup that a phone made?
 *
 * Not an argument, a demonstration. `fixtures/drive-phone-only-v1.json` is
 * written by Android's real `DriveVaultRemote` — the shipping writer, not a
 * hand-built approximation — and this suite runs the browser's real restore
 * paths against those exact bytes.
 *
 * The answer is: not with a passkey, and yes with the recovery code. Which
 * makes the code the *first* visit in a browser for anybody whose vault began
 * on a phone, rather than a last resort for when something has gone wrong.
 *
 * If Android ever gains a way to write the passkey envelope, the first test
 * here fails and says so.
 */

const fixture = JSON.parse(
  readFileSync(new URL("../../../fixtures/drive-phone-only-v1.json", import.meta.url), "utf8"),
) as { recoveryCode: string; password: string; content: string };

function driveHoldingThePhonesBackup() {
  const drive = new FakeDrive();
  drive.files.set("file-1", {
    name: "keyweb-vault-v1.json",
    content: fixture.content,
    revision: 1,
    appProperties: { keyweb: "vault-v1" },
  });
  return drive;
}

async function browser(drive: FakeDrive, sealed: unknown | null = null) {
  const { cipher } = await unlockVault(sealed, {
    rpId: "localhost",
    navigator: fakeAuthenticator(1),
    secureContext: () => true,
  });
  return new GoogleDriveRemote({
    clientId: "test",
    cipher,
    store: drive.asStore(),
    authorize: async () => ({ accessToken: "token" }) as never,
  });
}

describe("a backup a phone made, opened in a browser", () => {
  /**
   * The claim, at its root. Keyweb's Android app does not write a passkey
   * envelope — it writes the recovery one and carries a browser's forward when
   * one exists — so a vault that began on a phone has never had one.
   *
   * Note what this does *not* say. The platform is perfectly capable:
   * sync-kit-android ships `AndroidPasskeyKeyProvider` and easy-bc uses it.
   * This suite pins what Keyweb does today, not what Android permits.
   */
  it("has nothing in it that a passkey could open", () => {
    const payload = JSON.parse(fixture.content) as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual(["recovery", "v"]);
    expect(payload.passkey).toBeUndefined();
  });

  /**
   * So the "I already use Keyweb — restore my passwords" path finds nothing.
   * Null rather than a throw is the fix; before it, the whole wrapper went to
   * the envelope parser and came back as "not a supported v1 encrypted
   * snapshot", which is the error that was reported.
   */
  it("gives the passkey restore path nothing to work with", async () => {
    const drive = driveHoldingThePhonesBackup();
    const remote = await browser(drive);
    expect(await remote.fetchSealedState()).toBeNull();
  });

  /** And the recovery code opens it — the real code, the real passwords. */
  it("opens with the recovery code, which is therefore the way in", async () => {
    const drive = driveHoldingThePhonesBackup();
    const remote = await browser(drive);

    const sealed = await remote.fetchRecoverySealed();
    expect(sealed).not.toBeNull();

    const secret = parseRecoveryCode(fixture.recoveryCode);
    const viaCode = await createRecoveryCipher(secret, sealed, "keyweb");
    const state = await viaCode.openState(sealed);

    const bank = Object.values(state.items)[0]!;
    expect(itemField(bank, "password")).toBe(fixture.password);
    expect(itemField(bank, "title")).toBe("Credit Union");
  });

  /**
   * After that one code entry the browser publishes, which adds its own
   * passkey envelope — so the *next* visit needs no code. That is what makes
   * this a first visit rather than a permanent condition.
   */
  it("needs the code once, because the browser then adds its own way in", async () => {
    const drive = driveHoldingThePhonesBackup();

    const { cipher } = await unlockVault(null, {
      rpId: "localhost",
      navigator: fakeAuthenticator(1),
      secureContext: () => true,
    });
    const remote = new GoogleDriveRemote({
      clientId: "test",
      cipher,
      store: drive.asStore(),
      authorize: async () => ({ accessToken: "token" }) as never,
    });

    const secret = parseRecoveryCode(fixture.recoveryCode);
    const sealed = await remote.fetchRecoverySealed();
    const cipherFromCode = await createRecoveryCipher(secret, sealed, "keyweb");
    const recovered = await cipherFromCode.openState(sealed);
    await remote.write(recovered, null);

    const after = JSON.parse(drive.files.get("file-1")!.content) as Record<string, unknown>;
    expect(after.passkey).toBeDefined();
    // And the phone's own way in is still there, untouched.
    expect(after.recovery).toEqual(JSON.parse(fixture.content).recovery);

    // A second visit by that browser now finds something a passkey can open.
    expect(await remote.fetchSealedState()).not.toBeNull();
  });

  /**
   * The opposite order, for completeness: a vault that *began* in a browser
   * has a passkey envelope from the first write, so the restore path works
   * without any code. The asymmetry is Android's missing PRF, not a rule
   * about which device goes first.
   */
  it("works without a code when the vault began in a browser", async () => {
    const drive = new FakeDrive();
    const remote = await browser(drive);
    const state = (await (await browser(drive)).read()) ?? null;
    expect(state).toBeNull();

    await remote.write(
      {
        keyrings: {
          ring: {
            id: "ring",
            name: { value: "Home", ts: "001700000000000-00000-w" },
            deleted: { value: false, ts: "000000000000000-00000-" },
            dataset: { value: "", ts: "000000000000000-00000-" },
          },
        },
        items: {},
      },
      null,
    );

    expect(await remote.fetchSealedState()).not.toBeNull();
  });
});

/**
 * The overwrite, and the two guards that now stop it.
 *
 * What happened: a browser read a phone's backup, found no envelope its
 * passkey could open, and took that for an *empty* backup. It then published
 * an empty vault — and because that browser had minted its own recovery code
 * the first time somebody set Keyweb up in it, the write resealed the recovery
 * envelope under that code too. The phone's own code stopped opening its own
 * backup, and the phone crashed trying.
 *
 * Both halves are pinned here. Neither is a nicety: each on its own would have
 * been enough to prevent it.
 */
describe("a browser must not overwrite a backup it cannot read", () => {
  it("refuses to call an unreadable backup an empty one", async () => {
    const drive = driveHoldingThePhonesBackup();
    const remote = await browser(drive);

    // Not null. Null is "there is nothing here", which the engine acts on by
    // publishing, and publishing is the thing that destroyed the backup.
    await expect(remote.read()).rejects.toThrow(BackupNeedsRecoveryCodeError);

    // And nothing was written on the way to finding that out.
    expect(drive.files.get("file-1")!.content).toBe(fixture.content);
  });

  it("never reseals the recovery copy with a code that does not open it", async () => {
    const drive = driveHoldingThePhonesBackup();
    const before = JSON.parse(fixture.content).recovery;

    // A browser that has its own recovery secret from its own first run — a
    // different code entirely from the one the phone uses.
    const { cipher } = await unlockVault(null, {
      rpId: "localhost",
      navigator: fakeAuthenticator(9),
      secureContext: () => true,
    });
    const strangersCode = await createRecoveryCipher(
      parseRecoveryCode(formatRecoveryCode(generateRecoverySecret())),
      undefined,
      "keyweb",
    );
    const remote = new GoogleDriveRemote({
      clientId: "test",
      cipher,
      recoveryCipher: strangersCode,
      store: drive.asStore(),
      authorize: async () => ({ accessToken: "token" }) as never,
    });

    await remote.write({ items: {}, keyrings: {} }, null);

    const after = JSON.parse(drive.files.get("file-1")!.content).recovery;
    // Byte for byte. The phone's way into its own backup is not this
    // browser's to retire.
    expect(after).toEqual(before);
  });
});

/**
 * A browser holding the wrong passkey is not a browser that is offline.
 *
 * It happens whenever the credential that sealed the file is not the one this
 * browser has: a second browser, a reinstall, a profile that lost its passkey.
 * The decrypt fails, and it used to be flattened into `RemoteUnavailableError`
 * — which means "offline", so the engine retried quietly forever and the
 * screen never said the one thing that would have helped. There was no way
 * out, because the way out is a recovery code nobody was asked for.
 */
describe("a browser whose passkey does not fit", () => {
  it("asks for the recovery code instead of looking offline", async () => {
    const drive = new FakeDrive();

    // One browser writes the backup...
    const first = await browser(drive);
    await first.write(
      {
        keyrings: {
          ring: {
            id: "ring",
            name: { value: "Home", ts: "001700000000000-00000-w" },
            deleted: { value: false, ts: "000000000000000-00000-" },
            dataset: { value: "", ts: "000000000000000-00000-" },
          },
        },
        items: {},
      },
      null,
    );

    // ...and a different one, with a different credential, reads it.
    const { cipher } = await unlockVault(null, {
      rpId: "localhost",
      navigator: fakeAuthenticator(42),
      secureContext: () => true,
    });
    const other = new GoogleDriveRemote({
      clientId: "test",
      cipher,
      store: drive.asStore(),
      authorize: async () => ({ accessToken: "token" }) as never,
    });

    await expect(other.read()).rejects.toThrow(BackupNeedsRecoveryCodeError);
    // And it is emphatically not the error that means "retry later, quietly".
    await expect(other.read()).rejects.not.toThrow(RemoteUnavailableError);
  });
});

/**
 * Two devices, both reporting success, against different copies.
 *
 * The phone can only reseal the recovery envelope, so the passkey copy stays
 * frozen at whatever a browser last wrote. The browser then opens its own
 * copy — which works — and says it is synced while showing a vault the phone
 * moved on from, or an empty one.
 *
 * Nothing about that looks like a failure from inside the browser: the decrypt
 * succeeds, the file is there, the sync completes. Two clients each confidently
 * reporting success against a different copy of somebody's passwords is worse
 * than either of them erroring.
 */
describe("a browser whose copy has fallen behind", () => {
  /** A real envelope sealed by the same fake authenticator `browser()` uses. */
  async function aPasskeyEnvelopeThisBrowserCanOpen(): Promise<string> {
    const staging = new FakeDrive();
    await (await browser(staging)).write({ items: {}, keyrings: {} }, null);
    // The folder is in this map too, created with empty content.
    const vault = [...staging.files.values()].find(
      (file) => file.appProperties["keyweb"] === "vault-v1",
    );
    return vault!.content;
  }

  function fileWith(passkeyAt: string, recoveryAt: string, passkeyBody: unknown) {
    const drive = new FakeDrive();
    drive.files.set("file-1", {
      name: "keyweb-vault-v1.json",
      content: JSON.stringify({
        v: 1,
        passkey: { ...(passkeyBody as object), updatedAt: passkeyAt },
        recovery: {
          schemaVersion: 1,
          algorithm: "AES-GCM-256",
          credentialId: "recovery",
          rpId: "keyweb",
          prfInput: "x",
          kdfSalt: "y",
          nonce: "z",
          ciphertext: "newer-on-the-phone",
          updatedAt: recoveryAt,
        },
      }),
      revision: 1,
      appProperties: { keyweb: "vault-v1" },
    });
    return drive;
  }

  it("says so instead of reporting itself synced", async () => {
    // A real passkey envelope this browser can open, deliberately older.
    const mine = JSON.parse(await aPasskeyEnvelopeThisBrowserCanOpen()).passkey;

    const drive = fileWith("2026-09-01T00:00:00.000Z", "2026-09-18T00:00:00.000Z", mine);
    const remote = await browser(drive);

    // Not "an empty vault, synced". The copy it can read is behind the one it
    // cannot, and that is knowable from the timestamps alone.
    await expect(remote.read()).rejects.toThrow(BackupBehindError);
  });

  /** Equal timestamps are the normal case: one device sealed both at once. */
  it("is happy when both copies were written together", async () => {
    const written = JSON.parse(await aPasskeyEnvelopeThisBrowserCanOpen());
    const at = written.passkey.updatedAt as string;

    const drive = fileWith(at, at, written.passkey);
    // Unlocking the envelope that is there, rather than minting a fresh
    // credential that could not open it.
    const remote = await browser(drive, written.passkey);
    await expect(remote.read()).resolves.not.toBeNull();
  });
});

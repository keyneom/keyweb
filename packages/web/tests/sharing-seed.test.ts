import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { GoogleDriveRemote } from "../src/vault/drive";
import { createRecoveryCipher } from "../src/vault/crypto";
import { parseRecoveryCode } from "../src/vault/recovery";
import { FakeDrive } from "./helpers";

/**
 * The seed that makes one person one participant, read across platforms.
 *
 * Sharing pins one key per person. A keyring shared from a phone has to be
 * manageable from that person's laptop, and the people they shared with
 * trusted one key — so every device of theirs has to derive the same sharing
 * identity, which means arriving at the same seed.
 *
 * Until now the only thing carrying that seed was the printed recovery code,
 * and nothing distributed it. A browser holding the passkey could read every
 * password in the vault and still not touch a shared keyring, because it had
 * never been handed a piece of paper.
 *
 * `fixtures/drive-with-sharing-seed-v1.json` is written by Android's real
 * `DriveVaultRemote`. This opens it with the browser's real reader.
 */
const fixture = JSON.parse(
  readFileSync(
    new URL("../../../fixtures/drive-with-sharing-seed-v1.json", import.meta.url),
    "utf8",
  ),
) as { recoveryCode: string; content: string };

function driveHoldingIt() {
  const drive = new FakeDrive();
  drive.files.set("file-1", {
    name: "keyweb-vault-v1.json",
    content: fixture.content,
    revision: 1,
    appProperties: { keyweb: "vault-v1" },
  });
  return drive;
}

describe("a sharing seed a phone published", () => {
  it("is not in the file in the clear", () => {
    expect(fixture.content).not.toContain(fixture.recoveryCode);
    expect(fixture.content).not.toContain(fixture.recoveryCode.replace(/-/g, ""));
  });

  it("opens in a browser holding the same code", async () => {
    const drive = driveHoldingIt();
    const secret = parseRecoveryCode(fixture.recoveryCode);
    const payload = JSON.parse(fixture.content) as { seed: { recovery: unknown } };
    const recoveryCipher = await createRecoveryCipher(secret, payload.seed.recovery, "localhost");

    const remote = new GoogleDriveRemote({
      clientId: "test",
      cipher: recoveryCipher,
      recoveryCipher,
      store: drive.asStore(),
      authorize: async () => ({ accessToken: "token" }) as never,
    });

    const seed = await remote.sharingSeed();
    expect(seed).not.toBeNull();
    // The same bytes the phone derives its identity from — which is what makes
    // the two devices one participant rather than two.
    expect([...seed!]).toEqual([...secret]);
  });

  it("is absent, not invented, when the file has none", async () => {
    const drive = new FakeDrive();
    drive.files.set("file-1", {
      name: "keyweb-vault-v1.json",
      content: JSON.stringify({ v: 1, recovery: JSON.parse(fixture.content).recovery }),
      revision: 1,
      appProperties: { keyweb: "vault-v1" },
    });
    const secret = parseRecoveryCode(fixture.recoveryCode);
    const recoveryCipher = await createRecoveryCipher(
      secret,
      JSON.parse(fixture.content).recovery,
      "localhost",
    );
    const remote = new GoogleDriveRemote({
      clientId: "test",
      cipher: recoveryCipher,
      recoveryCipher,
      store: drive.asStore(),
      authorize: async () => ({ accessToken: "token" }) as never,
    });

    expect(await remote.sharingSeed()).toBeNull();
  });
});

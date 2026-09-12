import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { fingerprint, itemField, type VaultState } from "@keyweb/vault-core";
import { createRecoveryCipher } from "../src/vault/crypto";
import { parseRecoveryCode } from "../src/vault/recovery";

/**
 * The other half of the interop proof.
 *
 * `EnvelopeInteropTest` on the Kotlin side opens a backup this implementation
 * sealed. This opens one Kotlin sealed, with the recovery code printed beside
 * it. Both directions have to hold: a vault sealed on a phone and restored in a
 * browser is exactly as likely as the reverse, and a one-way check would pass
 * while half the promise was broken.
 *
 * Regenerate the fixture with:
 *   (cd packages/android && ./gradlew :vault:test)
 */
type EnvelopeFixture = {
  recoveryCode: string;
  fingerprint: string;
  envelope: unknown;
};

function androidFixture(): EnvelopeFixture {
  const path = new URL("../../../fixtures/envelope-android-v1.json", import.meta.url);
  try {
    return JSON.parse(readFileSync(path, "utf8")) as EnvelopeFixture;
  } catch {
    throw new Error(
      `Missing ${path.pathname}. Regenerate with: (cd packages/android && ./gradlew :vault:test)`,
    );
  }
}

describe("a backup sealed by the Android implementation", () => {
  it("opens with the printed recovery code", async () => {
    const fixture = androidFixture();

    const secret = parseRecoveryCode(fixture.recoveryCode);
    const cipher = await createRecoveryCipher(secret, fixture.envelope, "keyweb");
    const state = (await cipher.openState(fixture.envelope)) as VaultState;

    expect(itemField(state.items.chase!, "title")).toBe("Chase Bank");
    expect(itemField(state.items.chase!, "password")).toBe("sealed-on-android");
    expect(itemField(state.items.chase!, "folder")).toBe("Banking / Personal");
    expect(state.keyrings.ring!.name.value).toBe("Household");
  });

  it("agrees with Android on the fingerprint", async () => {
    // Not cosmetic: the fingerprint is what decides whether a sync republishes.
    // If the platforms disagreed, each would see the other's published revision
    // as unpublished work and overwrite it, forever.
    const fixture = androidFixture();
    const cipher = await createRecoveryCipher(
      parseRecoveryCode(fixture.recoveryCode),
      fixture.envelope,
      "keyweb",
    );
    const state = (await cipher.openState(fixture.envelope)) as VaultState;
    expect(fingerprint(state)).toBe(fixture.fingerprint);
  });

  it("refuses to open under a different code", async () => {
    const fixture = androidFixture();
    const wrong = parseRecoveryCode("7A8G-RZF2-8PW1-DW19-BJHQ-3N38-1EF3-FGJG");
    const cipher = await createRecoveryCipher(wrong, fixture.envelope, "keyweb");
    await expect(cipher.openState(fixture.envelope)).rejects.toThrow();
  });
});

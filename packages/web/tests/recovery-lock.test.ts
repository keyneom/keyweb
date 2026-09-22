import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "../src/vault/sharing/identity";

/**
 * The printed code, opening in a browser the lock a phone wrote.
 *
 * Every keyring is a file whose key is wrapped to its participants, and you
 * are one of them on every file of yours. So what a code has to give back is
 * *you* — the same keypair the passkey record holds — and a browser with no
 * passkey at all can then open everything again.
 *
 * `fixtures/sharing-recovery-lock-v1.json` is written by Android's real
 * `KeywebSharingIdentity`. The browser cannot write such a lock (sync-kit does
 * not expose re-wrapping on the web), so this direction is the only one, and
 * it is the one a restore in a browser rests on.
 */
const fixture = JSON.parse(
  readFileSync(
    new URL("../../../fixtures/sharing-recovery-lock-v1.json", import.meta.url),
    "utf8",
  ),
) as { secretBase64: string; keyId: string; record: ProtectedSharingIdentityV1 };

function appData(): ProtectedSharingIdentityStore {
  return {
    load: async (appId) => (appId === KEYWEB_RECOVERY_APP_ID ? fixture.record : null),
    save: async () => undefined,
    delete: async () => undefined,
  };
}

const secret = Uint8Array.from(Buffer.from(fixture.secretBase64, "base64"));

describe("a recovery lock a phone wrote, opened in a browser", () => {
  it("gives back the same person", async () => {
    const identity = await unlockRecoveryIdentity(appData(), secret);
    expect(identity?.publicKey.keyId).toBe(fixture.keyId);
  });

  it("opens nothing with the wrong code", async () => {
    await expect(unlockRecoveryIdentity(appData(), new Uint8Array(20).fill(9))).rejects.toThrow();
  });

  it("says so, rather than inventing one, when there is no lock", async () => {
    const empty: ProtectedSharingIdentityStore = {
      load: async () => null,
      save: async () => undefined,
      delete: async () => undefined,
    };
    expect(await unlockRecoveryIdentity(empty, secret)).toBeNull();
  });
});

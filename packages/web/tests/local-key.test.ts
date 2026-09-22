import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "../src/vault/sharing/identity";
import { openLocalKeyWithCode, sealLocalKey } from "../src/vault/localKey";

/**
 * The browser's own copy, opened with the printed code when the passkey can't.
 *
 * The local copy is locked by a random key of its own; that key is kept sealed
 * by the passkey for every day, and sealed to your identity for the day the
 * passkey is unavailable. The recovery lock is cached beside it, so the code
 * reaches the key with no network. Uses the lock the phone really writes.
 */
const fixture = JSON.parse(
  readFileSync(new URL("../../../fixtures/sharing-recovery-lock-v1.json", import.meta.url), "utf8"),
) as { secretBase64: string; keyId: string; record: ProtectedSharingIdentityV1 };
const code = Uint8Array.from(Buffer.from(fixture.secretBase64, "base64"));

const appData: ProtectedSharingIdentityStore = {
  load: async (appId) => (appId === KEYWEB_RECOVERY_APP_ID ? fixture.record : null),
  save: async () => undefined,
  delete: async () => undefined,
};

async function sealed(localKey: Uint8Array) {
  const identity = (await unlockRecoveryIdentity(appData, code))!;
  // Stored in IndexedDB as a plain value, so round-trip it the same way.
  return JSON.parse(JSON.stringify(await sealLocalKey(localKey, identity)));
}

describe("the browser's local key, opened with the printed code", () => {
  it("gives back the very same key", async () => {
    const localKey = crypto.getRandomValues(new Uint8Array(32));
    const opened = await openLocalKeyWithCode(await sealed(localKey), fixture.record, code);
    expect([...opened.localKey]).toEqual([...localKey]);
  });

  it("gives back you as well, for the session it starts", async () => {
    const opened = await openLocalKeyWithCode(
      await sealed(new Uint8Array(32).fill(4)),
      fixture.record,
      code,
    );
    expect(opened.identity.publicKey.keyId).toBe(fixture.keyId);
  });

  it("opens nothing with the wrong code", async () => {
    await expect(
      openLocalKeyWithCode(await sealed(new Uint8Array(32)), fixture.record, new Uint8Array(20)),
    ).rejects.toThrow();
  });

  it("keeps the key out of what is stored", async () => {
    const localKey = new Uint8Array(32).fill(173);
    const text = JSON.stringify(await sealed(localKey));
    expect(text).not.toContain([...localKey].join(","));
  });
});

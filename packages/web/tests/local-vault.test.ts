import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { readFileSync } from "node:fs";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { applyOps, emptyVault, encodeHlc, type VaultState } from "@keyweb/vault-core";
import { IndexedDbVaultStorage, peekMeta, type VaultCipher } from "@keyweb/vault-idb";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { createLocalCipher, type UnlockedVault } from "../src/vault/crypto";
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "../src/vault/sharing/identity";
import {
  codeCanOpen,
  createWithCode,
  opensOnlyWithCode,
  OpensWithCode,
  openWithCode,
  openWithPasskey,
  protectLocalKey,
} from "../src/vault/localVault";
import { LOCAL_KEY_PASSKEY, RECOVERY_LOCK } from "../src/vault/localKey";

/**
 * This browser's copy, opened by the passkey on an ordinary day and by the
 * printed code alone on the day the passkey is unavailable.
 *
 * The passkey is a stand-in cipher — tests have no authenticator — but every
 * other piece is real: the IndexedDB store, the re-keying, and the recovery
 * lock the phone actually writes.
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

let passkey: VaultCipher;
let otherPasskey: VaultCipher;
let factory: IDBFactory;
let name: string;
let counter = 0;
let prompts = 0;

beforeAll(async () => {
  (globalThis as { location?: unknown }).location ??= { hostname: "localhost" };
  passkey = await createLocalCipher(new Uint8Array(32).fill(1), new Uint8Array(32).fill(2));
  otherPasskey = await createLocalCipher(new Uint8Array(32).fill(3), new Uint8Array(32).fill(2));
});

beforeEach(() => {
  factory = new IDBFactory();
  name = `local-vault-${++counter}`;
  prompts = 0;
});

const unlock = (cipher = passkey) => async (): Promise<UnlockedVault> => {
  prompts += 1;
  return { cipher, lock: () => undefined };
};

const at = (n: number) => encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "t" });
const withKeyring = (state: VaultState, keyringId: string, name: string, n: number) =>
  applyOps(state, [{ kind: "keyring.put", opId: keyringId, ts: at(n), keyringId, name }]);
const somePasswords = () => withKeyring(emptyVault(), "k1", "Home", 0);
const names = (state: VaultState) => Object.values(state.keyrings).map((k) => k.name.value);

/** A browser from before this change: the passkey locks the copy directly. */
async function oldBrowser() {
  const storage = await IndexedDbVaultStorage.open({ factory, name, cipher: passkey });
  await storage.applyRemote(somePasswords());
  await storage.writeMeta("recovery-secret", await passkey.sealOp([7, 7, 7] as never));
  storage.close();
}

describe("opening this browser's copy", () => {
  it("moves an old copy onto a key of its own, keeping everything in it", async () => {
    await oldBrowser();
    const opened = await openWithPasskey({ factory, name, unlock: unlock() });
    expect(names(await opened.storage.readState())).toEqual(["Home"]);
    // The copy is no longer readable with the passkey key alone.
    const raw = await IndexedDbVaultStorage.open({ factory, name, cipher: passkey });
    await expect(raw.readState()).rejects.toThrow();
    // And the sealed code came across too, so the old file still opens during the move.
    expect(await opened.storage.readMeta("recovery-secret")).toBeTruthy();
    expect(
      [...((await (await createLocalCipher(opened.localKey, (await peekMeta("local-key:salt", factory, name)) as never)).openOp(
        await opened.storage.readMeta("recovery-secret"),
      )) as unknown as number[])],
    ).toEqual([7, 7, 7]);
  });

  it("opens with the passkey again afterwards, asking once", async () => {
    await oldBrowser();
    (await openWithPasskey({ factory, name, unlock: unlock() })).storage.close();
    prompts = 0;
    const again = await openWithPasskey({ factory, name, unlock: unlock() });
    expect(prompts).toBe(1);
    expect(names(await again.storage.readState())).toHaveLength(1);
  });

  it("does not open for a different passkey", async () => {
    await oldBrowser();
    (await openWithPasskey({ factory, name, unlock: unlock() })).storage.close();
    await expect(openWithPasskey({ factory, name, unlock: unlock(otherPasskey) })).rejects.toThrow();
  });

  it("sets up a brand-new browser the same way", async () => {
    const opened = await openWithPasskey({ factory, name, unlock: unlock() });
    await opened.storage.applyRemote(somePasswords());
    opened.storage.close();
    expect(await peekMeta(LOCAL_KEY_PASSKEY, factory, name)).toBeTruthy();
    const again = await openWithPasskey({ factory, name, unlock: unlock() });
    expect(names(await again.storage.readState())).toHaveLength(1);
  });
});

describe("opening this browser's copy with the printed code", () => {
  async function protectedBrowser() {
    await oldBrowser();
    const opened = await openWithPasskey({ factory, name, unlock: unlock() });
    const identity = (await unlockRecoveryIdentity(appData, code))!;
    expect(await protectLocalKey(opened.storage, opened.localKey, identity, fixture.record)).toBe(true);
    opened.storage.close();
    return identity;
  }

  it("has nothing to open before the key is sealed to you", async () => {
    await oldBrowser();
    (await openWithPasskey({ factory, name, unlock: unlock() })).storage.close();
    expect(await codeCanOpen({ factory, name })).toBe(false);
    expect(await openWithCode(code, { factory, name })).toBeNull();
  });

  it("opens the copy with no passkey and no network", async () => {
    await protectedBrowser();
    expect(await codeCanOpen({ factory, name })).toBe(true);
    prompts = 0;
    const opened = (await openWithCode(code, { factory, name }))!;
    expect(prompts).toBe(0);
    expect(names(await opened.storage.readState())).toEqual(["Home"]);
    expect(opened.identity?.publicKey.keyId).toBe(fixture.keyId);
  });

  it("keeps working with the passkey after the code was used", async () => {
    await protectedBrowser();
    const viaCode = (await openWithCode(code, { factory, name }))!;
    await viaCode.storage.applyRemote(
      withKeyring(await viaCode.storage.readState(), "k2", "Work", 1),
    );
    viaCode.storage.close();
    const viaPasskey = await openWithPasskey({ factory, name, unlock: unlock() });
    expect(names(await viaPasskey.storage.readState())).toHaveLength(2);
  });

  it("opens nothing with the wrong code", async () => {
    await protectedBrowser();
    await expect(openWithCode(new Uint8Array(20), { factory, name })).rejects.toThrow();
  });

  it("does nothing the second time", async () => {
    const identity = await protectedBrowser();
    const opened = await openWithPasskey({ factory, name, unlock: unlock() });
    expect(await protectLocalKey(opened.storage, opened.localKey, identity, fixture.record)).toBe(false);
  });

  it("keeps no lock that belongs to someone else", async () => {
    await oldBrowser();
    const opened = await openWithPasskey({ factory, name, unlock: unlock() });
    const identity = (await unlockRecoveryIdentity(appData, code))!;
    const foreign = { ...fixture.record, publicKey: { ...fixture.record.publicKey, keyId: "someone-else" } };
    await protectLocalKey(opened.storage, opened.localKey, identity, foreign);
    expect(await opened.storage.readMeta(RECOVERY_LOCK)).toBeUndefined();
  });

  it("never stores the local key where it can be read", async () => {
    await protectedBrowser();
    const opened = (await openWithCode(code, { factory, name }))!;
    const stored = JSON.stringify([
      await peekMeta(LOCAL_KEY_PASSKEY, factory, name),
      await peekMeta("local-key:identity", factory, name),
    ]);
    expect(stored).not.toContain([...opened.localKey].join(","));
  });
});

describe("a browser set up from a backup file with no passkey to be had", () => {
  it("opens with the code alone from then on", async () => {
    const identity = (await unlockRecoveryIdentity(appData, code))!;
    const made = await createWithCode(identity, fixture.record, { factory, name });
    await made.storage.applyRemote(somePasswords());
    made.storage.close();

    expect(await opensOnlyWithCode({ factory, name })).toBe(true);
    const opened = (await openWithCode(code, { factory, name }))!;
    expect(names(await opened.storage.readState())).toEqual(["Home"]);
  });

  it("does not ask for a passkey it was never given", async () => {
    const identity = (await unlockRecoveryIdentity(appData, code))!;
    (await createWithCode(identity, fixture.record, { factory, name })).storage.close();
    await expect(openWithPasskey({ factory, name, unlock: unlock() })).rejects.toBeInstanceOf(
      OpensWithCode,
    );
    expect(prompts).toBe(0);
  });

  it("never replaces a copy that is already here", async () => {
    await oldBrowser();
    const identity = (await unlockRecoveryIdentity(appData, code))!;
    await expect(createWithCode(identity, fixture.record, { factory, name })).rejects.toThrow();
  });

  it("is not code-only once a passkey locks it", async () => {
    await oldBrowser();
    (await openWithPasskey({ factory, name, unlock: unlock() })).storage.close();
    expect(await opensOnlyWithCode({ factory, name })).toBe(false);
  });
});

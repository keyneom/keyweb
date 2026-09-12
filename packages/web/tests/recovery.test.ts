import { describe, expect, it } from "vitest";
import { createClock, MemoryVaultStorage, VaultSync, itemField, seqIds } from "@keyweb/vault-core";
import { createRecoveryCipher, unlockVault } from "../src/vault/crypto";
import {
  formatRecoveryCode,
  generateRecoverySecret,
  InvalidRecoveryCode,
  parseRecoveryCode,
} from "../src/vault/recovery";
import { GoogleDriveRemote } from "../src/vault/drive";
import { FakeDrive, fakeAuthenticator } from "./helpers";

describe("recovery code", () => {
  it("round-trips through the printed form", () => {
    for (let i = 0; i < 200; i += 1) {
      const secret = generateRecoverySecret();
      expect(parseRecoveryCode(formatRecoveryCode(secret))).toEqual(secret);
    }
  });

  it("prints as six groups of four, with no ambiguous characters", () => {
    const code = formatRecoveryCode(generateRecoverySecret());
    expect(code.split("-")).toHaveLength(6);
    for (const group of code.split("-")) expect(group).toHaveLength(4);
    // I, L, O and U are absent, so 1/l and 0/O cannot be confused.
    expect(code).not.toMatch(/[ILOU]/);
  });

  it("forgives the mistakes people actually make when reading paper", () => {
    const secret = generateRecoverySecret();
    const code = formatRecoveryCode(secret);
    // Lower case, stray spaces, missing dashes, and I/L/O typed for 1/1/0.
    const mangled = code
      .toLowerCase()
      .replace(/-/g, " ")
      .replace(/1/g, "l")
      .replace(/0/g, "o");
    expect(parseRecoveryCode(mangled)).toEqual(secret);
  });

  it("rejects a code that is wrong rather than silently deriving a useless key", () => {
    expect(() => parseRecoveryCode("not a code")).toThrow(InvalidRecoveryCode);
    expect(() => parseRecoveryCode("")).toThrow(InvalidRecoveryCode);
    expect(() => parseRecoveryCode("ABCD-EFGH")).toThrow(InvalidRecoveryCode);
  });
});

describe("recovering a backup without the passkey", () => {
  async function seededDrive() {
    const drive = new FakeDrive();
    const secret = generateRecoverySecret();

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
      clock: createClock({ node: "A" }),
      newId: seqIds("A"),
    });

    await sync.putKeyring({ keyringId: "ring", name: "Household" });
    await sync.putItem({
      itemId: "bank",
      keyringId: "ring",
      fields: { title: "Credit Union", password: "the-one-that-matters" },
    });
    await sync.sync();
    return { drive, secret, remote };
  }

  it("opens the vault with the code when the passkey is gone for good", async () => {
    const { drive, secret, remote } = await seededDrive();

    // The passkey is unreachable: new laptop, lost Google account, whatever.
    // Only the printed code and the Drive file remain.
    const sealed = await remote.fetchRecoverySealed();
    expect(sealed).not.toBeNull();

    const typedBackIn = parseRecoveryCode(formatRecoveryCode(secret));
    const cipher = await createRecoveryCipher(typedBackIn, sealed, "localhost");
    const restored = await cipher.openState(sealed);

    expect(itemField(restored.items.bank!, "password")).toBe("the-one-that-matters");
    expect(restored.keyrings.ring!.name.value).toBe("Household");
    expect(drive.vaultFile()!.content).not.toContain("the-one-that-matters");
  });

  it("keeps the recovery copy current, never stale", async () => {
    const { drive, secret, remote } = await seededDrive();

    // A later edit must reach both copies in the same write, or a recovery
    // would silently restore an older vault.
    const { cipher } = await unlockVault(await remote.fetchSealedState(), {
      rpId: "localhost",
      navigator: fakeAuthenticator(3),
      secureContext: () => true,
    });
    const recoveryCipher = await createRecoveryCipher(
      secret,
      await remote.fetchRecoverySealed(),
      "localhost",
    );
    const second = new GoogleDriveRemote({
      clientId: "test",
      cipher,
      recoveryCipher,
      store: drive.asStore(),
      authorize: async () => ({ accessToken: "token" }) as never,
    });
    const sync = new VaultSync({
      storage: new MemoryVaultStorage(),
      remote: second,
      clock: createClock({ node: "B" }),
      newId: seqIds("B"),
    });
    await sync.sync();
    await sync.putItem({ itemId: "later", keyringId: "ring", fields: { password: "added-after" } });
    await sync.sync();

    const sealed = await second.fetchRecoverySealed();
    const viaCode = await createRecoveryCipher(secret, sealed, "localhost");
    const restored = await viaCode.openState(sealed);
    expect(itemField(restored.items.later!, "password")).toBe("added-after");
    expect(itemField(restored.items.bank!, "password")).toBe("the-one-that-matters");
  });

  it("refuses a wrong code rather than returning an empty vault", async () => {
    const { remote } = await seededDrive();
    const sealed = await remote.fetchRecoverySealed();
    const wrong = await createRecoveryCipher(generateRecoverySecret(), sealed, "localhost");
    await expect(wrong.openState(sealed)).rejects.toThrow();
  });
});

import { describe, expect, it } from "vitest";
import { emptyVault, type VaultOp, type VaultState } from "@keyweb/vault-core";
import { unlockVault } from "../src/vault/crypto";

/**
 * Proves the at-rest encryption path end to end without a real authenticator.
 *
 * WebAuthn's PRF extension is what produces the secret the vault key is derived
 * from, so a fake navigator that returns a deterministic PRF result stands in
 * for the passkey. Everything below that — HKDF, AES-GCM, the envelope format —
 * is the production code path, unmodified.
 */
function fakeAuthenticator(prfSeed: number) {
  const secret = new Uint8Array(32).fill(prfSeed);
  const rawId = new Uint8Array(16).fill(prfSeed + 100);

  const credential = {
    rawId: rawId.buffer.slice(0),
    response: {},
    getClientExtensionResults: () => ({
      prf: { results: { first: secret.buffer.slice(0) } },
    }),
  };

  return {
    credentials: {
      create: async () => credential,
      get: async () => credential,
    },
  } as unknown as Navigator;
}

const RP = "localhost";

function sampleState(): VaultState {
  const ts = "001700000000000-00001-test";
  return {
    keyrings: {
      ring: {
        id: "ring",
        name: { value: "Household", ts },
        deleted: { value: false, ts },
        dataset: { value: "", ts: "000000000000000-00000-" },
      },
    },
    items: {
      bank: {
        id: "bank",
        keyring: { value: "ring", ts },
        deleted: { value: false, ts },
        fields: {
          title: { value: "Credit Union", ts },
          password: { value: "correct-horse-battery", ts },
        },
        history: [],
      },
    },
  };
}

const sampleOp: VaultOp = {
  kind: "item.put",
  opId: "op-1",
  ts: "001700000000000-00002-test",
  itemId: "bank",
  keyringId: "ring",
  fields: { password: "correct-horse-battery" },
};

describe("vault encryption at rest", () => {
  it("seals the vault so no plaintext appears in the stored form", async () => {
    const { cipher } = await unlockVault(null, {
      rpId: RP,
      navigator: fakeAuthenticator(1),
      secureContext: () => true,
    });

    const sealed = await cipher.sealState(sampleState());
    const serialised = JSON.stringify(sealed);
    expect(serialised).not.toContain("correct-horse-battery");
    expect(serialised).not.toContain("Credit Union");
    expect(serialised).not.toContain("Household");

    const sealedOp = await cipher.sealOp(sampleOp);
    expect(JSON.stringify(sealedOp)).not.toContain("correct-horse-battery");
  });

  it("reads its own vault back unchanged", async () => {
    const { cipher } = await unlockVault(null, {
      rpId: RP,
      navigator: fakeAuthenticator(2),
      secureContext: () => true,
    });

    const original = sampleState();
    const reopened = await cipher.openState(await cipher.sealState(original));
    expect(reopened).toEqual(original);

    const op = await cipher.openOp(await cipher.sealOp(sampleOp));
    expect(op).toEqual(sampleOp);
  });

  it("unlocks an existing vault from its stored envelope, as a reload would", async () => {
    const first = await unlockVault(null, {
      rpId: RP,
      navigator: fakeAuthenticator(3),
      secureContext: () => true,
    });
    const sealed = await first.cipher.sealState(sampleState());
    first.lock();

    // A later visit: the envelope names the credential, the same passkey
    // answers, and the vault opens.
    const second = await unlockVault(sealed, {
      rpId: RP,
      navigator: fakeAuthenticator(3),
      secureContext: () => true,
    });
    expect(await second.cipher.openState(sealed)).toEqual(sampleState());
  });

  it("cannot open a vault with a different passkey", async () => {
    const mine = await unlockVault(null, {
      rpId: RP,
      navigator: fakeAuthenticator(4),
      secureContext: () => true,
    });
    const sealed = await mine.cipher.sealState(sampleState());

    // A different authenticator derives a different key from a different PRF
    // secret. The bytes on disk must be useless to it.
    const theirs = await unlockVault(sealed, {
      rpId: RP,
      navigator: fakeAuthenticator(5),
      secureContext: () => true,
    });
    await expect(theirs.cipher.openState(sealed)).rejects.toThrow();
  });

  it("refuses a vault created for a different relying party", async () => {
    const elsewhere = await unlockVault(null, {
      rpId: "keyneom.github.io",
      navigator: fakeAuthenticator(6),
      secureContext: () => true,
    });
    const sealed = await elsewhere.cipher.sealState(emptyVault());

    // A vault bound to another origin must not be silently opened here.
    await expect(
      unlockVault(sealed, {
        rpId: RP,
        navigator: fakeAuthenticator(6),
        secureContext: () => true,
      }),
    ).rejects.toThrow();
  });
});

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { applyOps, datasetOf, emptyVault, encodeHlc, itemField } from "@keyweb/vault-core";
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "../src/vault/sharing/identity";
import {
  openBackupFile,
  openBackupFileAsYou,
  restoreInto,
  sealBackupFile,
  WrongBackupCode,
} from "../src/vault/backupFile";

/**
 * An encrypted backup you keep yourself, opened with only the printed code.
 *
 * The case this exists for is Google being unreachable — an outage, a locked
 * account — with every device gone. The keyring files and the recovery lock in
 * Drive are no use then, so the file carries both halves: the vault sealed to
 * your identity, and the lock the code opens. These tests use the lock the
 * phone really writes (`sharing-recovery-lock-v1.json`), so they exercise the
 * cross-platform path rather than one the browser made for itself.
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

function at(n: number) {
  return encodeHlc({ wall: 1_700_000_000_000 + n, counter: 0, node: "phone" });
}

/** A vault as it really is now: a keyring bound to its own Drive file. */
function vault() {
  return applyOps(emptyVault(), [
    { kind: "keyring.put", opId: "k", ts: at(0), keyringId: "personal", name: "Just mine" },
    { kind: "keyring.bind", opId: "b", ts: at(1), keyringId: "personal", datasetId: "keyweb-abc" },
    {
      kind: "item.put",
      opId: "i",
      ts: at(2),
      itemId: "bank",
      keyringId: "personal",
      fields: { title: "Credit Union", password: "the-one-that-matters" },
    },
  ]);
}

async function saved(): Promise<string> {
  const identity = (await unlockRecoveryIdentity(appData, code))!;
  const file = await sealBackupFile(vault(), identity, fixture.record);
  // As it would sit on a USB stick: text, not an object in memory.
  return JSON.stringify(file);
}

describe("an encrypted backup file", () => {
  it("opens with the printed code alone, and gives back the passwords", async () => {
    const restored = await openBackupFile(await saved(), code);
    expect(itemField(restored.items["bank"]!, "password")).toBe("the-one-that-matters");
    expect(restored.keyrings["personal"]!.name.value).toBe("Just mine");
  });

  it("hands back keyrings unbound, so they open with no Drive to reach", async () => {
    const restored = await openBackupFile(await saved(), code);
    expect(datasetOf(restored.keyrings["personal"])).toBeNull();
  });

  it("opens nothing with the wrong code", async () => {
    await expect(openBackupFile(await saved(), new Uint8Array(20).fill(9))).rejects.toThrow();
  });

  it("has nothing readable in it", async () => {
    const text = await saved();
    expect(text).not.toContain("the-one-that-matters");
    expect(text).not.toContain("Credit Union");
    expect(text).not.toContain("Just mine");
  });

  it("refuses a file that is not a Keyweb backup", async () => {
    await expect(openBackupFile('{"format":"something-else"}', code)).rejects.toThrow(
      "That isn't a Keyweb backup file.",
    );
  });

  it("refuses a lock that belongs to someone else", async () => {
    const identity = (await unlockRecoveryIdentity(appData, code))!;
    const stranger = {
      ...fixture.record,
      publicKey: { ...fixture.record.publicKey, keyId: "someone-else" },
    };
    await expect(sealBackupFile(vault(), identity, stranger)).rejects.toThrow(
      "different identity",
    );
  });
});

describe("restoring a backup file into this browser", () => {
  it("says plainly when the code is wrong", async () => {
    await expect(openBackupFileAsYou(await saved(), new Uint8Array(20))).rejects.toBeInstanceOf(
      WrongBackupCode,
    );
  });

  it("gives back you, and the lock the code opens", async () => {
    const opened = await openBackupFileAsYou(await saved(), code);
    expect(opened.identity.publicKey.keyId).toBe(fixture.keyId);
    expect(opened.recoveryLock.publicKey.keyId).toBe(fixture.keyId);
  });

  /** Two documents, the way a browser keeps a keyring that has its own file. */
  function memory(vaultDoc: ReturnType<typeof vault>) {
    const docs = new Map<string, ReturnType<typeof vault>>([["", vaultDoc]]);
    return {
      docs,
      readState: async (id = "") => docs.get(id) ?? emptyVault(),
      applyRemote: async (incoming: ReturnType<typeof vault>, id = "") => {
        docs.set(id, incoming);
        return incoming;
      },
    };
  }

  it("puts a keyring's passwords in its own file's document", async () => {
    const restored = await openBackupFile(await saved(), code);
    const store = memory(vault());
    await restoreInto(store, restored);
    expect(itemField(store.docs.get("keyweb-abc")!.items["bank"]!, "password")).toBe(
      "the-one-that-matters",
    );
    expect(store.docs.get("")!.items["bank"]).toBeUndefined();
  });

  it("puts everything in the vault on a browser with nothing yet", async () => {
    const restored = await openBackupFile(await saved(), code);
    const store = memory(emptyVault());
    await restoreInto(store, restored);
    expect(store.docs.get("")!.items["bank"]).toBeDefined();
  });
});

/**
 * The other direction: a file the phone saved, opened in a browser.
 *
 * Written by Android's `BackupFileTest`. The format is shared so that the one
 * copy somebody kept away from Google opens wherever they happen to be.
 */
describe("a backup file the phone saved", () => {
  const phone = JSON.parse(
    readFileSync(new URL("../../../fixtures/backup-file-android-v1.json", import.meta.url), "utf8"),
  ) as { secretBase64: string; keyId: string; file: string };

  it("opens here with the same code", async () => {
    const opened = await openBackupFileAsYou(
      phone.file,
      Uint8Array.from(Buffer.from(phone.secretBase64, "base64")),
    );
    expect(itemField(opened.state.items["bank"]!, "title")).toBe("Credit Union");
    expect(opened.state.keyrings["personal"]!.name.value).toBe("Just mine");
    expect(opened.identity.publicKey.keyId).toBe(phone.keyId);
  });
});

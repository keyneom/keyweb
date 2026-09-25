import { writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { applyOps, emptyVault, encodeHlc } from "@keyweb/vault-core";
import { isSharingRecoveryCodeWellFormed } from "@keyneom/sync-kit/sharing/participant-keys";
import {
  createWebCryptoSharingIdentity,
  type WebCryptoSharingIdentity,
} from "@keyneom/sync-kit/sharing/web-crypto";
import {
  participantRecoveryCode,
  protectWithRecoveryCode,
  recoverWithParticipantKey,
} from "../src/vault/sharing/recoveryKeys";
import { MemoryMeta, MemorySharingTransport, memoryController } from "./memorySharing";

/**
 * The printed code as a second key of yours on every keyring.
 *
 * Driven through sync-kit's real controller over an in-memory Drive, because
 * what matters is what the files end up saying: whose keys they hold, which
 * keyrings let members hold keys at all, and that the code alone — with the
 * passkey gone — makes you a participant again.
 */

const INDEX = "keyweb-index";
const secret = (fill: number) => new Uint8Array(20).fill(fill);

async function account() {
  const transport = new MemorySharingTransport();
  const me = await createWebCryptoSharingIdentity();
  const controller = memoryController(me, transport);
  await controller.createDataset(INDEX, emptyVault());
  await controller.createDataset("keyweb-ring", emptyVault());
  return { transport, me, controller };
}

async function member(
  transport: MemorySharingTransport,
  ownerController: ReturnType<typeof memoryController>,
  datasetId: string,
  role: "viewer" | "writer",
): Promise<{ identity: WebCryptoSharingIdentity; controller: ReturnType<typeof memoryController> }> {
  const identity = await createWebCryptoSharingIdentity();
  await ownerController.addDatasetParticipant({
    datasetId,
    participant: { publicKey: identity.publicKey, role },
    emailAddress: `${role}@example.com`,
  });
  const controller = memoryController(identity, transport);
  await controller.adoptDataset(datasetId);
  return { identity, controller };
}

describe("the code the printed code stands for", () => {
  it("is always the same for one printed code, and a real sync-kit code", async () => {
    const first = await participantRecoveryCode(secret(7));
    expect(await participantRecoveryCode(secret(7))).toBe(first);
    expect(await participantRecoveryCode(secret(8))).not.toBe(first);
    expect(await isSharingRecoveryCodeWellFormed(first)).toBe(true);
  });

  it("matches what the phone derives", async () => {
    // Pinned so the Kotlin port is checked against the same value: a key the
    // phone attaches must open with the code a browser derives, and back.
    const bytes = Uint8Array.from({ length: 20 }, (_, index) => index + 1);
    const code = await participantRecoveryCode(bytes);
    writeFileSync(
      new URL("../../../fixtures/participant-recovery-code-v1.json", import.meta.url),
      `${JSON.stringify(
        {
          note: "The sync-kit recovery code a Keyweb recovery secret stands for. Both platforms derive this.",
          secretBase64: Buffer.from(bytes).toString("base64"),
          code,
        },
        null,
        2,
      )}\n`,
    );
    expect(code).toMatch(/^[0-9A-Z]{4}(-[0-9A-Z]{4}){6}$/);
  });
});

describe("putting your recovery key on your keyrings", () => {
  it("changes nothing until somebody turns it on", async () => {
    const { me, controller, transport } = await account();
    const before = JSON.stringify([...transport.datasets.values()].map((d) => d.envelope));
    const coverage = await protectWithRecoveryCode({
      controller,
      identity: me,
      store: new MemoryMeta(),
      datasetIds: [INDEX, "keyweb-ring"],
      secret: secret(1),
    });
    expect(coverage).toEqual([]);
    expect(JSON.stringify([...transport.datasets.values()].map((d) => d.envelope))).toBe(before);
  });

  it("carries on by itself on another device once it is on", async () => {
    const { me, controller } = await account();
    const datasetIds = [INDEX, "keyweb-ring"];
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds: [INDEX], secret: secret(1), turnOn: true });
    const coverage = await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds, secret: secret(1) });
    expect(coverage.map((entry) => entry.status)).toEqual(["protected", "protected"]);
  });

  it("keeps every password when a keyring is switched over", async () => {
    const { me, controller } = await account();
    const withPassword = await controller.syncDataset("keyweb-ring", {
      read: () =>
        applyOps(emptyVault(), [
          {
            kind: "keyring.put",
            opId: "ring",
            ts: encodeHlc({ wall: 1_700_000_000_000, counter: 0, node: "test" }),
            keyringId: "ring",
            name: "Home",
          },
        ]),
      apply: (merged) => merged,
    });
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds: [INDEX, "keyweb-ring"], secret: secret(1), turnOn: true });
    expect((await controller.loadDataset("keyweb-ring")).value).toEqual(withPassword.value);
  });

  it("lets members hold keys on keyrings you own, and puts yours on each", async () => {
    const { me, controller } = await account();
    const meta = new MemoryMeta();

    const coverage = await protectWithRecoveryCode({
      controller,
      identity: me,
      store: meta,
      datasetIds: [INDEX, "keyweb-ring"],
      secret: secret(1),
      turnOn: true,
    });

    expect(coverage.map((entry) => entry.status)).toEqual(["protected", "protected"]);
    for (const datasetId of [INDEX, "keyweb-ring"]) {
      const { enabled, keys } = await controller.getDatasetParticipantKeys(datasetId);
      expect(enabled).toBe(true);
      expect(keys).toHaveLength(1);
      expect(keys[0]!.principalKeyId).toBe(me.publicKey.keyId);
      expect(keys[0]!.purpose).toBe("recovery");
    }
  });

  it("attaches one key, however often it runs and from however many devices", async () => {
    const { me, controller } = await account();
    const datasetIds = [INDEX, "keyweb-ring"];
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds, secret: secret(1), turnOn: true });
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds, secret: secret(1), turnOn: true });
    const { keys } = await controller.getDatasetParticipantKeys("keyweb-ring");
    expect(keys).toHaveLength(1);
  });

  it("takes an old code's key off when the code is replaced", async () => {
    const { me, controller } = await account();
    const datasetIds = [INDEX, "keyweb-ring"];
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds, secret: secret(1), turnOn: true });
    const before = (await controller.getDatasetParticipantKeys(INDEX)).keys[0]!.keyId;

    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds, secret: secret(2), turnOn: true });
    const { keys } = await controller.getDatasetParticipantKeys(INDEX);
    expect(keys).toHaveLength(1);
    expect(keys[0]!.keyId).not.toBe(before);
  });

  it("adds a writer's key on a keyring somebody else owns, once they allow it", async () => {
    const { transport, me, controller } = await account();
    const writer = await member(transport, controller, "keyweb-ring", "writer");

    // Not until the owner has said members may hold keys of their own.
    const early = await protectWithRecoveryCode({
      controller: writer.controller,
      identity: writer.identity,
      store: new MemoryMeta(),
      datasetIds: ["keyweb-ring"],
      secret: secret(3),
      turnOn: true,
    });
    expect(early[0]!.status).toBe("waiting-for-owner");

    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds: ["keyweb-ring"], secret: secret(1), turnOn: true });
    const later = await protectWithRecoveryCode({
      controller: writer.controller,
      identity: writer.identity,
      store: new MemoryMeta(),
      datasetIds: ["keyweb-ring"],
      secret: secret(3),
      turnOn: true,
    });
    expect(later[0]!.status).toBe("protected");
    const { keys } = await controller.getDatasetParticipantKeys("keyweb-ring");
    expect(keys.map((key) => key.principalKeyId).sort()).toEqual(
      [me.publicKey.keyId, writer.identity.publicKey.keyId].sort(),
    );
  });

  it("says plainly when you can only view a keyring", async () => {
    const { transport, me, controller } = await account();
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds: ["keyweb-ring"], secret: secret(1), turnOn: true });
    const viewer = await member(transport, controller, "keyweb-ring", "viewer");
    const coverage = await protectWithRecoveryCode({
      controller: viewer.controller,
      identity: viewer.identity,
      store: new MemoryMeta(),
      datasetIds: ["keyweb-ring"],
      secret: secret(4),
      turnOn: true,
    });
    expect(coverage[0]!.status).toBe("view-only");
  });
});

describe("becoming yourself again with the code", () => {
  it("replaces the lost key on the index and every keyring, keeping your role", async () => {
    const { transport, me, controller } = await account();
    await protectWithRecoveryCode({
      controller,
      identity: me,
      store: new MemoryMeta(),
      datasetIds: [INDEX, "keyweb-ring"],
      secret: secret(1),
      turnOn: true,
    });

    // The passkey is gone, and with it `me`. A fresh key, a fresh device.
    const replacement = await createWebCryptoSharingIdentity();
    const fresh = memoryController(replacement, transport);
    const result = await recoverWithParticipantKey({
      controller: fresh,
      secret: secret(1),
      replacement,
      indexDatasetId: INDEX,
      datasetsAfterIndex: async () => ["keyweb-ring"],
    });

    expect(result).toEqual({ rotated: [INDEX, "keyweb-ring"], missed: [] });
    for (const datasetId of [INDEX, "keyweb-ring"]) {
      const { participants } = await fresh.getDatasetParticipants(datasetId);
      expect(participants.some((p) => p.keyId === me.publicKey.keyId)).toBe(false);
      expect(participants.find((p) => p.keyId === replacement.publicKey.keyId)?.role).toBe("owner");
      await expect(fresh.loadDataset(datasetId)).resolves.toBeTruthy();
    }
  });

  it("refuses a code that is not yours", async () => {
    const { transport, me, controller } = await account();
    await protectWithRecoveryCode({ controller, identity: me, store: new MemoryMeta(), datasetIds: [INDEX], secret: secret(1), turnOn: true });
    const replacement = await createWebCryptoSharingIdentity();
    await expect(
      recoverWithParticipantKey({
        controller: memoryController(replacement, transport),
        secret: secret(9),
        replacement,
        indexDatasetId: INDEX,
        datasetsAfterIndex: async () => [],
      }),
    ).rejects.toThrow();
  });
});

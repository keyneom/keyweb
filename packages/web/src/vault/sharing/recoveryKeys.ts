import type {
  SharedBackupAdditionalKeyV1,
  SharingRole,
} from "@keyneom/sync-kit/sharing";
import {
  createAuthorizedKeyRotationV1,
  createParticipantKeyAdditionV1,
  createParticipantKeyRemovalV1,
  createSharingRecoveryKeyV1,
  generateSharingRecoveryCode,
  openSharingRecoveryKeyV1,
} from "@keyneom/sync-kit/sharing/participant-keys";
import type { WebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";
import { KEYWEB_APP_ID, type SharingController } from "./controller";

/**
 * Your printed code, as a second key of yours on every keyring.
 *
 * A keyring's file names who may read it, one key per person — for you, the
 * key your passkey protects. This adds a second key of yours beside it: a
 * recovery key whose private half is sealed under your code and stored in the
 * file itself. So the code and any one keyring file are enough to become you
 * again, even with the passkey gone and no phone ever set up — which the older
 * recovery lock could not promise, because only the phone could write one.
 *
 * It is the same printed code, not a second one to keep. sync-kit's codes are
 * its own shape (128 bits, with check letters), so the one it needs is derived
 * from yours: the same derivation on the web and on the phone, which is what
 * makes a key either of them attached open with the code either of them
 * printed.
 */

/** Stored beside the vault: the recovery key this device attaches, once made. */
export const RECOVERY_KEY_META = "participant-recovery-key";

const DERIVATION_INFO = "keyweb-participant-recovery-code-v1";

/**
 * The sync-kit recovery code your printed code stands for.
 *
 * sync-kit only ever makes codes from randomness, so it is handed randomness
 * that is not random: sixteen bytes derived from the printed code's secret.
 * Everything else about the code — its letters, its check characters — is
 * sync-kit's own, so it parses and opens exactly as one sync-kit made.
 */
export async function participantRecoveryCode(secret: Uint8Array): Promise<string> {
  const material = await crypto.subtle.importKey("raw", copy(secret), "HKDF", false, [
    "deriveBits",
  ]);
  const seed = new Uint8Array(
    await crypto.subtle.deriveBits(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: new Uint8Array(0),
        info: new TextEncoder().encode(DERIVATION_INFO),
      },
      material,
      128,
    ),
  );
  const seeded = {
    subtle: crypto.subtle,
    getRandomValues<T extends ArrayBufferView | null>(array: T): T {
      if (array) new Uint8Array(array.buffer, array.byteOffset, array.byteLength).set(seed);
      return array;
    },
  } as unknown as Crypto;
  try {
    return await generateSharingRecoveryCode({ crypto: seeded });
  } finally {
    seed.fill(0);
  }
}

/** Where the code protects one keyring, for saying so in plain words. */
export type RecoveryCoverage = {
  datasetId: string;
  status:
    | "protected"
    /** The keyring's owner hasn't let members add keys to it yet. */
    | "waiting-for-owner"
    /** You can only view it, so you can't add your key to it yourself. */
    | "view-only"
    | "failed";
};

type Deps = {
  controller: SharingController;
  identity: WebCryptoSharingIdentity;
  /** Where the recovery key this device attached is remembered. */
  store: {
    readMeta(key: string): Promise<unknown | undefined>;
    writeMeta(key: string, value: unknown): Promise<void>;
  };
};

/**
 * Put your recovery key on every keyring you can write.
 *
 * On a keyring you own or run, members are first allowed to hold keys of their
 * own — sync-kit's per-keyring switch, off until an owner turns it on. Then
 * your recovery key is added, and any older one of yours the code no longer
 * opens is taken off, so a replaced code stops working rather than lingering.
 *
 * The same key on every keyring, found before it is made: a key another of
 * your devices already attached is reused if the code opens it, so two devices
 * never leave two keys for one code. Safe to run on every sync; a keyring that
 * already carries the key is only read.
 */
export async function protectWithRecoveryCode(
  deps: Deps & {
    datasetIds: string[];
    secret: Uint8Array;
    /**
     * Let members hold keys on keyrings you own that don't allow it yet.
     *
     * Only when asked. A keyring that allows it is written in a newer format
     * that Keyweb before 0.2.0-beta.37 cannot open, so the first keyring is
     * switched over by a person who knows their devices are up to date — and
     * from then on every device carries on by itself.
     */
    turnOn?: boolean;
  },
): Promise<RecoveryCoverage[]> {
  const { controller, identity } = deps;
  const me = identity.publicKey.keyId;
  const turnOn = deps.turnOn ?? (await recoveryKeysOn(controller, deps.datasetIds[0]));
  if (!turnOn) return [];
  const code = await participantRecoveryCode(deps.secret);

  const addition = await recoveryKeyFor({ ...deps, code, datasetIds: deps.datasetIds });

  const coverage: RecoveryCoverage[] = [];
  for (const datasetId of deps.datasetIds) {
    try {
      const role = await roleIn(controller, datasetId, me);
      if (!role) continue;
      let { enabled, keys } = await controller.getDatasetParticipantKeys(datasetId);
      if (!enabled && (role === "owner" || role === "admin")) {
        await controller.setParticipantKeysPolicy({ datasetId, enabled: true });
        enabled = true;
        keys = [];
      }
      if (!enabled) {
        coverage.push({ datasetId, status: "waiting-for-owner" });
        continue;
      }
      if (role === "viewer") {
        const present = keys.some((key) => key.keyId === addition.keyId);
        coverage.push({ datasetId, status: present ? "protected" : "view-only" });
        continue;
      }
      const mine = keys.filter((key) => key.principalKeyId === me && key.purpose === "recovery");
      const stale = mine.filter((key) => key.keyId !== addition.keyId);
      if (stale.length > 0) {
        await controller.removeParticipantKeys({
          datasetId,
          removals: await Promise.all(
            stale.map((key) =>
              createParticipantKeyRemovalV1({ appId: KEYWEB_APP_ID, authorizer: identity, key }),
            ),
          ),
        });
      }
      if (!mine.some((key) => key.keyId === addition.keyId)) {
        await controller.addParticipantKeys({ datasetId, additions: [addition] });
      }
      coverage.push({ datasetId, status: "protected" });
    } catch {
      coverage.push({ datasetId, status: "failed" });
    }
  }
  return coverage;
}

/**
 * Has anyone turned recovery keys on for this account?
 *
 * Read from the first file you have — the index, in the app — because every
 * device of yours writes it, so a switch thrown on one is seen by the rest.
 */
export async function recoveryKeysOn(
  controller: SharingController,
  datasetId: string | undefined,
): Promise<boolean> {
  if (!datasetId) return false;
  return controller
    .getDatasetParticipantKeys(datasetId)
    .then((found) => found.enabled)
    .catch(() => false);
}

/**
 * The one recovery key for this code: remembered, found, or made.
 *
 * Remembered first, then looked for on your keyrings — your other devices
 * attach theirs to the same files — and only made if neither turns one up
 * that this code opens.
 */
async function recoveryKeyFor(
  deps: Deps & { code: string; datasetIds: string[] },
): Promise<SharedBackupAdditionalKeyV1> {
  const me = deps.identity.publicKey.keyId;
  const opens = async (key: SharedBackupAdditionalKeyV1) =>
    key.principalKeyId === me &&
    key.purpose === "recovery" &&
    Boolean(key.sealedPrivateKeys) &&
    (await openSharingRecoveryKeyV1({ appId: KEYWEB_APP_ID, code: deps.code, key }).then(
      () => true,
      () => false,
    ));

  const remembered = (await deps.store.readMeta(RECOVERY_KEY_META)) as
    | SharedBackupAdditionalKeyV1
    | undefined;
  if (remembered && (await opens(remembered))) return remembered;

  for (const datasetId of deps.datasetIds) {
    const keys = await deps.controller
      .getDatasetParticipantKeys(datasetId)
      .then((found) => found.keys)
      .catch(() => [] as SharedBackupAdditionalKeyV1[]);
    for (const key of keys) {
      if (await opens(key)) {
        await deps.store.writeMeta(RECOVERY_KEY_META, key);
        return key;
      }
    }
  }

  const recovery = await createSharingRecoveryKeyV1({ appId: KEYWEB_APP_ID, code: deps.code });
  const addition = await createParticipantKeyAdditionV1({
    appId: KEYWEB_APP_ID,
    principalKeyId: me,
    authorizer: deps.identity,
    key: recovery.identity,
    purpose: "recovery",
    sealedPrivateKeys: recovery.sealedPrivateKeys,
  });
  await deps.store.writeMeta(RECOVERY_KEY_META, JSON.parse(JSON.stringify(addition)));
  return addition;
}

async function roleIn(
  controller: SharingController,
  datasetId: string,
  keyId: string,
): Promise<SharingRole | null> {
  const { participants } = await controller.getDatasetParticipants(datasetId);
  return participants.find((participant) => participant.keyId === keyId)?.role ?? null;
}

/**
 * Become a participant again with the code, when the passkey is gone.
 *
 * The recovery key is opened from the one file every account has — the index
 * — and signs "replace my lost key with this new one". That rotation is then
 * written into the index, and into every keyring the index names that the
 * recovery key is on. The lost key comes off each file it is written to, the
 * new one takes its place with the same role, and the recovery key stays,
 * attached to the new key, for next time.
 *
 * Returns the datasets it could not rotate: a keyring you can only view has no
 * recovery key of yours on it, and waits for its owner to add you again.
 */
export async function recoverWithParticipantKey(input: {
  /** A controller acting as the replacement identity. */
  controller: SharingController;
  secret: Uint8Array;
  replacement: WebCryptoSharingIdentity;
  indexDatasetId: string;
  /** The keyring files the index names, read once the index is yours again. */
  datasetsAfterIndex: () => Promise<string[]>;
}): Promise<{ rotated: string[]; missed: string[] }> {
  const code = await participantRecoveryCode(input.secret);
  const opened = await input.controller.openRecoveryKey({
    datasetId: input.indexDatasetId,
    code,
  });
  const rotation = await createAuthorizedKeyRotationV1({
    appId: KEYWEB_APP_ID,
    fromKeyId: opened.key.principalKeyId,
    authorizer: opened.identity,
    replacement: input.replacement,
  });
  const recovery = { replacement: input.replacement, key: opened.identity };
  await input.controller.rotateWithAdditionalKey({
    datasetId: input.indexDatasetId,
    rotation,
    recovery,
  });

  const rotated = [input.indexDatasetId];
  const missed: string[] = [];
  for (const datasetId of await input.datasetsAfterIndex()) {
    try {
      await input.controller.rotateWithAdditionalKey({ datasetId, rotation, recovery });
      rotated.push(datasetId);
    } catch {
      missed.push(datasetId);
    }
  }
  return { rotated, missed };
}

function copy(bytes: Uint8Array): ArrayBuffer {
  return bytes.slice().buffer as ArrayBuffer;
}

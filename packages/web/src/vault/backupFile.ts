import {
  createSharedBackupEnvelopeV1,
  decryptSharedBackupEnvelopeV1,
  type WebCryptoSharingIdentity,
} from "@keyneom/sync-kit/sharing/web-crypto";
import type { SharedBackupEnvelopeV1 } from "@keyneom/sync-kit/sharing";
import type {
  ProtectedSharingIdentityStore,
  ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import {
  boundDatasets,
  extractDataset,
  keyringsOf,
  type VaultState,
  withoutDatasetItems,
} from "@keyweb/vault-core";
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "./sharing/identity";

/**
 * An encrypted backup you keep yourself, and open with your printed code.
 *
 * ## What it is for
 *
 * Everything else Keyweb keeps lives in your Google account: the keyring files,
 * the index, and the lock your recovery code opens. That covers losing a
 * passkey. It does not cover losing Google — an outage, a locked account —
 * because then there is nothing for the code to open. This file is the one
 * thing that survives that: it holds your passwords *and* the lock, so the file
 * and the code on paper are enough, with no network at all.
 *
 * ## How it is sealed
 *
 * The same way every keyring file is: the vault encrypted once, its key granted
 * to your identity through sync-kit's envelope. The recovery lock rides beside
 * it — your identity wrapped by your code — so the code gives back you, and you
 * open the envelope. Neither half is readable without the code.
 *
 * It holds the vault's CRDT state rather than the plain export, because this is
 * a backup to restore rather than a file for another app: restoring is a merge,
 * which keeps history, files and timestamps and cannot lose anything newer that
 * is already on the device.
 */
export const BACKUP_FILE_FORMAT = "keyweb-backup";

export type BackupFileV1 = {
  format: typeof BACKUP_FILE_FORMAT;
  version: 1;
  savedAt: string;
  /** Your identity, wrapped by your printed recovery code. */
  recoveryLock: ProtectedSharingIdentityV1;
  /** Your vault, encrypted once, its key granted to that identity. */
  envelope: SharedBackupEnvelopeV1;
};

const codec = {
  serialize: (value: VaultState) => value,
  parse: (value: unknown) => value as VaultState,
};

/**
 * Seal a vault into a backup file.
 *
 * `recoveryLock` has to be the lock for *this* identity — the one the phone
 * writes to app-data — or the file would open for nobody. Checked by keyId
 * rather than trusted.
 */
export async function sealBackupFile(
  state: VaultState,
  identity: WebCryptoSharingIdentity,
  recoveryLock: ProtectedSharingIdentityV1,
  now: Date = new Date(),
): Promise<BackupFileV1> {
  if (recoveryLock.publicKey.keyId !== identity.publicKey.keyId) {
    throw new Error("That recovery lock belongs to a different identity.");
  }
  const envelope = await createSharedBackupEnvelopeV1(state, codec, identity, {
    appId: "keyweb-backup",
    backupId: `backup-${crypto.randomUUID()}`,
    participants: [{ publicKey: identity.publicKey, role: "owner" }],
    createdAt: now.toISOString(),
  });
  return {
    format: BACKUP_FILE_FORMAT,
    version: 1,
    savedAt: now.toISOString(),
    recoveryLock,
    envelope,
  };
}

/**
 * Open a backup file with the printed code — no network, no passkey.
 *
 * Throws on a wrong code, a file that is not a Keyweb backup, or one whose
 * envelope was not signed by the identity its own lock holds.
 */
export async function openBackupFile(input: unknown, code: Uint8Array): Promise<VaultState> {
  return (await openBackupFileAsYou(input, code)).state;
}

/**
 * Open a backup file, and give back who you are along with what was in it.
 *
 * The lock inside the file is the one beside your passkey in the Google
 * account, so opening it makes you *you* — the same identity every one of your
 * keyring files is wrapped to. A browser restoring from the file keeps both:
 * the identity for the session, so your files open once Google is back, and
 * the lock, so the code opens this browser's copy from then on.
 */
export async function openBackupFileAsYou(
  input: unknown,
  code: Uint8Array,
): Promise<{
  state: VaultState;
  identity: WebCryptoSharingIdentity;
  recoveryLock: ProtectedSharingIdentityV1;
}> {
  const file = parseBackupFile(input);
  const lockOnly: ProtectedSharingIdentityStore = {
    load: async (appId) => (appId === KEYWEB_RECOVERY_APP_ID ? file.recoveryLock : null),
    save: async () => undefined,
    delete: async () => undefined,
  };
  const identity = await unlockRecoveryIdentity(lockOnly, code).catch(() => {
    throw new WrongBackupCode();
  });
  if (!identity) throw new Error("This backup file has no recovery lock in it.");
  const state = await decryptSharedBackupEnvelopeV1(file.envelope, codec, identity, undefined, {
    trustedOwnerKeyId: identity.publicKey.keyId,
  });
  return { state: restorable(state), identity, recoveryLock: file.recoveryLock };
}

/** The code given does not open this file. Said plainly, not as a crypto error. */
export class WrongBackupCode extends Error {
  constructor() {
    super("That recovery code doesn't open this backup file.");
    this.name = "WrongBackupCode";
  }
}

/**
 * Put a restored vault into this browser's copy, each keyring where it lives.
 *
 * A keyring this copy already keeps in a file of its own gets its passwords in
 * that file's document, not the vault's — otherwise they would be published
 * into the index, beside keyrings they were never meant to travel with. A copy
 * with no such keyrings, which is any browser being set up from the file,
 * simply takes the lot.
 */
export async function restoreInto(
  storage: {
    readState(documentId?: string): Promise<VaultState>;
    applyRemote(incoming: VaultState, documentId?: string): Promise<VaultState>;
  },
  restored: VaultState,
): Promise<void> {
  let rest = restored;
  for (const { keyringId, datasetId } of boundDatasets(await storage.readState())) {
    const slice = extractDataset(rest, keyringId);
    if (Object.keys(slice.items).length > 0) await storage.applyRemote(slice, datasetId);
    rest = withoutDatasetItems(rest, keyringId);
  }
  await storage.applyRemote(rest);
}

function parseBackupFile(input: unknown): BackupFileV1 {
  const value = typeof input === "string" ? (JSON.parse(input) as unknown) : input;
  const file = value as Partial<BackupFileV1> | null;
  if (!file || file.format !== BACKUP_FILE_FORMAT || file.version !== 1) {
    throw new Error("That isn't a Keyweb backup file.");
  }
  if (!file.recoveryLock || !file.envelope) {
    throw new Error("That Keyweb backup file is incomplete.");
  }
  return file as BackupFileV1;
}

/**
 * A backed-up vault, ready to be merged into one that may have no Drive.
 *
 * The keyring records carry which Drive file each keyring lives in. A restore
 * is most likely to happen exactly when Drive cannot be reached, so the state
 * is handed back with every keyring's items composed in and its binding left
 * off — the passwords open here and now. When Google is back and the device
 * syncs, the index brings the bindings back with their own timestamps and
 * every keyring finds its file again.
 */
function restorable(state: VaultState): VaultState {
  const keyrings = Object.fromEntries(
    Object.entries(keyringsOf(state)).map(([id, ring]) => {
      const { dataset: _dataset, ...unbound } = ring;
      return [id, unbound];
    }),
  );
  return { ...state, keyrings: keyrings as VaultState["keyrings"] };
}

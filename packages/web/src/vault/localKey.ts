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
import { KEYWEB_RECOVERY_APP_ID, unlockRecoveryIdentity } from "./sharing/identity";

/**
 * Where the browser keeps what it needs to open its own copy.
 *
 * All of them are read before anything is unlocked, which is why none of them
 * is sealed by the local key they lead to.
 */
/** The local key, sealed by the passkey. The everyday way in. */
export const LOCAL_KEY_PASSKEY = "local-key:passkey";
/** The salt the local key is stretched with. Not secret. */
export const LOCAL_KEY_SALT = "local-key:salt";
/** The local key, sealed to your identity. The way in with the printed code. */
export const LOCAL_KEY_IDENTITY = "local-key:identity";
/**
 * Your identity wrapped by your printed code, cached from the Google account.
 *
 * Kept here so the code works with no network: the copy in Google's app-data
 * folder is no use during exactly the outage this exists for.
 */
export const RECOVERY_LOCK = "recovery-lock";

const bytes = {
  serialize: (value: number[]) => value,
  parse: (value: unknown) => value as number[],
};

/**
 * Seal the local key so your identity — and so your printed code — can open it.
 *
 * The same envelope every keyring file uses, with you as its one participant.
 * Written while the passkey still works, so that on the day it does not, the
 * code is enough.
 */
export function sealLocalKey(
  localKey: Uint8Array,
  identity: WebCryptoSharingIdentity,
): Promise<SharedBackupEnvelopeV1> {
  return createSharedBackupEnvelopeV1([...localKey], bytes, identity, {
    appId: "keyweb-local",
    backupId: `local-${crypto.randomUUID()}`,
    participants: [{ publicKey: identity.publicKey, role: "owner" }],
  });
}

/**
 * Open the local key with the printed code, using only what this browser holds.
 *
 * The cached recovery lock gives back your identity, and your identity opens the
 * sealed local key. No passkey, no network. Also returns the identity, so the
 * session it starts can use your keyring files as you when Google is back.
 */
export async function openLocalKeyWithCode(
  sealed: unknown,
  lock: ProtectedSharingIdentityV1,
  code: Uint8Array,
): Promise<{ localKey: Uint8Array; identity: WebCryptoSharingIdentity }> {
  const lockOnly: ProtectedSharingIdentityStore = {
    load: async (appId) => (appId === KEYWEB_RECOVERY_APP_ID ? lock : null),
    save: async () => undefined,
    delete: async () => undefined,
  };
  const identity = await unlockRecoveryIdentity(lockOnly, code);
  if (!identity) throw new Error("This browser has no recovery lock to open.");
  const localKey = await decryptSharedBackupEnvelopeV1(sealed, bytes, identity, undefined, {
    trustedOwnerKeyId: identity.publicKey.keyId,
  });
  return { localKey: Uint8Array.from(localKey), identity };
}

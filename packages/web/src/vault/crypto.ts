import {
  createV1EnvelopeCrypto,
  createWebCryptoBackend,
  deriveContentKey,
  parseSyncEnvelopeV1,
  type SyncEnvelopeV1,
  type V1KeyMetadata,
} from "@keyneom/sync-kit/crypto";
import { createWebPasskeyProvider } from "@keyneom/sync-kit/keys/web-passkey";
import type { SyncCodec } from "@keyneom/sync-kit/core";
import {
  fingerprint,
  mergeVaults,
  type VaultOp,
  type VaultState,
} from "@keyweb/vault-core";
import type { VaultCipher } from "@keyweb/vault-idb";
import { keywebRpId, keywebV1Profile } from "./profile";

/**
 * Encryption at rest, keyed by a passkey.
 *
 * The vault must not sit in IndexedDB as plaintext: GitHub Pages project sites
 * share one origin, so any other page on that origin can open this database.
 * The stored form is a sync-kit v1 envelope, which is self-describing — it
 * carries the credential id and KDF salt needed to unlock it — and is the same
 * envelope format the encrypted Drive backup will use.
 *
 * The key is AES-GCM, non-extractable, derived from the passkey's PRF output.
 * It lives in memory only and is dropped on lock.
 */

const backend = createWebCryptoBackend();

/** Envelope crypto only calls serialize/parse; merge and fingerprint are
 *  required by the type and unused on this path. */
const stateCodec: SyncCodec<VaultState> = {
  serialize: (value) => value,
  parse: (value) => value as VaultState,
  merge: mergeVaults,
  fingerprint,
};

const opCodec: SyncCodec<VaultOp> = {
  serialize: (value) => value,
  parse: (value) => value as VaultOp,
  merge: (local) => local,
  fingerprint: (value) => value.opId,
};

const stateCrypto = createV1EnvelopeCrypto(keywebV1Profile, stateCodec, backend);
const opCrypto = createV1EnvelopeCrypto(keywebV1Profile, opCodec, backend);

export type UnlockedVault = {
  cipher: VaultCipher;
  /** Drop the key from memory. The vault becomes unreadable until unlocked. */
  lock(): void;
};

export function passkeySupported(): boolean {
  return (
    typeof window !== "undefined" &&
    window.isSecureContext &&
    typeof window.PublicKeyCredential === "function"
  );
}

/**
 * Unlock, or set up on first run.
 *
 * Pass the raw stored state row. When it is null this is a first run and a new
 * passkey is created; otherwise the existing envelope names the credential to
 * unlock with, and the browser prompts for it.
 */
export type UnlockOptions = {
  rpId?: string;
  /** Injectable so the crypto path can be tested without an authenticator. */
  navigator?: Navigator;
  secureContext?: () => boolean;
};

export async function unlockVault(
  sealedState: unknown | null,
  options: UnlockOptions = {},
): Promise<UnlockedVault> {
  const injected = options.navigator !== undefined;
  if (!injected && !passkeySupported()) {
    throw new Error(
      "This browser can't protect your vault with a passkey. Keyweb needs a secure connection and passkey support.",
    );
  }

  const keyProvider = createWebPasskeyProvider(keywebV1Profile, {
    rpId: options.rpId ?? keywebRpId(),
    backend,
    ...(options.navigator ? { navigator: options.navigator } : {}),
    ...(options.secureContext ? { secureContext: options.secureContext } : {}),
  });

  let key: CryptoKey;
  let metadata;
  if (sealedState === null) {
    const created = await keyProvider.create();
    key = created.key;
    metadata = created.metadata;
  } else {
    const envelope = asEnvelope(sealedState);
    key = await keyProvider.unlock(envelope);
    metadata = stateCrypto.metadataFromEnvelope(envelope);
  }

  const cipher: VaultCipher = {
    sealState: (state) => stateCrypto.encrypt(state, key, metadata),
    openState: (stored) => stateCrypto.decrypt(asEnvelope(stored), key),
    sealOp: (op) => opCrypto.encrypt(op, key, metadata),
    openOp: (stored) => opCrypto.decrypt(asEnvelope(stored), key),
  };

  return {
    cipher,
    lock: () => keyProvider.clear(),
  };
}

function asEnvelope(value: unknown): SyncEnvelopeV1 {
  return parseSyncEnvelopeV1(
    typeof value === "string" ? value : JSON.stringify(value),
    keywebV1Profile,
  );
}


/**
 * A cipher keyed by the printed recovery code rather than the passkey.
 *
 * Pass the existing recovery envelope to reuse its salt, so the same code keeps
 * opening the same backup. Omit it at setup, when a fresh salt is minted.
 */
export async function createRecoveryCipher(
  secret: Uint8Array,
  existing?: unknown,
  rpId: string = keywebRpId(),
): Promise<VaultCipher> {
  let metadata: V1KeyMetadata;
  if (existing) {
    metadata = stateCrypto.metadataFromEnvelope(asEnvelope(existing));
  } else {
    metadata = {
      // The credential fields are meaningless on this path -- there is no
      // passkey involved -- but the envelope format requires them, and naming
      // the path makes a stored envelope self-describing.
      credentialId: "recovery",
      rpId,
      prfInput: backend.randomBytes(32),
      kdfSalt: backend.randomBytes(32),
    };
  }
  const key = await deriveContentKey(
    keywebV1Profile,
    secret,
    metadata.kdfSalt,
    backend,
  );
  return {
    sealState: (state) => stateCrypto.encrypt(state, key, metadata),
    openState: (stored) => stateCrypto.decrypt(asEnvelope(stored), key),
    sealOp: (op) => opCrypto.encrypt(op, key, metadata),
    openOp: (stored) => opCrypto.decrypt(asEnvelope(stored), key),
  };
}

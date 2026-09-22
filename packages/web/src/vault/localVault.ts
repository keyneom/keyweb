import type { SharedBackupEnvelopeV1 } from "@keyneom/sync-kit/sharing";
import type { WebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";
import {
  parseProtectedSharingIdentityV1,
  type ProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import {
  IndexedDbVaultStorage,
  peekMeta,
  peekSealedState,
  type VaultCipher,
} from "@keyweb/vault-idb";
import { createLocalCipher, unlockVault, type UnlockedVault } from "./crypto";
import {
  LOCAL_KEY_IDENTITY,
  LOCAL_KEY_PASSKEY,
  LOCAL_KEY_SALT,
  openLocalKeyWithCode,
  RECOVERY_LOCK,
  sealLocalKey,
} from "./localKey";

/**
 * This browser's copy of the vault, and the two ways into it.
 *
 * The copy is locked by a random key of its own. That key is kept twice beside
 * it: sealed by the passkey, which is how it opens every day, and sealed to
 * your identity, which the printed code can unlock. So a browser whose passkey
 * is unavailable — Google down, the credential gone — still opens with the
 * code alone, and needs no network to do it.
 *
 * Before this the copy was locked by the passkey directly, and nothing else
 * could open it.
 */

/** What an opened copy hands to the rest of the app. */
export type OpenedVault = {
  storage: IndexedDbVaultStorage;
  /** The cipher the copy is sealed with, for the named values kept beside it. */
  cipher: VaultCipher;
  /** The key this copy is locked with. Held so it can be sealed to you later. */
  localKey: Uint8Array;
  /**
   * The passkey's own cipher, when the passkey opened it.
   *
   * Only for the old Drive file, which was sealed with the passkey key and is
   * read once during the move off it. Null when the code opened the copy.
   */
  passkeyCipher: VaultCipher | null;
  /** You, when the code opened the copy — for the session it starts. */
  identity: WebCryptoSharingIdentity | null;
  lock: () => void;
};

type Where = { factory?: IDBFactory; name?: string };

type Deps = Where & {
  /** The passkey. Injected by tests, which have none. */
  unlock?: (sealed: unknown | null) => Promise<UnlockedVault>;
  /** For tests: pin the local key rather than drawing a random one. */
  random?: (length: number) => Uint8Array;
};

/** The named values sealed by the vault key, re-sealed when it changes. */
const SEALED_META = ["recovery-secret"];

/**
 * Open with the passkey, moving a copy the passkey locked directly onto a key
 * of its own the first time.
 *
 * `sealed` is the envelope that names the passkey when this browser has no copy
 * yet — restoring from Drive — so the passkey that sealed the backup is the one
 * asked for. Otherwise the copy itself names it.
 */
export async function openWithPasskey(
  deps: Deps & { sealed?: unknown | null } = {},
): Promise<OpenedVault> {
  const unlock = deps.unlock ?? ((sealed: unknown | null) => unlockVault(sealed));
  const where = { factory: deps.factory, name: deps.name };

  const wrapped = await peekMeta(LOCAL_KEY_PASSKEY, deps.factory, deps.name);
  if (wrapped) {
    const { cipher: passkey, lock } = await unlock(wrapped);
    const localKey = Uint8Array.from((await passkey.openOp(wrapped)) as unknown as number[]);
    const salt = await peekMeta(LOCAL_KEY_SALT, deps.factory, deps.name);
    if (!salt) throw new Error("This browser's vault is missing part of its lock.");
    const local = await createLocalCipher(localKey, Uint8Array.from(salt as number[]));
    const storage = await IndexedDbVaultStorage.open({ ...defined(where), cipher: local });
    return { storage, cipher: local, localKey, passkeyCipher: passkey, identity: null, lock };
  }

  /*
   * A copy the passkey locks directly, or no copy at all.
   *
   * Unlocked with the passkey as it always was, then moved onto a key of its
   * own in one step: every record re-sealed, and the new key stored sealed by
   * the passkey in the same write. The database is entirely old or entirely
   * new, never half of each, and never sealed under a key stored nowhere.
   */
  // A copy made with the code alone, while no passkey could be used. It has
  // no passkey lock to open, and asking for one would mint a stranger's.
  if (await peekMeta(LOCAL_KEY_SALT, deps.factory, deps.name)) throw new OpensWithCode();

  const sealed = deps.sealed ?? (await peekSealedState(deps.factory, deps.name));
  const { cipher: passkey, lock } = await unlock(sealed);
  const storage = await IndexedDbVaultStorage.open({ ...defined(where), cipher: passkey });
  const random = deps.random ?? ((length: number) => crypto.getRandomValues(new Uint8Array(length)));
  const localKey = random(32);
  const salt = random(32);
  const local = await createLocalCipher(localKey, salt);
  await storage.rekey(local, {
    sealedMeta: SEALED_META,
    alongside: {
      [LOCAL_KEY_PASSKEY]: await passkey.sealOp([...localKey] as never),
      [LOCAL_KEY_SALT]: [...salt],
    },
  });
  return { storage, cipher: local, localKey, passkeyCipher: passkey, identity: null, lock };
}

/** This browser's copy opens with the printed code only. */
export class OpensWithCode extends Error {
  constructor() {
    super("The passwords in this browser open with your recovery code.");
    this.name = "OpensWithCode";
  }
}

/**
 * Is the code the only way into this browser's copy?
 *
 * True for a copy set up from a backup file while no passkey could be used.
 * The lock screen then offers the code, not a passkey prompt that cannot work.
 */
export async function opensOnlyWithCode(where: Where = {}): Promise<boolean> {
  const wrapped = await peekMeta(LOCAL_KEY_PASSKEY, where.factory, where.name);
  return !wrapped && (await codeCanOpen(where));
}

/**
 * Set this browser up with the code alone, from a backup file.
 *
 * For the day there is no passkey to be had: the copy gets a key of its own,
 * sealed only to you, and the code opens it from then on. Only onto a browser
 * with nothing in it — a copy that exists already has a lock, and replacing it
 * would leave its passwords under a key nobody holds.
 */
export async function createWithCode(
  identity: WebCryptoSharingIdentity,
  lock: ProtectedSharingIdentityV1,
  deps: Where & { random?: (length: number) => Uint8Array } = {},
): Promise<OpenedVault> {
  if (lock.publicKey.keyId !== identity.publicKey.keyId) {
    throw new Error("That recovery lock belongs to somebody else.");
  }
  const existing =
    (await peekSealedState(deps.factory, deps.name)) ??
    (await peekMeta(LOCAL_KEY_SALT, deps.factory, deps.name));
  if (existing) throw new Error("This browser already has passwords in it.");

  const random = deps.random ?? ((length: number) => crypto.getRandomValues(new Uint8Array(length)));
  const localKey = random(32);
  const salt = random(32);
  const local = await createLocalCipher(localKey, salt);
  const storage = await IndexedDbVaultStorage.open({ ...defined(deps), cipher: local });
  // All three in one write: a salt without the sealed key would be a copy
  // locked by a key stored nowhere.
  await storage.rekey(local, {
    alongside: {
      [LOCAL_KEY_SALT]: [...salt],
      [LOCAL_KEY_IDENTITY]: JSON.parse(JSON.stringify(await sealLocalKey(localKey, identity))),
      [RECOVERY_LOCK]: lock,
    },
  });
  return { storage, cipher: local, localKey, passkeyCipher: null, identity, lock: () => undefined };
}

/** Can the printed code open this browser's copy without the network? */
export async function codeCanOpen(where: Where = {}): Promise<boolean> {
  const [sealed, lock, salt] = await Promise.all([
    peekMeta(LOCAL_KEY_IDENTITY, where.factory, where.name),
    peekMeta(RECOVERY_LOCK, where.factory, where.name),
    peekMeta(LOCAL_KEY_SALT, where.factory, where.name),
  ]);
  return Boolean(sealed && lock && salt);
}

/**
 * Open with the printed code alone.
 *
 * Null when this browser has nothing the code can open — it never had the
 * passkey working long enough to seal its key to you — so the caller can go
 * on to the copy in Drive instead. A wrong code throws.
 */
export async function openWithCode(code: Uint8Array, where: Where = {}): Promise<OpenedVault | null> {
  const [sealed, lock, salt] = await Promise.all([
    peekMeta(LOCAL_KEY_IDENTITY, where.factory, where.name),
    peekMeta(RECOVERY_LOCK, where.factory, where.name),
    peekMeta(LOCAL_KEY_SALT, where.factory, where.name),
  ]);
  if (!sealed || !lock || !salt) return null;
  const { localKey, identity } = await openLocalKeyWithCode(
    sealed,
    parseProtectedSharingIdentityV1(lock),
    code,
  );
  const local = await createLocalCipher(localKey, Uint8Array.from(salt as number[]));
  const storage = await IndexedDbVaultStorage.open({ ...defined(where), cipher: local });
  return { storage, cipher: local, localKey, passkeyCipher: null, identity, lock: () => undefined };
}

/**
 * Make sure the code can open this copy next time.
 *
 * Seals the local key to you, and keeps the recovery lock from the Google
 * account beside it — the copy in app-data being no use on the day Google is
 * the thing that is down. Only a lock on *this* identity is kept: a lock on
 * somebody else would open nothing and look as though it would.
 *
 * Returns whether anything was written. Safe to call on every sync; it does
 * nothing once both are in place and current.
 */
export async function protectLocalKey(
  storage: Pick<IndexedDbVaultStorage, "readMeta" | "writeMeta">,
  localKey: Uint8Array,
  identity: WebCryptoSharingIdentity,
  lock: ProtectedSharingIdentityV1 | null,
): Promise<boolean> {
  let wrote = false;
  const keyId = identity.publicKey.keyId;
  if (lock && lock.publicKey.keyId === keyId) {
    const cached = await storage.readMeta(RECOVERY_LOCK);
    if (JSON.stringify(cached) !== JSON.stringify(lock)) {
      await storage.writeMeta(RECOVERY_LOCK, lock);
      wrote = true;
    }
  }
  const sealed = (await storage.readMeta(LOCAL_KEY_IDENTITY)) as
    | Partial<SharedBackupEnvelopeV1>
    | undefined;
  const sealedFor = sealed?.keyGrants?.map((grant) => grant.recipientKeyId) ?? [];
  if (!sealedFor.includes(keyId)) {
    const envelope = await sealLocalKey(localKey, identity);
    // Stored as plain JSON, the way it would travel.
    await storage.writeMeta(LOCAL_KEY_IDENTITY, JSON.parse(JSON.stringify(envelope)));
    wrote = true;
  }
  return wrote;
}

function defined(where: Where): Where {
  const out: Where = {};
  if (where.factory) out.factory = where.factory;
  if (where.name) out.name = where.name;
  return out;
}

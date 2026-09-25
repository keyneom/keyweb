import {
  base64UrlToBytes,
  createWebCryptoBackend,
  defineV1CompatibilityProfile,
  deriveContentKey,
  type V1KeyMetadata,
} from "@keyneom/sync-kit/crypto";
import {
  createProtectedSharingIdentityV1,
  parseProtectedSharingIdentityV1,
  type ProtectedSharingIdentityStore,
  type ProtectedSharingIdentityV1,
  unlockProtectedSharingIdentityV1,
} from "@keyneom/sync-kit/sharing/web-passkey";
import type { WebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";
import { sharingKeyFingerprint } from "@keyneom/sync-kit/sharing/web-crypto";

/**
 * Who you are to somebody you share a keyring with.
 *
 * The vault key is symmetric and derived from your passkey: it is how *you*
 * open *your* passwords, and it can never be given to anyone else without
 * giving them everything. Sharing needs the other shape — a keypair, so a
 * keyring's content key can be wrapped for one specific person, and so what
 * they receive can be proved to have come from you.
 *
 * ECDH and ECDSA on P-256, generated once and then never again. The private
 * keys are exported, wrapped with a key derived from the vault's recovery
 * secret, and only the wrapped form is ever stored. Unlocking re-imports them as
 * non-extractable, so after the first moment of their life they cannot leave
 * the browser even in principle.
 */

export const KEYWEB_SHARING_APP_ID = "keyweb";

/**
 * The wrapping key comes from the recovery secret, not from the passkey.
 *
 * The passkey is the obvious answer on the web and it is the wrong one,
 * because the phone does not have one. Keyweb on Android is locked by the
 * Android Keystore and a fingerprint; it opens the Drive backup through the
 * *recovery* envelope precisely because it cannot derive a WebAuthn PRF. An
 * identity wrapped by the browser's passkey would therefore be unreadable on
 * the phone — and a phone that cannot read it would make its own, which means
 * one person appearing as two participants and being unable to open the
 * keyrings they themselves shared.
 *
 * The recovery secret is the one secret both platforms genuinely hold: 160
 * random bits, minted once, stored sealed on each device and printed once on
 * paper. Deriving from it adds no exposure — anyone holding it can already
 * restore the entire vault — and it makes the identity the same on every
 * device the person owns, which is the whole requirement.
 *
 * The cost is honest and narrow: a browser that has the passkey but has never
 * been given the printed code can read and back up the vault, but cannot share
 * until the code is entered. That state already exists and is already surfaced.
 */
export const keywebSharingProfile = defineV1CompatibilityProfile({
  appId: "keyweb-sharing",
  filename: "unused",
  aad: "keyweb-sharing-identity-v1",
  hkdfInfo: "keyweb-sharing-identity-wrap-v1",
  compression: "none",
  passkey: {
    // Unused on this path — nothing here presents a credential — but the
    // profile type requires it and naming it makes that explicit.
    rpName: "Keyweb",
    userName: "sharing",
    userDisplayName: "Keyweb sharing",
    algorithm: -7,
    residentKey: "required",
    userVerification: "required",
    timeoutMs: 60_000,
  },
});

const backend = createWebCryptoBackend();

/**
 * The credential fields are meaningless on this path — no passkey is
 * involved — but the record format requires them, and naming the path makes a
 * stored record self-describing rather than merely valid.
 */
const RECOVERY_CREDENTIAL = { credentialId: "recovery", rpId: "keyweb" };

/**
 * What this app needs of an identity, rather than which class provides it.
 *
 * sync-kit's `PasskeyProtectedSharingIdentityProvider` is what actually
 * provides it now; the class below predates that and is kept for the migration
 * path. Depending on the shape rather than the class is what let the two swap
 * without the controller or the operations knowing.
 */
export type SharingIdentityLike = {
  getOrCreate(): Promise<WebCryptoSharingIdentity>;
  clear(): void;
};

/**
 * Where the recovery lock on the identity is kept, beside the passkey one.
 * The same app id the phone writes it under.
 */
export const KEYWEB_RECOVERY_APP_ID = "keyweb-recovery";

/**
 * Become yourself again from the printed code, in a browser with no passkey.
 *
 * Every keyring is a file whose key is wrapped to its participants, and you
 * are a participant on every one of yours — so what the code has to restore is
 * *you*, not a copy of anything. The phone that minted the code writes this
 * record: the very keypair the passkey record holds, wrapped by a key derived
 * from the code with the same label both platforms use.
 *
 * Null when the account has no such record, which is every account whose
 * phone has not yet run a build that writes it.
 */
export async function unlockRecoveryIdentity(
  store: ProtectedSharingIdentityStore,
  code: Uint8Array,
): Promise<WebCryptoSharingIdentity | null> {
  const stored = await store.load(KEYWEB_RECOVERY_APP_ID);
  if (!stored) return null;
  const record = parseProtectedSharingIdentityV1(stored);
  const key = await deriveContentKey(
    keywebSharingProfile,
    code,
    base64UrlToBytes(record.kdfSalt),
    backend,
  );
  return unlockProtectedSharingIdentityV1(record, key);
}

export class SharingIdentityMissing extends Error {
  constructor(message = "This device has no sharing key yet.") {
    super(message);
    this.name = "SharingIdentityMissing";
  }
}

/**
 * Where the wrapped identity is kept.
 *
 * Two places, deliberately, and the order of the two reads is the whole
 * design.
 *
 * `drive.appdata` is authoritative, because it is per Google account rather
 * than per device: sign in on a second browser and the identity is already
 * there, so both are the same participant and neither has to be added to
 * anything. That is the entire reason Keyweb asks for the appdata scope.
 *
 * But reading it needs the network, and a shared keyring has to open on a
 * train. So a device that has already fetched the identity keeps its own copy
 * and reads that first. The copy is the *wrapped* blob — unreadable without
 * the passkey — which is the same reason it is safe to leave in Drive.
 *
 * Absent and unreachable are kept strictly apart. Returning null for a network
 * failure would tell the caller "no identity exists", and the caller would
 * respond by generating one: a second identity for the same person, holding
 * none of the grants the first one has. That is the failure this whole store
 * exists to avoid, so an unreachable Drive throws.
 */
export class KeywebSharingIdentityStore implements ProtectedSharingIdentityStore {
  readonly #local: ProtectedSharingIdentityStore;
  readonly #remote: ProtectedSharingIdentityStore;
  /** The background look at the account's copy, for tests to wait on. */
  refreshed: Promise<void> = Promise.resolve();

  constructor(options: {
    local: ProtectedSharingIdentityStore;
    remote: ProtectedSharingIdentityStore;
  }) {
    this.#local = options.local;
    this.#remote = options.remote;
  }

  async load(appId: string): Promise<ProtectedSharingIdentityV1 | null> {
    const cached = (await this.#local.load(appId)) as ProtectedSharingIdentityV1 | null;
    if (cached) {
      /*
       * The copy here, at once — and the account's, for next time.
       *
       * The account's record can change under this device: recovering with
       * the printed code puts a new key in it, and takes the old one off every
       * keyring. A device that only ever read its own copy would keep offering
       * the old key for ever. So the account is asked in the background, and a
       * different answer replaces the copy, which the next start then uses.
       */
      this.refreshed = this.#remote
        .load(appId)
        .then(async (stored) => {
          if (stored && JSON.stringify(stored) !== JSON.stringify(cached)) {
            await this.#local.save(stored as ProtectedSharingIdentityV1);
          }
        })
        .catch(() => undefined);
      return cached;
    }

    const stored = (await this.#remote.load(appId)) as ProtectedSharingIdentityV1 | null;
    if (stored) {
      // Best effort: failing to cache is a slower next start, not a failure.
      await this.#local.save(stored).catch(() => undefined);
    }
    return stored;
  }

  async save(record: ProtectedSharingIdentityV1): Promise<void> {
    // Drive first. A local-only identity would look established to this device
    // while the next one to sign in found nothing and made its own.
    await this.#remote.save(record);
    await this.#local.save(record);
  }

  async delete(appId: string): Promise<void> {
    await this.#remote.delete(appId);
    await this.#local.delete(appId);
  }
}

export type SharingIdentityOptions = {
  store: ProtectedSharingIdentityStore;
  /**
   * The vault's recovery secret, which is what the wrapping key is derived
   * from. Throwing here — because this device has never been given the printed
   * code — is the honest answer, and better than making a second identity.
   */
  secret(): Promise<Uint8Array>;
  appId?: string;
};

/**
 * Loads the sharing identity, or makes the one and only one.
 *
 * Kept warm for the page session once unlocked, because every operation that
 * touches a shared keyring needs it and a passkey prompt per operation would
 * be unusable. Dropped by `clear()`, which the lock screen calls.
 */
export class SharingIdentity {
  readonly #options: SharingIdentityOptions;
  #cached: WebCryptoSharingIdentity | null = null;
  #inFlight: Promise<WebCryptoSharingIdentity> | null = null;

  constructor(options: SharingIdentityOptions) {
    this.#options = options;
  }

  get appId(): string {
    return this.#options.appId ?? KEYWEB_SHARING_APP_ID;
  }

  /** True once unlocked in this session, so callers can avoid a pointless prompt. */
  get warm(): boolean {
    return this.#cached !== null;
  }

  /** The existing identity. Throws `SharingIdentityMissing` if there is none. */
  get(): Promise<WebCryptoSharingIdentity> {
    return this.#single(() => this.#load(false));
  }

  /** The existing identity, creating it the first time. */
  getOrCreate(): Promise<WebCryptoSharingIdentity> {
    return this.#single(() => this.#load(true));
  }

  clear(): void {
    this.#cached = null;
  }

  /**
   * Six characters people can read to each other.
   *
   * Not decoration. The link-carried exchange is only as trustworthy as the
   * channel it travelled over, and the one defence against a swapped
   * invitation is the two of them comparing this out loud.
   */
  static fingerprint(identity: WebCryptoSharingIdentity): string {
    return sharingKeyFingerprint(identity.publicKey.keyId);
  }

  /**
   * One prompt at a time, whatever asks.
   *
   * Unwrapping needs the vault's recovery secret, which needs the vault
   * open. Letting a background sync and a share flow each ask for that
   * separately is how one of them fails on a prompt the person never saw.
   * Everything funnels through the same promise.
   */
  #single(operation: () => Promise<WebCryptoSharingIdentity>): Promise<WebCryptoSharingIdentity> {
    if (this.#cached) return Promise.resolve(this.#cached);
    if (this.#inFlight) return this.#inFlight;
    const run = operation()
      .then((identity) => {
        this.#cached = identity;
        return identity;
      })
      .finally(() => {
        this.#inFlight = null;
      });
    this.#inFlight = run;
    return run;
  }

  async #load(create: boolean): Promise<WebCryptoSharingIdentity> {
    const stored = await this.#options.store.load(this.appId);
    const secret = await this.#options.secret();

    if (stored) {
      // Parsed rather than trusted: the record comes back from Drive, and a
      // malformed one must fail as a bad record rather than as a crypto error
      // somewhere further in.
      const record = parseProtectedSharingIdentityV1(stored);
      const key = await deriveContentKey(
        keywebSharingProfile,
        secret,
        base64UrlToBytes(record.kdfSalt),
        backend,
      );
      return unlockProtectedSharingIdentityV1(record, key);
    }
    if (!create) throw new SharingIdentityMissing();

    // A fresh salt, stored beside the wrapped keys, so the same secret derives
    // the same wrapping key on every device without any of them having to
    // agree on anything else.
    const metadata: V1KeyMetadata = {
      ...RECOVERY_CREDENTIAL,
      prfInput: backend.randomBytes(32),
      kdfSalt: backend.randomBytes(32),
    };
    const key = await deriveContentKey(
      keywebSharingProfile,
      secret,
      metadata.kdfSalt,
      backend,
    );
    const created = await createProtectedSharingIdentityV1(this.appId, metadata, key);
    await this.#options.store.save(created.record);
    return created.identity;
  }
}

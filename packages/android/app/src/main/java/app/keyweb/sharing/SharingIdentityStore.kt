package app.keyweb.sharing

import app.keyweb.vault.hkdfSha256
import com.keyneom.synckit.core.Authorization
import com.keyneom.synckit.crypto.V1KeyMetadata
import com.keyneom.synckit.sharing.DriveAppDataProtectedSharingIdentityStore
import com.keyneom.synckit.sharing.ProtectedSharingIdentityCrypto
import com.keyneom.synckit.sharing.ProtectedSharingIdentityStore
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import com.keyneom.synckit.sharing.SharingIdentity
import java.security.SecureRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Who you are to somebody you share a keyring with.
 *
 * The vault key is symmetric — it is how *you* open *your* passwords, and it
 * cannot be given to anyone without giving them everything. Sharing needs the
 * other shape: a keypair, so a keyring's content key can be wrapped for one
 * specific person, and so what they receive can be proved to have come from
 * you.
 *
 * A direct counterpart of `packages/web/src/vault/sharing/identity.ts`, and it
 * has to be: the browser and the phone must be the **same participant**, or
 * one person appears as two and cannot open the keyrings they shared
 * themselves.
 */

/** The app id the record is filed under. Same string on both platforms. */
const val KEYWEB_SHARING_APP_ID = "keyweb"

/**
 * The wrapping key comes from the recovery secret, not from a passkey.
 *
 * The browser's obvious answer — derive it from the vault passkey — cannot
 * work here. Keyweb on Android is locked by the Android Keystore and a
 * fingerprint; it opens the Drive backup through the *recovery* envelope
 * because this app does not use a passkey on Android, which was believed to
 * be impossible and is not: sync-kit-android ships `AndroidPasskeyKeyProvider`.
 * So the one secret
 * both platforms genuinely hold is the printed recovery code: 160 random bits,
 * minted once, stored sealed on every device.
 *
 * Deriving from it adds no exposure, because anyone holding it can already
 * restore the entire vault.
 *
 * This label is what separates this key from the vault content key the same
 * secret also derives. It must match the web's `hkdfInfo` exactly.
 */
private const val HKDF_INFO = "keyweb-sharing-identity-wrap-v1"

private const val SALT_BYTES = 32
private const val KEY_BYTES = 32

/**
 * The credential fields are meaningless on this path — no passkey is
 * involved — but the record format requires them, and naming the path makes a
 * stored record self-describing rather than merely valid.
 */
/**
 * Where the recovery lock on the identity is kept, beside the passkey one.
 *
 * Its own app id so the two records sit side by side in app-data and neither
 * overwrites the other.
 */
const val RECOVERY_APP_ID = "keyweb-recovery"

/** What a record wrapped by the printed code calls itself. */
private const val LEGACY_CREDENTIAL_ID = "recovery"
private const val RP_ID = "keyweb"

class SharingIdentityMissing(
    message: String = "This device has no sharing key yet.",
) : Exception(message)

/**
 * Where the wrapped identity is kept.
 *
 * Two places, and the order of the two reads is the whole design.
 *
 * `drive.appdata` is authoritative, because it is per Google account rather
 * than per device: sign in on another device and the identity is already
 * there, so both are the same participant and neither has to be added to
 * anything.
 *
 * But reading it needs the network, and a shared keyring has to open on a
 * train. So a device that has already fetched it keeps its own copy and reads
 * that first. The copy is the *wrapped* blob — unreadable without the recovery
 * secret — which is the same reason it is safe to leave in Drive at all.
 *
 * Absent and unreachable are kept strictly apart. Returning null for a network
 * failure would tell the caller no identity exists, and the caller would
 * respond by generating one: a second identity for the same person, holding
 * none of the grants the first one has. So an unreachable Drive throws.
 */
class KeywebSharingIdentityStore(
    private val local: ProtectedSharingIdentityStore,
    private val remote: ProtectedSharingIdentityStore,
) : ProtectedSharingIdentityStore {

    override suspend fun load(appId: String): ProtectedSharingIdentityV1? {
        local.load(appId)?.let { return it }
        val stored = remote.load(appId) ?: return null
        // Best effort: failing to cache is a slower next start, not a failure.
        runCatching { local.save(stored) }
        return stored
    }

    override suspend fun save(record: ProtectedSharingIdentityV1) {
        // Drive first. A local-only identity would look established to this
        // device while the next one to sign in found nothing and made its own.
        remote.save(record)
        local.save(record)
    }

    override suspend fun delete(appId: String) {
        remote.delete(appId)
        local.delete(appId)
    }
}

/**
 * Loads the sharing identity, or makes the one and only one.
 *
 * Kept warm once unlocked, because every operation touching a shared keyring
 * needs it. Dropped by [clear], which locking the vault calls.
 */
class KeywebSharingIdentity(
    private val store: ProtectedSharingIdentityStore,
    /**
     * The key this identity is wrapped with, derived from the passkey.
     *
     * The same passkey that opens the vault, through the sharing profile whose
     * HKDF label the web shares — so this phone and a browser derive one
     * identity rather than one each. That is the whole point of keeping the
     * record in the account's app-data folder, and it was undone by wrapping
     * with the printed recovery code, which only the device that minted it
     * ever had.
     */
    private val passkey: SharingPasskey,
    /**
     * The vault's recovery secret, for a record wrapped before the passkey was
     * used. Migrating it needs this; nothing else does.
     */
    private val secret: suspend () -> ByteArray,
    private val appId: String = KEYWEB_SHARING_APP_ID,
    private val random: SecureRandom = SecureRandom(),
) {
    private val gate = Mutex()

    @Volatile
    private var cached: SharingIdentity? = null

    /** True once unlocked, so callers can skip work that would need the vault. */
    val warm: Boolean get() = cached != null

    /** The existing identity. Throws [SharingIdentityMissing] if there is none. */
    suspend fun get(): SharingIdentity = load(create = false)

    /** The existing identity, creating it the first time. */
    suspend fun getOrCreate(): SharingIdentity = load(create = true)

    fun clear() {
        cached = null
    }

    /**
     * One at a time, whatever asks.
     *
     * Unwrapping needs the recovery secret, which needs the vault open. Letting
     * a background sync and a share flow each ask separately is how one of them
     * fails on a prompt the person never saw.
     */
    private suspend fun load(create: Boolean): SharingIdentity {
        cached?.let { return it }
        return gate.withLock {
            cached?.let { return@withLock it }
            val identity = loadUnlocked(create)
            cached = identity
            ensureRecoveryLock(identity)
            identity
        }
    }

    private suspend fun loadUnlocked(create: Boolean): SharingIdentity {
        val stored = store.load(appId)

        if (stored != null) {
            // Parsed rather than trusted: the record comes back from Drive, and
            // a malformed one must fail as a bad record rather than as a crypto
            // error somewhere further in.
            val record = ProtectedSharingIdentityCrypto.parse(stored)

            /*
             * A record from before the passkey wrapped it.
             *
             * Re-wrapped in place, never regenerated: the keypair inside is
             * what every person this vault has shared with has pinned, and a
             * fresh one would leave them trusting a key nobody holds. Only a
             * device that has the printed code can do this, which is the
             * device that minted it — after which every other device of theirs
             * opens the record with its passkey and needs no code at all.
             */
            if (record.credentialId == LEGACY_CREDENTIAL_ID) {
                val identity = ProtectedSharingIdentityCrypto.unlock(
                    record,
                    wrapping(secret(), base64UrlToBytes(record.kdfSalt)),
                )
                // Best effort, and usable either way: a phone that cannot raise
                // the passkey sheet right now still shares through the old
                // wrapping, and migrates the next time it can. Failing the load
                // over the migration would take sharing away to tidy up.
                return runCatching {
                    val rewrapped = passkey.wrap(appId, identity)
                    store.save(rewrapped.record)
                    rewrapped.identity
                }.getOrDefault(identity)
            }

            return ProtectedSharingIdentityCrypto.unlock(record, passkey.keyFor(record))
        }
        if (!create) throw SharingIdentityMissing()

        val created = passkey.create(appId)
        store.save(created.record)
        return created.identity
    }

    /**
     * A second lock on the same identity, opened by the printed recovery code.
     *
     * Every keyring now lives in its own file, with its key wrapped to its
     * participants — and you are a participant on every one of yours. So the
     * recovery code does not need to be a reader of each file, and there is no
     * second copy of anything to keep current: it only needs to be able to
     * make you *you* again on a device that has lost the passkey. It does that
     * by unwrapping this record, which holds the very keypair the passkey
     * record does.
     *
     * Written by a device that holds both — which is the phone that minted
     * the code — and never regenerated: the keypair is what everyone you have
     * shared with has pinned. Best effort, like the migration above, because
     * failing to write the spare key must not stop you using the real one.
     */
    private suspend fun ensureRecoveryLock(identity: SharingIdentity) {
        runCatching {
            if (store.load(RECOVERY_APP_ID) != null) return
            val code = secret()
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val metadata = V1KeyMetadata(
                credentialId = LEGACY_CREDENTIAL_ID,
                rpId = RP_ID,
                prfInput = ByteArray(SALT_BYTES).also(random::nextBytes),
                kdfSalt = salt,
            )
            val locked = ProtectedSharingIdentityCrypto.create(
                RECOVERY_APP_ID,
                metadata,
                wrapping(code, salt),
                identity,
            )
            store.save(locked.record)
        }
    }

    /**
     * Become yourself again from the printed code alone.
     *
     * For a device with no passkey that works — a new phone, a reset, a lost
     * account passkey. The recovery record gives back the same keypair, and
     * a fresh passkey is wrapped around it so this device never needs the code
     * again. Nothing is regenerated, so every file you are on still opens.
     */
    suspend fun recoverWith(code: ByteArray): SharingIdentity = gate.withLock {
        val stored = store.load(RECOVERY_APP_ID) ?: throw SharingIdentityMissing()
        val identity = unlockRecoveryLock(stored, code)
        runCatching {
            val rewrapped = passkey.wrap(appId, identity)
            store.save(rewrapped.record)
        }
        cached = identity
        identity
    }

    /**
     * The lock the printed code opens, as it stands in the account.
     *
     * For a backup file, which carries it so the file opens with the code and
     * nothing else. Null until a phone holding the code has written one.
     */
    suspend fun recoveryLock(): ProtectedSharingIdentityV1? =
        store.load(RECOVERY_APP_ID)?.let(ProtectedSharingIdentityCrypto::parse)

    companion object {
        /**
         * Six characters people can read to each other.
         *
         * Not decoration. The link-carried exchange is only as trustworthy as
         * the channel it travelled over, and the one defence against a swapped
         * invitation is the two of them comparing this out loud. A port of the
         * web's `sharingKeyFingerprint`, so both show the same six characters.
         */
        fun fingerprint(keyId: String): String =
            base64UrlToBytes(keyId)
                .take(12)
                .joinToString("") { "%02x".format(it) }
                .chunked(4)
                .joinToString("-")

        /** The app-data half of the store, against the signed-in Google account. */
        fun driveStore(
            authorization: suspend () -> Authorization,
        ): ProtectedSharingIdentityStore =
            DriveAppDataProtectedSharingIdentityStore({ authorization() })
    }
}

private fun wrapping(secret: ByteArray, salt: ByteArray): ByteArray =
    hkdfSha256(secret, salt, HKDF_INFO.toByteArray(Charsets.UTF_8), KEY_BYTES)

/**
 * Become yourself from the printed code and a recovery lock, wherever the lock
 * came from — the account's app-data folder, or a backup file.
 *
 * The same derivation the web's `unlockRecoveryIdentity` uses, so a lock
 * either one wrote opens on the other. Throws on the wrong code.
 */
internal fun unlockRecoveryLock(record: ProtectedSharingIdentityV1, code: ByteArray): SharingIdentity {
    val parsed = ProtectedSharingIdentityCrypto.parse(record)
    return ProtectedSharingIdentityCrypto.unlock(
        parsed,
        wrapping(code, base64UrlToBytes(parsed.kdfSalt)),
    )
}

/**
 * Base64url without padding, as every sync-kit field is encoded.
 *
 * `java.util.Base64` rather than `android.util.Base64`: the platform class is
 * an unimplemented stub in a JVM unit test and throws rather than decoding, so
 * using it here would make the identity untestable off a device — which is
 * exactly where the cross-platform fixture has to be checked.
 */
internal fun base64UrlToBytes(value: String): ByteArray =
    java.util.Base64.getUrlDecoder().decode(value.trimEnd('='))

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
 * precisely because there is no WebAuthn PRF on this side. So the one secret
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
private const val CREDENTIAL_ID = "recovery"
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
     * The vault's recovery secret. Throwing here — because this device has
     * never been given the printed code — is the honest answer, and far better
     * than making a second identity.
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
            identity
        }
    }

    private suspend fun loadUnlocked(create: Boolean): SharingIdentity {
        val stored = store.load(appId)
        val key = secret()

        if (stored != null) {
            // Parsed rather than trusted: the record comes back from Drive, and
            // a malformed one must fail as a bad record rather than as a crypto
            // error somewhere further in.
            val record = ProtectedSharingIdentityCrypto.parse(stored)
            return ProtectedSharingIdentityCrypto.unlock(
                record,
                wrapping(key, base64UrlToBytes(record.kdfSalt)),
            )
        }
        if (!create) throw SharingIdentityMissing()

        // A fresh salt, stored beside the wrapped keys, so the same secret
        // derives the same wrapping key on every device without any of them
        // having to agree on anything else.
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val metadata = V1KeyMetadata(
            credentialId = CREDENTIAL_ID,
            rpId = RP_ID,
            prfInput = ByteArray(SALT_BYTES).also(random::nextBytes),
            kdfSalt = salt,
        )
        val created = ProtectedSharingIdentityCrypto.create(
            appId,
            metadata,
            wrapping(key, salt),
            null,
        )
        store.save(created.record)
        return created.identity
    }

    private fun wrapping(secret: ByteArray, salt: ByteArray): ByteArray =
        hkdfSha256(secret, salt, HKDF_INFO.toByteArray(Charsets.UTF_8), KEY_BYTES)

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

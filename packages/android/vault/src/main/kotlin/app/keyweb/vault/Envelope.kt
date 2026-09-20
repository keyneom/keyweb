package app.keyweb.vault

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sync-kit v1 encrypted envelope, reimplemented in Kotlin.
 *
 * The web app seals the Drive backup with sync-kit's WebCrypto implementation.
 * Android has no sync-kit, so for the two platforms to read one another's
 * backup this must agree with it byte for byte — not merely "also be AES-GCM".
 * The constants below are the contract, mirrored from
 * `packages/web/src/vault/profile.ts`; changing either side alone makes every
 * existing backup unreadable, which is a data-loss event rather than a bug.
 *
 * Agreement is not asserted by reading this code. `EnvelopeInteropTest` opens a
 * fixture the TypeScript implementation actually produced, and the web test
 * suite opens one this implementation actually produced.
 */
object KeywebEnvelope {
    const val ALGORITHM = "AES-256-GCM+HKDF-SHA-256"

    /** Bound into every ciphertext, so an envelope cannot be replayed as another app's. */
    const val AAD = "keyweb-vault-envelope-v1"

    /** The HKDF label separating the content key from any other key the same secret derives. */
    const val HKDF_INFO = "keyweb-vault-content-key-v1"

    const val NONCE_BYTES = 12
    const val SALT_BYTES = 32
    const val PRF_BYTES = 32
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)
}

/**
 * The envelope as it appears on disk and in Drive.
 *
 * `credentialId`, `rpId` and `prfInput` describe the passkey that derives the
 * key on the web. They carry no secret and Android ignores them, but they are
 * part of the format and must survive a round trip untouched — dropping them
 * would leave an envelope the web could no longer unlock.
 */
@Serializable
data class SyncEnvelopeV1(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("algorithm") val algorithm: String = KeywebEnvelope.ALGORITHM,
    @SerialName("compression") val compression: String? = null,
    @SerialName("credentialId") val credentialId: String,
    @SerialName("rpId") val rpId: String,
    @SerialName("prfInput") val prfInput: String,
    @SerialName("kdfSalt") val kdfSalt: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("ciphertext") val ciphertext: String,
    @SerialName("updatedAt") val updatedAt: String,
)

/** The non-secret fields a re-seal must preserve so the same key keeps working. */
data class EnvelopeMetadata(
    val credentialId: String,
    val rpId: String,
    val prfInput: ByteArray,
    val kdfSalt: ByteArray,
) {
    // Data classes compare ByteArray by identity, which would silently make two
    // equal metadata values unequal.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is EnvelopeMetadata &&
                    credentialId == other.credentialId &&
                    rpId == other.rpId &&
                    prfInput.contentEquals(other.prfInput) &&
                    kdfSalt.contentEquals(other.kdfSalt)
                )

    override fun hashCode(): Int {
        var result = credentialId.hashCode()
        result = 31 * result + rpId.hashCode()
        result = 31 * result + prfInput.contentHashCode()
        result = 31 * result + kdfSalt.contentHashCode()
        return result
    }
}

class EnvelopeFormatException(message: String) : Exception(message)

/** Thrown when the key is wrong, or the envelope was tampered with. */
class EnvelopeDecryptException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** base64url, unpadded — what sync-kit writes. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(value: String): ByteArray =
        try {
            decoder.decode(value)
        } catch (cause: IllegalArgumentException) {
            throw EnvelopeFormatException("Invalid base64url value.")
        }
}

/**
 * HKDF-SHA-256, matching WebCrypto's `deriveKey({ name: "HKDF", hash: "SHA-256" })`.
 *
 * Deliberately fast and with no work factor, which is correct here only because
 * every input is a full-entropy random secret rather than a chosen passphrase.
 * Never feed this something a person invented.
 */
fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")

    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)

    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val output = ByteArrayOutputStream()
    var block = ByteArray(0)
    var counter = 1
    while (output.size() < length) {
        mac.update(block)
        mac.update(info)
        mac.update(counter.toByte())
        block = mac.doFinal()
        output.write(block)
        counter += 1
    }
    return output.toByteArray().copyOf(length)
}

/**
 * Seals and opens vault state under one derived key.
 *
 * Create it with [forRecoveryCode] for the printed-code path, or with
 * [forDerivedKey] for the passkey path — both platforms derive that one from
 * the same WebAuthn PRF, through their own `KeyProvider`.
 */
class VaultEnvelopeCipher internal constructor(
    private val key: SecretKeySpec,
    val metadata: EnvelopeMetadata,
) {

    fun seal(state: VaultState, updatedAt: String): SyncEnvelopeV1 {
        val plain = vaultJson.encodeToString(VaultState.serializer(), state).toByteArray(Charsets.UTF_8)

        // "gzip-if-smaller", as the profile specifies. Compressing a payload
        // that grows is pointless, and the flag must reflect what was done.
        val gzipped = gzip(plain)
        val useGzip = gzipped.size < plain.size
        val payload = if (useGzip) gzipped else plain

        val nonce = KeywebEnvelope.randomBytes(KeywebEnvelope.NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(KeywebEnvelope.AAD.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(payload)

        return SyncEnvelopeV1(
            compression = if (useGzip) "gzip" else null,
            credentialId = metadata.credentialId,
            rpId = metadata.rpId,
            prfInput = Base64Url.encode(metadata.prfInput),
            kdfSalt = Base64Url.encode(metadata.kdfSalt),
            nonce = Base64Url.encode(nonce),
            ciphertext = Base64Url.encode(ciphertext),
            updatedAt = updatedAt,
        )
    }

    fun open(envelope: SyncEnvelopeV1): VaultState {
        validate(envelope)
        val plain =
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, Base64Url.decode(envelope.nonce)),
                )
                cipher.updateAAD(KeywebEnvelope.AAD.toByteArray(Charsets.UTF_8))
                cipher.doFinal(Base64Url.decode(envelope.ciphertext))
            } catch (cause: Exception) {
                // Authentication failure is indistinguishable from a wrong key,
                // and must stay that way: saying which would help an attacker.
                throw EnvelopeDecryptException(
                    "That key could not open this backup.",
                    cause,
                )
            }

        val bytes = if (envelope.compression == "gzip") gunzip(plain) else plain
        return try {
            vaultJson.decodeFromString(VaultState.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (cause: Exception) {
            throw EnvelopeDecryptException("The backup opened but could not be read.", cause)
        }
    }

    companion object {
        /**
         * Derive the cipher from a printed recovery code.
         *
         * Pass the existing envelope so its salt is reused: the key is a
         * function of the code *and* the salt, so minting a fresh salt would
         * produce a key that cannot open the backup already in Drive.
         */
        fun forRecoveryCode(secret: ByteArray, existing: SyncEnvelopeV1? = null): VaultEnvelopeCipher {
            val metadata =
                if (existing != null) {
                    validate(existing)
                    EnvelopeMetadata(
                        credentialId = existing.credentialId,
                        rpId = existing.rpId,
                        prfInput = Base64Url.decode(existing.prfInput),
                        kdfSalt = Base64Url.decode(existing.kdfSalt),
                    )
                } else {
                    EnvelopeMetadata(
                        // Meaningless on this path -- no passkey is involved --
                        // but required by the format, and naming the path makes
                        // a stored envelope self-describing.
                        credentialId = "recovery",
                        rpId = "keyweb",
                        prfInput = KeywebEnvelope.randomBytes(KeywebEnvelope.PRF_BYTES),
                        kdfSalt = KeywebEnvelope.randomBytes(KeywebEnvelope.SALT_BYTES),
                    )
                }
            return fromMetadata(secret, metadata)
        }

        /**
         * The cipher for a backup sealed under a raw WebAuthn PRF output.
         *
         * [prfSecret] is the assertion's PRF bytes, *before* any derivation —
         * the HKDF below is what turns them into the content key.
         *
         * This is not what sync-kit's `KeyProvider.unlock` hands back. See
         * [forDerivedKey], and the comment there, which is the whole story of
         * why a phone and a browser holding the same passkey could not open
         * each other's copy.
         */
        fun forPasskeySecret(
            prfSecret: ByteArray,
            metadata: EnvelopeMetadata,
        ): VaultEnvelopeCipher = fromMetadata(prfSecret, metadata)

        /**
         * The cipher for a key that has already been derived.
         *
         * `KeyProvider.unlock` and `KeyProvider.create` do the HKDF themselves
         * — on both platforms, from the same profile, over the same salt — and
         * hand back the finished content key. The browser uses those bytes as
         * the key, exactly as they arrive. This phone ran them through the
         * HKDF a *second* time.
         *
         * So the browser held `HKDF(prf, salt)` and the phone held
         * `HKDF(HKDF(prf, salt), salt)`, and the passkey copy neither could
         * open was not a passkey problem at all: not Credential Manager
         * returning the wrong credential, not Google Password Manager failing
         * to sync one, not the asset link. One derivation too many, on one
         * side, which looks identical from the outside to every one of those.
         *
         * `PasskeyDerivationTest` pins both halves: that one derivation
         * matches the browser, and that two do not.
         */
        fun forDerivedKey(
            contentKey: ByteArray,
            metadata: EnvelopeMetadata,
        ): VaultEnvelopeCipher = VaultEnvelopeCipher(SecretKeySpec(contentKey, "AES"), metadata)

        internal fun fromMetadata(secret: ByteArray, metadata: EnvelopeMetadata): VaultEnvelopeCipher {
            val derived =
                hkdfSha256(
                    ikm = secret,
                    salt = metadata.kdfSalt,
                    info = KeywebEnvelope.HKDF_INFO.toByteArray(Charsets.UTF_8),
                    length = 32,
                )
            return VaultEnvelopeCipher(SecretKeySpec(derived, "AES"), metadata)
        }

        /** Reject anything that is not a v1 envelope before trusting its fields. */
        fun validate(envelope: SyncEnvelopeV1) {
            if (envelope.schemaVersion != 1 || envelope.algorithm != KeywebEnvelope.ALGORITHM) {
                throw EnvelopeFormatException("This is not a Keyweb v1 encrypted backup.")
            }
            if (envelope.compression != null && envelope.compression != "gzip") {
                throw EnvelopeFormatException("Unsupported compression: ${envelope.compression}.")
            }
            expectLength(envelope.nonce, KeywebEnvelope.NONCE_BYTES, "nonce")
            expectLength(envelope.kdfSalt, KeywebEnvelope.SALT_BYTES, "KDF salt")
            expectLength(envelope.prfInput, KeywebEnvelope.PRF_BYTES, "PRF input")
            if (envelope.ciphertext.isEmpty() || envelope.credentialId.isEmpty()) {
                throw EnvelopeFormatException("The encrypted backup is missing required fields.")
            }
        }

        private fun expectLength(value: String, expected: Int, label: String) {
            if (Base64Url.decode(value).size != expected) {
                throw EnvelopeFormatException("The v1 envelope $label has an invalid length.")
            }
        }
    }
}

/**
 * Lenient on read, exact on write.
 *
 * `encodeDefaults = false` keeps absent optional fields absent rather than
 * emitting nulls, matching what the TypeScript side produces.
 */
internal val vaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** The envelope itself, which is public metadata and never secret. */
val envelopeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

private fun gunzip(bytes: ByteArray): ByteArray =
    try {
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    } catch (cause: Exception) {
        throw EnvelopeDecryptException("The backup contains invalid gzip data.", cause)
    }

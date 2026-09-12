package app.keyweb.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.keyweb.vault.VaultCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption at rest, keyed by the Android Keystore.
 *
 * The key material is generated inside the TEE or StrongBox and never enters
 * the app's address space; only the ability to *use* it is granted, and only
 * after the user has authenticated. So a copy of the app's data directory —
 * from a rooted device, a backup, or forensic extraction — yields ciphertext
 * with no key attached.
 *
 * Two deliberate choices, both about not destroying someone's vault:
 *
 *  - the key survives biometric enrolment changes. The default behaviour is to
 *    permanently invalidate a key when a fingerprint is added, which for a
 *    password manager would silently destroy the vault the next time someone
 *    registers a new finger;
 *  - device credential (PIN, pattern, password) is accepted alongside
 *    biometrics, so a failed or unavailable sensor is not a lockout.
 *
 * One authentication authorises the key for [AUTH_VALIDITY_SECONDS], which is
 * what makes "unlock the vault, then use it" work without a prompt per write.
 */
private const val KEY_ALIAS = "keyweb-vault-v1"
private const val KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128
const val AUTH_VALIDITY_SECONDS = 300

class VaultKeyUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

object VaultKeystore {

    /** True once a vault key exists, i.e. this device has been set up. */
    fun exists(): Boolean = keyStore().containsAlias(KEY_ALIAS)

    /**
     * Create the vault key. Called once, during setup.
     *
     * Returns false when the device has no secure lock screen at all, which is
     * the one case where a user-authentication-bound key cannot be created.
     */
    fun create(): Boolean = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
                // Adding a fingerprint must not destroy the vault.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        generator.init(spec)
        generator.generateKey()
        true
    } catch (error: Exception) {
        // The usual cause is no secure lock screen. Report it rather than
        // falling back to an unprotected key, which would be a silent downgrade.
        false
    }

    fun delete() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey =
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)
            ?: throw VaultKeyUnavailable("This device has no Keyweb vault key.")

    /**
     * A cipher bound to the vault key.
     *
     * Every call touches the Keystore, so if the authentication window has
     * expired the platform throws and the app returns to the unlock screen
     * rather than silently continuing with a key it is no longer entitled to.
     */
    fun cipher(): VaultCipher = object : VaultCipher {

        override fun seal(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return encode(cipher.iv, ciphertext)
        }

        override fun open(sealed: String): String {
            val (iv, ciphertext) = decode(sealed)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }

    /** `v1.<iv>.<ciphertext>`, both base64. Versioned so the format can move. */
    private fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        "v1." + base64(iv) + "." + base64(ciphertext)

    private fun decode(sealed: String): Pair<ByteArray, ByteArray> {
        val parts = sealed.split(".")
        if (parts.size != 3 || parts[0] != "v1") {
            throw VaultKeyUnavailable("This vault entry is not in a format Keyweb understands.")
        }
        val iv = unBase64(parts[1])
        if (iv.size != IV_BYTES) throw VaultKeyUnavailable("This vault entry is damaged.")
        return iv to unBase64(parts[2])
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun unBase64(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
}

/** True when the failure means the user must authenticate again. */
fun needsAuthentication(error: Throwable): Boolean =
    error is android.security.keystore.UserNotAuthenticatedException ||
        error is KeyPermanentlyInvalidatedException ||
        error.cause?.let { needsAuthentication(it) } == true

package app.keyweb.data

import app.keyweb.vault.EnvelopeMetadata
import app.keyweb.vault.KeywebEnvelope
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.VaultOp
import app.keyweb.vault.applyOp
import app.keyweb.vault.emptyVault
import app.keyweb.vault.field
import com.keyneom.synckit.core.SyncCodec
import com.keyneom.synckit.crypto.PasskeyProfile
import com.keyneom.synckit.crypto.V1CompatibilityProfile
import com.keyneom.synckit.crypto.V1Compression
import com.keyneom.synckit.crypto.V1EnvelopeCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How many times the passkey secret gets derived, and by whom.
 *
 * The phone told somebody "that passkey isn't the one your backup was sealed
 * with" while holding the correct passkey for the correct backup. Every
 * explanation that fit pointed outward — Credential Manager returning a
 * different credential, Google Password Manager not syncing the passkey, the
 * `get_login_creds` asset link — and none of them was wrong.
 *
 * `KeyProvider.unlock` runs the profile's HKDF itself and returns the finished
 * content key. The browser uses those bytes as the key. This phone ran them
 * through the same HKDF a second time, so it held `HKDF(HKDF(prf, salt), salt)`
 * against the browser's `HKDF(prf, salt)` — two keys that could never open each
 * other's copy, for a reason invisible from outside the cipher.
 *
 * Both halves are pinned here: that one derivation agrees with sync-kit, and
 * that two do not.
 */
class PasskeyDerivationTest {

    private val profile = V1CompatibilityProfile(
        appId = "keyweb",
        filename = "keyweb-vault-v1.json",
        aad = KeywebEnvelope.AAD,
        hkdfInfo = KeywebEnvelope.HKDF_INFO,
        compression = V1Compression.GZIP_IF_SMALLER,
        passkey = PasskeyProfile(
            rpName = "Keyweb",
            userName = "vault",
            userDisplayName = "Keyweb vault",
        ),
    )

    private object UnusedCodec : SyncCodec<Unit> {
        override fun serialize(value: Unit): ByteArray = ByteArray(0)
        override fun parse(bytes: ByteArray) = Unit
        override fun merge(local: Unit, remote: Unit) = Unit
        override fun fingerprint(value: Unit): String = ""
        override fun updatedAt(value: Unit): String = "1970-01-01T00:00:00.000Z"
    }

    private val syncKit = V1EnvelopeCrypto(profile, UnusedCodec)

    private val prf = ByteArray(32) { it.toByte() }
    private val metadata = EnvelopeMetadata(
        credentialId = "cred",
        rpId = "keyneom.github.io",
        prfInput = ByteArray(32) { 7 },
        kdfSalt = ByteArray(32) { (it * 3).toByte() },
    )

    private fun vault() = applyOp(
        applyOp(
            emptyVault(),
            VaultOp.KeyringPut("k", "001700000000000-00000-t", "ring", "Household"),
        ),
        VaultOp.ItemPut(
            "i",
            "001700000000001-00000-t",
            "bank",
            "ring",
            mapOf("title" to "Credit Union", "password" to "the-one-that-matters"),
        ),
    )

    /** What the provider returns: the PRF put through the HKDF, once. */
    private fun whatTheProviderReturns(): ByteArray = syncKit.deriveContentKey(prf, metadata.kdfSalt)

    @Test
    fun `sync-kit's derivation is the one Keyweb implements`() {
        // If these ever diverge, no passkey backup opens anywhere, so it is
        // worth saying out loud rather than inferring from a failure later.
        val ours = app.keyweb.vault.hkdfSha256(
            ikm = prf,
            salt = metadata.kdfSalt,
            info = KeywebEnvelope.HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = 32,
        )
        assertContentEquals(ours, whatTheProviderReturns())
    }

    @Test
    fun `the provider's bytes are the key, not more material to derive from`() {
        // Sealed the way a browser seals it: one derivation over the raw PRF.
        val browsers = VaultEnvelopeCipher.forPasskeySecret(prf, metadata)
        val sealed = browsers.seal(vault(), updatedAt = "2026-09-19T00:00:00.000Z")

        val phone = VaultEnvelopeCipher.forDerivedKey(whatTheProviderReturns(), metadata)
        assertEquals("the-one-that-matters", phone.open(sealed).items["bank"]?.field("password"))
    }

    @Test
    fun `deriving a second time yields a key that opens nothing`() {
        val browsers = VaultEnvelopeCipher.forPasskeySecret(prf, metadata)
        val sealed = browsers.seal(vault(), updatedAt = "2026-09-19T00:00:00.000Z")

        // Precisely what this phone used to do with the provider's answer.
        val doubled = VaultEnvelopeCipher.forPasskeySecret(whatTheProviderReturns(), metadata)
        val failure = assertFailsWith<app.keyweb.vault.EnvelopeDecryptException> {
            doubled.open(sealed)
        }
        assertTrue(failure.message != null)
    }
}

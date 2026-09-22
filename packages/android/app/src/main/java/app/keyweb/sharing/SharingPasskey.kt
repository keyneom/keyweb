package app.keyweb.sharing

import android.app.Activity
import app.keyweb.vault.Base64Url
import com.keyneom.synckit.core.SyncCodec
import com.keyneom.synckit.crypto.PasskeyProfile
import com.keyneom.synckit.crypto.V1CompatibilityProfile
import com.keyneom.synckit.crypto.V1Compression
import com.keyneom.synckit.crypto.V1EnvelopeCrypto
import com.keyneom.synckit.crypto.V1KeyMetadata
import com.keyneom.synckit.keys.AndroidPasskeyKeyProvider
import com.keyneom.synckit.sharing.ProtectedSharingIdentityCrypto
import com.keyneom.synckit.sharing.ProtectedSharingIdentityResult
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import com.keyneom.synckit.sharing.SharingIdentity

/**
 * The key this person's sharing identity is wrapped with, from their passkey.
 *
 * ## Why this exists
 *
 * Sharing pins one key per person: a keyring shared from this phone has to be
 * manageable from that person's laptop, and everyone they shared with trusted
 * one key. So every device they own has to unwrap the *same* identity record,
 * which is why it lives in the Google account's app-data folder rather than on
 * a device.
 *
 * It was wrapped with the printed recovery code, on the reasoning that this app
 * could not derive a passkey secret. It can — the same one a browser derives,
 * pinned byte for byte by `PasskeyDerivationTest` — and until that changed a
 * browser holding the passkey could read every password in the vault and still
 * not touch a shared keyring, because it had never been handed a piece of paper.
 *
 * sync-kit ships the store and the provider for exactly this, and easy-bc has
 * used both from the start. Keyweb had the store and the wrong key.
 *
 * ## The profile
 *
 * Every value mirrors the web's `keywebSharingProfile`. They are the wire
 * format of the wrapping key: disagree on one and the two platforms derive
 * different keys from the same passkey, which looks exactly like the passkey
 * being wrong.
 */
class SharingPasskey(private val host: () -> Activity?) {

    private object UnusedCodec : SyncCodec<Unit> {
        override fun serialize(value: Unit): ByteArray = ByteArray(0)

        override fun parse(bytes: ByteArray) = Unit

        override fun merge(local: Unit, remote: Unit) = Unit

        override fun fingerprint(value: Unit): String = ""

        override fun updatedAt(value: Unit): String = "1970-01-01T00:00:00.000Z"
    }

    private val profile = V1CompatibilityProfile(
        appId = "keyweb-sharing",
        filename = "unused",
        aad = "keyweb-sharing-identity-v1",
        hkdfInfo = "keyweb-sharing-identity-wrap-v1",
        compression = V1Compression.NONE,
        passkey = PasskeyProfile(
            rpName = "Keyweb",
            userName = "sharing",
            userDisplayName = "Keyweb sharing",
        ),
    )

    private val provider by lazy {
        AndroidPasskeyKeyProvider(profile, RP_ID, V1EnvelopeCrypto(profile, UnusedCodec))
    }

    private fun activity(): Activity =
        host() ?: throw IllegalStateException("Open Keyweb and unlock it first.")

    /** The wrapping key for a record that already exists. */
    suspend fun keyFor(record: ProtectedSharingIdentityV1): ByteArray =
        provider.unlockMetadata(
            activity(),
            V1KeyMetadata(
                credentialId = record.credentialId,
                rpId = record.rpId,
                prfInput = Base64Url.decode(record.prfInput),
                kdfSalt = Base64Url.decode(record.kdfSalt),
            ),
        )

    /** A new identity, wrapped with a passkey made for it. */
    suspend fun create(appId: String): ProtectedSharingIdentityResult {
        val created = provider.create(activity(), "Keyweb sharing")
        return ProtectedSharingIdentityCrypto.create(appId, created.metadata, created.key, null)
    }

    /**
     * The same keypair, wrapped with the passkey instead of the printed code.
     *
     * The keypair is carried across rather than replaced, because it is what
     * everyone this vault has shared with has pinned. A fresh one would leave
     * them trusting a key nobody holds.
     */
    suspend fun wrap(appId: String, identity: SharingIdentity): ProtectedSharingIdentityResult {
        val created = provider.create(activity(), "Keyweb sharing")
        return ProtectedSharingIdentityCrypto.create(
            appId,
            created.metadata,
            created.key,
            identity,
        )
    }

    fun clear() = provider.clear()

    private companion object {
        /**
         * The domain the passkey belongs to — the web app's, and the same one
         * `VaultPasskey` uses, because it is the same credential.
         */
        const val RP_ID = "keyneom.github.io"
    }
}

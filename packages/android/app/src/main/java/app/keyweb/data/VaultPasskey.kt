package app.keyweb.data

import android.app.Activity
import app.keyweb.vault.EnvelopeMetadata
import app.keyweb.vault.KeywebEnvelope
import app.keyweb.vault.SyncEnvelopeV1
import app.keyweb.vault.VaultEnvelopeCipher
import com.keyneom.synckit.core.SyncCodec
import com.keyneom.synckit.core.SyncKitError
import com.keyneom.synckit.core.SyncKitErrorCode
import com.keyneom.synckit.crypto.PasskeyProfile
import com.keyneom.synckit.crypto.V1CompatibilityProfile
import com.keyneom.synckit.crypto.V1Compression
import com.keyneom.synckit.crypto.V1EnvelopeCrypto
import com.keyneom.synckit.keys.AndroidPasskeyKeyProvider

/**
 * The same passkey the browser uses, on the phone.
 *
 * ## Why this exists, late
 *
 * Keyweb was built believing an Android app could not reach a WebAuthn PRF
 * secret, and every awkward thing about its backup follows from that: the Drive
 * file is sealed twice, once under the browser's passkey and once under the
 * printed recovery code, because no key appeared to be reachable from both
 * sides. Each platform could only rewrite one of the two, so they drifted apart
 * — and a browser resealing the recovery envelope under its own code once
 * locked a phone out of its own backup.
 *
 * The belief was simply wrong. `AndroidPasskeyKeyProvider` ships in the
 * sync-kit-android this app already depends on, easy-bc has used it from the
 * start, and the only thing missing was a `get_login_creds` entry in the RP
 * domain's `assetlinks.json` — which Keyweb's own asset link deliberately
 * omitted, with a confident and inverted explanation of why.
 *
 * ## What it derives
 *
 * `unlock` hands back the content key, already derived: the provider runs the
 * profile's HKDF over the PRF output and the envelope's salt before returning,
 * exactly as the browser's provider does. So the bytes are used as the key —
 * see [VaultEnvelopeCipher.forDerivedKey] — and the two platforms arrive at the
 * same one, which is what makes the `passkey` envelope readable and writable
 * from either side.
 *
 * Deriving them a second time here was a real bug with a long tail: it looked
 * exactly like Credential Manager handing back the wrong credential, and sent
 * the search into asset links and passkey syncing, neither of which was ever
 * wrong.
 *
 * ## The RP id
 *
 * `keyneom.github.io`, the registrable domain the web app is served from. A
 * passkey is bound to it exactly, so this must match `keywebRpId()` on the web
 * or the phone will create a second credential rather than find the existing
 * one. Getting it wrong does not fail loudly; it simply never finds anything.
 */
object VaultPasskey {

    /**
     * The RP the passkey belongs to.
     *
     * The bare domain, not the `/keyweb/` path — an RP id is a domain and
     * cannot carry one. It is shared with easy-bc, which is noted rather than
     * liked: two apps under one RP id are distinguished only by credential id.
     */
    const val RP_ID = "keyneom.github.io"

    /**
     * Mirrors the web's `keywebV1Profile` exactly.
     *
     * Every value here is part of the wire format. Changing one makes existing
     * backups unreadable, and disagreeing with the browser over any of them
     * means the two derive different keys from the same passkey and neither
     * can open what the other wrote.
     */
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

    /**
     * A codec the provider requires and this use never exercises.
     *
     * `AndroidPasskeyKeyProvider` is constructed with an envelope crypto, which
     * is constructed with a payload codec — but nothing here seals a payload
     * through it. Keyweb's own [VaultEnvelopeCipher] does that, because the
     * envelope format is pinned byte for byte against the browser by fixtures
     * and must keep going through the implementation those fixtures cover.
     */
    private object UnusedCodec : SyncCodec<Unit> {
        override fun serialize(value: Unit): ByteArray = ByteArray(0)

        override fun parse(bytes: ByteArray) = Unit

        override fun merge(local: Unit, remote: Unit) = Unit

        override fun fingerprint(value: Unit): String = ""

        override fun updatedAt(value: Unit): String = "1970-01-01T00:00:00.000Z"
    }

    private val provider by lazy {
        AndroidPasskeyKeyProvider(profile, RP_ID, V1EnvelopeCrypto(profile, UnusedCodec))
    }

    /**
     * Raised when the phone has no passkey for this vault, or cannot use one.
     *
     * Its own type because the caller's response is specific: carry on with the
     * recovery envelope and say so, rather than treat the backup as broken.
     * A missing `get_login_creds` asset link surfaces from Credential Manager
     * as a bare "no credential", indistinguishable from "this person has never
     * made one" — which is exactly the signal that produced the wrong
     * conclusion this class exists to undo.
     */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Open an existing passkey envelope, giving back a cipher for it. */
    suspend fun unlock(activity: Activity, envelope: SyncEnvelopeV1): VaultEnvelopeCipher =
        try {
            // The provider has already run the HKDF. These bytes ARE the
            // content key, and putting them through it again is what made this
            // phone's key differ from the browser's over the same passkey.
            val contentKey = provider.unlock(activity, envelope.toSyncKit())
            VaultEnvelopeCipher.forDerivedKey(contentKey, envelope.toMetadata())
        } catch (cause: Exception) {
            throw Unavailable(explain(cause, "open the passkey that unlocks your backup"), cause)
        }

    /** Make a passkey for this vault, for a backup that has none yet. */
    suspend fun create(activity: Activity): VaultEnvelopeCipher =
        try {
            val created = provider.create(activity, "Keyweb vault")
            // Derived by the provider, exactly as in `unlock`.
            VaultEnvelopeCipher.forDerivedKey(
                created.key,
                EnvelopeMetadata(
                    credentialId = created.metadata.credentialId,
                    rpId = created.metadata.rpId,
                    prfInput = created.metadata.prfInput,
                    kdfSalt = created.metadata.kdfSalt,
                ),
            )
        } catch (cause: Exception) {
            throw Unavailable(explain(cause, "make a passkey for your backup"), cause)
        }

    /**
     * Say what actually went wrong, when the library knows.
     *
     * sync-kit 0.4.2 raises `SyncKitErrorCode.KEY` with a message naming the
     * asset-link fix, precisely because the platform exception underneath it
     * cannot tell "this app is not authorised for that domain" from "this
     * person has no passkey". Before that, both arrived as a bare
     * `NoCredentialException` — and reading it as the second is the mistake
     * that had this whole app built around a limitation that did not exist.
     *
     * So the library's own words are passed through rather than replaced with
     * a tidier sentence of ours. It is hedged on purpose at the source, and
     * flattening that hedge into something confident would repeat the original
     * error in the opposite direction.
     */
    private fun explain(cause: Exception, attempt: String): String {
        if (cause is SyncKitError && cause.code == SyncKitErrorCode.KEY) {
            return cause.message ?: "This phone couldn't $attempt."
        }
        return "This phone couldn't $attempt."
    }

    /** Drop the cached key, so the next use asks again. */
    fun clear() = provider.clear()
}

/** Keyweb's envelope as sync-kit's, for the one call that needs its shape. */
private fun SyncEnvelopeV1.toSyncKit(): com.keyneom.synckit.crypto.SyncEnvelopeV1 =
    com.keyneom.synckit.crypto.SyncEnvelopeV1(
        schemaVersion = schemaVersion,
        algorithm = algorithm,
        compression = compression,
        credentialId = credentialId,
        rpId = rpId,
        prfInput = prfInput,
        kdfSalt = kdfSalt,
        nonce = nonce,
        ciphertext = ciphertext,
        updatedAt = updatedAt,
    )

private fun SyncEnvelopeV1.toMetadata(): EnvelopeMetadata =
    EnvelopeMetadata(
        credentialId = credentialId,
        rpId = rpId,
        prfInput = app.keyweb.vault.Base64Url.decode(prfInput),
        kdfSalt = app.keyweb.vault.Base64Url.decode(kdfSalt),
    )

package app.keyweb.sharing

import com.keyneom.synckit.sharing.SharingDatasetFileV1
import com.keyneom.synckit.sharing.SharingInvitationV1
import com.keyneom.synckit.sharing.SharingPublicKeyResponseV1
import com.keyneom.synckit.sharing.buildSharingJoinLinkV1
import com.keyneom.synckit.sharing.buildSharingResponseLinkV1
import com.keyneom.synckit.sharing.parseSharingJoinLinkV1
import com.keyneom.synckit.sharing.parseSharingResponseLinkV1

/**
 * The two links a share travels on.
 *
 * There is no Keyweb server, so there is nowhere to park a pending invitation.
 * The exchange rides in the links themselves: the owner sends one, the
 * recipient sends one back, and the handshake is done. People pass them over
 * whatever they already use to talk to each other.
 *
 * Neither link is worth much on its own. The invitation carries the owner's
 * *public* key and a signature; the reply carries the recipient's *public* key.
 * Someone who intercepts both still cannot read a keyring, because the content
 * key is only ever wrapped to a public key whose private half never leaves its
 * owner's device. What they could do is substitute their own key for the
 * recipient's — which is what the read-aloud fingerprint is for.
 *
 * A port of `packages/web/src/vault/sharing/links.ts`, and it has to be one:
 * a link made on a phone is opened in a browser and the other way round.
 */

/** Where links land. The app claims this as an App Link. */
const val KEYWEB_LANDING_URL = "https://keyneom.github.io/keyweb/"

private const val OWNER_PARAM = "owner"
private const val LABEL_PARAM = "kw-name"

data class KeywebJoinLink(
    val invitation: SharingInvitationV1,
    val files: List<SharingDatasetFileV1>,
    /** Who sent it, for the preview. Never trusted for anything cryptographic. */
    val ownerEmail: String?,
    /** What the keyring is called, so the preview can name it before joining. */
    val label: String?,
)

object ShareLinks {

    fun buildJoin(
        invitation: SharingInvitationV1,
        files: List<SharingDatasetFileV1>,
        label: String,
        ownerEmail: String? = null,
        landing: String = KEYWEB_LANDING_URL,
    ): String {
        val base = buildSharingJoinLinkV1(landing, invitation, files)
        val extra = StringBuilder()
        // The name is a label only. It is never read as the keyring's real
        // name: that comes from the shared document, which is signed. A link
        // claiming to be "Household" must not be able to rename anything.
        extra.append("&").append(LABEL_PARAM).append("=").append(encode(label))
        if (!ownerEmail.isNullOrBlank()) {
            extra.append("&").append(OWNER_PARAM).append("=").append(encode(ownerEmail))
        }
        return base + extra
    }

    fun buildResponse(
        response: SharingPublicKeyResponseV1,
        landing: String = KEYWEB_LANDING_URL,
    ): String = buildSharingResponseLinkV1(landing, response)

    fun parseJoin(url: String): KeywebJoinLink? {
        val parsed = runCatching { parseSharingJoinLinkV1(url) }.getOrNull() ?: return null
        val params = queryParams(url)
        return KeywebJoinLink(
            invitation = parsed.invitation,
            files = parsed.files,
            ownerEmail = params[OWNER_PARAM],
            label = params[LABEL_PARAM],
        )
    }

    fun parseResponse(url: String): SharingPublicKeyResponseV1? =
        runCatching { parseSharingResponseLinkV1(url) }.getOrNull()?.response

    /** True for a URL this app should handle as a share rather than as a page. */
    fun isShareLink(url: String?): Boolean =
        url != null && (parseJoin(url) != null || parseResponse(url) != null)

    /**
     * Query parameters, without `android.net.Uri`.
     *
     * The platform class is an unimplemented stub in a JVM unit test and throws
     * rather than parsing, so using it here would make link handling testable
     * only on a device — and a link that parses wrong is exactly the kind of
     * thing worth a test rather than a phone.
     */
    private fun queryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=', "")
            if (name.isEmpty()) return@mapNotNull null
            name to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

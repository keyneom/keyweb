package app.keyweb.sharing

import com.keyneom.synckit.sharing.CreateSharingInvitationInput
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingDatasetFileV1
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The two links a share travels on.
 *
 * A link built on a phone is opened in a browser and the other way round, so
 * what matters is that both ends carry the same things and that neither reads
 * anything from a link that a link has no right to decide.
 */
class ShareLinksTest {

    private val owner = SharingCrypto.generateIdentity()
    private val joiner = SharingCrypto.generateIdentity()

    private fun invitation(datasetId: String, role: SharingRole) =
        SharingCrypto.createSharingInvitationV1(
            owner,
            CreateSharingInvitationInput(
                appId = "keyweb",
                appFolderId = "folder-1",
                exchangeId = "exchange-1",
                recipientDrivePermissionId = "permission-1",
                requestedGrants = listOf(SharingDatasetGrantV1(datasetId, role)),
            ),
        )

    @Test
    fun `carries the invitation and the files there and back`() {
        val files = listOf(SharingDatasetFileV1("ds-1", "file-1", SharingRole.WRITER))
        val link = ShareLinks.buildJoin(
            invitation = invitation("ds-1", SharingRole.WRITER),
            files = files,
            label = "Household",
            ownerEmail = "leslie@example.com",
            landing = "https://example.test/keyweb/",
        )

        val parsed = assertNotNull(ShareLinks.parseJoin(link))
        assertEquals("exchange-1", parsed.invitation.exchangeId)
        assertEquals(files, parsed.files)
        assertEquals("leslie@example.com", parsed.ownerEmail)
        assertEquals("Household", parsed.label)
    }

    @Test
    fun `survives a name with spaces and punctuation in it`() {
        val link = ShareLinks.buildJoin(
            invitation = invitation("ds-1", SharingRole.VIEWER),
            files = listOf(SharingDatasetFileV1("ds-1", "file-1", SharingRole.VIEWER)),
            label = "Mum & Dad's things",
            landing = "https://example.test/keyweb/",
        )
        assertEquals("Mum & Dad's things", assertNotNull(ShareLinks.parseJoin(link)).label)
    }

    @Test
    fun `carries the reply back`() {
        val response = SharingCrypto.createSharingPublicKeyResponseV1(
            joiner,
            "keyweb",
            "exchange-1",
        )
        val link = ShareLinks.buildResponse(response, landing = "https://example.test/keyweb/")
        assertEquals(response.keyId, assertNotNull(ShareLinks.parseResponse(link)).keyId)
    }

    @Test
    fun `is not a share link when it is just the app`() {
        assertNull(ShareLinks.parseJoin("https://keyneom.github.io/keyweb/"))
        assertNull(ShareLinks.parseResponse("https://keyneom.github.io/keyweb/?grant=import"))
        assertEquals(false, ShareLinks.isShareLink("https://keyneom.github.io/keyweb/"))
        assertEquals(false, ShareLinks.isShareLink(null))
    }

    /** The grant hand-off is a file list, never the join link itself. */
    @Test
    fun `the browser hand-off carries files and no invitation`() {
        val url = app.keyweb.data.GrantBrowser.shareGrantUrl("abc123")
        assertEquals(true, url.contains("grant=share"))
        assertEquals(true, url.contains("sk-files=abc123"))
        assertNull(ShareLinks.parseJoin(url))
    }
}

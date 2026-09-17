package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Files attached to a password.
 *
 * Stored as ordinary items so they inherit the vault's guarantees rather than
 * re-earning them. Parity with the web's `attachments.test.ts`, question for
 * question — a file that behaves differently on the phone is a file somebody
 * cannot open on the device they need it on.
 */
class AttachmentsTest {

    private class Rig {
        val storage = MemoryVaultStorage()
        val vaultRemote = FakeRemote()
        val datasetRemote = FakeRemote()
        val sync = VaultSync(
            storage = storage,
            remote = vaultRemote,
            remoteFor = { id ->
                when (id) {
                    VAULT_DOCUMENT -> vaultRemote
                    "ds-house" -> datasetRemote
                    else -> null
                }
            },
            clock = Clock("test"),
        )
    }

    private suspend fun withScan(): Rig {
        val rig = Rig()
        rig.sync.putKeyring(keyringId = "house", name = "Household")
        rig.sync.putItem(itemId = "passport", keyringId = "house", fields = mapOf("title" to "Passport"))
        rig.sync.attachFile(
            itemId = "passport",
            keyringId = "house",
            blobId = "blob:abc123",
            name = "scan.jpg",
            type = "image/jpeg",
            data = "PRETEND-JPEG-BYTES",
            bytes = 18,
        )
        return rig
    }

    @Test
    fun `shows up on the password it belongs to`() = runTest {
        val state = withScan().sync.state()
        assertEquals(
            listOf(Attachment("blob:abc123", "scan.jpg")),
            state.items.getValue("passport").attachments(),
        )
    }

    /** A file is an item, but it is not a password and must not look like one. */
    @Test
    fun `does not appear in the password list`() = runTest {
        val state = withScan().sync.state()
        assertEquals(listOf("passport"), visibleItems(state).map { it.id })
        assertTrue(state.items.getValue("blob:abc123").isBlob())
    }

    @Test
    fun `stores one copy of a file attached twice`() = runTest {
        val rig = withScan()
        rig.sync.putItem(itemId = "visa", keyringId = "house", fields = mapOf("title" to "Visa"))
        rig.sync.attachFile(
            itemId = "visa",
            keyringId = "house",
            blobId = "blob:abc123",
            name = "scan.jpg",
            type = "image/jpeg",
            data = "PRETEND-JPEG-BYTES",
            bytes = 18,
        )
        assertEquals(
            listOf("blob:abc123"),
            rig.sync.state().items.keys.filter { it.startsWith("blob:") },
        )
    }

    @Test
    fun `removing a file takes the bytes with it`() = runTest {
        val rig = withScan()
        rig.sync.removeAttachment("passport", "blob:abc123")

        val state = rig.sync.state()
        assertTrue(state.items.getValue("passport").attachments().isEmpty())
        assertEquals("", state.items.getValue("blob:abc123").field("secret:data"))
        assertTrue(state.items.getValue("blob:abc123").deleted.value)
    }

    @Test
    fun `removing keeps the bytes while another password still wants them`() = runTest {
        val rig = withScan()
        rig.sync.putItem(itemId = "visa", keyringId = "house", fields = mapOf("title" to "Visa"))
        rig.sync.attachFile(
            itemId = "visa",
            keyringId = "house",
            blobId = "blob:abc123",
            name = "scan.jpg",
            type = "image/jpeg",
            data = "PRETEND-JPEG-BYTES",
            bytes = 18,
        )
        rig.sync.removeAttachment("passport", "blob:abc123")

        val state = rig.sync.state()
        assertEquals("PRETEND-JPEG-BYTES", state.items.getValue("blob:abc123").field("secret:data"))
    }

    @Test
    fun `deleting the password takes its file`() = runTest {
        val rig = withScan()
        rig.sync.deleteItem("passport")
        assertEquals("", rig.sync.state().items.getValue("blob:abc123").field("secret:data"))
    }

    @Test
    fun `deleting the keyring takes its files`() = runTest {
        val rig = withScan()
        rig.sync.deleteKeyringWithItems("house")
        assertEquals("", rig.sync.state().items.getValue("blob:abc123").field("secret:data"))
    }

    /**
     * Sharing a keyring has to share its files. They are items on the keyring,
     * so this works for nothing — but it is exactly what would have needed
     * rebuilding had files been stored anywhere else.
     */
    @Test
    fun `a file moves into the shared document with its password`() = runTest {
        val rig = withScan()
        rig.sync.bindKeyring("house", "ds-house")

        val dataset = rig.storage.readState("ds-house")
        assertEquals("PRETEND-JPEG-BYTES", dataset.items.getValue("blob:abc123").field("secret:data"))

        // And the private vault keeps a scrubbed tombstone rather than the scan.
        val vault = rig.storage.readState(VAULT_DOCUMENT)
        assertEquals("", vault.items.getValue("blob:abc123").field("secret:data"))
    }

    @Test
    fun `a file is published to the shared file, not the private one`() = runTest {
        val rig = withScan()
        rig.sync.bindKeyring("house", "ds-house")
        rig.sync.sync()

        assertEquals(
            "PRETEND-JPEG-BYTES",
            rig.datasetRemote.snapshot().items.getValue("blob:abc123").field("secret:data"),
        )
        assertEquals(
            "",
            rig.vaultRemote.snapshot().items.getValue("blob:abc123").field("secret:data"),
        )
    }
}

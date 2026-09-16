package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A keyring that lives in its own document.
 *
 * The same questions the web suite asks, because an edit written to the wrong
 * document does not throw — it just never reaches the other side, on whichever
 * platform got it wrong.
 */
class MultiDocumentTest {

    private class Rig {
        val storage = MemoryVaultStorage()
        val vaultRemote = FakeRemote()
        val houseRemote = FakeRemote()
        val sync = VaultSync(
            storage = storage,
            remote = vaultRemote,
            remoteFor = { documentId ->
                when (documentId) {
                    VAULT_DOCUMENT -> vaultRemote
                    "ds-house" -> houseRemote
                    else -> null
                }
            },
            clock = Clock("test"),
        )
    }

    private suspend fun bound(): Rig {
        val rig = Rig()
        rig.sync.putKeyring(keyringId = "personal", name = "Just mine")
        rig.sync.putKeyring(keyringId = "house", name = "Household")
        rig.sync.putItem(itemId = "bank", keyringId = "personal", fields = mapOf("title" to "Bank"))
        val stamp = rig.sync.stamp()
        rig.sync.commitAll(listOf(VaultOp.KeyringBind(stamp.opId, stamp.ts, "house", "ds-house")))
        return rig
    }

    @Test
    fun `puts its items in that document, not the vault`() = runTest {
        val rig = bound()
        rig.sync.putItem(itemId = "wifi", keyringId = "house", fields = mapOf("title" to "Wifi"))

        assertNull(rig.storage.readState(VAULT_DOCUMENT).items["wifi"])
        assertNotNull(rig.storage.readState("ds-house").items["wifi"])
        // The one that is not in a bound keyring stays in the vault.
        assertNotNull(rig.storage.readState(VAULT_DOCUMENT).items["bank"])
    }

    @Test
    fun `still reads as one vault`() = runTest {
        val rig = bound()
        rig.sync.putItem(itemId = "wifi", keyringId = "house", fields = mapOf("title" to "Wifi"))

        assertEquals(listOf("bank", "wifi"), visibleItems(rig.sync.state()).map { it.id }.sorted())
    }

    @Test
    fun `publishes each document to its own remote`() = runTest {
        val rig = bound()
        rig.sync.putItem(itemId = "wifi", keyringId = "house", fields = mapOf("title" to "Wifi"))
        rig.sync.sync()

        // The shared keyring's file holds its passwords, and the vault's does
        // not. That is the whole point: the file that gets shared must not
        // carry everything else.
        assertNotNull(rig.houseRemote.snapshot().items["wifi"])
        assertNull(rig.vaultRemote.snapshot().items["wifi"])
        assertNotNull(rig.vaultRemote.snapshot().items["bank"])
    }

    @Test
    fun `counts work queued in every document as still to back up`() = runTest {
        val rig = bound()
        rig.sync.putItem(itemId = "wifi", keyringId = "house", fields = mapOf("title" to "Wifi"))
        assertTrue(rig.sync.status().pending > 0)

        rig.sync.sync()
        assertEquals(0, rig.sync.status().pending)
    }

    @Test
    fun `deleting a password in a shared keyring reaches that document`() = runTest {
        val rig = bound()
        rig.sync.putItem(itemId = "wifi", keyringId = "house", fields = mapOf("title" to "Wifi"))
        rig.sync.sync()
        rig.sync.deleteItem("wifi")
        rig.sync.sync()

        // The tombstone has to land where the item is, or the other side keeps it.
        assertTrue(rig.houseRemote.snapshot().items.getValue("wifi").deleted.value)
        assertEquals(listOf("bank"), visibleItems(rig.sync.state()).map { it.id })
    }

    @Test
    fun `a keyring with nowhere to publish keeps its edits queued`() = runTest {
        // Bound locally but no Drive file yet: the edits wait, as an offline
        // vault's do, rather than being lost or landing in the vault.
        val storage = MemoryVaultStorage()
        val sync = VaultSync(
            storage = storage,
            remote = FakeRemote(),
            remoteFor = { documentId -> if (documentId == VAULT_DOCUMENT) FakeRemote() else null },
            clock = Clock("test"),
        )
        sync.putKeyring(keyringId = "house", name = "Household")
        val stamp = sync.stamp()
        sync.commitAll(listOf(VaultOp.KeyringBind(stamp.opId, stamp.ts, "house", "ds-house")))
        sync.putItem(itemId = "wifi", keyringId = "house", fields = mapOf("title" to "Wifi"))

        sync.sync()
        assertTrue(storage.pending("ds-house").isNotEmpty())
        // And it is visible locally meanwhile.
        assertEquals(listOf("wifi"), visibleItems(sync.state()).map { it.id })
    }

    @Test
    fun `a vault that shares nothing touches one document`() = runTest {
        // The change has to be invisible to everyone who has not shared anything.
        val rig = Rig()
        rig.sync.putKeyring(keyringId = "personal", name = "Just mine")
        rig.sync.putItem(itemId = "bank", keyringId = "personal", fields = mapOf("title" to "Bank"))
        rig.sync.sync()

        assertEquals(emptyList(), rig.storage.knownDocuments())
    }
}

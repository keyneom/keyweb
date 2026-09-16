package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Moving a keyring into its own document, and back out of it.
 *
 * This is the step that turns a private keyring into a shareable one, and the
 * only place in the app where passwords are deliberately taken out of one file
 * and put into another. Every failure here is a lost password, so these tests
 * care mostly about what is true *between* the two writes.
 *
 * Parity with the web's `binding.test.ts`, question for question.
 */
class BindingTest {

    private class Rig {
        val storage = MemoryVaultStorage()
        val vaultRemote = FakeRemote()
        val datasetRemote = FakeRemote()
        val sync = VaultSync(
            storage = storage,
            remote = vaultRemote,
            remoteFor = { documentId ->
                when (documentId) {
                    VAULT_DOCUMENT -> vaultRemote
                    "ds-house" -> datasetRemote
                    else -> null
                }
            },
            clock = Clock("test"),
        )
    }

    private suspend fun household(): Rig {
        val rig = Rig()
        rig.sync.putKeyring(keyringId = "personal", name = "Just mine")
        rig.sync.putKeyring(keyringId = "house", name = "Household")
        rig.sync.putItem(itemId = "bank", keyringId = "personal", fields = mapOf("title" to "Bank"))
        rig.sync.putItem(
            itemId = "wifi",
            keyringId = "house",
            fields = mapOf("title" to "Wifi", "password" to "hunter2"),
        )
        rig.sync.putItem(itemId = "gas", keyringId = "house", fields = mapOf("title" to "Gas"))
        return rig
    }

    @Test
    fun `moves its passwords out of the vault and nothing else`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")

        val vault = rig.storage.readState(VAULT_DOCUMENT)
        val dataset = rig.storage.readState("ds-house")

        assertEquals(listOf("gas", "wifi"), dataset.items.keys.sorted())
        assertEquals("hunter2", dataset.items.getValue("wifi").field("password"))

        // What stays behind in the vault is a scrubbed tombstone, not the
        // password. A record has to stay — a CRDT has no way to say "gone"
        // except by saying it — but it carries no value any more.
        assertTrue(vault.items.getValue("wifi").deleted.value)
        assertEquals("", vault.items.getValue("wifi").field("password"))
        assertTrue(vault.items.getValue("wifi").history.isEmpty())
        assertTrue(vault.items.getValue("gas").deleted.value)

        // The keyring that was not shared is untouched, in the file it was in.
        assertTrue(!vault.items.getValue("bank").deleted.value)
    }

    @Test
    fun `keeps the vault able to name what it no longer holds`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")

        val vault = rig.storage.readState(VAULT_DOCUMENT)
        assertEquals("Household", vault.keyrings.getValue("house").name.value)
        assertEquals("ds-house", datasetOf(vault.keyrings["house"]))
    }

    @Test
    fun `changes nothing a person can see`() = runTest {
        val rig = household()
        val before = visibleItems(rig.sync.state()).map { it.id }.sorted()

        rig.sync.bindKeyring("house", "ds-house")

        val after = rig.sync.state()
        assertEquals(before, visibleItems(after).map { it.id }.sorted())
        assertEquals("hunter2", after.items.getValue("wifi").field("password"))
    }

    /**
     * The crash-safety property the ordering exists for. If the process dies
     * between the two writes, the passwords must still be somewhere — and the
     * only way to guarantee that is to write the copy before removing the
     * original.
     */
    @Test
    fun `never has the passwords in neither document`() = runTest {
        val rig = Rig()
        val seen = mutableListOf<String>()
        val failing = object : VaultStorage by rig.storage {
            override suspend fun commitAll(
                ops: List<VaultOp>,
                nextState: VaultState,
                documentId: String,
            ) {
                // Refuse the second write, exactly as a crash would.
                if (seen.isNotEmpty()) error("crashed")
                seen += documentId
                rig.storage.commitAll(ops, nextState, documentId)
            }
        }
        val sync = VaultSync(storage = failing, remote = rig.vaultRemote, clock = Clock("test"))
        rig.storage.commitAll(
            listOf(
                VaultOp.KeyringPut("k", "001700000000000-00000-test", "house", "Household"),
                VaultOp.ItemPut(
                    "i",
                    "001700000000000-00001-test",
                    "wifi",
                    "house",
                    mapOf("password" to "hunter2"),
                ),
            ),
            applyOps(
                emptyVault(),
                listOf(
                    VaultOp.KeyringPut("k", "001700000000000-00000-test", "house", "Household"),
                    VaultOp.ItemPut(
                        "i",
                        "001700000000001-00001-test",
                        "wifi",
                        "house",
                        mapOf("password" to "hunter2"),
                    ),
                ),
            ),
            VAULT_DOCUMENT,
        )

        assertFailsWith<IllegalStateException> { sync.bindKeyring("house", "ds-house") }

        // The dataset was written first, so the passwords survived.
        assertEquals(listOf("ds-house"), seen)
        assertEquals("hunter2", rig.storage.readState("ds-house").items.getValue("wifi").field("password"))
        // And the vault still has them too, because the removal never happened.
        assertEquals(
            "hunter2",
            rig.storage.readState(VAULT_DOCUMENT).items.getValue("wifi").field("password"),
        )
    }

    @Test
    fun `refuses to bind a keyring twice`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")
        assertFailsWith<IllegalStateException> { rig.sync.bindKeyring("house", "ds-other") }
    }

    /**
     * The bug this caught: binding changes nothing else in the vault, so a
     * fingerprint that ignored the binding reported "unchanged" and the sync
     * skipped the upload. Other devices would then never learn the keyring had
     * moved, and would keep looking for its passwords in the vault.
     */
    @Test
    fun `changes the vault's fingerprint, so it gets published`() = runTest {
        val rig = household()
        rig.sync.sync()
        val before = fingerprint(rig.storage.readState(VAULT_DOCUMENT))

        rig.sync.bindKeyring("house", "ds-house")
        assertTrue(fingerprint(rig.storage.readState(VAULT_DOCUMENT)) != before)

        val writes = rig.vaultRemote.writes
        rig.sync.sync()
        assertTrue(rig.vaultRemote.writes > writes)
        assertEquals("ds-house", datasetOf(rig.vaultRemote.snapshot().keyrings["house"]))
    }

    @Test
    fun `publishes the keyring's passwords to its own remote and no other`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")
        rig.sync.sync()

        assertEquals(listOf("gas", "wifi"), rig.datasetRemote.snapshot().items.keys.sorted())
        assertEquals("hunter2", rig.datasetRemote.snapshot().items.getValue("wifi").field("password"))
        // The backup of the private vault keeps no copy of the shared password.
        assertEquals("", rig.vaultRemote.snapshot().items.getValue("wifi").field("password"))
        assertTrue(!rig.vaultRemote.snapshot().items.getValue("bank").deleted.value)
    }

    /** A rename is part of what was shared, so it has to travel with the items. */
    @Test
    fun `sends a later rename to the shared document`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")
        rig.sync.putKeyring(keyringId = "house", name = "Ours")

        assertEquals("Ours", rig.storage.readState("ds-house").keyrings.getValue("house").name.value)
        assertEquals("Ours", rig.sync.state().keyrings.getValue("house").name.value)
    }

    @Test
    fun `unbinding brings the passwords back into the vault`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")
        rig.sync.unbindKeyring("house")

        val vault = rig.storage.readState(VAULT_DOCUMENT)
        assertEquals("hunter2", vault.items.getValue("wifi").field("password"))
        assertTrue(!vault.items.getValue("wifi").deleted.value)
        assertNull(datasetOf(vault.keyrings["house"]))
    }

    @Test
    fun `leaving hides it here without deleting anyone else's passwords`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")
        rig.sync.sync()

        rig.sync.leaveKeyring("house")

        assertEquals(listOf("bank"), visibleItems(rig.sync.state()).map { it.id })

        rig.sync.sync()
        val shared = rig.datasetRemote.snapshot()
        assertEquals("hunter2", shared.items.getValue("wifi").field("password"))
        assertTrue(!shared.items.getValue("wifi").deleted.value)
        assertTrue(!shared.keyrings.getValue("house").deleted.value)

        // The tombstone is local: it lives in the vault, not in what was shared.
        assertTrue(rig.storage.readState(VAULT_DOCUMENT).keyrings.getValue("house").deleted.value)
    }

    @Test
    fun `moving a password out of a shared keyring takes it away`() = runTest {
        val rig = household()
        rig.sync.bindKeyring("house", "ds-house")
        rig.sync.sync()
        assertEquals("hunter2", rig.datasetRemote.snapshot().items.getValue("wifi").field("password"))

        rig.sync.moveItem("wifi", "personal")
        rig.sync.sync()

        val shared = assertNotNull(rig.datasetRemote.snapshot().items["wifi"])
        assertTrue(shared.deleted.value)
        assertEquals("", shared.field("password"))
        assertTrue(shared.history.isEmpty())

        // And it is still readable where it moved to.
        val state = rig.sync.state()
        assertEquals("personal", state.items.getValue("wifi").keyring.value)
        assertEquals("hunter2", state.items.getValue("wifi").field("password"))
    }
}

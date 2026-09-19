package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * A password saved against a keyring that is not there.
 *
 * This is the shape of a real report: a password added in the browser, saved
 * successfully, and then absent from the list on that browser *and* on the
 * phone it synced to. Nothing had gone wrong with the sync — the item was in
 * the vault the whole time. Every screen filtered it out, because the list was
 * built from "items on a keyring that exists" and its keyring did not.
 *
 * The rule now is narrower and much harder to lose something under: an item
 * that is not deleted is shown. Where it lives is a question about how to
 * label it, never about whether it is real. Parity with the web's
 * `homeless-items.test.ts`.
 */
class HomelessItemsTest {

    private fun at(n: Int): Hlc =
        encodeHlc(HlcParts(wall = 1_700_000_000_000L + n, counter = 0, node = "test"))

    /** One real keyring, and three ways of ending up without one. */
    private fun vault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            VaultOp.KeyringPut("k1", at(0), "mine", "Just mine"),
            VaultOp.KeyringPut("k2", at(1), "old", "Old"),
            VaultOp.ItemPut("i1", at(2), "kept", "mine", mapOf("title" to "Bank")),
            // The one the report is about: a stale default that names no keyring.
            VaultOp.ItemPut("i2", at(3), "stray", "personal", mapOf("title" to "New password")),
            // Saved on another device onto a keyring this one had already deleted.
            VaultOp.ItemPut("i3", at(4), "late", "old", mapOf("title" to "Late arrival")),
            VaultOp.KeyringDelete("k3", at(5), "old"),
        ),
    )

    @Test
    fun `is in the list rather than filtered out of existence`() {
        val titles = visibleItems(vault()).map { it.field("title") }
        assertContains(titles, "New password")
    }

    @Test
    fun `is named as one that needs a keyring, not left blank`() {
        val state = vault()
        assertEquals(NO_KEYRING, keyringLabel(state, "personal"))
        assertEquals("Just mine", keyringLabel(state, "mine"))
    }

    @Test
    fun `counts as one somebody should be offered a keyring for`() {
        assertEquals(listOf("late", "stray"), itemsWithoutKeyring(vault()).map { it.id }.sorted())
    }

    @Test
    fun `does not drag a deleted keyring's name back onto the screen`() {
        assertEquals(NO_KEYRING, keyringLabel(vault(), "old"))
        assertEquals(listOf("mine"), liveKeyrings(vault()).map { it.id })
    }

    @Test
    fun `has a place of its own in the folder tree`() {
        val state = vault()
        val top = browseFolders(visibleItems(state), state, emptyList())
        assertEquals(listOf("Just mine", NO_KEYRING).sorted(), top.folders.map { it.name }.sorted())
        val inside = browseFolders(visibleItems(state), state, listOf(NO_KEYRING))
        assertEquals(listOf("late", "stray"), inside.items.map { it.id }.sorted())
    }

    @Test
    fun `comes out in an export instead of being silently left behind`() {
        val exported = exportVault(vault(), "2026-09-18T00:00:00Z")
        assertEquals(NO_KEYRING, exported.items.first { it.id == "stray" }.keyringName)
    }

    @Test
    fun `stops being homeless once it is moved`() {
        val moved = applyOps(
            vault(),
            listOf(VaultOp.ItemMove("m1", at(6), "stray", "mine")),
        )
        assertEquals(listOf("late"), itemsWithoutKeyring(moved).map { it.id })
    }

    @Test
    fun `still hides a password that was actually deleted`() {
        val deleted = applyOps(vault(), listOf(VaultOp.ItemDelete("d1", at(6), "stray")))
        assertFalse(visibleItems(deleted).any { it.id == "stray" })
        assertEquals(listOf("late"), itemsWithoutKeyring(deleted).map { it.id })
    }
}

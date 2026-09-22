package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * What the undo window keeps when two devices edit at once.
 *
 * A single device archives what it overwrites. Two devices editing the same
 * field offline produce no such moment on either of them — each saw only its
 * own write — so the merge is the only place the losing value can be kept.
 * Parity with the web's `merge-history.test.ts`; these two implementations
 * have to agree, because the same vault passes through both.
 */
class MergeHistoryTest {

    private fun at(node: String, n: Int): Hlc =
        encodeHlc(HlcParts(wall = 1_700_000_000_000L + n, counter = 0, node = node))

    private fun past(item: ItemRecord, field: ItemField): List<String> =
        item.history.filter { it.field == field && it.value.isNotEmpty() }.map { it.value }

    private fun diverged(left: String, right: String): Pair<VaultState, VaultState> {
        val base = applyOps(
            emptyVault(),
            listOf(
                VaultOp.KeyringPut("k", at("a", 0), "ring", "Just mine"),
                VaultOp.ItemPut(
                    "i",
                    at("a", 1),
                    "bank",
                    "ring",
                    mapOf("title" to "Bank", "password" to "original"),
                ),
            ),
        )
        return applyOps(
            base,
            listOf(VaultOp.ItemPut("l", at("a", 5), "bank", "ring", mapOf("password" to left))),
        ) to applyOps(
            base,
            listOf(VaultOp.ItemPut("r", at("b", 9), "bank", "ring", mapOf("password" to right))),
        )
    }

    @Test
    fun `keeps the value that lost, not just the one that won`() {
        val (left, right) = diverged("from-the-laptop", "from-the-phone")
        val item = assertNotNull(mergeVaults(left, right).items["bank"])

        assertEquals("from-the-phone", item.field("password"))
        assertContains(past(item, "password"), "from-the-laptop")
        assertContains(past(item, "password"), "original")
    }

    @Test
    fun `says the same thing whichever way round the merge runs`() {
        val (left, right) = diverged("from-the-laptop", "from-the-phone")
        val one = assertNotNull(mergeVaults(left, right).items["bank"])
        val other = assertNotNull(mergeVaults(right, left).items["bank"])

        assertEquals(one.field("password"), other.field("password"))
        assertEquals(past(one, "password").sorted(), past(other, "password").sorted())
    }

    @Test
    fun `does not archive the loser twice when the merge is repeated`() {
        val (left, right) = diverged("from-the-laptop", "from-the-phone")
        val thrice = mergeVaults(mergeVaults(mergeVaults(left, right), right), left)
        val values = past(assertNotNull(thrice.items["bank"]), "password")

        assertEquals(1, values.count { it == "from-the-laptop" })
        assertEquals(1, values.count { it == "original" })
    }

    @Test
    fun `keeps a field's own history however busy another field is`() {
        var state = applyOps(
            emptyVault(),
            listOf(
                VaultOp.KeyringPut("k", at("a", 0), "ring", "Just mine"),
                VaultOp.ItemPut(
                    "i",
                    at("a", 1),
                    "bank",
                    "ring",
                    mapOf("title" to "Bank", "password" to "the-one-that-matters"),
                ),
                VaultOp.ItemPut("p", at("a", 2), "bank", "ring", mapOf("password" to "replaced-it")),
            ),
        )

        repeat(HISTORY_LIMIT * 3) { n ->
            state = applyOps(
                state,
                listOf(
                    VaultOp.ItemPut(
                        "n-$n",
                        at("a", 100 + n),
                        "bank",
                        "ring",
                        mapOf("note" to "note $n"),
                    ),
                ),
            )
        }

        val item = assertNotNull(state.items["bank"])
        assertContains(past(item, "password"), "the-one-that-matters")
        assertTrue(past(item, "note").size <= HISTORY_LIMIT)
    }

    @Test
    fun `caps each field on its own and keeps the newest of them`() {
        val entries = (0 until HISTORY_LIMIT + 4).map {
            HistoryEntry("password", "v$it", at("a", it))
        } + HistoryEntry("note", "a note", at("a", 0))

        val capped = capHistory(entries)
        assertEquals(HISTORY_LIMIT, capped.count { it.field == "password" })
        assertEquals(1, capped.count { it.field == "note" })
        assertEquals("v${HISTORY_LIMIT + 3}", capped.first().value)
        assertTrue(capped.none { it.value == "v0" })
    }

    private fun <T : Any> assertNotNull(value: T?): T = kotlin.test.assertNotNull(value)
}

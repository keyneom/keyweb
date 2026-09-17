package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals

/** Parity with the web's `sorting.test.ts`: the same vault, the same top row. */
class SortingTest {

    private fun at(n: Int): Hlc =
        encodeHlc(HlcParts(wall = 1_700_000_000_000L + n, counter = 0, node = "test"))

    private fun vault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            VaultOp.KeyringPut("k1", at(0), "home", "Home"),
            VaultOp.KeyringPut("k2", at(0), "work", "work"),
            VaultOp.KeyringPut("k3", at(0), "old", "Archive"),
            VaultOp.ItemPut("a", at(1), "a", "home", mapOf("title" to "Zebra")),
            VaultOp.ItemPut("b", at(2), "b", "home", mapOf("title" to "apple")),
            VaultOp.ItemPut("c", at(3), "c", "work", mapOf("title" to "Mango")),
        ),
    )

    private fun titles(list: List<ItemRecord>) = list.map { it.fields["title"]?.value }

    /** Case must not split the alphabet, or "apple" lands after "Zebra". */
    @Test
    fun `orders by name in both directions, ignoring case`() {
        val items = vault().items.values.toList()
        assertEquals(listOf("apple", "Mango", "Zebra"), titles(sortItems(items, ItemSort.NAME_AZ)))
        assertEquals(listOf("Zebra", "Mango", "apple"), titles(sortItems(items, ItemSort.NAME_ZA)))
    }

    @Test
    fun `orders by when something last changed`() {
        val items = vault().items.values.toList()
        assertEquals(listOf("Mango", "apple", "Zebra"), titles(sortItems(items, ItemSort.NEWEST)))
        assertEquals(listOf("Zebra", "apple", "Mango"), titles(sortItems(items, ItemSort.OLDEST)))
    }

    /**
     * Read off the CRDT rather than a stored field, so it stays right through
     * a merge — and so a later edit to *any* field counts, including moving
     * the item to another keyring or attaching a file to it.
     */
    @Test
    fun `takes the newest write on the item, whichever field it was`() {
        val state = applyOps(
            vault(),
            listOf(VaultOp.ItemPut("z", at(99), "a", "home", mapOf("note" to "later"))),
        )
        assertEquals(1_700_000_000_099L, state.items.getValue("a").lastChangedAt())
        assertEquals(
            "Zebra",
            titles(sortItems(state.items.values.toList(), ItemSort.NEWEST)).first(),
        )
    }

    @Test
    fun `orders keyrings by name or by how much is in them`() {
        val rings = vault().keyrings.values.toList()
        val counts = mapOf("home" to 2, "work" to 1, "old" to 0)
        fun names(sort: KeyringSort) = sortKeyrings(rings, counts, sort).map { it.name.value }

        assertEquals(listOf("Archive", "Home", "work"), names(KeyringSort.NAME_AZ))
        assertEquals(listOf("work", "Home", "Archive"), names(KeyringSort.NAME_ZA))
        assertEquals(listOf("Home", "work", "Archive"), names(KeyringSort.MOST))
        assertEquals(listOf("Archive", "work", "Home"), names(KeyringSort.FEWEST))
    }

    /**
     * A list that reshuffles between renders is a list you cannot point at.
     * Two keyrings with the same count must come out the same way every time.
     */
    @Test
    fun `never leaves a tie to chance`() {
        val rings = vault().keyrings.values.toList()
        val counts = mapOf("home" to 1, "work" to 1, "old" to 1)
        assertEquals(
            sortKeyrings(rings, counts, KeyringSort.MOST).map { it.id },
            sortKeyrings(rings.reversed(), counts, KeyringSort.MOST).map { it.id },
        )
    }
}

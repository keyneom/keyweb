package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The folders an import has been preserving and nothing ever showed.
 *
 * The `folder` field has always held the full nested path, so two folders
 * called "Banks" under two different top-level groups have never collided.
 * What was missing is that nothing read it. Parity with the web's
 * `folders.test.ts`.
 */
class FoldersTest {

    private fun at(n: Int): Hlc =
        encodeHlc(HlcParts(wall = 1_700_000_000_000L + n, counter = 0, node = "test"))

    private fun put(n: Int, id: String, ring: String, title: String, folder: String) =
        VaultOp.ItemPut("op-$n", at(n), id, ring, mapOf("title" to title, "folder" to folder))

    /** Two top-level groups, each with a "Banks" inside it. The user's question. */
    private fun vault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            VaultOp.KeyringPut("k1", at(0), "leslie", "Leslie"),
            VaultOp.KeyringPut("k2", at(0), "mika", "Mika"),
            put(1, "l-chase", "leslie", "Chase", "Leslie / Banks"),
            put(2, "l-amex", "leslie", "Amex", "Leslie / Banks / Cards"),
            put(3, "l-gas", "leslie", "Gas", "Leslie / Utilities"),
            put(4, "l-loose", "leslie", "Loose", "Leslie"),
            put(5, "m-wells", "mika", "Wells Fargo", "Mika / Banks"),
        ),
    )

    private fun titles(items: List<ItemRecord>) = items.map { it.fields["title"]?.value }

    /** The question that prompted this: do the two "Banks" collide? */
    @Test
    fun `keeps two folders of the same name under different keyrings apart`() {
        val state = vault()
        val items = visibleItems(state)
        val leslie = items.filter { it.keyring.value == "leslie" }
        val mika = items.filter { it.keyring.value == "mika" }

        assertEquals(listOf("Chase"), titles(browseFolders(leslie, state, listOf("Banks")).items))
        assertEquals(
            listOf("Wells Fargo"),
            titles(browseFolders(mika, state, listOf("Banks")).items),
        )
    }

    /**
     * The keyring's own name is the first segment of every path in it, and
     * showing it would open every vault onto a folder that contains everything
     * and is named after the thing you just opened.
     */
    @Test
    fun `does not repeat the keyring as a folder inside itself`() {
        val state = vault()
        assertEquals(listOf("Banks"), folderPath(state.items.getValue("l-chase"), state))
        assertEquals(emptyList(), folderPath(state.items.getValue("l-loose"), state))
    }

    @Test
    fun `shows the folders and the loose items at one level`() {
        val state = vault()
        val leslie = visibleItems(state).filter { it.keyring.value == "leslie" }
        val top = browseFolders(leslie, state, emptyList())
        assertEquals(listOf("Banks", "Utilities"), top.folders.map { it.name })
        assertEquals(listOf("Loose"), titles(top.items))
    }

    /**
     * Counted all the way down. A folder showing "0" that opens onto three
     * subfolders full of passwords is a folder nobody opens.
     */
    @Test
    fun `counts everything beneath a folder, not only what is directly in it`() {
        val state = vault()
        val leslie = visibleItems(state).filter { it.keyring.value == "leslie" }
        val banks = browseFolders(leslie, state, emptyList()).folders.first { it.name == "Banks" }
        assertEquals(2, banks.count)
    }

    @Test
    fun `descends`() {
        val state = vault()
        val leslie = visibleItems(state).filter { it.keyring.value == "leslie" }
        val banks = browseFolders(leslie, state, listOf("Banks"))
        assertEquals(listOf("Cards"), banks.folders.map { it.name })
        assertEquals(listOf("Chase"), titles(banks.items))
        assertEquals(
            listOf("Amex"),
            titles(browseFolders(leslie, state, listOf("Banks", "Cards")).items),
        )
    }

    @Test
    fun `lists every folder that exists, for moving something into one`() {
        val state = vault()
        val leslie = visibleItems(state).filter { it.keyring.value == "leslie" }
        assertEquals(
            listOf(listOf("Banks"), listOf("Banks", "Cards"), listOf("Utilities")),
            allFolders(leslie, state),
        )
    }
}

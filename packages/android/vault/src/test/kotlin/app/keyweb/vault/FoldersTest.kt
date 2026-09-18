package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * The bug the other cases here walked straight past.
     *
     * Every one of them filters to a keyring before browsing, which is what
     * the screen does only when a keyring chip is selected. With "All"
     * selected the list holds every keyring at once, each path was computed
     * relative to its *own* keyring, and Leslie's "Banks" and Mika's "Banks"
     * became one folder holding both. The data never collided; the view
     * invented the collision, which is worse — the two passwords looked like
     * they were in one place.
     */
    @Test
    fun `does not merge two keyrings' folders when showing all of them`() {
        val state = vault()
        val all = visibleItems(state)

        val top = browseFolders(all, state, emptyList(), null)
        // The keyrings, not their insides: at this level "Banks" is ambiguous
        // and the honest answer is to say which one first.
        assertEquals(listOf("Leslie", "Mika"), top.folders.map { it.name })
        assertTrue(top.folders.all { it.kind == FolderKind.KEYRING })

        val leslie = browseFolders(all, state, listOf("Leslie"), null)
        assertEquals(listOf("Banks", "Utilities"), leslie.folders.map { it.name })
        assertTrue(leslie.folders.all { it.kind == FolderKind.FOLDER })

        assertEquals(
            listOf("Chase"),
            titles(browseFolders(all, state, listOf("Leslie", "Banks"), null).items),
        )
        assertEquals(
            listOf("Wells Fargo"),
            titles(browseFolders(all, state, listOf("Mika", "Banks"), null).items),
        )
    }

    /** The question that prompted this: do the two "Banks" collide? */
    @Test
    fun `keeps two folders of the same name under different keyrings apart`() {
        val state = vault()
        val items = visibleItems(state)
        val leslie = items.filter { it.keyring.value == "leslie" }
        val mika = items.filter { it.keyring.value == "mika" }

        assertEquals(listOf("Chase"), titles(browseFolders(leslie, state, listOf("Banks"), "leslie").items))
        assertEquals(
            listOf("Wells Fargo"),
            titles(browseFolders(mika, state, listOf("Banks"), "mika").items),
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
        val top = browseFolders(leslie, state, emptyList(), "leslie")
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
        val banks = browseFolders(leslie, state, emptyList(), "leslie").folders.first { it.name == "Banks" }
        assertEquals(2, banks.count)
    }

    @Test
    fun `descends`() {
        val state = vault()
        val leslie = visibleItems(state).filter { it.keyring.value == "leslie" }
        val banks = browseFolders(leslie, state, listOf("Banks"), "leslie")
        assertEquals(listOf("Cards"), banks.folders.map { it.name })
        assertEquals(listOf("Chase"), titles(banks.items))
        assertEquals(
            listOf("Amex"),
            titles(browseFolders(leslie, state, listOf("Banks", "Cards"), "leslie").items),
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

package app.keyweb.vault.kdbx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where an imported entry lands.
 *
 * Reading the right bytes is only half of an import. Putting an entry in the
 * wrong keyring is not a decoding failure — nothing throws, every password is
 * correct — and it is the kind of wrongness that is hard to see afterwards and
 * tedious to undo by hand, because it is spread across hundreds of items.
 *
 * The other fixture puts every entry in a leaf group, so the arrangements that
 * actually decide placement went untested: entries directly inside a top-level
 * group, entries loose at the database root, and subgroups sharing a name under
 * different parents. This holds the Kotlin reader to the same answers the web
 * reader gives for the same file.
 */
class KdbxStructureTest {

    private val file = File("../../../fixtures/keepass-structure.kdbx")

    private fun read(): KdbxFile {
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npm run fixture:kdbx --workspace @keyweb/web",
        )
        return KdbxReader.read(file.readBytes(), "correct horse battery staple")
    }

    private fun KdbxFile.at(title: String): KdbxEntry =
        assertNotNull(entries.find { it.title == title }, "no entry titled $title")

    @Test
    fun `an entry directly inside a top-level group stays in it`() {
        val found = read().at("direct-in-Banking")
        assertEquals("Banking", found.keyringName)
        assertEquals("Banking", found.folder)
    }

    @Test
    fun `a nested entry keeps its top-level group as the keyring`() {
        val found = read().at("in-Banking-Personal")
        assertEquals("Banking", found.keyringName)
        assertEquals("Banking / Personal", found.folder)
    }

    @Test
    fun `three levels deep keeps the whole path`() {
        val found = read().at("three-deep")
        assertEquals("Banking", found.keyringName)
        assertEquals("Banking / Personal / Deep", found.folder)
    }

    @Test
    fun `subgroups sharing a name stay under their own parents`() {
        val file = read()
        assertEquals("Shopping / Personal", file.at("in-Shopping-Personal").folder)
        assertEquals("Banking / Personal", file.at("in-Banking-Personal").folder)
        assertEquals("Shopping", file.at("in-Shopping-Personal").keyringName)
    }

    @Test
    fun `every group that will hold something is offered as a keyring`() {
        // "MyVault" is the database's root group, earning a keyring only
        // because an entry actually sits loose in it.
        //
        // Compared as a set: this reader walks the file in document order,
        // while the web reader takes a group's entries before its subgroups,
        // so the two put the root's own keyring in a different position. Both
        // are deterministic and neither is wrong, and nothing depends on the
        // position now that no entry falls back to "whichever came first".
        assertEquals(
            setOf("MyVault", "Banking", "Shopping", "Work"),
            read().keyringNames.toSet(),
        )
    }

    @Test
    fun `lists the real groups in the order the file has them`() {
        val listed = read().keyringNames.filter { it != "MyVault" }
        assertEquals(listOf("Banking", "Shopping", "Work"), listed)
    }

    @Test
    fun `never names a keyring that was not offered`() {
        // The invariant behind the whole bug. An unregistered name is not an
        // error at commit time — it falls through to whichever keyring came
        // out of the map first, so the entry lands in a real folder it was
        // never in, and nothing anywhere says so.
        val file = read()
        for (entry in file.entries) {
            assertTrue(
                entry.keyringName in file.keyringNames,
                "'${entry.title}' claims keyring '${entry.keyringName}', which is not offered",
            )
        }
    }

    @Test
    fun `entries inside a deleted group are not resurrected`() {
        // Deleting a group in KeePass moves the whole group into the recycle
        // bin with its entries inside. Skipping only the bin's own direct
        // entries lets these back in -- and they arrive claiming the bin as
        // their keyring, so deleted passwords turn up in a folder nobody made.
        val titles = read().entries.map { it.title }
        assertTrue(
            "deleted-inside-a-deleted-group" !in titles,
            "a password deleted with its group came back",
        )
        assertTrue("deleted-on-its-own" !in titles, "a deleted password came back")
    }

    @Test
    fun `the recycle bin is never offered as a keyring`() {
        assertTrue(
            read().keyringNames.none { it.contains("Recycle", ignoreCase = true) },
            "the recycle bin was offered as a keyring",
        )
    }

    @Test
    fun `a root-level entry gets its own keyring rather than someone else's`() {
        val file = read()
        val loose = file.at("loose-at-root")
        assertEquals("MyVault", loose.keyringName)
        assertEquals("MyVault", loose.folder)
        assertTrue(loose.keyringName in file.keyringNames, "its keyring is never created")
        assertTrue(
            loose.keyringName !in listOf("Banking", "Shopping", "Work"),
            "a loose entry was filed into a real folder it was never in",
        )
    }
}

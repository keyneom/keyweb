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
    fun `offers a keyring for each real group, and only those`() {
        // The database's own root group is not among them: its name is the
        // database's, not a folder anyone made, and what happens to the
        // entries sitting in it is the user's choice rather than this
        // reader's.
        assertEquals(listOf("Banking", "Shopping", "Work"), read().keyringNames)
    }

    @Test
    fun `never names a keyring that was not offered`() {
        // The invariant behind the whole bug. An unregistered name is not an
        // error at commit time — it used to fall through to whichever keyring
        // came out of the map first, so the entry landed in a real folder it
        // had never been in, and nothing anywhere said so.
        val file = read()
        for (entry in file.entries) {
            val named = entry.keyringName ?: continue
            assertTrue(
                named in file.keyringNames,
                "'${entry.title}' claims keyring '$named', which is not offered",
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
    fun `counts the entries that are in no group instead of inventing one`() {
        val file = read()
        assertEquals(1, file.ungrouped)

        val loose = file.at("loose-at-root")
        assertEquals(null, loose.keyringName)
        assertEquals("", loose.folder)
    }

    @Test
    fun `suggests a keyring name from the file, not the database's root group`() {
        // The root group here is "MyVault", a name the user may never have
        // seen. The file name is what they actually call this collection.
        assertEquals("Family passwords", suggestedKeyringName("Family passwords.kdbx"))
        assertEquals("Work passwords.v2", suggestedKeyringName("Work passwords.v2.kdbx"))
        assertEquals("passwords", suggestedKeyringName("passwords"))
        assertEquals("vault", suggestedKeyringName("/storage/emulated/0/Download/vault.kdbx"))
        assertEquals("Imported", suggestedKeyringName(".kdbx"))
        assertEquals("Imported", suggestedKeyringName("   "))
    }
}

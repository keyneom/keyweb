package app.keyweb.vault.kdbx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Nothing a KeePass file holds may go missing, and the two readers must agree
 * about what it holds.
 *
 * This is a data-loss suite. Somebody imports, sees a count that looks right,
 * deletes the original file, and only finds out months later that their
 * security answers and two-factor seeds were never carried across — there is
 * no recovering from that.
 *
 * The parity half matters just as much and is easier to get wrong: the web
 * reader wraps `kdbxweb` and this one is written from the format, and they
 * feed the *same vault*. A field the phone calls `secret:Answer` and the
 * browser calls `Answer` is one field that has quietly become two.
 */
class KdbxFidelityTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = File("../../../fixtures")

    @Serializable
    private data class Parity(
        val password: String,
        val skipped: Int,
        val keyringNames: List<String>,
        val entries: List<Entry>,
    )

    @Serializable
    private data class Entry(
        val itemId: String,
        val fields: Map<String, String>,
        val versions: Int,
        val attachments: List<File> = emptyList(),
    )

    @Serializable
    private data class File(
        val blobId: String,
        val name: String,
        val type: String,
        val data: String,
        val bytes: Int,
    )

    private fun parity(): Parity {
        val file = File(fixtures, "keepass-rich-fields.json")
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npm run fixture:kdbx-parity --workspace @keyweb/web",
        )
        return json.decodeFromString(Parity.serializer(), file.readText())
    }

    private fun read(password: String): KdbxFile =
        KdbxReader.read(File(fixtures, "keepass-rich.kdbx").readBytes(), password)

    /** The whole point: the same file, the same field names, on both platforms. */
    @Test
    fun `agrees with the web reader field for field`() {
        val expected = parity()
        val file = read(expected.password)

        val actual = file.entries.associate { entry ->
            "kdbx:${entry.uuid}" to entry.toItemFields().filterValues { it.isNotEmpty() }
        }

        assertEquals(expected.entries.size, actual.size, "entry count")
        for (want in expected.entries) {
            val got = actual[want.itemId] ?: error("missing entry ${want.itemId}")
            assertEquals(
                want.fields.keys.sorted(),
                got.keys.sorted(),
                "field names for ${want.itemId}",
            )
            for ((name, value) in want.fields) {
                assertEquals(value, got[name], "value of $name on ${want.itemId}")
            }
        }
    }

    @Test
    fun `keeps custom fields somebody added themselves`() {
        val file = read(parity().password)
        val bank = file.entries.first { it.title == "Chase Bank" }
        assertEquals("First pet's name", bank.extra["Security question"])
        assertEquals("00112233", bank.extra["Account number"])
    }

    /**
     * A field KeePass marked protected stays protected here. The `secret:`
     * prefix is what `Fields.isSecret` keys off, so this is the difference
     * between an answer being masked and being printed beside the username.
     */
    @Test
    fun `keeps a protected field protected`() {
        val file = read(parity().password)
        val bank = file.entries.first { it.title == "Chase Bank" }
        val fields = bank.toItemFields()
        assertEquals("Rufus", fields["secret:Security answer"])
        assertTrue(!fields.containsKey("Security answer"))
        // ...and an unprotected one is not masked just for looking sensitive.
        assertEquals("First pet's name", fields["Security question"])
    }

    @Test
    fun `recognises a one-time-code seed as one`() {
        val file = read(parity().password)
        val mail = file.entries.first { it.title == "Webmail" }
        val otp = mail.toItemFields()["otp"] ?: error("no otp")
        assertTrue(otp.contains("JBSWY3DPEHPK3PXP"), otp)
        assertTrue(otp.startsWith("otpauth://"), otp)
    }

    /** The entry that used to disappear: no title, no password, passport in it. */
    @Test
    fun `keeps an entry that has no title and no password`() {
        val file = read(parity().password)
        val note = file.entries.first { it.extra.containsKey("Passport number") }
        assertEquals("X1234567", note.toItemFields()["secret:Passport number"])
        assertEquals("20-00-00", note.toItemFields()["Sort code"])
    }

    @Test
    fun `keeps the earlier versions of an entry`() {
        val file = read(parity().password)
        val bank = file.entries.first { it.title == "Chase Bank" }
        assertTrue(bank.versions.isNotEmpty(), "no history carried")
        val passwords = bank.versions.mapNotNull { it.fields["Password"] }
        assertTrue(passwords.contains("first-password"), passwords.toString())
    }

    /** A KeePass field named like one of ours must not be able to act like one. */
    @Test
    fun `does not let a custom field overwrite a real one`() {
        val file = read(parity().password)
        val odd = file.entries.first { it.title == "Odd names" }
        val fields = odd.toItemFields()
        assertEquals("Banking", fields["folder"])
        assertEquals("not-a-real-folder", fields["custom:folder"])
    }

    /** The file itself, not a note about it having existed. */
    @Test
    fun `brings the attached file across, bytes and all`() {
        val expected = parity()
        val file = read(expected.password)
        assertTrue(file.oversized.isEmpty(), file.oversized.toString())

        val codes = file.entries.first { it.title == "Recovery codes" }
        assertEquals(1, codes.attachments.size)
        val attached = codes.attachments.first()
        assertEquals("recovery-codes.txt", attached.name)
        assertEquals(
            "11111111\n22222222\n",
            String(java.util.Base64.getDecoder().decode(attached.data), Charsets.UTF_8),
        )
    }

    /**
     * The id is the content hash, and the two platforms must compute the same
     * one — otherwise importing the same file on a phone and in a browser puts
     * two copies of it in one vault.
     */
    @Test
    fun `agrees with the web reader on the id of a file's bytes`() {
        val expected = parity()
        val file = read(expected.password)
        val wanted = expected.entries.flatMap { it.attachments }
        assertTrue(wanted.isNotEmpty(), "the fixture has no attachments to compare")

        val got = file.entries.flatMap { it.attachments }.associateBy { it.name }
        for (want in wanted) {
            val actual = got[want.name] ?: error("missing file ${want.name}")
            assertEquals(want.blobId, actual.blobId, "blob id for ${want.name}")
            assertEquals(want.data, actual.data, "bytes of ${want.name}")
            assertEquals(want.bytes, actual.bytes, "size of ${want.name}")
        }
    }
}

/** The same mapping the import uses, so the test checks what actually lands. */
private fun KdbxEntry.toItemFields(): Map<String, String> = buildMap {
    putAll(kdbxFieldsToItemFields(allFields(), protectedKeys))
    if (title.isEmpty()) put("title", "Untitled")
    if (folder.isNotEmpty()) put("folder", folder)
    if (tags.isNotEmpty()) put("tags", tags)
}

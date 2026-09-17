package app.keyweb.vault.kdbx

import app.keyweb.vault.Clock
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.Stamp
import app.keyweb.vault.VaultOp
import app.keyweb.vault.VaultState
import app.keyweb.vault.applyOps
import app.keyweb.vault.attachments
import app.keyweb.vault.emptyVault
import app.keyweb.vault.isBlob
import app.keyweb.vault.visibleItems
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
     * What the import actually *writes*, not merely what the reader returns.
     *
     * The reader had this suite pinning it while the half that turns entries
     * into vault operations had no test at all — it lived inside the view
     * model, where nothing could reach it. Every case below is one a person
     * meets on a phone: a file that arrives as bytes on its own item, a blob
     * that must not show up in the password list, and a second import of the
     * same file that must update rather than duplicate.
     */
    private fun importedVault(existing: VaultState = emptyVault()): VaultState {
        val file = read(parity().password)
        var n = 0
        val clock = Clock(node = "test", physical = { 1_760_000_000_000L })
        val ops = importOperations(
            entries = file.entries,
            keyringIds = file.keyringNames.associateWith { "ring" },
            ungroupedKeyringId = "ring",
            existing = existing,
            stamp = { Stamp("op-${++n}", clock.now()) },
        )
        // The keyring has to exist or `visibleItems` hides everything on it,
        // which is the app's own rule rather than anything about importing.
        val keyring = VaultOp.KeyringPut("ring", clock.now(), "ring", "Imported")
        return applyOps(existing, listOf(keyring) + ops)
    }

    private fun VaultState.byTitle(title: String): ItemRecord =
        items.values.firstOrNull { it.fields["title"]?.value == title }
            ?: error("no imported item titled $title")

    @Test
    fun `the attached file lands as an item of its own`() {
        val state = importedVault()
        val codes = state.byTitle("Recovery codes")

        val files = codes.attachments()
        assertEquals(listOf("recovery-codes.txt"), files.map { it.name })

        val blob = state.items[files.first().blobId] ?: error("the bytes never landed")
        assertTrue(blob.isBlob())
        assertEquals("text/plain", blob.fields["type"]?.value)
        assertEquals(
            "11111111\n22222222\n",
            String(
                java.util.Base64.getDecoder().decode(blob.fields["secret:data"]?.value),
                Charsets.UTF_8,
            ),
        )
        // On the same keyring as the password it belongs to, which is what
        // makes sharing carry it for nothing.
        assertEquals(codes.keyring.value, blob.keyring.value)
    }

    /** The bytes are an item, but they are not a password. */
    @Test
    fun `the file does not appear in the password list`() {
        val state = importedVault()
        val titles = visibleItems(state).map { it.fields["title"]?.value }
        assertTrue(titles.contains("Recovery codes"), titles.toString())
        assertTrue(visibleItems(state).none { it.isBlob() })
    }

    @Test
    fun `earlier passwords land as history`() {
        val state = importedVault()
        val bank = state.byTitle("Chase Bank")
        assertEquals("current-password", bank.fields["password"]?.value)
        val was = bank.history.filter { it.field == "password" }.map { it.value }
        assertTrue(was.contains("first-password"), was.toString())
        assertTrue(was.contains("second-password"), was.toString())
    }

    /** Re-importing the same file updates what changed rather than duplicating. */
    @Test
    fun `importing the same file twice makes no second copy`() {
        val once = importedVault()
        val twice = importedVault(once)
        assertEquals(once.items.keys.sorted(), twice.items.keys.sorted())
    }

    /**
     * The name an older build's import gave a field is retired by a re-import.
     *
     * That build stored every custom field as `secret:<name>`, protected or
     * not, so an account number arrived masked and under a key today's import
     * never writes. Left alone, re-importing shows it twice — once masked
     * under the old key, once correctly — with no way to tell which is which.
     */
    @Test
    fun `an earlier import's name for a field is retired`() {
        val file = read(parity().password)
        val bank = file.entries.first { it.title == "Chase Bank" }
        val itemId = "kdbx:${bank.uuid}"

        val clock = Clock(node = "old", physical = { 1_700_000_000_000L })
        val before = applyOps(
            emptyVault(),
            listOf(
                VaultOp.KeyringPut("k", clock.now(), "ring", "Imported"),
                VaultOp.ItemPut(
                    opId = "old",
                    ts = clock.now(),
                    itemId = itemId,
                    keyringId = "ring",
                    fields = mapOf(
                        "title" to "Chase Bank",
                        "secret:Account number" to "00112233",
                    ),
                ),
            ),
        )

        val after = importedVault(before).items.getValue(itemId)
        assertEquals("00112233", after.fields["Account number"]?.value)
        assertEquals("", after.fields["secret:Account number"]?.value)
        // Superseded, not destroyed: the old value sits in the item's history
        // like any other overwritten write, so this is undoable.
        assertTrue(after.history.any { it.field == "secret:Account number" })
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

/**
 * The mapping the import really uses, not a copy of it living in the test.
 *
 * It was a copy, and a copy of the thing under test is worth nothing: the
 * import could have changed under it and every assertion here would still have
 * passed.
 */
private fun KdbxEntry.toItemFields(): Map<String, String> = toFields()

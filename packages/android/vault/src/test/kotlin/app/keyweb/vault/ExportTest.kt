package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Getting everything back out.
 *
 * The import was built on a promise — bring your KeePass file in and you can
 * delete the original — and that promise is only honest if the door swings
 * both ways. Parity with the web's `export.test.ts`, case for case, because a
 * file exported on a phone and a file exported in a browser have to be the
 * same file.
 */
class ExportTest {

    private fun at(n: Int): Hlc =
        encodeHlc(HlcParts(wall = 1_700_000_000_000L + n, counter = 0, node = "test"))

    private fun vault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            VaultOp.KeyringPut("k", at(0), "home", "Home"),
            VaultOp.ItemPut(
                "blob", at(1), "blob:abc", "home",
                mapOf(
                    "kind" to "blob",
                    "name" to "passport.png",
                    "type" to "image/png",
                    "size" to "12",
                    "secret:data" to "aGVsbG8gd29ybGQ=",
                ),
            ),
            VaultOp.ItemPut(
                "a", at(2), "bank", "home",
                mapOf(
                    "title" to "Chase Bank",
                    "username" to "maria@example.com",
                    "password" to "first",
                    "url" to "chase.com",
                    "note" to "Joint account",
                    "otp" to "otpauth://totp/Chase?secret=JBSWY3DPEHPK3PXP",
                    "folder" to "Banking / Personal",
                    "Account number" to "00112233",
                    "secret:Security answer" to "Rufus",
                    "file:blob:abc" to "passport.png",
                ),
            ),
            VaultOp.ItemPut("b", at(3), "bank", "home", mapOf("password" to "second")),
        ),
    )

    private fun exported() = exportVault(vault(), "2026-09-17T00:00:00Z")

    @Test
    fun `carries every part of an item`() {
        val out = exported()
        assertEquals(1, out.items.size)
        val bank = out.items.first()

        assertEquals("Chase Bank", bank.title)
        assertEquals("second", bank.password)
        assertTrue(bank.otp.contains("JBSWY3DPEHPK3PXP"))
        assertEquals("Home", bank.keyringName)
        // Custom fields under the name their owner gave them, with the masking
        // recorded — a re-import could not tell a security answer from an
        // account number without it.
        assertEquals(
            listOf(
                ExportedField("Account number", "00112233", false),
                ExportedField("Security answer", "Rufus", true),
            ),
            bank.fields,
        )
        // The file itself, not a note that one existed.
        assertEquals(
            listOf(ExportedFile("passport.png", "image/png", 12, "aGVsbG8gd29ybGQ=")),
            bank.files,
        )
        // And what the password used to be.
        assertEquals(
            listOf(
                ExportedHistory(
                    "password",
                    "first",
                    java.time.Instant.ofEpochMilli(1_700_000_000_002L).toString(),
                ),
            ),
            bank.history,
        )
    }

    /** A file is an item in the vault, and must not come out as a password. */
    @Test
    fun `does not export the files as entries of their own`() {
        assertEquals(listOf("Chase Bank"), exported().items.map { it.title })
    }

    @Test
    fun `says in the file itself that the file is not encrypted`() {
        assertTrue(exported().warning.contains("NOT encrypted"))
    }

    @Test
    fun `counts what a CSV cannot carry`() {
        assertEquals(CsvOmissions(files = 1, history = 1, customFields = 2), csvOmissions(exported()))
    }

    @Test
    fun `writes the columns other password managers read`() {
        val csv = exportCsv(exported())
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(
            "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\"",
            lines.first(),
        )
        val row = lines[1]
        assertTrue(row.contains("\"Chase Bank\""))
        assertTrue(row.contains("\"Banking / Personal\""))
        // Custom fields survive in the note rather than as extra columns, which
        // is what makes importers reject a file outright.
        assertTrue(row.contains("Account number: 00112233"))
        assertTrue(row.contains("Security answer: Rufus"))
        // And the file it could not carry is named rather than forgotten.
        assertTrue(row.contains("[file not included in CSV: passport.png]"))
    }

    /**
     * A password starting with `=` is a formula to Excel and Sheets, which will
     * evaluate it: the value on screen stops being the value in the vault, and
     * at worst the spreadsheet fetches a URL built out of somebody's password.
     */
    @Test
    fun `keeps a spreadsheet from executing a password`() {
        val state = applyOps(
            emptyVault(),
            listOf(
                VaultOp.KeyringPut("k", at(0), "home", "Home"),
                VaultOp.ItemPut(
                    "a", at(1), "x", "home",
                    mapOf("title" to "Odd", "password" to "=HYPERLINK(\"http://evil\",\"click\")"),
                ),
            ),
        )
        val csv = exportCsv(exportVault(state, "2026-09-17T00:00:00Z"))
        assertTrue(
            csv.contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\""),
            csv,
        )
    }

    @Test
    fun `escapes a quote and a newline the way every reader expects`() {
        val state = applyOps(
            emptyVault(),
            listOf(
                VaultOp.KeyringPut("k", at(0), "home", "Home"),
                VaultOp.ItemPut(
                    "a", at(1), "x", "home",
                    mapOf(
                        "title" to "He said \"hi\"",
                        "note" to "line one\nline two",
                        "password" to "p",
                    ),
                ),
            ),
        )
        val csv = exportCsv(exportVault(state, "2026-09-17T00:00:00Z"))
        assertTrue(csv.contains("\"He said \"\"hi\"\"\""), csv)
        assertTrue(csv.contains("\"line one\nline two\""), csv)
    }
}

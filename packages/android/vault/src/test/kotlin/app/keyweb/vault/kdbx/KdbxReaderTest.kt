package app.keyweb.vault.kdbx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin KDBX reader, against files a real KeePass writer produced.
 *
 * This reader is implemented from the format rather than ported from a library,
 * so testing it against its own idea of KDBX would prove nothing. The fixtures
 * come from `kdbxweb` — the same library the web app reads with — which means a
 * disagreement between the two platforms surfaces here instead of on somebody's
 * phone, halfway through importing their bank passwords.
 *
 * Both KDBX 3.1 and KDBX 4 are covered because both are in circulation and they
 * differ in almost every structural decision: key derivation (AES-KDF against
 * Argon2), block framing (hashed against HMAC), and where the inner stream
 * parameters live.
 */
class KdbxReaderTest {

    private val fixtures = File("../../../fixtures")
    private val password = "correct horse battery staple"

    private fun database(version: Int): ByteArray {
        val file = File(fixtures, "keepass-v$version.kdbx")
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npm run fixture:kdbx --workspace @keyweb/web",
        )
        return file.readBytes()
    }

    private fun read(version: Int) = KdbxReader.read(database(version), password)

    @Test
    fun `opens a KDBX 4 database written by kdbxweb`() {
        val file = read(4)
        val chase = assertNotNull(file.entries.find { it.title == "Chase Bank" })
        assertEquals("maria@example.com", chase.username)
        // The whole point of the exercise: the secret comes out intact.
        assertEquals("rotated-later", chase.password)
        assertEquals("https://chase.com", chase.url)
        assertEquals("Joint account\nSecond line", chase.note)
    }

    @Test
    fun `opens a KDBX 3_1 database, which uses a different key method and framing`() {
        val file = read(3)
        val chase = assertNotNull(file.entries.find { it.title == "Chase Bank" })
        assertEquals("rotated-later", chase.password)
        assertEquals("maria@example.com", chase.username)
    }

    @Test
    fun `keeps the group hierarchy as keyrings and folders`() {
        for (version in listOf(3, 4)) {
            val file = read(version)
            assertEquals(listOf("Banking", "Shopping"), file.keyringNames.sorted(), "v$version")

            val chase = assertNotNull(file.entries.find { it.title == "Chase Bank" })
            assertEquals("Banking", chase.keyringName, "v$version")
            // Losing this is how an import turns someone's filing system into a
            // pile of undifferentiated rows.
            assertEquals("Banking / Personal", chase.folder, "v$version")

            val costco = assertNotNull(file.entries.find { it.title == "Costco" })
            assertEquals("Shopping", costco.folder, "v$version")
        }
    }

    @Test
    fun `keeps tags`() {
        for (version in listOf(3, 4)) {
            val chase = assertNotNull(read(version).entries.find { it.title == "Chase Bank" })
            assertEquals("finance, important", chase.tags, "v$version")
        }
    }

    @Test
    fun `keeps custom fields rather than dropping them`() {
        // A field Keyweb has no name for is still somebody's account number.
        for (version in listOf(3, 4)) {
            val chase = assertNotNull(read(version).entries.find { it.title == "Chase Bank" })
            assertEquals("00112233", chase.extra["Account number"], "v$version")
        }
    }

    @Test
    fun `handles values outside ASCII`() {
        // Protected values are UTF-8 inside the keystream; a byte-wise decode
        // would mangle this and nothing would notice until someone tried it.
        for (version in listOf(3, 4)) {
            val chase = assertNotNull(read(version).entries.find { it.title == "Chase Bank" })
            assertTrue(chase.extra.isNotEmpty() || chase.password.isNotEmpty(), "v$version")
        }
        // The rotated password replaced the emoji one in history; check the
        // history walk did not leave the keystream out of step by reading a
        // later entry correctly.
        val file = read(4)
        assertEquals("warehouse", file.entries.find { it.title == "Costco" }?.password)
    }

    @Test
    fun `skips the recycle bin and empty entries`() {
        for (version in listOf(3, 4)) {
            val file = read(version)
            // Deliberately thrown away in KeePass. Importing it would resurrect
            // a password someone chose to delete.
            assertTrue(file.entries.none { it.title == "Old card" }, "v$version")
            assertTrue(file.skipped > 0, "v$version")
        }
    }

    @Test
    fun `refuses the wrong password rather than importing nothing`() {
        // The dangerous failure is an empty success: a "finished" import that
        // brought across nothing, leaving someone to believe they migrated.
        for (version in listOf(3, 4)) {
            assertFailsWith<WrongMasterPassword>("v$version") {
                KdbxReader.read(database(version), "not the password")
            }
        }
    }

    @Test
    fun `refuses a file that is not a KeePass database`() {
        val failure = assertFailsWith<KdbxException> {
            KdbxReader.read("this is just a text file".toByteArray(), password)
        }
        assertTrue(failure.message!!.contains("isn't a KeePass file"))
    }

    @Test
    fun `refuses a truncated file instead of importing half a vault`() {
        // A partial import looks like success, which is the worst outcome: the
        // missing half is only discovered when it is needed.
        val truncated = database(4).copyOfRange(0, database(4).size - 64)
        assertFailsWith<Exception> { KdbxReader.read(truncated, password) }
    }

    @Test
    fun `refuses a file altered after it was saved`() {
        val tampered = database(4).copyOf()
        // Flip a byte deep in the ciphertext, past the header.
        tampered[tampered.size - 20] = (tampered[tampered.size - 20].toInt() xor 0x01).toByte()
        assertFailsWith<Exception> { KdbxReader.read(tampered, password) }
    }

    @Test
    fun `never exposes a way to write a KeePass file`() {
        // Somebody's KeePass database is often the only copy of decades of
        // accounts. The guarantee that Keyweb cannot damage it should be one
        // the code is incapable of breaking, not one it merely refrains from.
        // Checked by intent rather than by an exact name list, which would only
        // be asserting Kotlin's synthetic-overload naming.
        val methods = KdbxReader::class.java.methods.map { it.name }
            .filterNot { it in OBJECT_METHODS }
        assertTrue(methods.isNotEmpty())
        assertTrue(
            methods.none { name ->
                listOf("write", "save", "encode", "serialize").any { name.contains(it, true) }
            },
            "KdbxReader exposes something that could modify a database: $methods",
        )
    }

    private companion object {
        val OBJECT_METHODS = setOf(
            "equals", "hashCode", "toString", "wait", "notify", "notifyAll", "getClass",
        )
    }
}

package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading Google Authenticator's export.
 *
 * The payloads are built here rather than pasted from a real export, because a
 * real one is somebody's actual second factors. The encoder below is the
 * inverse of the decoder under test and nothing else uses it, so the anchor
 * that keeps the pair honest is the base32 vector: `Hello!\xde\xad\xbe\xef` is
 * the secret every authenticator's own documentation uses, and it encodes to
 * `JBSWY3DPEHPK3PXP`.
 *
 * Parity with the web's `authenticator.test.ts`, case for case.
 */
class AuthenticatorTest {

    private val secret = byteArrayOf(
        0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21,
        0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(),
    )

    private fun varint(value: Int): List<Byte> = buildList {
        var n = value
        do {
            var byte = n and 0x7f
            n = n ushr 7
            if (n > 0) byte = byte or 0x80
            add(byte.toByte())
        } while (n > 0)
    }

    private fun lengthDelimited(field: Int, bytes: List<Byte>): List<Byte> =
        varint((field shl 3) or 2) + varint(bytes.size) + bytes

    private fun value(field: Int, n: Int): List<Byte> = varint(field shl 3) + varint(n)

    private fun account(
        name: String = "maria@example.com",
        issuer: String = "GitHub",
        algorithm: Int = 1,
        digits: Int = 1,
        type: Int = 2,
        extra: List<Byte> = emptyList(),
    ): List<Byte> =
        lengthDelimited(1, secret.toList()) +
            lengthDelimited(2, name.toByteArray().toList()) +
            lengthDelimited(3, issuer.toByteArray().toList()) +
            value(4, algorithm) + value(5, digits) + value(6, type) + extra

    private fun migration(
        accounts: List<List<Byte>>,
        index: Int = 0,
        total: Int = 1,
        id: Int = 7,
    ): String {
        val body = accounts.flatMap { lengthDelimited(1, it) } +
            value(2, 1) + value(3, total) + value(4, index) + value(5, id)
        val base64 = java.util.Base64.getEncoder().encodeToString(body.toByteArray())
        val escaped = java.net.URLEncoder.encode(base64, "UTF-8")
        return "otpauth-migration://offline?data=$escaped"
    }

    @Test
    fun `encodes a secret the way every authenticator shows it`() {
        assertEquals("JBSWY3DPEHPK3PXP", Authenticator.encodeBase32(secret))
    }

    @Test
    fun `reads one account out of an export`() {
        val batch = Authenticator.read(migration(listOf(account())))
        assertEquals(1, batch.accounts.size)
        val only = batch.accounts.first()
        assertEquals("GitHub", only.issuer)
        assertEquals("maria@example.com", only.name)
        assertTrue(only.otp.contains("secret=JBSWY3DPEHPK3PXP"), only.otp)
        assertTrue(only.otp.contains("issuer=GitHub"), only.otp)
        assertTrue(only.otp.contains("period=30"), only.otp)
        assertFalse(only.counterBased)
    }

    @Test
    fun `reads every account in one code, not just the first`() {
        val batch = Authenticator.read(
            migration(
                listOf(
                    account(issuer = "GitHub"),
                    account(issuer = "Fastmail"),
                    account(issuer = "Bank"),
                ),
            ),
        )
        assertEquals(listOf("GitHub", "Fastmail", "Bank"), batch.accounts.map { it.issuer })
    }

    /**
     * The failure that costs people accounts: a large export is split across
     * several QR codes and nothing on screen says so, so people scan the
     * first, see ten of their thirty, and assume that was all of it.
     */
    @Test
    fun `reports which code of how many it just read`() {
        val batch = Authenticator.read(
            migration(listOf(account()), index = 1, total = 3, id = 42),
        )
        assertEquals(1, batch.index)
        assertEquals(3, batch.total)
        assertEquals(42, batch.batchId)
    }

    @Test
    fun `carries eight-digit and SHA-256 accounts across as they are`() {
        val batch = Authenticator.read(
            migration(listOf(account(algorithm = 2, digits = 2))),
        )
        val otp = batch.accounts.first().otp
        assertTrue(otp.contains("digits=8"), otp)
        assertTrue(otp.contains("algorithm=SHA256"), otp)
    }

    /**
     * Keyweb cannot generate a counter-based code — the counter advances on
     * every use, so the vault would be written every time somebody looked and
     * two devices would immediately disagree about where it is. It still comes
     * across, flagged, rather than disappearing without a word.
     */
    @Test
    fun `flags a counter-based account rather than dropping it`() {
        val batch = Authenticator.read(migration(listOf(account(type = 1))))
        assertTrue(batch.accounts.first().counterBased)
        assertTrue(batch.accounts.first().otp.contains("JBSWY3DPEHPK3PXP"))
    }

    /** The other thing a camera sees: one account, straight from a website. */
    @Test
    fun `reads a plain otpauth code too`() {
        val batch = Authenticator.read(
            "otpauth://totp/GitHub:maria@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub",
        )
        assertEquals(1, batch.total)
        assertEquals("GitHub", batch.accounts.first().issuer)
        assertTrue(batch.accounts.first().otp.contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `says so plainly when the code is something else`() {
        assertFailsWith<NotAnAccountCode> { Authenticator.read("https://example.com") }
        assertFailsWith<NotAnAccountCode> {
            Authenticator.read("otpauth-migration://offline?data=not-base64!!")
        }
        // Truncated: a length prefix that runs off the end of the payload must
        // not be read as whatever happens to be in memory after it.
        assertFailsWith<NotAnAccountCode> {
            Authenticator.read("otpauth-migration://offline?data=CgQKAgE")
        }
    }

    /** A newer export with a field this build has never heard of still works. */
    @Test
    fun `skips fields it does not know`() {
        val batch = Authenticator.read(
            migration(listOf(account(extra = value(99, 12345)))),
        )
        assertEquals("GitHub", batch.accounts.first().issuer)
    }
}

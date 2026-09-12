package app.keyweb.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RFC 6238 Appendix B, and the same vectors the TypeScript suite uses.
 *
 * Two implementations of TOTP that agree with each other but not with the RFC
 * would be consistently wrong, which is worse than one being broken: every code
 * would be rejected everywhere, with no clue as to why.
 */
class TotpTest {

    private val sha1 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val sha256 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA===="
    private val sha512 =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA="

    private fun config(secret: String = sha1, algorithm: String = "SHA1") =
        OtpConfig(secret = secret, digits = 8, period = 30, algorithm = algorithm)

    @Test
    fun `matches the published vectors`() {
        val cases = listOf(
            Triple(59L, config(), "94287082"),
            Triple(1111111109L, config(), "07081804"),
            Triple(1111111111L, config(), "14050471"),
            Triple(1234567890L, config(), "89005924"),
            Triple(2000000000L, config(), "69279037"),
            // Past where a 32-bit counter silently wraps. The failure this
            // catches would otherwise appear years from now.
            Triple(20000000000L, config(), "65353130"),

            Triple(59L, config(sha256, "SHA256"), "46119246"),
            Triple(1111111109L, config(sha256, "SHA256"), "68084774"),
            Triple(20000000000L, config(sha256, "SHA256"), "77737706"),

            Triple(59L, config(sha512, "SHA512"), "90693936"),
            Triple(1111111109L, config(sha512, "SHA512"), "25091201"),
            Triple(20000000000L, config(sha512, "SHA512"), "47863826"),
        )
        for ((seconds, options, expected) in cases) {
            assertEquals(expected, Totp.at(options, seconds * 1000), "at ${seconds}s")
        }
    }

    @Test
    fun `produces the six digits sites actually ask for`() {
        assertEquals("287082", Totp.at(config().copy(digits = 6), 59_000))
    }

    @Test
    fun `holds steady across a window and changes at the boundary`() {
        val options = config().copy(digits = 6)
        assertEquals(Totp.at(options, 60_000), Totp.at(options, 89_999))
        assertTrue(Totp.at(options, 90_000) != Totp.at(options, 60_000))
    }

    @Test
    fun `counts down to the moment the code changes`() {
        assertEquals(30, Totp.secondsRemaining(config(), 60_000))
        assertEquals(15, Totp.secondsRemaining(config(), 75_000))
        assertEquals(1, Totp.secondsRemaining(config(), 89_000))
    }

    @Test
    fun `takes a bare secret with the spaces a site prints`() {
        val parsed = Totp.parse("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")
        assertEquals(sha1, parsed.secret)
        assertEquals(6, parsed.digits)
    }

    @Test
    fun `takes the otpauth URI behind a QR code`() {
        val parsed = Totp.parse(
            "otpauth://totp/GitHub:maria%40example.com?secret=$sha1&issuer=GitHub&digits=6",
        )
        assertEquals(sha1, parsed.secret)
        assertEquals("GitHub", parsed.issuer)
        assertEquals("maria@example.com", parsed.label)
    }

    @Test
    fun `round-trips through the single stored field`() {
        val original = Totp.parse(
            "otpauth://totp/Bank:me?secret=$sha1&digits=8&period=60&algorithm=SHA256&issuer=Bank",
        )
        val again = Totp.parse(Totp.format(original))
        assertEquals(original.secret, again.secret)
        assertEquals(original.digits, again.digits)
        assertEquals(original.period, again.period)
        assertEquals(original.algorithm, again.algorithm)
    }

    @Test
    fun `refuses a mistyped key rather than printing codes nothing accepts`() {
        // The cruel failure: confident six-digit codes that every site rejects.
        assertFailsWith<InvalidOtpSecret> { Totp.parse("not a real key!!") }
        assertFailsWith<InvalidOtpSecret> { Totp.parse("") }
        assertFailsWith<InvalidOtpSecret> { Totp.decodeBase32("0189") }
    }

    @Test
    fun `says plainly when a key is counter-based`() {
        val failure = assertFailsWith<InvalidOtpSecret> {
            Totp.parse("otpauth://hotp/Bank?secret=$sha1&counter=1")
        }
        assertTrue(failure.message!!.contains("counter-based"))
    }

    @Test
    fun `groups a code for reading aloud`() {
        assertEquals("123 456", Totp.group("123456"))
        assertEquals("1234 5678", Totp.group("12345678"))
    }
}

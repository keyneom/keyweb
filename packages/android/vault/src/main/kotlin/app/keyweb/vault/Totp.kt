package app.keyweb.vault

import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238).
 *
 * A direct counterpart to `packages/web/src/vault/totp.ts`; the reasoning for
 * keeping second factors in the password manager at all lives there and in
 * `docs/two-factor.md`.
 *
 * Checked against the RFC's published vectors rather than against itself. A
 * TOTP implementation that is subtly wrong still emits six plausible digits,
 * and would fail only at a sign-in screen, with nothing to tell anyone why.
 */
class InvalidOtpSecret(
    message: String = "That doesn't look like a valid authentication key.",
) : Exception(message)

data class OtpConfig(
    val secret: String,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String = "SHA1",
    val label: String? = null,
    val issuer: String? = null,
)

object Totp {

    /** RFC 4648 base32, as every authenticator issues it. Not Crockford. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decodeBase32(value: String): ByteArray {
        val cleaned = value.uppercase().replace(Regex("[\\s-]"), "").trimEnd('=')
        if (cleaned.isEmpty()) throw InvalidOtpSecret()

        var bits = 0
        var accumulator = 0
        val bytes = ArrayList<Byte>(cleaned.length)
        for (character in cleaned) {
            val index = ALPHABET.indexOf(character)
            if (index < 0) throw InvalidOtpSecret()
            accumulator = (accumulator shl 5) or index
            bits += 5
            if (bits >= 8) {
                bytes.add(((accumulator ushr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        if (bytes.isEmpty()) throw InvalidOtpSecret()
        return bytes.toByteArray()
    }

    /**
     * Accepts what people actually have to hand.
     *
     * A site offers either a QR code, whose payload is an `otpauth://` URI, or
     * "can't scan it?" text that is the bare base32 secret, usually with spaces
     * in it. Both get pasted here, so both are understood.
     */
    fun parse(input: String): OtpConfig {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw InvalidOtpSecret()

        if (!trimmed.startsWith("otpauth://", ignoreCase = true)) {
            // A bare secret. Validate by decoding, so a typo fails now rather
            // than silently producing codes no site accepts.
            decodeBase32(trimmed)
            return OtpConfig(secret = normalize(trimmed))
        }

        val uri = try {
            URI(trimmed)
        } catch (cause: Exception) {
            throw InvalidOtpSecret()
        }
        if (uri.host?.lowercase() == "hotp") {
            throw InvalidOtpSecret(
                "That's a counter-based key, which Keyweb can't show yet. " +
                    "Ask the site for the time-based one.",
            )
        }

        val params = (uri.rawQuery ?: "").split("&").mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else {
                decodeComponent(pair.substring(0, index)) to decodeComponent(pair.substring(index + 1))
            }
        }.toMap()

        val secret = params["secret"] ?: throw InvalidOtpSecret()
        decodeBase32(secret)

        val digits = params["digits"]?.toIntOrNull()?.takeIf { it in 6..10 } ?: 6
        val period = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30
        val algorithm = when (params["algorithm"]?.uppercase()) {
            "SHA256" -> "SHA256"
            "SHA512" -> "SHA512"
            else -> "SHA1"
        }

        // The path is "/Issuer:account" or "/account".
        val path = decodeComponent((uri.rawPath ?: "").removePrefix("/"))
        val parts = path.split(":")
        val issuer = params["issuer"] ?: parts.takeIf { it.size > 1 }?.first()
        val label = if (parts.size > 1) parts.drop(1).joinToString(":").trim() else path

        return OtpConfig(
            secret = normalize(secret),
            digits = digits,
            period = period,
            algorithm = algorithm,
            label = label.ifBlank { null },
            issuer = issuer?.ifBlank { null },
        )
    }

    /**
     * Stored as an `otpauth://` URI even when the site gave a bare secret, so
     * the digit count and period travel with it in one field.
     */
    fun format(config: OtpConfig): String {
        val label = if (config.issuer != null) {
            "${config.issuer}:${config.label.orEmpty()}"
        } else {
            config.label ?: "Keyweb"
        }
        val query = buildString {
            append("secret=").append(config.secret)
            append("&digits=").append(config.digits)
            append("&period=").append(config.period)
            append("&algorithm=").append(config.algorithm)
            config.issuer?.let { append("&issuer=").append(encodeComponent(it)) }
        }
        return "otpauth://totp/${encodeComponent(label)}?$query"
    }

    /** The code for a given moment. */
    fun at(config: OtpConfig, atMs: Long): String {
        val counter = atMs / 1000 / config.period
        val message = ByteArray(8)
        for (index in 7 downTo 0) {
            message[index] = ((counter shr ((7 - index) * 8)) and 0xff).toByte()
        }

        val mac = Mac.getInstance("Hmac${config.algorithm}")
        mac.init(SecretKeySpec(decodeBase32(config.secret), "Hmac${config.algorithm}"))
        val digest = mac.doFinal(message)

        // Dynamic truncation, RFC 4226 section 5.4.
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val binary =
            ((digest[offset].toInt() and 0x7f) shl 24) or
                ((digest[offset + 1].toInt() and 0xff) shl 16) or
                ((digest[offset + 2].toInt() and 0xff) shl 8) or
                (digest[offset + 3].toInt() and 0xff)

        var modulus = 1
        repeat(config.digits) { modulus *= 10 }
        // Padded, because a code of "012345" typed as "12345" is rejected and
        // the person retyping it has no idea why.
        return (binary % modulus).toString().padStart(config.digits, '0')
    }

    /** Seconds until the current code stops working. */
    fun secondsRemaining(config: OtpConfig, atMs: Long): Int =
        config.period - ((atMs / 1000) % config.period).toInt()

    /** `123 456` — grouped, because it is read aloud and typed under pressure. */
    fun group(code: String): String {
        val half = (code.length + 1) / 2
        return "${code.take(half)} ${code.drop(half)}"
    }

    private fun normalize(value: String): String =
        value.uppercase().replace(Regex("[\\s-]"), "").trimEnd('=')

    private fun decodeComponent(value: String): String =
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun encodeComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

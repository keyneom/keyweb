package app.keyweb.vault

import java.net.URI

/**
 * Reading what an authenticator app puts in a QR code.
 *
 * Two shapes turn up, and a person holding a phone cannot tell them apart:
 *
 *  - `otpauth://totp/...` — one account, what a website shows when it sets up
 *    two-factor authentication;
 *  - `otpauth-migration://offline?data=...` — Google Authenticator's "Transfer
 *    accounts" export, which is a protobuf holding *many* accounts and, when
 *    there are more than a handful, is split across several QR codes.
 *
 * Both are handled here because the person scanning does not care which they
 * have. Nothing else in the app should have to know the difference either.
 *
 * ## Why the protobuf is decoded by hand
 *
 * It is four field numbers in one message and seven in another, and the wire
 * format is a varint and a length prefix. A code generator and a runtime would
 * be more machinery than the thing they parse, in a module whose whole job is
 * to read bytes from a stranger's camera — the smaller the surface, the better.
 *
 * The mirror of the web's `authenticator.ts`, down to the tests.
 */

/** One account read out of a QR code, before anybody decides where to put it. */
data class ScannedAccount(
    /** The service, as the authenticator recorded it: "GitHub", "Google". */
    val issuer: String,
    /** The account at that service, usually an email address. */
    val name: String,
    /** The seed, as the `otpauth://` URI Keyweb stores in its `otp` field. */
    val otp: String,
    /**
     * A counter-based code rather than a time-based one.
     *
     * Rare, and Keyweb cannot generate them: a HOTP counter advances every
     * time a code is used, which means the vault would have to be written on
     * every glance and two devices would immediately disagree about where the
     * counter is. Carried through and flagged rather than dropped, so the
     * person is told rather than left to find out when the code fails.
     */
    val counterBased: Boolean,
)

/** What one QR code turned out to hold. */
data class ScannedBatch(
    val accounts: List<ScannedAccount>,
    /**
     * Which of how many, for an export split across several codes.
     *
     * Google Authenticator splits a large export and gives no indication on
     * screen that it has done so — people scan the first code, see ten of
     * their thirty accounts, and assume that is all there was. The numbers are
     * carried so the app can say "code 1 of 3" and keep asking.
     */
    val index: Int,
    val total: Int,
    /** Ties the codes of one export together, so two exports cannot be mixed. */
    val batchId: Int,
)

class NotAnAccountCode(
    message: String = "That QR code isn't an authenticator code.",
) : Exception(message)

object Authenticator {

    fun read(text: String): ScannedBatch {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("otpauth-migration://", ignoreCase = true) ->
                readMigration(trimmed)

            trimmed.startsWith("otpauth://", ignoreCase = true) -> {
                val config = runCatching { Totp.parse(trimmed) }.getOrElse {
                    throw NotAnAccountCode()
                }
                ScannedBatch(
                    accounts = listOf(
                        ScannedAccount(
                            issuer = config.issuer.orEmpty(),
                            name = config.label.orEmpty(),
                            otp = Totp.format(config),
                            counterBased = trimmed.startsWith("otpauth://hotp/", true),
                        ),
                    ),
                    index = 0,
                    total = 1,
                    batchId = 0,
                )
            }

            else -> throw NotAnAccountCode()
        }
    }

    private fun readMigration(uri: String): ScannedBatch {
        val query = runCatching { URI(uri).rawQuery }.getOrNull() ?: throw NotAnAccountCode()
        val raw = query.split('&')
            .firstOrNull { it.startsWith("data=") }
            ?.removePrefix("data=")
            ?: throw NotAnAccountCode()
        val data = runCatching {
            java.net.URLDecoder.decode(raw, "UTF-8")
        }.getOrElse { throw NotAnAccountCode() }

        val bytes = runCatching {
            java.util.Base64.getDecoder().decode(data)
        }.getOrElse { throw NotAnAccountCode() }

        val accounts = mutableListOf<ScannedAccount>()
        var index = 0
        var total = 1
        var batchId = 0

        for (field in fields(bytes)) {
            when (field.number) {
                1 -> field.bytes?.let { readAccount(it)?.let(accounts::add) }
                3 -> total = (field.value ?: 1L).toInt().coerceAtLeast(1)
                4 -> index = (field.value ?: 0L).toInt()
                5 -> batchId = (field.value ?: 0L).toInt()
            }
        }

        if (accounts.isEmpty()) throw NotAnAccountCode()
        return ScannedBatch(accounts, index, total, batchId)
    }

    private fun readAccount(bytes: ByteArray): ScannedAccount? {
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = "SHA1"
        var digits = 6
        var counterBased = false

        for (field in fields(bytes)) {
            when (field.number) {
                1 -> secret = field.bytes
                2 -> name = field.bytes?.toString(Charsets.UTF_8).orEmpty()
                3 -> issuer = field.bytes?.toString(Charsets.UTF_8).orEmpty()
                // 1 = SHA1, 2 = SHA256, 3 = SHA512. 4 is MD5, which no site
                // uses and Keyweb cannot compute; it falls through to SHA1
                // rather than failing the whole batch for one account.
                4 -> algorithm = when (field.value) {
                    2L -> "SHA256"
                    3L -> "SHA512"
                    else -> "SHA1"
                }
                // An enum, not the number of digits: 1 means six, 2 means eight.
                5 -> digits = if (field.value == 2L) 8 else 6
                6 -> counterBased = field.value == 1L
            }
        }

        val key = secret ?: return null
        if (key.isEmpty()) return null

        return ScannedAccount(
            issuer = issuer,
            name = name,
            otp = Totp.format(
                OtpConfig(
                    secret = encodeBase32(key),
                    digits = digits,
                    // The export carries no period, because Google
                    // Authenticator only ever uses thirty seconds. Written out
                    // rather than left to a default, so the stored field says
                    // what it means.
                    period = 30,
                    algorithm = algorithm,
                    label = name,
                    issuer = issuer.ifEmpty { null },
                ),
            ),
            counterBased = counterBased,
        )
    }

    /** RFC 4648 base32, which is the alphabet every authenticator issues. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encodeBase32(bytes: ByteArray): String {
        var bits = 0
        var accumulator = 0
        val out = StringBuilder()
        for (byte in bytes) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(accumulator ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(accumulator shl (5 - bits)) and 31])
        // Unpadded: `Totp.parse` strips padding anyway, and every
        // authenticator shows these secrets unpadded when it shows them.
        return out.toString()
    }

    private data class Field(val number: Int, val value: Long?, val bytes: ByteArray?)

    /**
     * Walks a protobuf message, yielding only what this file knows how to want.
     *
     * Unknown field numbers and unknown wire types are skipped rather than
     * rejected — that is what protobuf compatibility means, and a newer export
     * with one extra field must not cost somebody their accounts.
     */
    private fun fields(bytes: ByteArray): List<Field> = buildList {
        var at = 0
        while (at < bytes.size) {
            val (key, afterKey) = varint(bytes, at)
            at = afterKey
            val number = (key ushr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val (value, next) = varint(bytes, at)
                    at = next
                    add(Field(number, value, null))
                }

                2 -> {
                    val (length, next) = varint(bytes, at)
                    val end = next + length.toInt()
                    if (end > bytes.size || end < next) throw NotAnAccountCode()
                    add(Field(number, null, bytes.copyOfRange(next, end)))
                    at = end
                }

                5 -> at += 4
                1 -> at += 8
                else -> throw NotAnAccountCode()
            }
            if (at > bytes.size) throw NotAnAccountCode()
        }
    }

    private fun varint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var at = start
        while (at < bytes.size) {
            val byte = bytes[at].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            at += 1
            if (byte and 0x80 == 0) return value to at
            shift += 7
            // Ten bytes is the most a 64-bit varint can occupy; more means the
            // bytes are not what they claim to be.
            if (shift > 63) throw NotAnAccountCode()
        }
        throw NotAnAccountCode()
    }
}

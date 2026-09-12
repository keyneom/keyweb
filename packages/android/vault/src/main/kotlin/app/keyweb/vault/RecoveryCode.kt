package app.keyweb.vault

/**
 * The printed recovery code.
 *
 * A direct port of `packages/web/src/vault/recovery.ts`. It has to be a port
 * rather than a lookalike: a code written down from the web app is typed into
 * the phone, and the bytes behind it must come out identical or the backup does
 * not open.
 *
 * 20 bytes — 160 bits, exactly 32 characters with no padding waste. The size
 * carries the whole defence, because the key derivation is HKDF, which is fast
 * by design and adds no brute-force resistance of its own. That is sound only
 * because this code is generated rather than chosen. 160 bits leaves ~80 even
 * against Grover's algorithm, for a backup that may sit in Drive for decades.
 */
object RecoveryCode {

    /**
     * Crockford base32: no I, L, O or U, so 1 cannot be read as l, 0 as O, and
     * no word in the alphabet can spell anything unfortunate.
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    const val SECRET_BYTES = 20
    private const val GROUP = 4

    /** 32 characters. */
    const val CODE_LENGTH = SECRET_BYTES * 8 / 5

    fun generate(): ByteArray = KeywebEnvelope.randomBytes(SECRET_BYTES)

    /** `H7K2-9MNP-...`, eight groups of four. */
    fun format(secret: ByteArray): String {
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (byte in secret) {
            value = (value shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(value ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(value shl (5 - bits)) and 31])
        return out.chunked(GROUP).joinToString("-")
    }

    /**
     * Parse a code as a person actually typed it.
     *
     * Lower case, stray spaces, missing dashes, and I/L typed for 1 or O for 0
     * are all accepted, because someone reading a printed sheet makes exactly
     * those mistakes and a rejected code reads as "my backup is gone".
     */
    fun parse(code: String): ByteArray {
        val cleaned = code
            .uppercase()
            .replace(Regex("[\\s-]"), "")
            .replace(Regex("[IL]"), "1")
            .replace("O", "0")
        if (cleaned.isEmpty()) throw InvalidRecoveryCode()

        var bits = 0
        var value = 0
        val bytes = ArrayList<Byte>(SECRET_BYTES)
        for (character in cleaned) {
            val index = ALPHABET.indexOf(character)
            if (index < 0) throw InvalidRecoveryCode()
            value = (value shl 5) or index
            bits += 5
            if (bits >= 8) {
                bytes.add(((value ushr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        if (bytes.size != SECRET_BYTES) {
            throw InvalidRecoveryCode(
                "A recovery code is $CODE_LENGTH characters. That one has ${cleaned.length}.",
            )
        }
        return bytes.toByteArray()
    }
}

class InvalidRecoveryCode(
    message: String = "That recovery code isn't right. Check it and try again.",
) : Exception(message)

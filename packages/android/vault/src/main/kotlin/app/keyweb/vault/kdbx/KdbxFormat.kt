package app.keyweb.vault.kdbx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The KDBX container format, as KeePass and KeeWeb write it.
 *
 * Implemented from the format rather than ported from a library, because the
 * JVM has no KDBX reader and the web side gets one free from `kdbxweb`. The
 * risk in that is silent divergence — a file the browser opens and the phone
 * does not, or worse, one both open differently. So correctness is settled
 * against real files produced by `kdbxweb` itself, not against this code's own
 * idea of the format.
 *
 * Two versions are in circulation and both are read here. KDBX 3.1 is what
 * older KeePass installs still write; KDBX 4 is the current one, and the two
 * differ in almost every structural decision — key derivation, block framing,
 * where the inner stream parameters live. Supporting only the newer would leave
 * anyone with an older database unable to import at all.
 */
internal const val SIGNATURE_1 = 0x9AA2D903.toInt()
internal const val SIGNATURE_2 = 0xB54BFB67.toInt()

internal const val VERSION_3_1 = 0x00030001
internal const val VERSION_4_0 = 0x00040000

class KdbxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The master password was wrong, or the file was altered after it was written. */
class WrongMasterPassword :
    Exception("That password didn't open the file. Check it and try again.")

/** Little-endian throughout: KDBX is a Windows format and never varies. */
internal class LittleEndianReader(private val bytes: ByteArray) {
    var position: Int = 0
        private set

    fun remaining(): Int = bytes.size - position

    fun byte(): Int = take(1)[0].toInt() and 0xff

    fun uint16(): Int = ByteBuffer.wrap(take(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    fun int32(): Int = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).int

    fun uint32(): Long = int32().toLong() and 0xffff_ffffL

    fun int64(): Long = ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).long

    fun take(count: Int): ByteArray {
        if (count < 0 || position + count > bytes.size) {
            throw KdbxException("This file ends sooner than it should. It may be damaged.")
        }
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun rest(): ByteArray = take(remaining())

    fun sliceFromStart(end: Int): ByteArray = bytes.copyOfRange(0, end)
}

internal object HeaderField {
    const val END = 0
    const val CIPHER_ID = 2
    const val COMPRESSION = 3
    const val MASTER_SEED = 4
    const val TRANSFORM_SEED = 5
    const val TRANSFORM_ROUNDS = 6
    const val ENCRYPTION_IV = 7
    const val PROTECTED_STREAM_KEY = 8
    const val STREAM_START_BYTES = 9
    const val INNER_RANDOM_STREAM_ID = 10
    const val KDF_PARAMETERS = 11
}

internal object InnerHeaderField {
    const val END = 0
    const val STREAM_ID = 1
    const val STREAM_KEY = 2
    const val BINARY = 3
}

/** Cipher and KDF identifiers, as 16-byte UUIDs in the file. */
internal object Uuids {
    val AES_CBC = uuid("31C1F2E6BF714350BE5805216AFC5AFF")
    val CHACHA20 = uuid("D6038A2B8B6F4CB5A524339A31DBB59A")
    val AES_KDF = uuid("C9D9F39A628A4460BF740D08C18A4FEA")
    val ARGON2D = uuid("EF636DDF8C29444B91F7A9A403E30A0C")
    val ARGON2ID = uuid("9E298B1956DB4773B23DFC3EC6F0A1E6")

    private fun uuid(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * KeePass's own little key-value format, used for the KDF parameters.
 *
 * Only the types KDBX actually uses are handled. An unknown type is an error
 * rather than a skip: the KDF parameters decide the key, so quietly ignoring
 * one would produce a wrong key and an indistinguishable "wrong password".
 */
internal class VariantDictionary(private val values: Map<String, Any>) {

    fun bytes(key: String): ByteArray? = values[key] as? ByteArray

    fun long(key: String): Long? = when (val value = values[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    companion object {
        fun parse(bytes: ByteArray): VariantDictionary {
            val reader = LittleEndianReader(bytes)
            reader.uint16() // Format version; only the major part matters and 1 is all there is.
            val values = mutableMapOf<String, Any>()
            while (true) {
                val type = reader.byte()
                if (type == 0) break
                val key = String(reader.take(reader.int32()), Charsets.UTF_8)
                val size = reader.int32()
                val raw = reader.take(size)
                values[key] = when (type) {
                    0x04 -> LittleEndianReader(raw).uint32()
                    0x05 -> LittleEndianReader(raw).int64()
                    0x08 -> raw[0].toInt() != 0
                    0x0C -> LittleEndianReader(raw).int32()
                    0x0D -> LittleEndianReader(raw).int64()
                    0x18 -> String(raw, Charsets.UTF_8)
                    0x42 -> raw
                    else -> throw KdbxException(
                        "This file uses a setting Keyweb doesn't recognise (type $type).",
                    )
                }
            }
            return VariantDictionary(values)
        }
    }
}

/** Everything the outer header carries, whichever version wrote it. */
internal class KdbxHeader(
    val version: Int,
    val cipherId: ByteArray,
    val compressed: Boolean,
    val masterSeed: ByteArray,
    val encryptionIv: ByteArray,
    val transformSeed: ByteArray?,
    val transformRounds: Long?,
    val protectedStreamKey: ByteArray?,
    val streamStartBytes: ByteArray?,
    val innerStreamId: Int?,
    val kdfParameters: VariantDictionary?,
    /** The raw header bytes, which are authenticated in KDBX 4. */
    val raw: ByteArray,
) {
    val isVersion4: Boolean get() = version >= VERSION_4_0

    companion object {
        fun parse(reader: LittleEndianReader): KdbxHeader {
            if (reader.int32() != SIGNATURE_1 || reader.int32() != SIGNATURE_2) {
                throw KdbxException("That isn't a KeePass file.")
            }
            val version = reader.int32()
            if (version < VERSION_3_1) {
                throw KdbxException(
                    "That file was written by a version of KeePass too old for Keyweb to read.",
                )
            }
            val isV4 = version >= VERSION_4_0

            var cipherId: ByteArray? = null
            var compressed = false
            var masterSeed: ByteArray? = null
            var encryptionIv: ByteArray? = null
            var transformSeed: ByteArray? = null
            var transformRounds: Long? = null
            var protectedStreamKey: ByteArray? = null
            var streamStartBytes: ByteArray? = null
            var innerStreamId: Int? = null
            var kdfParameters: VariantDictionary? = null

            while (true) {
                val id = reader.byte()
                val size = if (isV4) reader.int32() else reader.uint16()
                val data = reader.take(size)
                if (id == HeaderField.END) break
                when (id) {
                    HeaderField.CIPHER_ID -> cipherId = data
                    HeaderField.COMPRESSION ->
                        compressed = LittleEndianReader(data).uint32() == 1L
                    HeaderField.MASTER_SEED -> masterSeed = data
                    HeaderField.TRANSFORM_SEED -> transformSeed = data
                    HeaderField.TRANSFORM_ROUNDS ->
                        transformRounds = LittleEndianReader(data).int64()
                    HeaderField.ENCRYPTION_IV -> encryptionIv = data
                    HeaderField.PROTECTED_STREAM_KEY -> protectedStreamKey = data
                    HeaderField.STREAM_START_BYTES -> streamStartBytes = data
                    HeaderField.INNER_RANDOM_STREAM_ID ->
                        innerStreamId = LittleEndianReader(data).uint32().toInt()
                    HeaderField.KDF_PARAMETERS -> kdfParameters = VariantDictionary.parse(data)
                    // Comment and public custom data carry nothing we need, but
                    // they are part of the authenticated header and must not be
                    // treated as an error.
                }
            }

            return KdbxHeader(
                version = version,
                cipherId = cipherId
                    ?: throw KdbxException("This file doesn't say how it was encrypted."),
                compressed = compressed,
                masterSeed = masterSeed
                    ?: throw KdbxException("This file is missing the seed needed to open it."),
                encryptionIv = encryptionIv
                    ?: throw KdbxException("This file is missing its encryption details."),
                transformSeed = transformSeed,
                transformRounds = transformRounds,
                protectedStreamKey = protectedStreamKey,
                streamStartBytes = streamStartBytes,
                innerStreamId = innerStreamId,
                kdfParameters = kdfParameters,
                raw = reader.sliceFromStart(reader.position),
            )
        }
    }
}

/** The inner header, which only KDBX 4 has. */
internal class KdbxInnerHeader(
    val streamId: Int,
    val streamKey: ByteArray,
    /**
     * Attached files, in the order the header lists them.
     *
     * Position *is* the identity: an entry refers to a file by the index it
     * appeared at, so these must be collected even when a particular file is
     * never referenced, or every later reference points at the wrong file.
     */
    val binaries: List<ByteArray>,
) {
    companion object {
        fun parse(reader: LittleEndianReader): KdbxInnerHeader {
            var streamId: Int? = null
            var streamKey: ByteArray? = null
            val binaries = mutableListOf<ByteArray>()
            while (true) {
                val id = reader.byte()
                val size = reader.int32()
                val data = reader.take(size)
                if (id == InnerHeaderField.END) break
                when (id) {
                    InnerHeaderField.STREAM_ID ->
                        streamId = LittleEndianReader(data).uint32().toInt()
                    InnerHeaderField.STREAM_KEY -> streamKey = data
                    // The first byte is a flags byte — bit 0 means the file is
                    // memory-protected — and the rest is the file itself.
                    InnerHeaderField.BINARY ->
                        binaries += if (data.isEmpty()) data else data.copyOfRange(1, data.size)
                }
            }
            return KdbxInnerHeader(
                streamId ?: throw KdbxException("This file is missing its inner header."),
                streamKey ?: throw KdbxException("This file is missing its inner header key."),
                binaries,
            )
        }
    }
}

/**
 * KDBX 4 frames its payload as HMAC-authenticated blocks.
 *
 * Each block carries its own HMAC over its own index, so neither reordering nor
 * truncation goes unnoticed. Verifying rather than trusting matters here: the
 * file comes from cloud storage, and a silently truncated one would otherwise
 * import as a *partial* vault, which looks like success.
 */
internal fun readHmacBlocks(reader: LittleEndianReader, hmacKey: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var index = 0L
    while (true) {
        val expected = reader.take(32)
        val length = reader.int32()
        if (length < 0) throw KdbxException("This file is damaged.")
        val data = reader.take(length)

        val actual = KdbxCrypto.blockHmac(hmacKey, index, length, data)
        if (!actual.contentEquals(expected)) {
            throw KdbxException("This file has been damaged or altered since it was saved.")
        }
        if (length == 0) break
        out.write(data)
        index += 1
    }
    return out.toByteArray()
}

/**
 * KDBX 3.1 frames its payload as SHA-256 hashed blocks.
 *
 * Weaker than the HMAC framing — a hash proves the block is intact, not that it
 * came from whoever held the key — but it is what the format specifies, and
 * checking it still catches a corrupted download.
 */
internal fun readHashedBlocks(reader: LittleEndianReader): ByteArray {
    val out = ByteArrayOutputStream()
    var expectedIndex = 0
    while (true) {
        val index = reader.int32()
        if (index != expectedIndex) throw KdbxException("This file's blocks are out of order.")
        val hash = reader.take(32)
        val length = reader.int32()
        if (length < 0) throw KdbxException("This file is damaged.")
        if (length == 0) {
            if (hash.any { it.toInt() != 0 }) {
                throw KdbxException("This file is damaged.")
            }
            break
        }
        val data = reader.take(length)
        if (!KdbxCrypto.sha256(data).contentEquals(hash)) {
            throw KdbxException("This file has been damaged since it was saved.")
        }
        out.write(data)
        expectedIndex += 1
    }
    return out.toByteArray()
}

package app.keyweb.vault.kdbx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.generators.Argon2BytesGenerator

/**
 * The cryptography KDBX needs.
 *
 * The primitives come from BouncyCastle rather than from here. Argon2 in
 * particular is a password-hashing function guarding every password someone
 * owns; writing one by hand to save a dependency would be a poor trade at any
 * size. What this file does is assemble them in the exact order KeePass
 * specifies, which is where the real risk of a subtle mistake lies.
 */
internal object KdbxCrypto {

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * The per-block HMAC key is derived from the block index, so a block cannot
     * be moved, dropped or replayed without the check failing.
     */
    fun blockHmac(hmacKey: ByteArray, index: Long, length: Int, data: ByteArray): ByteArray {
        val prefix = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(index).putInt(length).array()
        return hmacSha256(blockKey(hmacKey, index), prefix + data)
    }

    /**
     * The header's HMAC covers the header bytes alone.
     *
     * Deliberately *not* the block format: a block authenticates its own index
     * and length as well as its data, while the header authenticates only
     * itself, under the key for index 0xFFFFFFFFFFFFFFFF. Reusing the block
     * shape here computes a different value and every KDBX 4 file looks like a
     * wrong password.
     */
    fun headerHmac(hmacKey: ByteArray, header: ByteArray): ByteArray =
        hmacSha256(blockKey(hmacKey, -1L), header)

    private fun blockKey(hmacKey: ByteArray, index: Long): ByteArray =
        sha512(le64(index), hmacKey)

    fun le64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /**
     * The composite key: what the user knows, hashed into a fixed size.
     *
     * A key file is hashed alongside the password when one is used. Both are
     * folded into a single SHA-256 before the KDF ever sees them, which is what
     * lets the expensive step have a fixed-size input.
     */
    fun compositeKey(password: String, keyFile: ByteArray?): ByteArray {
        val parts = ArrayList<ByteArray>(2)
        // An empty password still contributes its hash; KeePass only omits the
        // component when the password is not used at all, which Keyweb's import
        // does not offer.
        parts += sha256(password.toByteArray(Charsets.UTF_8))
        keyFile?.let { parts += keyFileKey(it) }
        return sha256(*parts.toTypedArray())
    }

    /**
     * Key files come in three shapes and all three are in the wild: a KeePass
     * XML key file, a bare 32-byte binary file, or a 64-character hex file.
     * Anything else is hashed whole, which is what KeePass does too.
     */
    private fun keyFileKey(bytes: ByteArray): ByteArray {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (text != null && text.contains("<KeyFile")) {
            val data = Regex("<Data[^>]*>([^<]+)</Data>").find(text)?.groupValues?.get(1)?.trim()
            if (data != null) {
                return runCatching { java.util.Base64.getDecoder().decode(data) }
                    .getOrElse { sha256(bytes) }
            }
        }
        if (bytes.size == 32) return bytes
        if (bytes.size == 64 && text != null && text.all { it.isDigit() || it in "abcdefABCDEF" }) {
            return ByteArray(32) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        return sha256(bytes)
    }

    /**
     * KDBX 3.1: repeated AES-ECB over the composite key.
     *
     * A deliberately slow loop rather than a memory-hard function, which is why
     * KDBX 4 replaced it. Millions of rounds are normal, so this is the slow
     * part of opening an older file and there is nothing to be done about it.
     */
    fun aesKdf(compositeKey: ByteArray, seed: ByteArray, rounds: Long): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
        val block = compositeKey.copyOf()
        var remaining = rounds
        while (remaining > 0) {
            cipher.update(block, 0, 16, block, 0)
            cipher.update(block, 16, 16, block, 16)
            remaining -= 1
        }
        return sha256(block)
    }

    /** KDBX 4: Argon2d or Argon2id, with the file's own parameters. */
    fun argon2(
        compositeKey: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        memoryKib: Int,
        iterations: Int,
        version: Int,
        argon2id: Boolean,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(
            if (argon2id) Argon2Parameters.ARGON2_id else Argon2Parameters.ARGON2_d,
        )
            .withSalt(salt)
            .withParallelism(parallelism)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withVersion(version)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        return ByteArray(32).also { generator.generateBytes(compositeKey, it) }
    }

    fun decryptAesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return try {
            cipher.doFinal(data)
        } catch (cause: Exception) {
            // Bad padding here means the key was wrong, which in practice means
            // the password was. Saying so beats "decryption failed".
            throw WrongMasterPassword()
        }
    }

    fun decryptChaCha20(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(false, ParametersWithIV(KeyParameter(key), iv))
        return ByteArray(data.size).also { engine.processBytes(data, 0, data.size, it, 0) }
    }

    /**
     * The inner stream that protects individual values.
     *
     * Every protected field in the XML is a slice of one keystream, consumed in
     * document order. Read them out of order and every value after the first is
     * garbage — which is why the XML walk below is strictly sequential.
     */
    fun innerStream(streamId: Int, key: ByteArray): InnerStream = when (streamId) {
        2 -> SalsaStream(sha256(key))
        3 -> ChaChaStream(sha512(key))
        else -> throw KdbxException("This file protects its passwords in a way Keyweb can't read.")
    }
}

internal interface InnerStream {
    fun decrypt(data: ByteArray): ByteArray
}

/** KDBX 3.1 used Salsa20 with a fixed nonce, which the format hard-codes. */
private class SalsaStream(key: ByteArray) : InnerStream {
    private val engine = Salsa20Engine().apply {
        init(false, ParametersWithIV(KeyParameter(key), SALSA_NONCE))
    }

    override fun decrypt(data: ByteArray): ByteArray =
        ByteArray(data.size).also { engine.processBytes(data, 0, data.size, it, 0) }

    companion object {
        private val SALSA_NONCE =
            byteArrayOf(0xE8.toByte(), 0x30, 0x09, 0x4B, 0x97.toByte(), 0x20, 0x5D, 0x2A)
    }
}

/** KDBX 4 uses ChaCha20, keyed and nonced from one SHA-512 of the stream key. */
private class ChaChaStream(keyMaterial: ByteArray) : InnerStream {
    private val engine = ChaCha7539Engine().apply {
        init(
            false,
            ParametersWithIV(
                KeyParameter(keyMaterial.copyOfRange(0, 32)),
                keyMaterial.copyOfRange(32, 44),
            ),
        )
    }

    override fun decrypt(data: ByteArray): ByteArray =
        ByteArray(data.size).also { engine.processBytes(data, 0, data.size, it, 0) }
}

internal fun gunzip(bytes: ByteArray): ByteArray =
    try {
        java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    } catch (cause: Exception) {
        throw KdbxException("This file's contents could not be unpacked.", cause)
    }

internal fun concat(parts: List<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    for (part in parts) out.write(part)
    return out.toByteArray()
}

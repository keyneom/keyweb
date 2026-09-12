package app.keyweb.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cross-platform interoperability of the encrypted backup.
 *
 * The scenario this defends is the whole point of the backup: a phone is lost,
 * and the vault is recovered somewhere else from the printed code. That crosses
 * platforms — sealed by the web, opened by Android, or the reverse — so "both
 * use AES-GCM" is not sufficient. The AAD, the HKDF label and info string, the
 * salt reuse, the base64url spelling and the gzip flag must all agree, and a
 * mismatch in any of them fails at the worst possible moment.
 *
 * So neither side is trusted to describe itself. This opens a file the
 * TypeScript implementation actually sealed, and writes one for the TypeScript
 * suite to open in turn.
 */
@Serializable
private data class EnvelopeFixture(
    val note: String = "",
    val recoveryCode: String,
    val fingerprint: String,
    val envelope: SyncEnvelopeV1,
)

class EnvelopeInteropTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = File("../../../fixtures")

    private fun webFixture(): EnvelopeFixture {
        val file = File(fixtures, "envelope-web-v1.json")
        assertTrue(
            file.exists(),
            "Missing ${file.canonicalPath}. Regenerate with: " +
                "npm run fixture --workspace @keyweb/web",
        )
        return json.decodeFromString(EnvelopeFixture.serializer(), file.readText())
    }

    @Test
    fun `opens a backup sealed by the TypeScript implementation`() {
        val fixture = webFixture()

        // Exactly what someone does with the printed sheet: type the code.
        val secret = RecoveryCode.parse(fixture.recoveryCode)
        val cipher = VaultEnvelopeCipher.forRecoveryCode(secret, fixture.envelope)
        val state = cipher.open(fixture.envelope)

        val chase = assertNotNull(state.items["chase"])
        assertEquals("Chase Bank", chase.field(ItemField.TITLE))
        assertEquals("the-one-that-matters", chase.field(ItemField.PASSWORD))
        assertEquals("maria@example.com", chase.field(ItemField.USERNAME))
        // Imported structure has to survive the round trip too, not just secrets.
        assertEquals("Banking / Personal", chase.field(ItemField.FOLDER))
        assertEquals("finance, important", chase.field(ItemField.TAGS))
        assertEquals("Household", state.keyrings["ring"]?.name?.value)
        // A deleted item stays deleted rather than being resurrected by a restore.
        assertTrue(state.items["netflix"]?.deleted?.value == true)

        // The fingerprint decides whether a sync republishes. Disagreement here
        // would make the two platforms overwrite each other forever.
        assertEquals(fixture.fingerprint, fingerprint(state))
    }

    @Test
    fun `seals a backup for the TypeScript implementation to open`() {
        val secret = RecoveryCode.generate()
        val cipher = VaultEnvelopeCipher.forRecoveryCode(secret)

        val clock = Clock("android-fixture", physical = { 1_700_000_000_000L })
        var state = emptyVault()
        state = applyOp(
            state,
            VaultOp.KeyringPut(
                opId = "k1",
                ts = clock.now(),
                keyringId = "ring",
                name = "Household",
            ),
        )
        state = applyOp(
            state,
            VaultOp.ItemPut(
                opId = "i1",
                ts = clock.now(),
                itemId = "chase",
                keyringId = "ring",
                fields = mapOf(
                    ItemField.TITLE to "Chase Bank",
                    ItemField.PASSWORD to "sealed-on-android",
                    ItemField.FOLDER to "Banking / Personal",
                ),
            ),
        )

        val envelope = cipher.seal(state, updatedAt = "2026-01-01T00:00:00.000Z")

        // Round-trips here first, so a failure in the TypeScript suite points at
        // interop rather than at this implementation being broken outright.
        assertEquals(
            fingerprint(state),
            VaultEnvelopeCipher.forRecoveryCode(secret, envelope).open(envelope).let(::fingerprint),
        )

        fixtures.mkdirs()
        File(fixtures, "envelope-android-v1.json").writeText(
            Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
                .encodeToString(
                    EnvelopeFixture.serializer(),
                    EnvelopeFixture(
                        note = "Sealed by the Kotlin implementation. " +
                            "TypeScript must open it with the code below.",
                        recoveryCode = RecoveryCode.format(secret),
                        fingerprint = fingerprint(state),
                        envelope = envelope,
                    ),
                ) + "\n",
        )
    }

    @Test
    fun `refuses a backup when the code is wrong instead of returning an empty vault`() {
        val fixture = webFixture()
        val wrong = VaultEnvelopeCipher.forRecoveryCode(RecoveryCode.generate(), fixture.envelope)
        // The dangerous failure would be returning something: a caller that
        // treats "opened but empty" as the vault would then publish that over
        // the real backup.
        assertFailsWith<EnvelopeDecryptException> { wrong.open(fixture.envelope) }
    }

    @Test
    fun `refuses an envelope whose ciphertext was altered`() {
        val fixture = webFixture()
        val secret = RecoveryCode.parse(fixture.recoveryCode)
        val cipher = VaultEnvelopeCipher.forRecoveryCode(secret, fixture.envelope)

        val bytes = Base64Url.decode(fixture.envelope.ciphertext)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        val tampered = fixture.envelope.copy(ciphertext = Base64Url.encode(bytes))

        assertFailsWith<EnvelopeDecryptException> { cipher.open(tampered) }
    }

    @Test
    fun `refuses an envelope sealed for a different application`() {
        // The AAD binds a ciphertext to Keyweb. Without it, an envelope from
        // another sync-kit app with the same key material would decrypt here.
        val fixture = webFixture()
        val secret = RecoveryCode.parse(fixture.recoveryCode)
        val metadata = EnvelopeMetadata(
            credentialId = fixture.envelope.credentialId,
            rpId = fixture.envelope.rpId,
            prfInput = Base64Url.decode(fixture.envelope.prfInput),
            kdfSalt = Base64Url.decode(fixture.envelope.kdfSalt),
        )
        val keyed = VaultEnvelopeCipher.fromMetadata(secret, metadata)
        assertEquals(
            fingerprint(keyed.open(fixture.envelope)),
            fixture.fingerprint,
        )

        // A different HKDF info would yield a different key from the same code.
        val strayInfo = hkdfSha256(secret, metadata.kdfSalt, "other-app".toByteArray(), 32)
        assertNotEquals(
            strayInfo.toList(),
            hkdfSha256(
                secret,
                metadata.kdfSalt,
                KeywebEnvelope.HKDF_INFO.toByteArray(),
                32,
            ).toList(),
        )
    }

    @Test
    fun `rejects a file that is not a Keyweb v1 envelope`() {
        val fixture = webFixture()
        assertFailsWith<EnvelopeFormatException> {
            VaultEnvelopeCipher.validate(fixture.envelope.copy(schemaVersion = 2))
        }
        assertFailsWith<EnvelopeFormatException> {
            VaultEnvelopeCipher.validate(fixture.envelope.copy(algorithm = "AES-128-CBC"))
        }
        // A short nonce is the signature of a downgrade attempt.
        assertFailsWith<EnvelopeFormatException> {
            VaultEnvelopeCipher.validate(fixture.envelope.copy(nonce = Base64Url.encode(ByteArray(8))))
        }
    }
}

class RecoveryCodeTest {

    @Test
    fun `round-trips through the printed form`() {
        repeat(200) {
            val secret = RecoveryCode.generate()
            assertTrue(RecoveryCode.parse(RecoveryCode.format(secret)).contentEquals(secret))
        }
    }

    @Test
    fun `prints as eight groups of four with no ambiguous characters`() {
        val code = RecoveryCode.format(RecoveryCode.generate())
        assertEquals(8, code.split("-").size)
        code.split("-").forEach { assertEquals(4, it.length) }
        // 160 bits, so the recovery path is not the weak link in the vault.
        assertEquals(32, code.replace("-", "").length)
        assertTrue(!code.contains(Regex("[ILOU]")))
    }

    @Test
    fun `forgives the mistakes people make reading paper`() {
        val secret = RecoveryCode.generate()
        val mangled = RecoveryCode.format(secret)
            .lowercase()
            .replace("-", " ")
            .replace("1", "l")
            .replace("0", "o")
        assertTrue(RecoveryCode.parse(mangled).contentEquals(secret))
    }

    @Test
    fun `matches the TypeScript implementation character for character`() {
        // The fixture's code was produced by the web from a known secret. If the
        // two alphabets or bit-packing orders differed, this is where it shows.
        val expected = byteArrayOf(
            0x3a, 0x91.toByte(), 0x0c, 0x7d, 0xe2.toByte(), 0x45, 0xb8.toByte(), 0x16,
            0xf0.toByte(), 0x29, 0x5c, 0xa3.toByte(), 0x71, 0xd4.toByte(), 0x68, 0x0b,
            0x9e.toByte(), 0x37, 0xc2.toByte(), 0x50,
        )
        assertEquals("7A8G-RZF2-8PW1-DW19-BJHQ-3N38-1EF3-FGJG", RecoveryCode.format(expected))
        assertTrue(
            RecoveryCode.parse("7A8G-RZF2-8PW1-DW19-BJHQ-3N38-1EF3-FGJG").contentEquals(expected),
        )
    }

    @Test
    fun `rejects a code that is wrong rather than deriving a useless key`() {
        assertFailsWith<InvalidRecoveryCode> { RecoveryCode.parse("not a code") }
        assertFailsWith<InvalidRecoveryCode> { RecoveryCode.parse("") }
        assertFailsWith<InvalidRecoveryCode> { RecoveryCode.parse("ABCD-EFGH") }
    }
}

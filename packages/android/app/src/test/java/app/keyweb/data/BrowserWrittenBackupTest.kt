package app.keyweb.data

import app.keyweb.vault.BackupBehindException
import app.keyweb.vault.BackupUnreadableException
import app.keyweb.vault.Fields
import app.keyweb.vault.RecoveryCode
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Can this phone open a backup a browser just wrote?
 *
 * Not an argument, a demonstration — the mirror of the web suite's
 * `phone-made-backup.test.ts`. `fixtures/drive-browser-written-v1.json` is
 * produced by the web's shipping `GoogleDriveRemote`, and this runs the
 * phone's shipping reader over those exact bytes.
 *
 * The answer used to be no, and for a reason nothing in the format would
 * reveal. The browser sealed the two copies in two calls, so the recovery copy
 * carried a timestamp a millisecond or two behind the passkey copy. A phone
 * holding no key to the passkey copy has only those timestamps to go on, and a
 * recovery copy older than the passkey copy is exactly what a browser leaves
 * when it rewrites one and carries the other forward — so the phone refused
 * the copy it could perfectly well have opened, said the backup was not its
 * own, and offered to replace it. Every time. After every save in a browser.
 */
class BrowserWrittenBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Fixture(
        val note: String = "",
        val recoveryCode: String,
        val password: String,
        val content: String,
    )

    private val fixture: Fixture =
        json.decodeFromString(
            Fixture.serializer(),
            java.io.File("../../../fixtures/drive-browser-written-v1.json").readText(),
        )

    private fun driveHoldingIt(content: String = fixture.content): FakeDrive {
        val drive = FakeDrive()
        drive.files["file-1"] = FakeDrive.Entry(content, mapOf("keyweb" to "vault-v1"))
        return drive
    }

    /**
     * A phone that has never run the passkey ceremony: the recovery key only.
     *
     * Built from the envelope already in the file, which is what the app does
     * — the key is derived over the salt the envelope carries, so a cipher
     * that minted its own salt would open nothing. Handing it the envelope is
     * the difference between reading the backup and declaring it foreign.
     */
    private suspend fun phone(drive: FakeDrive): DriveVaultRemote {
        val secret = RecoveryCode.parse(fixture.recoveryCode)
        val onFile = DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret))
            .fetchRecoverySealed()
        return DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret, onFile))
    }

    @Test
    fun `a browser seals both copies at one instant`() {
        val payload = json.parseToJsonElement(fixture.content).jsonObject
        val passkey = payload["passkey"]!!.jsonObject["updatedAt"]!!.jsonPrimitive.content
        val recovery = payload["recovery"]!!.jsonObject["updatedAt"]!!.jsonPrimitive.content
        assertEquals(passkey, recovery, "the browser stamped its two copies apart")
    }

    @Test
    fun `this phone reads what the browser wrote`() = runTest {
        val revision = assertNotNull(phone(driveHoldingIt()).read())
        val bank = assertNotNull(revision.state.items["bank"])
        assertEquals(fixture.password, bank.field(Fields.PASSWORD))
        assertEquals("Added later", revision.state.items["later"]?.field(Fields.TITLE))
    }

    /**
     * And the refusal it replaced is still there for the case it was built for.
     *
     * A browser without the recovery code rewrites the passkey copy alone and
     * carries the recovery copy forward, which leaves it genuinely behind. The
     * phone must not open that one and call itself synced — but it must also
     * not offer to overwrite the newer copy, so the refusal now says which
     * kind of refusal it is.
     */
    @Test
    fun `still refuses a recovery copy that has genuinely fallen behind`() = runTest {
        val payload = json.parseToJsonElement(fixture.content).jsonObject
        val stale = buildJsonObject {
            put("v", 1)
            put(
                "passkey",
                JsonObject(
                    payload["passkey"]!!.jsonObject.toMutableMap().apply {
                        put("updatedAt", kotlinx.serialization.json.JsonPrimitive("2030-01-01T00:00:00.000Z"))
                    },
                ),
            )
            put("recovery", payload["recovery"]!!)
        }

        val failure = assertFailsWith<BackupBehindException> {
            phone(driveHoldingIt(stale.toString())).read()
        }
        assertEquals(true, failure.message?.contains("newer passwords"))
    }
}

/**
 * A file this phone holds no key to at all.
 *
 * Separate from the interop cases above because it is not about interop: it is
 * the rule the whole engine rests on, checked on the side that was still
 * breaking it. "I cannot read this" must never become "I may overwrite this".
 */
class UnreadableIsNotEmptyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a backup with no copy this phone can open is refused, not treated as absent`() = runTest {
        val drive = FakeDrive()
        // What a browser leaves when it has no code of its own for the second
        // copy: a passkey envelope, and nothing else.
        val browserOnly = """{"v":1,"passkey":{"schemaVersion":1,"algorithm":"AES-256-GCM+HKDF-SHA-256","credentialId":"c","rpId":"keyneom.github.io","prfInput":"AAAA","kdfSalt":"AAAA","nonce":"AAAA","ciphertext":"AAAA","updatedAt":"2026-09-19T00:00:00.000Z"}}"""
        drive.files["file-1"] = FakeDrive.Entry(browserOnly, mapOf("keyweb" to "vault-v1"))

        val phone = DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(RecoveryCode.generate()))

        assertFailsWith<BackupUnreadableException> { phone.read() }
        // And the file is untouched: nothing published over it.
        assertEquals(browserOnly, drive.files["file-1"]!!.content)
    }
}

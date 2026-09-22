package app.keyweb.data

import app.keyweb.vault.RecoveryCode
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.VaultOp
import app.keyweb.vault.applyOp
import app.keyweb.vault.emptyVault
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The seed that makes one person one participant on all their devices.
 *
 * Sharing pins one key per person: the people you shared with trust that key,
 * and a keyring shared from your phone has to be manageable from your laptop.
 * The identity is wrapped with a key derived from this seed, so every device
 * has to arrive at the same bytes — and until now the only thing that carried
 * them was the printed recovery code, which nothing distributed. A browser
 * that had never been handed that code could read every password in the vault
 * and still not touch a shared keyring.
 *
 * So it rides in the backup, sealed under both keys, and a device takes it
 * from whichever copy it can open.
 */
class SharingSeedTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vault() = applyOp(
        emptyVault(),
        VaultOp.KeyringPut("k", "001700000000000-00000-t", "ring", "Household"),
    )

    @Test
    fun `is written into the backup, sealed so either key opens it`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        val remote = DriveVaultRemote(
            drive,
            VaultEnvelopeCipher.forRecoveryCode(secret),
            sharingSeed = secret,
        )
        remote.write(vault(), null)

        val payload = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content).jsonObject
        val seed = assertNotNull(payload["seed"]).jsonObject
        assertTrue(seed.containsKey("recovery"))
        // Nothing about it is in the clear.
        assertTrue(!assertNotNull(drive.vaultFile()).content.contains(RecoveryCode.format(secret)))
    }

    @Test
    fun `comes back out through the same code that sealed it`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        DriveVaultRemote(
            drive,
            VaultEnvelopeCipher.forRecoveryCode(secret),
            sharingSeed = secret,
        ).write(vault(), null)

        val payload = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content).jsonObject
        val sealed = json.decodeFromJsonElement(
            app.keyweb.vault.SyncEnvelopeV1.serializer(),
            assertNotNull(payload["seed"]).jsonObject.getValue("recovery"),
        )
        val cipher = VaultEnvelopeCipher.forRecoveryCode(secret, sealed)
        assertContentEquals(secret, cipher.openBytes(sealed))
    }

    @Test
    fun `is never replaced once the file has one`() = runTest {
        val drive = FakeDrive()
        val first = RecoveryCode.generate()
        DriveVaultRemote(
            drive,
            VaultEnvelopeCipher.forRecoveryCode(first),
            sharingSeed = first,
        ).write(vault(), null)
        val before = assertNotNull(drive.vaultFile()).content

        // A device that knows a *different* seed must carry the file's forward:
        // two seeds is two identities, and one person cannot be two participants.
        val second = RecoveryCode.generate()
        val head = DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(first))
            .write(vault(), null)
        DriveVaultRemote(
            drive,
            VaultEnvelopeCipher.forRecoveryCode(first),
            sharingSeed = second,
        ).write(vault(), head)

        val after = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content).jsonObject
        val sealed = json.decodeFromJsonElement(
            app.keyweb.vault.SyncEnvelopeV1.serializer(),
            assertNotNull(after["seed"]).jsonObject.getValue("recovery"),
        )
        assertContentEquals(
            first,
            VaultEnvelopeCipher.forRecoveryCode(first, sealed).openBytes(sealed),
        )
        assertTrue(before.contains("\"seed\""))
    }

    @kotlinx.serialization.Serializable
    private data class Fixture(
        val note: String,
        val recoveryCode: String,
        val content: String,
    )

    /**
     * And the browser has to be able to open it, which is the whole point:
     * written here by the shipping writer, read there by the shipping reader.
     */
    @Test
    fun `emits a backup carrying a seed, for the web suite`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        DriveVaultRemote(
            drive,
            VaultEnvelopeCipher.forRecoveryCode(secret),
            sharingSeed = secret,
        ).write(vault(), null)

        val fixtures = java.io.File("../../../fixtures")
        fixtures.mkdirs()
        java.io.File(fixtures, "drive-with-sharing-seed-v1.json").writeText(
            Json { prettyPrint = true }.encodeToString(
                Fixture.serializer(),
                Fixture(
                    note = "Written by Android's DriveVaultRemote. Do not edit.",
                    recoveryCode = RecoveryCode.format(secret),
                    content = assertNotNull(drive.vaultFile()).content,
                ),
            ),
        )
    }

    @Test
    fun `a phone with no seed leaves the file alone`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret)).write(vault(), null)

        val payload = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content).jsonObject
        assertNull(payload["seed"])
    }
}

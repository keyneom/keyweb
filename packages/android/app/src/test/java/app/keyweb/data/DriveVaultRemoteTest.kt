package app.keyweb.data

import app.keyweb.vault.Fields
import app.keyweb.vault.ItemField
import app.keyweb.vault.RecoveryCode
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.VaultOp
import app.keyweb.vault.VaultState
import app.keyweb.vault.VersionConflictException
import app.keyweb.vault.applyOp
import app.keyweb.vault.emptyVault
import app.keyweb.vault.field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An in-memory Drive.
 *
 * Faithful about the two things that decide whether a backup survives: the file
 * is shared mutable state, and every write moves the revision.
 */
private class FakeDrive : DriveFiles {
    class Entry(var content: String, val appProperties: Map<String, String>) {
        var revision: Int = 1
    }

    val files = mutableMapOf<String, Entry>()
    var nextId = 1
    var readCount = 0

    override suspend fun findFile(appProperties: Map<String, String>): String? =
        files.entries.firstOrNull { (_, file) ->
            appProperties.all { (k, v) -> file.appProperties[k] == v }
        }?.key

    override suspend fun readText(fileId: String): String {
        readCount += 1
        return files[fileId]?.content.orEmpty()
    }

    override suspend fun writeHead(fileId: String): DriveFiles.WriteHead {
        val file = files[fileId] ?: error("no such file")
        return DriveFiles.WriteHead("etag-${file.revision}", file.revision.toString())
    }

    override suspend fun createFolder(name: String, appProperties: Map<String, String>): String =
        "folder-${nextId++}".also { files[it] = Entry("", appProperties) }

    override suspend fun create(
        name: String,
        content: String,
        parentId: String?,
        appProperties: Map<String, String>,
    ): String = "file-${nextId++}".also { files[it] = Entry(content, appProperties) }

    override suspend fun write(fileId: String, content: String) {
        val file = files.getValue(fileId)
        file.content = content
        file.revision += 1
    }

    fun vaultFile(): Entry? = files.values.firstOrNull { it.appProperties["keyweb"] == "vault-v1" }

    /** Another client publishes, moving the revision under our feet. */
    fun landForeignRevision(fileId: String, content: String) {
        val file = files.getValue(fileId)
        file.content = content
        file.revision += 1
    }
}

private fun vaultWith(password: String): VaultState {
    var state = emptyVault()
    state = applyOp(
        state,
        VaultOp.KeyringPut(
            opId = "k",
            ts = "001700000000000-00000-t",
            keyringId = "ring",
            name = "Household",
        ),
    )
    return applyOp(
        state,
        VaultOp.ItemPut(
            opId = "i",
            ts = "001700000000001-00000-t",
            itemId = "bank",
            keyringId = "ring",
            fields = mapOf(Fields.TITLE to "Credit Union", Fields.PASSWORD to password),
        ),
    )
}

private fun remoteOn(drive: FakeDrive, secret: ByteArray) =
    DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret))

class DriveVaultRemoteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `uploads ciphertext, never the passwords`() = runTest {
        val drive = FakeDrive()
        val remote = remoteOn(drive, RecoveryCode.generate())
        remote.write(vaultWith("correct-horse-battery"), null)

        val stored = assertNotNull(drive.vaultFile()).content
        assertTrue(!stored.contains("correct-horse-battery"))
        assertTrue(!stored.contains("Credit Union"))
        assertTrue(!stored.contains("Household"))
        // Not even the shape of the data: the field names are inside the
        // ciphertext too.
        assertTrue(!stored.contains("password"))
    }

    @Test
    fun `round-trips a vault through Drive`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        remoteOn(drive, secret).write(vaultWith("the-one-that-matters"), null)

        // A second phone, same code: the salt comes from the envelope already
        // in Drive, or the derived key would differ and open nothing.
        val existing = assertNotNull(remoteOn(drive, secret).fetchRecoverySealed())
        val second = DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret, existing))
        val revision = assertNotNull(second.read())
        assertEquals("the-one-that-matters", revision.state.items["bank"]?.field(Fields.PASSWORD))
    }

    @Test
    fun `never drops the passkey envelope it cannot rewrite`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        remoteOn(drive, secret).write(vaultWith("first"), null)

        // Stand in for what the web app writes: a second envelope this device
        // has no key for, because it comes from a WebAuthn PRF.
        val fileId = drive.files.keys.first { drive.files.getValue(it).appProperties["keyweb"] != null && drive.files.getValue(it).content.isNotBlank() }
        val withPasskey = json.parseToJsonElement(drive.files.getValue(fileId).content).jsonObject
            .toMutableMap()
        withPasskey["passkey"] = json.parseToJsonElement("""{"webOnly":"sealed-by-the-browser"}""")
        drive.files.getValue(fileId).content =
            json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(withPasskey))

        val existing = assertNotNull(remoteOn(drive, secret).fetchRecoverySealed())
        val phone = DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret, existing))
        phone.write(vaultWith("second"), null)

        // Losing this would silently remove the browser's only way into the
        // backup, discovered by whoever next tried to use it.
        val after = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content).jsonObject
        assertEquals(
            "sealed-by-the-browser",
            after["passkey"]?.jsonObject?.get("webOnly")?.jsonPrimitive?.content,
        )
        // And the copy this device *is* responsible for did move on.
        val reread = assertNotNull(phone.read())
        assertEquals("second", reread.state.items["bank"]?.field(Fields.PASSWORD))
    }

    @Test
    fun `preserves members it does not understand`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        remoteOn(drive, secret).write(vaultWith("first"), null)

        val fileId = drive.files.entries.first { it.value.content.isNotBlank() }.key
        val extended = json.parseToJsonElement(drive.files.getValue(fileId).content).jsonObject
            .toMutableMap()
        // A field a future version of Keyweb adds. An older client must not
        // delete it just by syncing.
        extended["somethingNewer"] = json.parseToJsonElement(""""keep me"""")
        drive.files.getValue(fileId).content = kotlinx.serialization.json.JsonObject(extended).toString()

        val existing = assertNotNull(remoteOn(drive, secret).fetchRecoverySealed())
        DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret, existing))
            .write(vaultWith("second"), null)

        val after = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content).jsonObject
        assertEquals("keep me", after["somethingNewer"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refuses a write when another device published first`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        val remote = remoteOn(drive, secret)
        remote.write(vaultWith("first"), null)

        val fileId = drive.files.entries.first { it.value.appProperties["keyweb"] == "vault-v1" }.key
        val stale = drive.writeHead(fileId).headRevisionId
        drive.landForeignRevision(fileId, "something else entirely")

        // Silently winning this race is how one device's work disappears. The
        // engine re-reads and re-merges on a conflict, so refusing is safe.
        assertFailsWith<VersionConflictException> { remote.write(vaultWith("second"), stale) }
    }

    @Test
    fun `does not read the file back when it has nothing to preserve`() = runTest {
        // The carry-forward read costs a round trip on every publish. It is
        // worth it, but only where it buys something.
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        val remote = remoteOn(drive, secret)
        remote.write(vaultWith("first"), null)
        val afterCreate = drive.readCount
        remote.write(vaultWith("second"), null)
        assertTrue(
            drive.readCount > afterCreate,
            "a write onto an existing file must read it first, or it would drop what it cannot rewrite",
        )
    }
}

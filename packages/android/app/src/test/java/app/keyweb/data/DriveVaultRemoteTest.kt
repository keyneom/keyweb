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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    override suspend fun listFiles(): List<DriveFiles.DriveFile> =
        files.map { (id, file) ->
            DriveFiles.DriveFile(id, "$id.kdbx", null, file.appProperties["keyweb"])
        }

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

/**
 * The exact bytes a phone-only vault puts in Drive, handed to the web suite.
 *
 * Written by the real [DriveVaultRemote] rather than assembled by hand, so the
 * claim it supports — "a vault created on a phone has no passkey envelope in
 * it, and a browser therefore cannot open it without the recovery code" — is
 * demonstrated by the shipping writer rather than asserted about it.
 *
 * The web's `phone-made-backup.test.ts` opens this file. If Android ever gains
 * a way to write the passkey envelope, that test fails and says so.
 */
class PhoneMadeBackupFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @kotlinx.serialization.Serializable
    private data class Fixture(
        val note: String,
        val recoveryCode: String,
        val password: String,
        val content: String,
    )

    @Test
    fun `emits what a phone-only vault looks like in Drive`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        // Two writes, because the second is the one that exercises the
        // carry-forward branch — a first write has nothing to preserve, and
        // the interesting question is what an established phone vault holds.
        val remote = remoteOn(drive, secret)
        val first = remote.write(vaultWith("phone-only-password"), null)
        remote.write(vaultWith("phone-only-password"), first)

        val content = assertNotNull(drive.vaultFile()).content

        // The claim, checked here as well as in the web suite: nothing a
        // browser's passkey could open is in this file.
        val payload = json.parseToJsonElement(content).jsonObject
        assertTrue(!payload.containsKey("passkey"), "a phone wrote a passkey envelope: $content")
        assertTrue(payload.containsKey("recovery"))

        val fixtures = java.io.File("../../../fixtures")
        fixtures.mkdirs()
        java.io.File(fixtures, "drive-phone-only-v1.json").writeText(
            Json { prettyPrint = true }.encodeToString(
                Fixture.serializer(),
                Fixture(
                    note = "Written by Android's DriveVaultRemote. Do not edit.",
                    recoveryCode = RecoveryCode.format(secret),
                    password = "phone-only-password",
                    content = content,
                ),
            ),
        )
    }
}

/**
 * Repairing a backup written before the envelope carried its own version.
 *
 * Builds up to 0.2.0-beta.8 dropped `schemaVersion` and `algorithm` from the
 * envelope they uploaded, because both hold defaults and the serializer used
 * there omitted defaults. A phone reads such a file perfectly, so nothing on
 * this side would ever have noticed; a browser could not open it at all.
 */
class LegacyEnvelopeRepairTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A reader keyed off the envelope already in Drive.
     *
     * The salt lives in the envelope, so a cipher minted with a fresh one
     * derives a different key and opens nothing — the same trap the
     * round-trip test above documents.
     */
    private suspend fun readerFor(drive: FakeDrive, secret: ByteArray): DriveVaultRemote {
        val existing = assertNotNull(remoteOn(drive, secret).fetchRecoverySealed())
        return DriveVaultRemote(drive, VaultEnvelopeCipher.forRecoveryCode(secret, existing))
    }

    /** A file exactly as an older build left it: the two fields simply absent. */
    private fun asOlderBuildsWroteIt(content: String): String {
        val payload = json.parseToJsonElement(content).jsonObject
        val recovery = payload.getValue("recovery").jsonObject
            .filterKeys { it != "schemaVersion" && it != "algorithm" }
        return buildJsonObject {
            payload.forEach { (key, value) -> if (key != "recovery") put(key, value) }
            put("recovery", JsonObject(recovery))
        }.toString()
    }

    @Test
    fun `rewrites the envelope the first time it is read`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        remoteOn(drive, secret).write(vaultWith("still-here"), null)

        val file = assertNotNull(drive.vaultFile())
        file.content = asOlderBuildsWroteIt(file.content)
        val before = json.parseToJsonElement(file.content).jsonObject
            .getValue("recovery").jsonObject
        assertTrue(before["schemaVersion"] == null, "the fixture is not the old shape")

        // Reading is enough. It has to be: the engine skips a write when
        // nothing has changed, so a vault that is simply correct would
        // otherwise never republish and would stay unreadable in a browser.
        val revision = assertNotNull(readerFor(drive, secret).read())
        assertEquals("still-here", revision.state.items["bank"]?.field(Fields.PASSWORD))

        val after = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content)
            .jsonObject.getValue("recovery").jsonObject
        assertEquals(1, after.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertTrue(after["algorithm"] != null)
    }

    @Test
    fun `leaves a healthy backup alone`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        remoteOn(drive, secret).write(vaultWith("untouched"), null)
        val revisionBefore = assertNotNull(drive.vaultFile()).revision

        assertNotNull(readerFor(drive, secret).read())

        // No second upload: a repair that runs every time is a repair that
        // fights every other device for the revision.
        assertEquals(revisionBefore, assertNotNull(drive.vaultFile()).revision)
    }

    @Test
    fun `the repaired file is what the web's parser requires`() = runTest {
        val drive = FakeDrive()
        val secret = RecoveryCode.generate()
        remoteOn(drive, secret).write(vaultWith("readable"), null)
        val file = assertNotNull(drive.vaultFile())
        file.content = asOlderBuildsWroteIt(file.content)

        readerFor(drive, secret).read()

        // The exact field list `parseSyncEnvelopeV1` checks for.
        val repaired = json.parseToJsonElement(assertNotNull(drive.vaultFile()).content)
            .jsonObject.getValue("recovery").jsonObject
        for (required in listOf(
            "schemaVersion", "algorithm", "credentialId", "rpId",
            "prfInput", "kdfSalt", "nonce", "ciphertext", "updatedAt",
        )) {
            assertTrue(repaired[required] != null, "missing $required")
        }
    }
}

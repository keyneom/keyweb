package app.keyweb.sharing

import app.keyweb.vault.HlcParts
import app.keyweb.vault.VaultOp
import app.keyweb.vault.VaultState
import app.keyweb.vault.applyOps
import app.keyweb.vault.datasetOf
import app.keyweb.vault.emptyVault
import app.keyweb.vault.encodeHlc
import app.keyweb.vault.field
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A locked backup file, for the day Google is the thing that is down.
 *
 * The format is shared with the web, and that is most of what there is to
 * test: a file saved in a browser has to open on this phone and the other way
 * round, or the one copy somebody kept away from Google only works on whichever
 * device made it. So this opens a file the web really saved, and writes one of
 * its own for the web suite to open back.
 */
class BackupFileTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = File("../../../fixtures")

    @Serializable
    private data class LockFixture(
        val secretBase64: String,
        val keyId: String,
        val record: ProtectedSharingIdentityV1,
    )

    @Serializable
    private data class FileFixture(
        val note: String = "",
        val secretBase64: String,
        val keyId: String,
        val file: String,
    )

    private fun lock(): LockFixture =
        json.decodeFromString(LockFixture.serializer(), File(fixtures, "sharing-recovery-lock-v1.json").readText())

    private fun decode(base64: String) = java.util.Base64.getDecoder().decode(base64)

    private fun at(n: Long) = encodeHlc(HlcParts(1_700_000_000_000 + n, 0, "phone"))

    private fun vault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            VaultOp.KeyringPut("k", at(0), "personal", "Just mine"),
            VaultOp.KeyringBind("b", at(1), "personal", "keyweb-abc"),
            VaultOp.ItemPut(
                "i",
                at(2),
                "bank",
                "personal",
                mapOf("title" to "Credit Union", "password" to "made-up-for-the-fixture"),
            ),
        ),
    )

    private fun saved(): Pair<String, ByteArray> {
        val lock = lock()
        val code = decode(lock.secretBase64)
        return sealBackupFile(vault(), unlockRecoveryLock(lock.record, code), lock.record) to code
    }

    @Test
    fun `opens with the printed code alone, and gives back the passwords`() {
        val (text, code) = saved()
        val opened = openBackupFile(text, code)
        assertEquals("made-up-for-the-fixture", opened.state.items.getValue("bank").field("password"))
        assertEquals(lock().keyId, opened.identity.publicKey.keyId)
    }

    @Test
    fun `opens a file the web saved`() {
        val fixture = json.decodeFromString(
            FileFixture.serializer(),
            File(fixtures, "backup-file-web-v1.json").readText(),
        )
        val opened = openBackupFile(fixture.file, decode(fixture.secretBase64))
        assertEquals("Credit Union", opened.state.items.getValue("bank").field("title"))
        assertEquals("Just mine", opened.state.keyrings.getValue("personal").name.value)
        assertEquals(fixture.keyId, opened.identity.publicKey.keyId)
    }

    @Test
    fun `hands back keyrings unbound`() {
        val (text, code) = saved()
        assertNull(datasetOf(openBackupFile(text, code).state.keyrings["personal"]))
    }

    @Test
    fun `says plainly when the code is wrong`() {
        val (text, _) = saved()
        assertFailsWith<WrongBackupCode> { openBackupFile(text, ByteArray(20)) }
    }

    @Test
    fun `says plainly when it is not a backup file at all`() {
        assertFailsWith<NotABackupFile> { openBackupFile("{\"items\":{}}", ByteArray(20)) }
    }

    @Test
    fun `never keeps the passwords readable in the file`() {
        val (text, _) = saved()
        assertTrue("made-up-for-the-fixture" !in text)
    }

    @Test
    fun `puts a keyring's passwords in the document of the file it lives in`() {
        val (text, code) = saved()
        val plan = restorePlan(vault(), openBackupFile(text, code).state)
        assertEquals(setOf("bank"), plan.getValue("keyweb-abc").items.keys)
        assertTrue("bank" !in plan.getValue("").items)
    }

    @Test
    fun `puts everything in the vault on a phone with nothing yet`() {
        val (text, code) = saved()
        val plan = restorePlan(emptyVault(), openBackupFile(text, code).state)
        assertEquals(setOf(""), plan.keys)
        assertTrue("bank" in plan.getValue("").items)
    }

    /** The file as the phone saves it, for the web suite to open back. */
    @Test
    fun `emits a file the phone saved, for the web suite`() {
        val lock = lock()
        val (text, _) = saved()
        File(fixtures, "backup-file-android-v1.json").writeText(
            Json { prettyPrint = true }.encodeToString(
                FileFixture.serializer(),
                FileFixture(
                    note = "Written by Android's sealBackupFile. Do not edit.",
                    secretBase64 = lock.secretBase64,
                    keyId = lock.keyId,
                    file = text,
                ),
            ) + "\n",
        )
    }
}

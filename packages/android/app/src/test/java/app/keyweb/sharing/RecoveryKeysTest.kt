package app.keyweb.sharing

import app.keyweb.vault.VaultState
import app.keyweb.vault.emptyVault
import com.keyneom.synckit.sharing.ExchangeAccessResult
import com.keyneom.synckit.sharing.MemorySharedBackupRegistry
import com.keyneom.synckit.sharing.SharedBackupAdditionalKeyV1
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharedBackupEnvelopeV1
import com.keyneom.synckit.sharing.SharedBackupStorage
import com.keyneom.synckit.sharing.SharedBackupTransport
import com.keyneom.synckit.sharing.SharedDatasetDrivePermission
import com.keyneom.synckit.sharing.SharedDatasetFile
import com.keyneom.synckit.sharing.SharedDatasetPermission
import com.keyneom.synckit.sharing.SharedExchangeFile
import com.keyneom.synckit.sharing.SharedKeyResponseFile
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.SharingInvitationV1
import com.keyneom.synckit.sharing.SharingPublicKeyResponseV1
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.VersionedSharedDataset
import com.keyneom.synckit.sharing.checkpoint.SharedDatasetHead
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The printed code as a second key of yours on every keyring.
 *
 * The parity of the web's `recovery-keys.test.ts`, through sync-kit's real
 * controller with Drive swapped for memory. The derivation is checked against
 * the value the web wrote, because a key one platform attaches has to open
 * with the code the other derives.
 */
class RecoveryKeysTest {

    private class MemoryDrive : SharedBackupTransport {
        val datasets = mutableMapOf<String, VersionedSharedDataset>()
        private var counter = 0

        override suspend fun ensureStorage() = SharedBackupStorage("app-folder", "exchanges-folder")
        override suspend fun listDatasets() =
            datasets.values.map { SharedDatasetFile(it.datasetId, it.fileId, it.name, it.canEdit) }
        override suspend fun readDataset(fileId: String) = datasets.getValue(fileId)
        override suspend fun createDataset(
            datasetId: String,
            envelope: SharedBackupEnvelopeV1,
        ): VersionedSharedDataset {
            val stored = VersionedSharedDataset(
                datasetId = datasetId,
                fileId = "file-$datasetId",
                name = "$datasetId.json",
                canEdit = true,
                envelope = envelope,
                version = "${++counter}",
            )
            datasets[stored.fileId] = stored
            return stored
        }
        override suspend fun writeDataset(
            current: VersionedSharedDataset,
            envelope: SharedBackupEnvelopeV1,
        ): VersionedSharedDataset =
            current.copy(envelope = envelope, version = "${++counter}").also {
                datasets[current.fileId] = it
            }
        override suspend fun listDatasetHeads(): List<SharedDatasetHead> = emptyList()
        override suspend fun setDatasetPermission(
            fileId: String,
            emailAddress: String,
            role: SharingRole,
            existingDirectPermissionId: String?,
            hasInheritedReadAccess: Boolean,
        ): SharedDatasetPermission = SharedDatasetPermission(
            permissionId = "permission-$emailAddress",
            role = if (role == SharingRole.VIEWER) "reader" else "writer",
        )
        override suspend fun removeDatasetPermission(fileId: String, permissionId: String) = Unit
        override suspend fun listDatasetPermissions(fileId: String): List<SharedDatasetDrivePermission> =
            emptyList()

        override suspend fun grantExchangeAccess(
            emailAddress: String,
            sendNotificationEmail: Boolean?,
            emailMessage: String?,
        ): ExchangeAccessResult = unused()
        override suspend fun createInvitation(invitation: SharingInvitationV1): String = unused()
        override suspend fun createKeyResponse(response: SharingPublicKeyResponseV1): String = unused()
        override suspend fun listExchanges(exchangeId: String?, kind: String?): List<SharedExchangeFile> =
            emptyList()
        override suspend fun readInvitation(fileId: String): SharingInvitationV1 = unused()
        override suspend fun readKeyResponse(
            fileId: String,
            expectedDrivePermissionId: String,
        ): SharedKeyResponseFile = unused()
        override suspend fun deleteExchange(fileId: String) = Unit

        private fun <T> unused(): T = error("Recovery keys never ask for this.")
    }

    private class Memory : RecoveryKeyMemory {
        var key: SharedBackupAdditionalKeyV1? = null
        override suspend fun remembered() = key
        override suspend fun remember(key: SharedBackupAdditionalKeyV1) {
            this.key = key
        }
    }

    @Serializable
    private data class CodeFixture(val note: String, val secretBase64: String, val code: String)

    private val ring = "keyweb-ring"
    private fun secret(fill: Int) = ByteArray(20) { fill.toByte() }

    private fun controller(identity: SharingIdentity, drive: MemoryDrive): SharedBackupController<VaultState> =
        keywebSharingController({ identity }, drive, MemorySharedBackupRegistry())

    private suspend fun account(): Triple<MemoryDrive, SharingIdentity, SharedBackupController<VaultState>> {
        val drive = MemoryDrive()
        val me = SharingCrypto.generateIdentity()
        val controller = controller(me, drive)
        controller.createDataset(INDEX_DATASET_ID, emptyVault())
        controller.createDataset(ring, emptyVault())
        return Triple(drive, me, controller)
    }

    @Test
    fun `derives the same code the browser does`() {
        val fixture = Json.decodeFromString(
            CodeFixture.serializer(),
            File("../../../fixtures/participant-recovery-code-v1.json").readText(),
        )
        assertEquals(fixture.code, participantRecoveryCode(Base64.getDecoder().decode(fixture.secretBase64)))
        assertEquals(participantRecoveryCode(secret(7)), participantRecoveryCode(secret(7)))
        assertNotEquals(participantRecoveryCode(secret(7)), participantRecoveryCode(secret(8)))
    }

    @Test
    fun `changes nothing until somebody turns it on`() = runTest {
        val (drive, me, controller) = account()
        val before = drive.datasets.values.map { it.version }
        val coverage = protectWithRecoveryCode(controller, me, Memory(), listOf(INDEX_DATASET_ID, ring), secret(1))
        assertTrue(coverage.isEmpty())
        assertEquals(before, drive.datasets.values.map { it.version })
    }

    @Test
    fun `puts one recovery key on each of your keyrings, however often it runs`() = runTest {
        val (_, me, controller) = account()
        val ids = listOf(INDEX_DATASET_ID, ring)
        val first = protectWithRecoveryCode(controller, me, Memory(), ids, secret(1), turnOn = true)
        protectWithRecoveryCode(controller, me, Memory(), ids, secret(1))
        assertEquals(listOf(RecoveryStatus.PROTECTED, RecoveryStatus.PROTECTED), first.map { it.status })
        for (id in ids) {
            val found = controller.getDatasetParticipantKeys(id)
            assertTrue(found.enabled)
            assertEquals(1, found.keys.size)
            assertEquals(me.publicKey.keyId, found.keys.single().principalKeyId)
        }
    }

    @Test
    fun `a writer's key waits for the owner, then goes on`() = runTest {
        val (drive, me, controller) = account()
        val writer = SharingCrypto.generateIdentity()
        controller.addDatasetParticipant(ring, writer.publicKey, SharingRole.WRITER, "writer@example.com")
        val theirs = controller(writer, drive)
        theirs.adoptDataset(ring)

        val early = protectWithRecoveryCode(theirs, writer, Memory(), listOf(ring), secret(3), turnOn = true)
        assertEquals(RecoveryStatus.WAITING_FOR_OWNER, early.single().status)

        protectWithRecoveryCode(controller, me, Memory(), listOf(ring), secret(1), turnOn = true)
        val later = protectWithRecoveryCode(theirs, writer, Memory(), listOf(ring), secret(3), turnOn = true)
        assertEquals(RecoveryStatus.PROTECTED, later.single().status)
    }

    @Test
    fun `the code replaces a lost key everywhere, keeping your role`() = runTest {
        val (drive, me, controller) = account()
        protectWithRecoveryCode(controller, me, Memory(), listOf(INDEX_DATASET_ID, ring), secret(1), turnOn = true)

        val replacement = SharingCrypto.generateIdentity()
        val fresh = controller(replacement, drive)
        val result = recoverWithParticipantKey(fresh, secret(1), replacement, INDEX_DATASET_ID) { listOf(ring) }

        assertEquals(listOf(INDEX_DATASET_ID, ring), result.rotated)
        for (id in listOf(INDEX_DATASET_ID, ring)) {
            val participants = fresh.getDatasetParticipants(id).participants
            assertTrue(participants.none { it.keyId == me.publicKey.keyId })
            assertEquals(SharingRole.OWNER, participants.single { it.keyId == replacement.publicKey.keyId }.role)
            fresh.loadDataset(id)
        }
    }

    @Test
    fun `a code that isn't yours opens nothing`() = runTest {
        val (drive, me, controller) = account()
        protectWithRecoveryCode(controller, me, Memory(), listOf(INDEX_DATASET_ID), secret(1), turnOn = true)
        val replacement = SharingCrypto.generateIdentity()
        assertFailsWith<Exception> {
            recoverWithParticipantKey(controller(replacement, drive), secret(9), replacement, INDEX_DATASET_ID) {
                emptyList()
            }
        }
    }
}

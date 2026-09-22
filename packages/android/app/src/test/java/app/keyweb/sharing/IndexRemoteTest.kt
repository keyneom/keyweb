package app.keyweb.sharing

import app.keyweb.vault.RemoteRevision
import app.keyweb.vault.RemoteVaultStore
import app.keyweb.vault.VaultOp
import app.keyweb.vault.VaultState
import app.keyweb.vault.applyOps
import app.keyweb.vault.emptyVault
import app.keyweb.vault.field
import com.keyneom.synckit.sharing.ExchangeAccessResult
import com.keyneom.synckit.sharing.MemorySharedBackupRegistry
import com.keyneom.synckit.sharing.SharedBackupEnvelopeV1
import com.keyneom.synckit.sharing.SharedBackupStorage
import com.keyneom.synckit.sharing.SharedBackupTransport
import com.keyneom.synckit.sharing.SharedDatasetDrivePermission
import com.keyneom.synckit.sharing.SharedDatasetFile
import com.keyneom.synckit.sharing.checkpoint.SharedDatasetHead
import com.keyneom.synckit.sharing.SharedDatasetPermission
import com.keyneom.synckit.sharing.SharedExchangeFile
import com.keyneom.synckit.sharing.SharedKeyResponseFile
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingInvitationV1
import com.keyneom.synckit.sharing.SharingPublicKeyResponseV1
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.VersionedSharedDataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The vault's root, moved into a file of the same kind as every keyring.
 *
 * Through sync-kit's real controller — its envelope, signing and registry — so
 * what passes here is what Drive would be handed, with only the transport
 * swapped for memory. The parity of the web's `index-remote.test.ts`.
 *
 * The move off the old double-sealed file must not lose anything and must not
 * touch that file again: its contents arrive in the index, and from then on
 * every device reads the index and none of them writes the old file.
 */
class IndexRemoteTest {

    /** Just the part of Drive the index uses; the rest is never reached. */
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

        override suspend fun grantExchangeAccess(
            emailAddress: String,
            sendNotificationEmail: Boolean?,
            emailMessage: String?,
        ): ExchangeAccessResult = unused()
        override suspend fun createInvitation(invitation: SharingInvitationV1): String = unused()
        override suspend fun createKeyResponse(response: SharingPublicKeyResponseV1): String = unused()
        override suspend fun listExchanges(exchangeId: String?, kind: String?): List<SharedExchangeFile> =
            unused()
        override suspend fun readInvitation(fileId: String): SharingInvitationV1 = unused()
        override suspend fun readKeyResponse(
            fileId: String,
            expectedDrivePermissionId: String,
        ): SharedKeyResponseFile = unused()
        override suspend fun deleteExchange(fileId: String) = unused<Unit>()
        override suspend fun setDatasetPermission(
            fileId: String,
            emailAddress: String,
            role: SharingRole,
            existingDirectPermissionId: String?,
            hasInheritedReadAccess: Boolean,
        ): SharedDatasetPermission = unused()
        override suspend fun removeDatasetPermission(fileId: String, permissionId: String) = unused<Unit>()
        override suspend fun listDatasetPermissions(fileId: String): List<SharedDatasetDrivePermission> =
            unused()

        private fun <T> unused(): T = error("The index never asks for this.")
    }

    /** The old double-sealed file, counting writes so "never again" can be checked. */
    private class LegacyFile(private val held: VaultState?) : RemoteVaultStore {
        var writes = 0
        override suspend fun read() = held?.let { RemoteRevision(it, "legacy-1") }
        override suspend fun write(state: VaultState, expectedVersion: String?, createOnly: Boolean): String {
            writes += 1
            return "legacy-2"
        }
    }

    private val me = SharingCrypto.generateIdentity()

    private fun controller(drive: MemoryDrive) =
        keywebSharingController({ me }, drive, MemorySharedBackupRegistry())

    private fun phoneVault(): VaultState = applyOps(
        emptyVault(),
        listOf(
            VaultOp.KeyringPut("k", "001700000000000-00000-phone", "personal", "Just mine"),
            VaultOp.ItemPut(
                "i",
                "001700000000001-00000-phone",
                "bank",
                "personal",
                mapOf("title" to "Credit Union", "password" to "saved-on-the-phone"),
            ),
        ),
    )

    @Test
    fun `makes the index from the old file on the first read`() = runTest {
        val drive = MemoryDrive()
        val legacy = LegacyFile(phoneVault())

        val read = IndexRemote(controller(drive), legacy).read()

        assertEquals("saved-on-the-phone", read?.state?.items?.get("bank")?.field("password"))
        assertEquals(listOf(INDEX_DATASET_ID), drive.datasets.values.map { it.datasetId })
        assertEquals(0, legacy.writes)
    }

    @Test
    fun `a second device reads the index and never touches its own old file`() = runTest {
        val drive = MemoryDrive()
        IndexRemote(controller(drive), LegacyFile(phoneVault())).read()

        // The same person on another device: a fresh registry, a stale old file.
        val stale = LegacyFile(emptyVault())
        val second = IndexRemote(
            keywebSharingController({ me }, drive, MemorySharedBackupRegistry()),
            stale,
        ).read()

        assertEquals("saved-on-the-phone", second?.state?.items?.get("bank")?.field("password"))
        assertEquals(1, drive.datasets.size)
        assertEquals(0, stale.writes)
    }

    @Test
    fun `writes go to the index, not the old file`() = runTest {
        val drive = MemoryDrive()
        val legacy = LegacyFile(phoneVault())
        val remote = IndexRemote(controller(drive), legacy)
        val first = remote.read()!!

        val edited = applyOps(
            first.state,
            listOf(
                VaultOp.ItemPut(
                    "n",
                    "001700000000009-00000-phone",
                    "new",
                    "personal",
                    mapOf("title" to "Added later"),
                ),
            ),
        )
        remote.write(edited, first.version, false)

        val reread = IndexRemote(controller(drive), null).read()
        assertEquals("Added later", reread?.state?.items?.get("new")?.field("title"))
        assertEquals(0, legacy.writes)
    }
}

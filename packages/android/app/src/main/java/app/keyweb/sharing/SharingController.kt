package app.keyweb.sharing

import app.keyweb.data.MetaRow
import app.keyweb.data.VaultDao
import app.keyweb.vault.RemoteRevision
import app.keyweb.vault.RemoteUnavailableException
import app.keyweb.vault.RemoteVaultStore
import app.keyweb.vault.VaultState
import app.keyweb.vault.fingerprint
import app.keyweb.vault.mergeVaults
import com.keyneom.synckit.core.Authorization
import com.keyneom.synckit.core.AuthorizationProvider
import com.keyneom.synckit.core.SyncKitError
import com.keyneom.synckit.core.SyncKitErrorCode
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharedBackupControllerCodec
import com.keyneom.synckit.sharing.SharedBackupRegistry
import com.keyneom.synckit.sharing.SharedDatasetRegistryRecord
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.sharedDatasetMutator
import com.keyneom.synckit.stores.GoogleDriveSharedBackupTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A shared keyring's Drive file, and the operations that change who reads it.
 *
 * The vault's own backup and a shared keyring are encrypted quite differently,
 * and the difference is the point. The vault is sealed with a symmetric key
 * only its owner has: there is no way to let a second person in without handing
 * over the key to everything. A shared keyring is sealed with a content key
 * *wrapped separately for each participant's public key*, so adding a person
 * adds one small wrapped copy and grants exactly that keyring.
 *
 * All of that is sync-kit's shared-backup envelope. What Keyweb supplies is the
 * payload — a [VaultState], the same structure the vault holds — and the merge,
 * so a shared keyring is a CRDT in exactly the way the rest of the vault is and
 * two people editing at once keep both edits.
 *
 * The web counterpart is `packages/web/src/vault/sharing/controller.ts`.
 */

const val KEYWEB_APP_ID = "keyweb"

/** The folder shared keyrings live in, visible in the owner's Drive. */
private const val SHARED_FOLDER_NAME = "Keyweb shared keyrings"

private val json = Json { ignoreUnknownKeys = true }

/**
 * The payload codec.
 *
 * [merge] is what makes a shared keyring behave like the rest of the vault
 * rather than like a file two people overwrite in turn.
 */
private object VaultStateCodec : SharedBackupControllerCodec<VaultState> {
    override fun serialize(value: VaultState): JsonElement =
        json.encodeToJsonElement(VaultState.serializer(), value)

    override fun parse(value: JsonElement): VaultState =
        json.decodeFromJsonElement(VaultState.serializer(), value)

    override fun merge(local: VaultState, remote: VaultState): VaultState =
        mergeVaults(local, remote)

    override fun fingerprint(value: VaultState): String = fingerprint(value)
}

/**
 * Which Drive file each dataset is, and which revision was last verified.
 *
 * Persisted in the vault's own meta table rather than held in memory, because
 * re-deriving it means listing a folder that the *recipient* of a share cannot
 * list at all — they were given one file, not the folder around it. Losing this
 * would make a received keyring unreadable until it was joined again.
 */
class RoomSharedBackupRegistry(private val dao: VaultDao) : SharedBackupRegistry {

    /**
     * Keyweb's own shape for the record, because sync-kit's is not
     * serializable. Written out field by field rather than reflected over, so
     * a field added upstream fails to compile here instead of being silently
     * dropped on the next write.
     */
    @kotlinx.serialization.Serializable
    private data class Stored(
        val datasetId: String,
        val fileId: String? = null,
        val trustedOwnerKeyId: String,
        val lastRevisionId: String? = null,
        val seenRevisionIds: List<String> = emptyList(),
        val participantPermissionIds: Map<String, String> = emptyMap(),
    )

    override suspend fun get(datasetId: String): SharedDatasetRegistryRecord? {
        val raw = dao.meta(key(datasetId))?.takeIf { it.isNotBlank() } ?: return null
        val stored = runCatching {
            json.decodeFromString(Stored.serializer(), raw)
        }.getOrNull() ?: return null
        return SharedDatasetRegistryRecord(
            datasetId = stored.datasetId,
            fileId = stored.fileId,
            trustedOwnerKeyId = stored.trustedOwnerKeyId,
            lastRevisionId = stored.lastRevisionId,
            seenRevisionIds = stored.seenRevisionIds,
            participantPermissionIds = stored.participantPermissionIds,
        )
    }

    override suspend fun set(record: SharedDatasetRegistryRecord) {
        dao.putMeta(
            MetaRow(
                key(record.datasetId),
                json.encodeToString(
                    Stored.serializer(),
                    Stored(
                        datasetId = record.datasetId,
                        fileId = record.fileId,
                        trustedOwnerKeyId = record.trustedOwnerKeyId,
                        lastRevisionId = record.lastRevisionId,
                        seenRevisionIds = record.seenRevisionIds.orEmpty(),
                        participantPermissionIds = record.participantPermissionIds.orEmpty(),
                    ),
                ),
            ),
        )
    }

    /** Blanked rather than removed: the meta table has no delete. */
    override suspend fun delete(datasetId: String) {
        dao.putMeta(MetaRow(key(datasetId), ""))
    }

    private fun key(datasetId: String) = "sharing:dataset:$datasetId"
}

fun createKeywebSharingController(
    identity: KeywebSharingIdentity,
    registry: SharedBackupRegistry,
    accessToken: suspend () -> String,
): SharedBackupController<VaultState> {
    val authorizationProvider = object : AuthorizationProvider {
        override suspend fun authorize(): Authorization = Authorization(accessToken(), null)

        /** The token is the app's, not this feature's, so this must not sign out. */
        override fun clear() = Unit
    }
    return SharedBackupController(
        appId = KEYWEB_APP_ID,
        codec = VaultStateCodec,
        identity = { identity.getOrCreate() },
        transport = GoogleDriveSharedBackupTransport(
            appId = KEYWEB_APP_ID,
            authorizationProvider = authorizationProvider,
            folderName = SHARED_FOLDER_NAME,
        ),
        registry = registry,
    )
}

/**
 * One shared keyring, as something [app.keyweb.vault.VaultSync] can publish to.
 *
 * The sync engine already knows how to keep a document and a remote in step; it
 * needs no idea that this particular remote is a file two people can write to,
 * because the merge it would do anyway is the merge that makes that safe.
 *
 * Failures come back as [RemoteUnavailableException] so a shared keyring that
 * cannot be reached leaves the rest of the vault backing up normally. That
 * matters more here than for the vault: a revoked grant, a deleted file or a
 * person who left are all ordinary states for a shared keyring, and none of
 * them should stop somebody's own passwords being saved.
 */
class SharedKeyringRemote(
    private val controller: SharedBackupController<VaultState>,
    private val datasetId: String,
) : RemoteVaultStore {

    /** Set once the dataset is known to exist, to skip the adopt attempt. */
    @Volatile
    private var known = false

    override suspend fun read(): RemoteRevision? = try {
        val result = load()
        known = true
        RemoteRevision(result.value, result.revisionId)
    } catch (cause: Exception) {
        // A dataset this device has a binding for but no registry record is the
        // normal state right after joining on a second device; adopting it is
        // what turns the binding into something readable.
        if (isMissing(cause)) null else throw unavailable(cause)
    }

    override suspend fun write(state: VaultState, expectedVersion: String?): String = try {
        val result = controller.syncDataset(
            datasetId,
            sharedDatasetMutator(
                read = { state },
                // Nothing to commit here: VaultSync owns the local document and
                // writes the merged state itself once this returns.
                apply = { merged -> merged },
            ),
        )
        known = true
        result.revisionId
    } catch (cause: Exception) {
        throw unavailable(cause)
    }

    private suspend fun load() = if (known) {
        controller.loadDataset(datasetId)
    } else {
        try {
            controller.loadDataset(datasetId)
        } catch (cause: Exception) {
            if (!isMissing(cause)) throw cause
            // Trust-on-first-use against the envelope's own owner key.
            // Deliberately not requiring ownership: the common case for
            // adopting is a keyring somebody else owns and shared with us.
            controller.adoptDataset(datasetId)
        }
    }
}

internal fun isMissing(cause: Throwable): Boolean =
    cause is SyncKitError && cause.code == SyncKitErrorCode.NOT_FOUND

private fun unavailable(cause: Throwable): RemoteUnavailableException =
    cause as? RemoteUnavailableException
        ?: RemoteUnavailableException(
            cause.message?.takeIf { it.isNotBlank() }
                ?: "Keyweb couldn't reach that shared keyring.",
        )

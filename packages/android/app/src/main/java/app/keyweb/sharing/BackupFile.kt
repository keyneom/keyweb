package app.keyweb.sharing

import app.keyweb.vault.HLC_ZERO
import app.keyweb.vault.Reg
import app.keyweb.vault.VaultState
import app.keyweb.vault.boundDatasets
import app.keyweb.vault.extractDataset
import app.keyweb.vault.withoutDatasetItems
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.CreateSharedBackupEnvelopeInput
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import com.keyneom.synckit.sharing.SharedBackupCodec
import com.keyneom.synckit.sharing.SharedBackupEnvelopeV1
import com.keyneom.synckit.sharing.SharedBackupParticipantInput
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.VerifySharedBackupOptions
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * An encrypted backup file, for the day Google is the thing that is down.
 *
 * The counterpart of the web's `backupFile.ts`, byte for byte in format, so a
 * file saved on either opens on the other. It is the vault sealed exactly as a
 * keyring file is — encrypted once, its key wrapped to you — with the recovery
 * lock carried beside it. The printed code opens the lock, the lock makes you
 * *you*, and you open the vault: no Google, no passkey, nothing but the file
 * and the code.
 */

const val BACKUP_FILE_FORMAT = "keyweb-backup"

@Serializable
data class BackupFileV1(
    val format: String,
    val version: Int,
    val savedAt: String,
    val recoveryLock: ProtectedSharingIdentityV1,
    val envelope: SharedBackupEnvelopeV1,
)

/** What opening a file gives back: the vault, and who you are. */
class OpenedBackupFile(
    val state: VaultState,
    val identity: SharingIdentity,
    val recoveryLock: ProtectedSharingIdentityV1,
)

/** The code does not open this file. Said plainly, not as a crypto error. */
class WrongBackupCode : Exception("That recovery code doesn't open this backup file.")

class NotABackupFile(message: String) : Exception(message)

private val vaultJson = Json { ignoreUnknownKeys = true }

private object BackupCodec : SharedBackupCodec<VaultState> {
    override fun serialize(value: VaultState): JsonElement =
        vaultJson.encodeToJsonElement(VaultState.serializer(), value)

    override fun parse(value: JsonElement): VaultState =
        vaultJson.decodeFromJsonElement(VaultState.serializer(), value)
}

/**
 * Seal the vault into a file the printed code opens.
 *
 * Refuses a lock on anyone but [identity]: a file whose lock opens somebody
 * else would open nothing, and look as though it would.
 */
fun sealBackupFile(
    state: VaultState,
    identity: SharingIdentity,
    recoveryLock: ProtectedSharingIdentityV1,
    now: String = java.time.Instant.now().toString(),
): String {
    require(recoveryLock.publicKey.keyId == identity.publicKey.keyId) {
        "That recovery lock is not yours."
    }
    val envelope = SharingCrypto.createSharedBackupEnvelopeV1(
        state,
        BackupCodec,
        identity,
        CreateSharedBackupEnvelopeInput(
            appId = "keyweb-backup",
            backupId = "backup-${UUID.randomUUID()}",
            participants = listOf(
                SharedBackupParticipantInput(publicKey = identity.publicKey, role = SharingRole.OWNER),
            ),
            createdAt = now,
        ),
    )
    val file = BackupFileV1(
        format = BACKUP_FILE_FORMAT,
        version = 1,
        savedAt = now,
        recoveryLock = recoveryLock,
        envelope = envelope,
    )
    return SyncKitJson.instance.encodeToString(BackupFileV1.serializer(), file)
}

/** Open a backup file with the printed code alone. */
fun openBackupFile(text: String, code: ByteArray): OpenedBackupFile {
    val file = try {
        SyncKitJson.instance.decodeFromString(BackupFileV1.serializer(), text)
    } catch (cause: Exception) {
        throw NotABackupFile("That isn't a Keyweb backup file.")
    }
    if (file.format != BACKUP_FILE_FORMAT || file.version != 1) {
        throw NotABackupFile("That isn't a Keyweb backup file.")
    }
    val identity = try {
        unlockRecoveryLock(file.recoveryLock, code)
    } catch (cause: Exception) {
        throw WrongBackupCode()
    }
    val state = SharingCrypto.decryptSharedBackupEnvelopeV1(
        file.envelope,
        BackupCodec,
        identity,
        VerifySharedBackupOptions(trustedOwnerKeyId = identity.publicKey.keyId),
    )
    return OpenedBackupFile(restorable(state), identity, file.recoveryLock)
}

/**
 * A backed-up vault, ready to go into one that may have no Drive to reach.
 *
 * Which file each keyring lives in is dropped: the restoring vault already
 * knows where its own keyrings live, and a binding from the file could point
 * at a file this account no longer has. Dropped as the oldest possible value
 * rather than an empty newer one, so a binding the vault holds always wins.
 */
private fun restorable(state: VaultState): VaultState =
    state.copy(
        keyrings = state.keyrings.mapValues { (_, keyring) -> keyring.copy(dataset = Reg("", HLC_ZERO)) },
    )

/**
 * Where each part of a restored vault goes, by the vault it is going into.
 *
 * A keyring kept in a file of its own gets its passwords in that file's
 * document, not the vault's; otherwise they would be published into the
 * index beside keyrings they were never meant to travel with. Returned as
 * document id to state, with the vault itself under `""`.
 */
fun restorePlan(current: VaultState, restored: VaultState): Map<String, VaultState> {
    val plan = mutableMapOf<String, VaultState>()
    var rest = restored
    for (binding in boundDatasets(current)) {
        val slice = extractDataset(rest, binding.keyringId)
        if (slice.items.isNotEmpty()) plan[binding.datasetId] = slice
        rest = withoutDatasetItems(rest, binding.keyringId)
    }
    plan[""] = rest
    return plan
}

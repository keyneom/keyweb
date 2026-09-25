package app.keyweb.sharing

import app.keyweb.vault.VaultState
import app.keyweb.vault.hkdfSha256
import com.keyneom.synckit.sharing.ParticipantKeyRecovery
import com.keyneom.synckit.sharing.ParticipantKeyRemoval
import com.keyneom.synckit.sharing.ParticipantKeys
import com.keyneom.synckit.sharing.SharedBackupAdditionalKeyV1
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharingCryptoOptions
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.SharingRole

/**
 * Your printed code, as a second key of yours on every keyring.
 *
 * The counterpart of the web's `recoveryKeys.ts`, and it has to match it
 * exactly: a recovery key the browser attached must open with the code this
 * phone printed, and the other way round. A keyring's file names who may read
 * it — for you, the key your passkey protects — and this adds a recovery key
 * beside it, its private half sealed under your code and stored in the file.
 * The code and any one keyring file are then enough to become you again.
 *
 * Still one printed code: sync-kit's codes are their own shape, so the one it
 * needs is derived from yours with the same label on both platforms.
 */

private const val DERIVATION_INFO = "keyweb-participant-recovery-code-v1"
private const val PURPOSE_RECOVERY = "recovery"

/**
 * The sync-kit recovery code your printed code stands for.
 *
 * sync-kit only makes codes from randomness, so it is given sixteen bytes that
 * are derived rather than random. An empty HKDF salt is the RFC's default of
 * hash-length zeros, spelt out because the JCE refuses an empty HMAC key —
 * and it is what WebCrypto uses for an empty salt, so both sides agree.
 */
fun participantRecoveryCode(secret: ByteArray): String {
    val seed = hkdfSha256(secret, ByteArray(32), DERIVATION_INFO.toByteArray(Charsets.UTF_8), 16)
    try {
        return ParticipantKeys.generateRecoveryCode(
            SharingCryptoOptions(randomBytes = { length -> seed.copyOf(length) }),
        )
    } finally {
        seed.fill(0)
    }
}

/** Where the code protects one keyring, for saying so in plain words. */
data class RecoveryCoverage(val datasetId: String, val status: RecoveryStatus)

enum class RecoveryStatus {
    PROTECTED,

    /** The keyring's owner hasn't let members add keys to it yet. */
    WAITING_FOR_OWNER,

    /** You can only view it, so you can't add your key to it yourself. */
    VIEW_ONLY,
    FAILED,
}

/** Where this device remembers the recovery key it attaches. */
interface RecoveryKeyMemory {
    suspend fun remembered(): SharedBackupAdditionalKeyV1?
    suspend fun remember(key: SharedBackupAdditionalKeyV1)
}

/**
 * Put your recovery key on every keyring you can write.
 *
 * Changes nothing until recovery keys have been turned on — [turnOn] true, or
 * already on in the first file, the index — because a keyring that allows them
 * is written in a format Keyweb before 0.2.0-beta.37 cannot open. On a keyring
 * you own or run, members are then allowed keys of their own; your recovery
 * key is added, and any older one of yours this code does not open is taken
 * off. The same key everywhere: one another device attached is reused.
 */
suspend fun protectWithRecoveryCode(
    controller: SharedBackupController<VaultState>,
    identity: SharingIdentity,
    memory: RecoveryKeyMemory,
    datasetIds: List<String>,
    secret: ByteArray,
    turnOn: Boolean? = null,
): List<RecoveryCoverage> {
    val on = turnOn ?: recoveryKeysOn(controller, datasetIds.firstOrNull())
    if (!on) return emptyList()
    val me = identity.publicKey.keyId
    val code = participantRecoveryCode(secret)
    val addition = recoveryKeyFor(controller, identity, memory, datasetIds, code)

    return datasetIds.mapNotNull { datasetId ->
        try {
            val role = controller.getDatasetParticipants(datasetId).participants
                .firstOrNull { it.keyId == me }?.role
                ?: return@mapNotNull null
            val found = controller.getDatasetParticipantKeys(datasetId)
            var enabled = found.enabled
            var keys = found.keys
            if (!enabled && (role == SharingRole.OWNER || role == SharingRole.ADMIN)) {
                controller.setParticipantKeysPolicy(datasetId, enabled = true)
                enabled = true
                keys = emptyList()
            }
            when {
                !enabled -> RecoveryCoverage(datasetId, RecoveryStatus.WAITING_FOR_OWNER)
                role == SharingRole.VIEWER -> RecoveryCoverage(
                    datasetId,
                    if (keys.any { it.keyId == addition.keyId }) RecoveryStatus.PROTECTED else RecoveryStatus.VIEW_ONLY,
                )
                else -> {
                    val mine = keys.filter { it.principalKeyId == me && it.purpose == PURPOSE_RECOVERY }
                    val stale = mine.filter { it.keyId != addition.keyId }
                    if (stale.isNotEmpty()) {
                        controller.removeParticipantKeys(
                            datasetId,
                            stale.map {
                                ParticipantKeyRemoval.Signed(
                                    ParticipantKeys.createRemoval(KEYWEB_APP_ID, identity, it),
                                )
                            },
                        )
                    }
                    if (mine.none { it.keyId == addition.keyId }) {
                        controller.addParticipantKeys(datasetId, listOf(addition))
                    }
                    RecoveryCoverage(datasetId, RecoveryStatus.PROTECTED)
                }
            }
        } catch (cause: Exception) {
            RecoveryCoverage(datasetId, RecoveryStatus.FAILED)
        }
    }
}

/** Has anyone turned recovery keys on? Read from the index, which every device writes. */
suspend fun recoveryKeysOn(
    controller: SharedBackupController<VaultState>,
    datasetId: String?,
): Boolean {
    if (datasetId == null) return false
    return runCatching { controller.getDatasetParticipantKeys(datasetId).enabled }.getOrDefault(false)
}

/** The one recovery key for this code: remembered, found on a keyring, or made. */
private suspend fun recoveryKeyFor(
    controller: SharedBackupController<VaultState>,
    identity: SharingIdentity,
    memory: RecoveryKeyMemory,
    datasetIds: List<String>,
    code: String,
): SharedBackupAdditionalKeyV1 {
    val me = identity.publicKey.keyId
    fun opens(key: SharedBackupAdditionalKeyV1): Boolean =
        key.principalKeyId == me &&
            key.purpose == PURPOSE_RECOVERY &&
            key.sealedPrivateKeys != null &&
            runCatching { ParticipantKeys.openRecoveryKey(KEYWEB_APP_ID, code, key) }.isSuccess

    memory.remembered()?.takeIf(::opens)?.let { return it }

    for (datasetId in datasetIds) {
        val keys = runCatching { controller.getDatasetParticipantKeys(datasetId).keys }.getOrDefault(emptyList())
        keys.firstOrNull(::opens)?.let {
            memory.remember(it)
            return it
        }
    }

    val recovery = ParticipantKeys.createRecoveryKey(KEYWEB_APP_ID, code)
    val addition = ParticipantKeys.createAddition(
        appId = KEYWEB_APP_ID,
        principalKeyId = me,
        authorizer = identity,
        key = recovery.identity,
        purpose = PURPOSE_RECOVERY,
        sealedPrivateKeys = recovery.sealedPrivateKeys,
    )
    memory.remember(addition)
    return addition
}

/**
 * Become a participant again with the code, when the passkey is gone.
 *
 * The recovery key is opened from the index and signs "replace my lost key
 * with this one"; that rotation is written into the index and every keyring it
 * names that carries the key. Returns what could not be rotated — a keyring
 * you can only view has no recovery key of yours on it.
 */
suspend fun recoverWithParticipantKey(
    controller: SharedBackupController<VaultState>,
    secret: ByteArray,
    replacement: SharingIdentity,
    indexDatasetId: String,
    datasetsAfterIndex: suspend () -> List<String>,
): RecoveryResult {
    val code = participantRecoveryCode(secret)
    val opened = controller.openRecoveryKey(indexDatasetId, code)
    val rotation = ParticipantKeys.createAuthorizedRotation(
        appId = KEYWEB_APP_ID,
        fromKeyId = opened.key.principalKeyId,
        authorizer = opened.identity,
        replacement = replacement,
    )
    val recovery = ParticipantKeyRecovery(replacement = replacement, key = opened.identity)
    controller.rotateWithAdditionalKey(indexDatasetId, rotation, recovery)

    val rotated = mutableListOf(indexDatasetId)
    val missed = mutableListOf<String>()
    for (datasetId in datasetsAfterIndex()) {
        try {
            controller.rotateWithAdditionalKey(datasetId, rotation, recovery)
            rotated += datasetId
        } catch (cause: Exception) {
            missed += datasetId
        }
    }
    return RecoveryResult(rotated, missed)
}

data class RecoveryResult(val rotated: List<String>, val missed: List<String>)

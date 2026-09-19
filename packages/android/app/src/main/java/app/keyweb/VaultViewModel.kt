package app.keyweb

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.fragment.app.FragmentActivity
import app.keyweb.data.DriveClient
import app.keyweb.data.DriveVaultRemote
import app.keyweb.data.DriveFiles
import app.keyweb.data.GoogleAuthorizer
import app.keyweb.data.RoomVaultStorage
import app.keyweb.data.VaultPasskey
import app.keyweb.data.SwitchableRemote
import app.keyweb.data.UnlockResult
import app.keyweb.data.VaultDatabase
import app.keyweb.data.VaultKeystore
import app.keyweb.data.VaultUnlock
import app.keyweb.data.GrantBrowser
import app.keyweb.data.needsAuthentication
import app.keyweb.sharing.AcceptedShare
import app.keyweb.sharing.KeywebSharing
import app.keyweb.sharing.KeywebSharingIdentity
import app.keyweb.sharing.KeywebSharingIdentityStore
import app.keyweb.sharing.Member
import app.keyweb.sharing.PendingInvite
import app.keyweb.sharing.PrefsSharingIdentityStore
import app.keyweb.sharing.RoomSharedBackupRegistry
import app.keyweb.sharing.SharedKeyringRemote
import app.keyweb.sharing.ShareLinks
import app.keyweb.sharing.createKeywebSharingController
import com.keyneom.synckit.sharing.SharingDatasetFileV1
import com.keyneom.synckit.sharing.SharingInvitationV1
import com.keyneom.synckit.sharing.SharingPublicKeyResponseV1
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.encodeSharingDatasetFilesV1
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.core.Authorization
import app.keyweb.vault.VAULT_DOCUMENT
import app.keyweb.vault.Clock
import app.keyweb.vault.InvalidRecoveryCode
import app.keyweb.vault.Fields
import app.keyweb.vault.AccountPlan
import app.keyweb.vault.Authenticator
import app.keyweb.vault.CsvOmissions
import app.keyweb.vault.PlanReason
import app.keyweb.vault.csvOmissions
import app.keyweb.vault.exportCsv
import app.keyweb.vault.exportJson
import app.keyweb.vault.exportVault
import app.keyweb.vault.planAccounts
import app.keyweb.vault.ItemField
import app.keyweb.vault.NotAnAccountCode
import app.keyweb.vault.ScannedAccount
import app.keyweb.vault.isBlob
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.PasswordGenerator
import app.keyweb.vault.PasswordRules
import app.keyweb.vault.RecoveryCode
import app.keyweb.vault.SavedRules
import app.keyweb.vault.SyncStatus
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.VaultState
import app.keyweb.vault.VaultOp
import app.keyweb.vault.datasetOf
import app.keyweb.vault.VaultSync
import app.keyweb.vault.supersededAliases
import app.keyweb.vault.field
import app.keyweb.vault.kdbx.KdbxOversized
import app.keyweb.vault.kdbx.MAX_ATTACHMENT_BYTES
import app.keyweb.vault.kdbx.blobIdFor
import app.keyweb.vault.kdbx.importOperations
import app.keyweb.vault.kdbx.KdbxEntry
import app.keyweb.vault.kdbx.KdbxFile
import app.keyweb.vault.kdbx.KdbxReader
import app.keyweb.vault.kdbx.allFields
import app.keyweb.vault.kdbx.kdbxFieldsToItemFields
import app.keyweb.vault.kdbx.suggestedKeyringName
import app.keyweb.vault.HlcParts
import app.keyweb.vault.BLOB_KIND
import app.keyweb.vault.attachmentField
import app.keyweb.vault.decodeHlc
import app.keyweb.vault.encodeHlc
import app.keyweb.vault.kdbx.WrongMasterPassword
import app.keyweb.vault.emptyVault
import app.keyweb.vault.keyringLabel
import app.keyweb.vault.liveKeyrings
import app.keyweb.vault.visibleItems
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the backup setup flow has got to.
 *
 * Modelled as one value rather than a handful of booleans so the screen can
 * never render a combination that does not exist -- "connecting and also asking
 * for a code", say.
 */
enum class BackupStage {
    /** No backup. Everything is on this phone only, and the app says so. */
    OFF,

    /** Talking to Google, or waiting for the consent screen to come back. */
    CONNECTING,

    /**
     * Drive already holds a Keyweb backup protected by a code this phone does
     * not have. Minting a fresh one would retire the printed sheet, so it asks.
     */
    NEEDS_CODE,

    /** A code was just minted. Shown exactly once, for writing down. */
    SHOW_CODE,

    ON,
}

data class BackupUiState(
    val stage: BackupStage = BackupStage.OFF,
    /** Non-null only in [BackupStage.SHOW_CODE], and never recoverable later. */
    val newCode: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

enum class ImportStage { CHOOSING, PASSWORD, PREVIEW, DONE }

/** Where the entries that are in no group should land. */
sealed interface UngroupedDestination {
    data class New(val name: String) : UngroupedDestination
    data class Existing(val keyringId: String) : UngroupedDestination
}

data class ImportUiState(
    val stage: ImportStage = ImportStage.CHOOSING,
    /** What Drive says this account has handed over. Never a local list. */
    val files: List<DriveFiles.DriveFile> = emptyList(),
    val openingName: String = "",
    val entryCount: Int = 0,
    val keyringNames: List<String> = emptyList(),
    /** How many entries sit at the database root, in no group. */
    val ungrouped: Int = 0,
    /** Where those go. Seeded from the file's name when the preview is built. */
    val ungroupedDestination: UngroupedDestination = UngroupedDestination.New(""),
    /** The keyrings already in the vault, to offer as a destination. */
    val existingKeyrings: List<Pair<String, String>> = emptyList(),
    val skipped: Int = 0,
    /** Files too large to carry, named so the person can keep their original. */
    val oversized: List<KdbxOversized> = emptyList(),
    /** How many earlier versions came across, for the preview to report. */
    val versions: Int = 0,
    val importedCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * One account read off a QR code, waiting to be told where to go.
 *
 * Held as a list of decisions rather than written as it is scanned, because a
 * Google Authenticator export arrives in pieces: a person with thirty accounts
 * gets three QR codes and no indication on screen that there is more than one.
 * Writing each code as it lands would mean a half-imported vault whenever
 * somebody stopped early, and would give them no chance to say where any of it
 * belongs.
 */
data class PendingAccount(
    /** Identity for deduplicating a code scanned twice, and for selection. */
    val key: String,
    val selected: Boolean = true,
    /**
     * Where this code is going, worked out over the whole batch at once.
     *
     * A second factor is a property of an account somebody already has, not a
     * new account — adding "GitHub" beside the GitHub password they have used
     * for years means the one they open out of habit is the one without the
     * code in it. But *which* password is a decision with no safe guess in it,
     * so `planAccounts` makes it under guarantees rather than by similarity,
     * and carries the reason so the row can say it before anything is written.
     */
    val plan: AccountPlan,
) {
    val account: ScannedAccount get() = plan.account
}

data class ScanUiState(
    val accounts: List<PendingAccount> = emptyList(),
    /** Which codes of this export have been read, so the screen can say. */
    val seen: Set<Int> = emptySet(),
    val total: Int = 1,
    val batchId: Int? = null,
    val addedTo: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val outstanding: Int get() = (total - seen.size).coerceAtLeast(0)
}

/** One candidate vault file, described without opening it. */
data class BackupFileChoice(
    val fileId: String,
    val modifiedAtMs: Long?,
    val chosen: Boolean,
)

enum class VaultPhase {
    /** Working out whether a vault already exists on this device. */
    CHECKING,

    /** A vault exists and needs authentication, or none exists and needs creating. */
    LOCKED,
    UNLOCKING,
    READY,

    /** No screen lock at all, so the Keystore cannot protect anything. */
    UNSUPPORTED,
}

data class VaultUiState(
    val phase: VaultPhase = VaultPhase.CHECKING,
    /**
     * Raise the unlock prompt without being asked.
     *
     * One shot, cleared the moment it is acted on. If it survived a
     * cancellation the sheet would reappear the instant it was dismissed, and
     * the app could not be put down.
     */
    val promptOnEntry: Boolean = false,
    /** True when this device has no vault yet, so unlocking means setting up. */
    val firstRun: Boolean = false,
    val error: String? = null,
    val vault: VaultState = emptyVault(),
    val status: SyncStatus = SyncStatus(),
    val items: List<ItemRecord> = emptyList(),
    val backupConfigured: Boolean = false,
    val backup: BackupUiState = BackupUiState(),
    /** Rule sets someone named and kept, alongside the built-in presets. */
    val savedRules: List<SavedRules> = emptyList(),
    val import: ImportUiState = ImportUiState(),
    val scan: ScanUiState = ScanUiState(),
    /** What the generator opens with: whatever was used last. */
    val lastRules: PasswordRules = PasswordRules(),
    val share: ShareUiState = ShareUiState(),
    /** This person's own sharing key, once they have asked to see it. */
    val sharingKey: String? = null,
    /**
     * Keyrings somebody shared with this person as a reader.
     *
     * Empty unless something has actually been shared. The edit screen uses it
     * to stop a save that would be accepted here and refused at the Drive
     * file — which would look exactly like saving, and never arrive.
     */
    val readOnlyKeyrings: Set<String> = emptySet(),
    /**
     * The backup has no passkey copy this phone can open.
     *
     * Which means it opens on this phone and nowhere else — a browser would
     * need the printed code every time, and would never see a change made
     * here. True for every vault set up before the phone could hold a passkey.
     */
    val backupPhoneOnly: Boolean = false,
    /** Vault files to choose between, when the account holds more than one. */
    val backupFiles: List<BackupFileChoice> = emptyList(),
    val toast: String? = null,
)

/** Where a share flow has got to, from either end of it. */
enum class ShareStage {
    /** Nothing in progress; the sharing screens show what already is. */
    IDLE,

    /** Somebody opened a link that shares a keyring with this phone. */
    INVITED,

    /** Waiting for the browser to come back with the file grant. */
    AWAITING_GRANT,

    /** The reply is made and needs sending back to the person who invited. */
    REPLY_READY,

    /** A reply came back; waiting for the fingerprint check before wrapping. */
    CONFIRM_ACCEPT,

    /** A reply came back and is being turned into access. */
    ACCEPTING,

    /** Somebody was let in. */
    ACCEPTED,
}

data class ShareUiState(
    val stage: ShareStage = ShareStage.IDLE,
    /** The keyring the share screens are about, when one is open. */
    val keyringId: String? = null,
    val datasetId: String? = null,
    /** Everyone who can read it, once Drive has been asked. */
    val members: List<Member> = emptyList(),
    /** Null until Drive has answered: unknown is a real state, not a spinner. */
    val youOwnIt: Boolean? = null,
    val pending: List<PendingInvite> = emptyList(),
    /** A link waiting to be sent to somebody. */
    val link: String? = null,
    /** A handover link, once one has been made, so it can be copied out. */
    val handoverLink: String? = null,
    /** The invitation this phone was sent, while it is being decided on. */
    val invite: PendingShareInvite? = null,
    val accepted: AcceptedShare? = null,
    /** Shown before wrapping, so the fingerprint can still stop the share. */
    val preview: AcceptedShare? = null,
    /** This person's own key, for reading aloud on the reply screen. */
    val fingerprint: String? = null,
    /** Held so a failed accept can be retried without the link being sent again. */
    val reply: SharingPublicKeyResponseV1? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** An invitation opened on this phone, held while the flow runs. */
data class PendingShareInvite(
    val invitation: SharingInvitationV1,
    val files: List<SharingDatasetFileV1>,
    val label: String?,
    val ownerEmail: String?,
    val role: SharingRole,
)

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("keyweb", android.content.Context.MODE_PRIVATE)
    private val database = VaultDatabase.open(app)

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var sync: VaultSync? = null
    private var storage: RoomVaultStorage? = null
    private var sharing: KeywebSharing? = null
    private var sharingController: SharedBackupController<VaultState>? = null
    private var sharingIdentity: KeywebSharingIdentity? = null

    private val authorizer = GoogleAuthorizer(app)
    private val remote = SwitchableRemote()

    /**
     * A consent screen Google wants shown. The Activity collects it and
     * launches it, because a ViewModel must not hold one.
     */
    private val _consent = MutableStateFlow<IntentSender?>(null)
    val consent: StateFlow<IntentSender?> = _consent.asStateFlow()

    /** Stable per-device id, so causal ordering survives a reinstall-free restart. */
    private fun deviceNode(): String =
        prefs.getString("device-node", null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("device-node", it).apply()
        }

    init {
        val firstRun = !VaultKeystore.exists()
        _state.value = _state.value.copy(
            phase = VaultPhase.LOCKED,
            firstRun = firstRun,
            // A returning person opened the app to get at their passwords, and
            // the only way through is this prompt — so raise it rather than
            // making them ask for the thing they already asked for.
            //
            // Not on first run. There the prompt would be creating a key and
            // choosing how the vault is protected, and the screen explaining
            // that should be read before a system dialog covers it.
            promptOnEntry = !firstRun,
            savedRules = readSavedRules(),
            lastRules = readLastRules(),
        )
    }

    // ---- Password generator rules ----------------------------------------
    //
    // Kept in this device's own preferences rather than in the vault. They are
    // a convenience, not a secret, and putting them in the synced document
    // would mean a merge conflict over a slider position. The trade is that
    // they do not follow you to another device yet.

    private val rulesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun readSavedRules(): List<SavedRules> =
        prefs.getString("generator-rules", null)?.let {
            runCatching {
                rulesJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(SavedRules.serializer()),
                    it,
                )
            }.getOrNull()
        } ?: emptyList()

    private fun readLastRules(): PasswordRules =
        prefs.getString("generator-last", null)?.let {
            runCatching {
                rulesJson.decodeFromString(PasswordRules.serializer(), it)
            }.getOrNull()
        } ?: PasswordGenerator.presets.first().rules

    fun saveRules(name: String, rules: PasswordRules) {
        // Re-saving under an existing name replaces it, rather than stacking up
        // near-identical entries nobody can tell apart.
        val next = _state.value.savedRules.filterNot { it.name.equals(name, ignoreCase = true) } +
            SavedRules(id = UUID.randomUUID().toString(), name = name, rules = rules)
        prefs.edit().putString(
            "generator-rules",
            rulesJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SavedRules.serializer()),
                next,
            ),
        ).apply()
        _state.value = _state.value.copy(savedRules = next, toast = "Saved \"$name\".")
    }

    fun rememberLastRules(rules: PasswordRules) {
        prefs.edit()
            .putString("generator-last", rulesJson.encodeToString(PasswordRules.serializer(), rules))
            .apply()
        _state.value = _state.value.copy(lastRules = rules)
    }

    /**
     * Authenticate, then open the vault.
     *
     * Raised on entry for a returning person, and by the button otherwise —
     * after a cancellation, after a failure, and on first run. The button
     * never goes away: it is the only way back once the sheet has been
     * dismissed, and the prompt cannot be raised again automatically without
     * making the app impossible to leave.
     */
    /** The Activity a passkey sheet can be raised from, while one is unlocked. */
    private var passkeyHost: java.lang.ref.WeakReference<FragmentActivity>? = null

    fun unlock(activity: FragmentActivity) {
        val firstRun = _state.value.firstRun
        _state.value = _state.value.copy(
            phase = VaultPhase.UNLOCKING,
            error = null,
            promptOnEntry = false,
        )
        viewModelScope.launch {
            when (val result = VaultUnlock.prompt(activity, firstRun)) {
                is UnlockResult.Cancelled ->
                    _state.value = _state.value.copy(
                        phase = VaultPhase.LOCKED,
                        error = "That was cancelled. Your passwords are still locked.",
                    )

                is UnlockResult.NoDeviceLock ->
                    _state.value = _state.value.copy(phase = VaultPhase.UNSUPPORTED)

                is UnlockResult.Failed ->
                    _state.value = _state.value.copy(
                        phase = VaultPhase.LOCKED,
                        error = result.message,
                    )

                is UnlockResult.Unlocked -> {
                    /*
                     * Held for the passkey ceremony, which Credential Manager
                     * can only run from an Activity — and the backup is
                     * restored a moment later, off this same unlock.
                     *
                     * A weak reference so a rotated-away Activity cannot be
                     * kept alive by the view model, and cleared on lock. The
                     * only thing it is ever used for is raising the sheet.
                     */
                    passkeyHost = java.lang.ref.WeakReference(activity)
                    openVault()
                }
            }
        }
    }

    private suspend fun openVault() {
        try {
            val store = RoomVaultStorage(database.dao(), VaultKeystore.cipher())
            storage = store
            val clock = Clock(deviceNode(), resume = store.readClock())

            // Sharing is wired in before the engine starts, because the engine
            // asks for a dataset's remote the first time it syncs one — and a
            // keyring bound on another device can already be waiting in the
            // vault it is about to pull down.
            val identity = KeywebSharingIdentity(
                store = KeywebSharingIdentityStore(
                    local = PrefsSharingIdentityStore(prefs),
                    remote = KeywebSharingIdentity.driveStore {
                        Authorization(authorizer.accessToken(), null)
                    },
                ),
                secret = {
                    storedSecret() ?: error(
                        "This phone needs your recovery code before it can share a keyring. " +
                            "Set up backup first.",
                    )
                },
            )
            sharingIdentity = identity
            val controller = createKeywebSharingController(
                identity = identity,
                registry = RoomSharedBackupRegistry(database.dao()),
                accessToken = { authorizer.accessToken() },
            )
            sharingController = controller

            val datasetRemotes = mutableMapOf<String, SharedKeyringRemote>()
            val engine = VaultSync(
                storage = store,
                remote = remote,
                clock = clock,
                remoteFor = { documentId ->
                    if (documentId == VAULT_DOCUMENT) {
                        remote
                    } else {
                        // Held rather than rebuilt, so "is this file there" is
                        // not re-derived on every sync of every shared keyring.
                        datasetRemotes.getOrPut(documentId) {
                            SharedKeyringRemote(controller, documentId)
                        }
                    }
                },
            )
            sync = engine
            sharing = KeywebSharing(engine, controller, identity, database.dao())

            // Backup, if it was already set up, resumes without asking again --
            // off the unlock path, because it talks to the network and nobody
            // should wait on Google to see their own passwords.
            viewModelScope.launch { restoreBackupIfConfigured() }

            var current = engine.state()
            // First run: give people somewhere to put things rather than an
            // empty screen with no obvious next step.
            // Live ones. Counting records meant a vault whose only keyring had
            // been deleted started with nowhere to put anything, and every save
            // from then on aimed at a keyring that was not there.
            if (liveKeyrings(current).isEmpty()) {
                current = engine.putKeyring(keyringId = "personal", name = "Just mine")
            }
            publish(current, phase = VaultPhase.READY)

            // A file picked before the window closed, attached now that it is
            // open again — so the trip back through the picker is not repeated.
            pendingAttachment?.let { (itemId, uri) ->
                pendingAttachment = null
                attachFile(itemId, uri)
            }
        } catch (error: Exception) {
            sync = null
            _state.value = _state.value.copy(
                phase = VaultPhase.LOCKED,
                error = if (needsAuthentication(error)) {
                    "Keyweb needed you to unlock again."
                } else {
                    error.message ?: "Keyweb couldn't open your vault."
                },
            )
        }
    }

    /** Drop the decrypted vault. The Keystore window may still be open, but
     *  nothing readable stays in memory. */
    fun lock() {
        passkeyHost = null
        VaultPasskey.clear()
        sync = null
        sharingIdentity?.clear()
        sharingIdentity = null
        sharing = null
        sharingController = null
        _state.value = VaultUiState(phase = VaultPhase.LOCKED, firstRun = false)
    }

    private fun publish(
        vault: VaultState,
        phase: VaultPhase = _state.value.phase,
        toast: String? = null,
    ) {
        _state.value = _state.value.copy(
            phase = phase,
            error = null,
            vault = vault,
            items = visibleItems(vault),
            status = sync?.status() ?: SyncStatus(),
            toast = toast,
        )
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    /** A passing message on the current screen, for things that need no decision. */
    fun showToast(message: String) {
        _state.value = _state.value.copy(toast = message)
    }

    /**
     * Save one password, and be sure it is actually in the vault afterwards.
     *
     * Each of these guards exists because, on its own, it was enough to make a
     * password vanish while the screen said "Saved on this phone".
     *
     * A keyring id that matches no keyring used to be written anyway. The item
     * was real, synced and backed up, and no screen on either platform would
     * show it. The list no longer hides such an item, but the better answer is
     * not to make one, so a save aimed at a keyring that is gone lands in a
     * keyring that is not — the first one there is, or a new one if this vault
     * somehow has none — and the toast says where it went.
     *
     * And the result is read back. A save that reports success without the
     * password being in the state it returns is a bug further down, and the
     * person in front of it should hear about it at the moment it happens
     * rather than the next time they go looking for that password.
     */
    fun saveItem(
        itemId: String?,
        keyringId: String,
        fields: Map<ItemField, String>,
        /** Handed the id it was saved under, so the caller can go and show it. */
        onDone: (String) -> Unit = {},
    ) {
        val engine = sync
        if (engine == null) {
            _state.value = _state.value.copy(toast = "Keyweb is locked. Unlock it and try again.")
            return
        }
        viewModelScope.launch {
            val before = engine.state()
            val wanted = before.keyrings[keyringId]
            val id = itemId ?: UUID.randomUUID().toString()
            var landedOn = keyringId
            val next = if (wanted != null && !wanted.deleted.value) {
                engine.putItem(itemId = id, keyringId = keyringId, fields = fields)
            } else {
                val ops = mutableListOf<VaultOp>()
                val fallback = liveKeyrings(before).firstOrNull()
                landedOn = fallback?.id ?: UUID.randomUUID().toString()
                if (fallback == null) {
                    val stamp = engine.stamp()
                    ops += VaultOp.KeyringPut(stamp.opId, stamp.ts, landedOn, "Just mine")
                }
                val stamp = engine.stamp()
                ops += VaultOp.ItemPut(stamp.opId, stamp.ts, id, landedOn, fields)
                engine.commitAll(ops)
            }

            if (next.items[id] == null) {
                publish(next, toast = "Keyweb could not save that password. Nothing was changed.")
                return@launch
            }
            publish(
                next,
                toast = if (landedOn == keyringId) {
                    "Saved on this phone."
                } else {
                    "Saved in ${keyringLabel(next, landedOn)}, because the keyring you chose is no longer there."
                },
            )
            onDone(id)
        }
    }

    /**
     * Put a value this item used to hold back where it was.
     *
     * A new write rather than a rewind. The value it replaces goes into
     * history in its turn, so somebody who restores the wrong one can restore
     * their way out again — the way back is never a one-way door.
     */
    fun restoreValue(itemId: String, field: ItemField, value: String) {
        val engine = sync ?: return
        val item = _state.value.vault.items[itemId] ?: return
        viewModelScope.launch {
            val next = engine.putItem(
                itemId = itemId,
                keyringId = item.keyring.value,
                fields = mapOf(field to value),
            )
            publish(next, toast = "Put back. The value it replaced is in the list too.")
        }
    }

    fun deleteItem(itemId: String, onDone: () -> Unit = {}) {
        val engine = sync ?: return
        viewModelScope.launch {
            val next = engine.deleteItem(itemId)
            publish(next, toast = "Deleted.")
            onDone()
        }
    }

    fun moveItem(itemId: String, keyringId: String) {
        val engine = sync ?: return
        viewModelScope.launch { publish(engine.moveItem(itemId, keyringId)) }
    }

    /**
     * Delete several passwords in one gesture.
     *
     * One write, not one per password. Each op is still stamped by the live
     * clock and queued individually, so the outbox and the merge behave
     * exactly as they always did — but the vault is read, re-encrypted and
     * stored once.
     */
    fun deleteItems(itemIds: List<String>) {
        val engine = sync ?: return
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val next = engine.deleteItems(itemIds)
            publish(
                next,
                toast = "${itemIds.size} password${if (itemIds.size == 1) "" else "s"} deleted.",
            )
        }
    }

    /** Move several passwords to one keyring. */
    fun moveItems(itemIds: List<String>, keyringId: String) {
        val engine = sync ?: return
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val next = engine.moveItems(itemIds, keyringId)
            val name = next.keyrings[keyringId]?.name?.value ?: "that keyring"
            publish(
                next,
                toast = "${itemIds.size} password${if (itemIds.size == 1) "" else "s"} moved to $name.",
            )
        }
    }

    /**
     * Delete a keyring and the passwords in it.
     *
     * The passwords go too, deliberately. Deleting only the keyring leaves
     * them in storage and in the Drive backup forever — invisible, because
     * the list hides anything whose keyring is gone, but still there. A
     * password manager should not keep passwords a person believes they
     * deleted.
     *
     * One atomic write, so the vault is never left holding half of it — which
     * also removes the question of what an interrupted bulk delete leaves
     * behind, since it can no longer be interrupted partway.
     */
    fun deleteKeyring(keyringId: String) {
        val engine = sync ?: return
        viewModelScope.launch {
            val name = engine.state().keyrings[keyringId]?.name?.value ?: "That keyring"
            publish(engine.deleteKeyringWithItems(keyringId), toast = "$name was deleted.")
        }
    }

    fun addKeyring(name: String) {
        val engine = sync ?: return
        viewModelScope.launch {
            val next = engine.putKeyring(keyringId = UUID.randomUUID().toString(), name = name)
            publish(next, toast = "The $name keyring is ready.")
        }
    }

    // ---- Encrypted backup ------------------------------------------------
    //
    // Android works through the *recovery* envelope. The web app's other
    // envelope is keyed by a WebAuthn PRF the phone cannot reproduce, so this
    // device carries that one forward untouched and is authoritative only for
    // the copy the printed code opens. See DriveVaultRemote.

    /** The recovery secret, sealed by the Keystore, alongside the vault. */
    private suspend fun storedSecret(): ByteArray? {
        val sealed = database.dao().meta(RECOVERY_SECRET_KEY) ?: return null
        return try {
            android.util.Base64.decode(
                VaultKeystore.cipher().open(sealed),
                android.util.Base64.NO_WRAP,
            )
        } catch (cause: Exception) {
            null
        }
    }

    private suspend fun rememberSecret(secret: ByteArray) {
        database.dao().putMeta(
            app.keyweb.data.MetaRow(
                RECOVERY_SECRET_KEY,
                VaultKeystore.cipher()
                    .seal(android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP)),
            ),
        )
    }

    private fun driveClient() = DriveClient(
        token = { authorizer.accessToken() },
        chosenFile = { prefs.getString(CHOSEN_BACKUP_KEY, null) },
    )

    /**
     * Every vault file in the account, for somebody to choose between.
     *
     * Offered rather than only refused. Refusing to guess was the safe half
     * and only the safe half: it left somebody with a true statement and
     * nothing to do about it. These are separate vaults sealed whole, so they
     * cannot be merged and somebody has to say which is theirs — but they can
     * only say it if they are shown the dates, because the names are
     * identical.
     */
    fun listBackupFiles() {
        viewModelScope.launch {
            val found = runCatching {
                driveClient().listFiles().filter { it.keywebMarker == "vault-v1" }
            }.getOrNull().orEmpty()
            _state.value = _state.value.copy(
                backupFiles = found.map {
                    BackupFileChoice(
                        fileId = it.fileId,
                        modifiedAtMs = it.modifiedAtMs,
                        chosen = it.fileId == prefs.getString(CHOSEN_BACKUP_KEY, null),
                    )
                },
            )
        }
    }

    /** Say which file is the real vault, and carry on with it. */
    fun chooseBackupFile(fileId: String) {
        prefs.edit().putString(CHOSEN_BACKUP_KEY, fileId).apply()
        _state.value = _state.value.copy(backupFiles = emptyList())
        viewModelScope.launch {
            restoreBackupIfConfigured()
        }
    }

    /**
     * The passkey copy's cipher, when this phone can have one.
     *
     * Best effort on purpose. A phone that declines the sheet, has no
     * Credential Manager, or is on a build where the asset link has not
     * propagated still syncs perfectly through the recovery envelope — it
     * simply carries the passkey copy forward instead of refreshing it, which
     * is what any device that cannot rewrite an envelope should do.
     *
     * When there is no passkey envelope in the file yet, one is *not* created
     * here. Making a passkey is a sheet with a fingerprint on it, and raising
     * that unprompted during a background restore is the kind of thing that
     * teaches people to dismiss security prompts. It is offered deliberately
     * instead, from the backup screen.
     */
    private suspend fun passkeyCipherFor(probe: DriveVaultRemote): VaultEnvelopeCipher? {
        val activity = passkeyHost?.get() ?: return null
        val envelope = runCatching { probe.fetchPasskeySealed() }.getOrNull() ?: return null
        return runCatching { VaultPasskey.unlock(activity, envelope) }.getOrNull()
    }

    /**
     * Give this phone a passkey for the backup, so it writes both copies.
     *
     * The thing that ends the divergence. Until a device can seal both
     * envelopes, whichever one it cannot write goes stale the moment it edits
     * anything — and the other device reads that stale copy believing it is
     * current.
     */
    fun addPasskeyToBackup() {
        val activity = passkeyHost?.get()
        if (activity == null) {
            showToast("Open Keyweb and unlock it first.")
            return
        }
        viewModelScope.launch {
            try {
                val secret = storedSecret()
                if (secret == null) {
                    showToast("Set up backup first.")
                    return@launch
                }
                val client = driveClient()
                val probe = DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(secret))
                val existing = probe.fetchRecoverySealed()
                // An envelope already there is joined, not replaced: replacing
                // it would lock out whichever browser wrote it.
                val onFile = runCatching { probe.fetchPasskeySealed() }.getOrNull()
                val passkey = if (onFile != null) {
                    VaultPasskey.unlock(activity, onFile)
                } else {
                    VaultPasskey.create(activity)
                }
                remote.attach(
                    DriveVaultRemote(
                        client,
                        VaultEnvelopeCipher.forRecoveryCode(secret, existing),
                        passkeyCipher = passkey,
                    ),
                )
                _state.value = _state.value.copy(backupPhoneOnly = false)
                syncNow()
                showToast(
                    if (onFile != null) {
                        "Done. This phone and your browser share one backup now."
                    } else {
                        "Done. You can open this backup in a browser now."
                    },
                )
            } catch (cause: Exception) {
                showToast(cause.message ?: "Keyweb couldn't set up a passkey for the backup.")
            }
        }
    }

    /** Turn the remote back on at unlock, silently, if it was set up before. */
    private suspend fun restoreBackupIfConfigured() {
        val secret = storedSecret() ?: return
        try {
            val client = driveClient()
            // Reuse the salt already in Drive, or the derived key will differ
            // from the one that sealed the backup and open nothing.
            val probe = DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(secret))
            val existing = probe.fetchRecoverySealed()
            val passkey = passkeyCipherFor(probe)
            remote.attach(
                DriveVaultRemote(
                    client,
                    VaultEnvelopeCipher.forRecoveryCode(secret, existing),
                    passkeyCipher = passkey,
                ),
            )
            // Said, not silently endured: without it this backup opens only on
            // this phone, and the person cannot know that from anywhere else.
            _state.value = _state.value.copy(backupPhoneOnly = passkey == null)
            _state.value = _state.value.copy(
                backupConfigured = true,
                backup = BackupUiState(stage = BackupStage.ON),
            )
            syncNow()
        } catch (cause: Exception) {
            // Offline, or consent since revoked. Backup stays off for this
            // session; the vault is fully usable and the status line says so.
            _state.value = _state.value.copy(
                backup = BackupUiState(stage = BackupStage.OFF, error = describeBackup(cause)),
            )
            // Whatever went wrong, show which files are there. It answers a
            // question somebody has at exactly this moment and needs no key.
            listBackupFiles()
        }
    }

    /**
     * Begin, or resume, setting up backup.
     *
     * Split into stages because the middle one asks a question that must not be
     * guessed: if Drive already holds a Keyweb backup, some other device minted
     * the recovery code, and minting a second one here would silently retire
     * the sheet already written down.
     */
    fun setUpBackup() {
        _state.value = _state.value.copy(
            backup = _state.value.backup.copy(stage = BackupStage.CONNECTING, busy = true, error = null),
        )
        viewModelScope.launch {
            try {
                val client = driveClient()
                val probe = DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(ByteArray(20)))
                val existing = probe.fetchRecoverySealed()
                if (existing != null) {
                    _state.value = _state.value.copy(
                        backup = BackupUiState(stage = BackupStage.NEEDS_CODE),
                    )
                    return@launch
                }
                val secret = RecoveryCode.generate()
                finishSetup(secret, existing = null)
                _state.value = _state.value.copy(
                    backupConfigured = true,
                    backup = BackupUiState(
                        stage = BackupStage.SHOW_CODE,
                        newCode = RecoveryCode.format(secret),
                    ),
                )
            } catch (cause: GoogleAuthorizer.ConsentRequired) {
                // Not an error: Google simply has not been asked yet.
                _consent.value = cause.intentSender
            } catch (cause: Exception) {
                _state.value = _state.value.copy(
                    backup = BackupUiState(stage = BackupStage.OFF, error = describeBackup(cause)),
                )
            }
        }
    }

    /** The consent screen came back. Whatever it returned, try again. */
    fun consentHandled() {
        _consent.value = null
        setUpBackup()
    }

    /**
     * Adopt the code that already protects this backup.
     *
     * Proved by opening the remote envelope with it, not by shape. Accepting an
     * unverified code would leave the phone believing it can keep the backup
     * current when every write would in fact be unreadable.
     */
    fun useExistingCode(typed: String) {
        _state.value = _state.value.copy(backup = _state.value.backup.copy(busy = true, error = null))
        viewModelScope.launch {
            try {
                val secret = RecoveryCode.parse(typed)
                val client = driveClient()
                val existing = DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(secret))
                    .fetchRecoverySealed()
                    ?: throw InvalidRecoveryCode("That backup has no recovery copy yet.")
                // Throws if the code is wrong, which is the whole point.
                VaultEnvelopeCipher.forRecoveryCode(secret, existing).open(existing)

                finishSetup(secret, existing)
                _state.value = _state.value.copy(
                    backupConfigured = true,
                    backup = BackupUiState(stage = BackupStage.ON),
                    toast = "Backup is on. Your passwords are safe if this phone is lost.",
                )
            } catch (cause: Exception) {
                _state.value = _state.value.copy(
                    backup = _state.value.backup.copy(
                        stage = BackupStage.NEEDS_CODE,
                        busy = false,
                        error = describeBackup(cause),
                    ),
                )
            }
        }
    }

    private suspend fun finishSetup(secret: ByteArray, existing: app.keyweb.vault.SyncEnvelopeV1?) {
        rememberSecret(secret)
        val client = driveClient()
        /*
         * The passkey is part of setting up a backup, not a later chore.
         *
         * Somebody turning on backup is already here, already deliberate, and
         * already expecting to be asked something — so the sheet belongs in
         * this moment rather than in a settings screen they would have to be
         * told to visit. A backup that only opens on the device that made it
         * is not the thing they think they are turning on.
         *
         * Best effort: if the sheet is declined or unavailable, backup still
         * works through the recovery code, and the phone says what is missing
         * rather than failing setup over it.
         */
        remote.attach(
            DriveVaultRemote(
                client,
                VaultEnvelopeCipher.forRecoveryCode(secret, existing),
                passkeyCipher = establishPasskey(client),
            ),
        )
        // Publish immediately, so "backup is on" is true the moment it is said
        // rather than at some later sync.
        syncNow()
    }

    /**
     * The passkey for the backup: the one already on the file, or a new one.
     *
     * Joined rather than replaced when the file already has a passkey copy —
     * replacing it would lock out whichever browser wrote it, which is the
     * exact failure this whole arrangement exists to stop.
     */
    private suspend fun establishPasskey(client: DriveClient): VaultEnvelopeCipher? {
        val activity = passkeyHost?.get() ?: return null
        return try {
            val probe = DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(ByteArray(20)))
            val onFile = runCatching { probe.fetchPasskeySealed() }.getOrNull()
            if (onFile != null) {
                VaultPasskey.unlock(activity, onFile)
            } else {
                VaultPasskey.create(activity)
            }
        } catch (cause: Exception) {
            _state.value = _state.value.copy(backupPhoneOnly = true)
            null
        }
    }

    /** Dismiss the written-down code. It is never shown again. */
    fun codeWrittenDown() {
        _state.value = _state.value.copy(
            backup = BackupUiState(stage = BackupStage.ON),
            toast = "Backup is on. Keep that code somewhere safe.",
        )
    }

    fun dismissBackupError() {
        _state.value = _state.value.copy(backup = _state.value.backup.copy(error = null))
    }

    /**
     * Back up now, and take whatever another device has published.
     *
     * Publishes the resulting state, not just the status. A sync pulls as well
     * as pushes, so a password added on another device arrives here — and
     * updating only the status would leave it sitting in storage, invisible
     * until something else happened to redraw the list.
     */
    fun syncNow() {
        val engine = sync ?: return
        if (!remote.configured) return
        viewModelScope.launch {
            engine.sync()
            adoptSharedKeyrings()
            publish(engine.state())
        }
    }

    /**
     * Pick up a shared keyring that has become readable.
     *
     * After a sync rather than on a timer, because "readable" changes only when
     * Drive says so — and by then there is already a token in hand, so this
     * never causes a sign-in nobody asked for. A keyring that is not ready yet
     * is the normal case and not worth an error; it will be ready on some later
     * sync.
     */
    private suspend fun adoptSharedKeyrings(): List<String> {
        val adopted = runCatching { sharing?.adoptJoinedKeyrings().orEmpty() }
            .getOrDefault(emptyList())
        refreshReadOnly()
        return adopted
    }

    /** Not knowing must never make a read-only keyring look editable. */
    private suspend fun refreshReadOnly() {
        val engine = sharing ?: return
        val vault = sync?.state() ?: return
        runCatching { engine.readOnlyKeyrings(vault) }.getOrNull()?.let {
            _state.value = _state.value.copy(readOnlyKeyrings = it)
        }
    }


    // ---- Files kept on a password -----------------------------------------

    /**
     * Attach a file the person picked.
     *
     * The keyring comes from the item rather than the caller, so a file always
     * lands in the same document as the password it belongs to — which is what
     * makes it travel when the keyring is shared.
     */
    /**
     * A file picked but not yet attached, because the Keystore window closed.
     *
     * Choosing a file leaves the app, and the trip through the system picker
     * routinely takes longer than the five minutes the vault key is authorised
     * for — so this is the one action that hits the expiry more than any
     * other. Making somebody find the file again after unlocking would be
     * punishing them for how long the picker took.
     */
    private var pendingAttachment: Pair<String, android.net.Uri>? = null

    fun attachFile(itemId: String, uri: android.net.Uri) {
        val engine = sync ?: return
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                } ?: error("Keyweb couldn't read that file.")

                if (bytes.size > MAX_ATTACHMENT_BYTES) {
                    _state.value = _state.value.copy(
                        toast = "That file is %.1f MB. Keyweb can hold files up to %d MB — ".format(
                            bytes.size / 1024.0 / 1024.0,
                            MAX_ATTACHMENT_BYTES / 1024 / 1024,
                        ) + "anything larger would make saving a password slow every time.",
                    )
                    return@launch
                }

                val name = displayName(uri) ?: "file"
                val keyringId = _state.value.vault.items[itemId]?.keyring?.value ?: return@launch
                val next = engine.attachFile(
                    itemId = itemId,
                    keyringId = keyringId,
                    blobId = blobIdFor(bytes),
                    name = name,
                    type = getApplication<Application>().contentResolver.getType(uri)
                        ?: "application/octet-stream",
                    data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                    bytes = bytes.size,
                )
                pendingAttachment = null
                publish(next, toast = "$name was added to this password.")
                syncNow()
            } catch (cause: Exception) {
                if (needsAuthentication(cause)) {
                    // Kept, and re-run the moment the vault is open again.
                    pendingAttachment = itemId to uri
                    relock("Keyweb needed you to unlock again. Your file is still waiting.")
                } else {
                    _state.value = _state.value.copy(
                        toast = cause.message ?: "Keyweb couldn't read that file.",
                    )
                }
            }
        }
    }

    /**
     * Send somebody back to the unlock screen, with the prompt already raised.
     *
     * A write that fails because the Keystore's authorisation window closed is
     * not an error to report and move on from: there is nothing the person can
     * do about it from the screen they are on, and a toast saying "unlock
     * again" with no way to unlock is worse than useless.
     */
    private fun relock(message: String) {
        sync = null
        _state.value = _state.value.copy(
            phase = VaultPhase.LOCKED,
            promptOnEntry = true,
            error = message,
        )
    }

    fun removeAttachment(itemId: String, blobId: String) {
        val engine = sync ?: return
        viewModelScope.launch {
            publish(
                engine.removeAttachment(itemId, blobId),
                toast = "That file was removed from your vault.",
            )
            syncNow()
        }
    }

    /**
     * Write a file out of the vault to somewhere the person chose.
     *
     * Deliberately the only way bytes leave: a saved file is outside
     * everything the vault protects, which the screen says before offering it.
     */
    fun saveAttachment(blobId: String, uri: android.net.Uri) {
        viewModelScope.launch {
            val data = _state.value.vault.items[blobId]?.field("secret:data") ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                    }
                }
                _state.value = _state.value.copy(toast = "Saved to this phone.")
            } catch (cause: Exception) {
                _state.value = _state.value.copy(toast = "Keyweb couldn't save that file.")
            }
        }
    }

    /** The name the picker gave the file, which is all we have to call it. */
    private fun displayName(uri: android.net.Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    // ---- Sharing a keyring ------------------------------------------------
    //
    // The awkward part of this on a phone is the file grant. `drive.file` is
    // per file, Google only issues it through the Picker, and the Picker only
    // runs in a browser — so joining a shared keyring means a trip out to the
    // web app and back. Everything else, including every prompt, happens on
    // this side of that trip.

    private fun setShare(update: (ShareUiState) -> ShareUiState) {
        _state.value = _state.value.copy(share = update(_state.value.share))
    }

    /** True when this build can share at all: it needs a Google account. */
    val canShare: Boolean get() = sharing != null

    /** The six characters naming this person, for reading aloud to someone. */
    fun showSharingKey() {
        val engine = sharing ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(sharingKey = engine.myFingerprint())
            } catch (cause: Exception) {
                _state.value = _state.value.copy(toast = describeShare(cause))
            }
        }
    }

    /**
     * Open the sharing screen for one keyring.
     *
     * Members are read from Drive rather than remembered, because who can see a
     * keyring is a fact about the file and not about this phone. Failing to
     * reach Drive leaves it unknown, which the screen shows as unknown instead
     * of as an empty list of people.
     */
    fun openSharing(keyringId: String) {
        val engine = sharing ?: return
        val datasetId = datasetOf(_state.value.vault.keyrings[keyringId])
        setShare {
            ShareUiState(
                stage = ShareStage.IDLE,
                keyringId = keyringId,
                datasetId = datasetId,
                busy = datasetId != null,
            )
        }
        viewModelScope.launch {
            val pending = runCatching { engine.pendingInvites(keyringId) }
                .getOrDefault(emptyList())
            if (datasetId == null) {
                setShare { it.copy(pending = pending, busy = false) }
                return@launch
            }
            val members = runCatching { engine.members(datasetId) }.getOrNull()
            refreshReadOnly()
            setShare {
                it.copy(
                    members = members.orEmpty(),
                    youOwnIt = members?.firstOrNull { member -> member.you }
                        // An admin can invite and revoke too, which is the
                        // whole point of the role. Checking only for OWNER
                        // would have offered an admin nothing but a list.
                        ?.let { me ->
                            me.role == SharingRole.OWNER || me.role == SharingRole.ADMIN
                        },
                    pending = pending,
                    busy = false,
                )
            }
        }
    }

    fun closeSharing() {
        setShare { ShareUiState() }
    }

    /** Make a link inviting somebody to a keyring. */
    fun shareKeyring(keyringId: String, email: String, role: SharingRole) {
        val engine = sharing ?: return
        setShare { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val link = engine.shareKeyring(keyringId, email, role)
                setShare { it.copy(link = link, busy = false) }
                openSharing(keyringId)
                setShare { it.copy(link = link) }
            } catch (cause: Exception) {
                setShare { it.copy(busy = false, error = describeShare(cause)) }
            }
        }
    }

    fun cancelInvite(exchangeId: String) {
        val engine = sharing ?: return
        val keyringId = _state.value.share.keyringId ?: return
        viewModelScope.launch {
            runCatching { engine.cancelInvite(exchangeId) }
            openSharing(keyringId)
        }
    }

    /**
     * Hand a keyring to somebody already on it, and get the link to send them.
     *
     * Nothing changes at this point except that a link exists. The transfer
     * happens when they open it, which is deliberate: there is no moment where
     * the keyring belongs to nobody, and an owner who changes their mind
     * before sending the link has changed nothing.
     */
    fun proposeOwnership(keyId: String, email: String?) {
        val engine = sharing ?: return
        val datasetId = _state.value.share.datasetId ?: return
        setShare { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val link = engine.proposeOwnership(datasetId, keyId, email.orEmpty())
                setShare { it.copy(busy = false, handoverLink = link) }
            } catch (cause: Exception) {
                setShare { it.copy(busy = false, error = describeShare(cause)) }
            }
        }
    }

    fun setShareRole(keyId: String, role: SharingRole) {
        val engine = sharing ?: return
        val datasetId = _state.value.share.datasetId ?: return
        val keyringId = _state.value.share.keyringId ?: return
        setShare { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                engine.setRole(datasetId, keyId, role)
                openSharing(keyringId)
            } catch (cause: Exception) {
                setShare { it.copy(busy = false, error = describeShare(cause)) }
            }
        }
    }

    fun revokeShare(keyId: String) {
        val engine = sharing ?: return
        val datasetId = _state.value.share.datasetId ?: return
        val keyringId = _state.value.share.keyringId ?: return
        setShare { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                engine.revoke(datasetId, keyId)
                openSharing(keyringId)
            } catch (cause: Exception) {
                setShare { it.copy(busy = false, error = describeShare(cause)) }
            }
        }
    }

    /** Stop sharing a keyring of your own and bring its passwords home. */
    fun stopSharing(keyringId: String) {
        val engine = sync ?: return
        viewModelScope.launch {
            val name = _state.value.vault.keyrings[keyringId]?.name?.value ?: "That keyring"
            val next = engine.unbindKeyring(keyringId)
            setShare { ShareUiState() }
            publish(next, toast = "$name is private again.")
        }
    }

    /** Stop carrying a keyring somebody else shared. Theirs is untouched. */
    fun leaveKeyring(keyringId: String) {
        val engine = sync ?: return
        viewModelScope.launch {
            val name = _state.value.vault.keyrings[keyringId]?.name?.value ?: "That keyring"
            val next = engine.leaveKeyring(keyringId)
            setShare { ShareUiState() }
            publish(next, toast = "$name was removed from this vault.")
        }
    }

    // ---- Links opened on this phone ----

    /**
     * A link somebody sent, arriving as an intent.
     *
     * Returns true when it was a share link and has been taken over, so the
     * caller knows not to treat it as an ordinary page.
     */
    fun openShareLink(url: String?): Boolean {
        if (url == null) return false
        ShareLinks.parseJoin(url)?.let { join ->
            setShare {
                ShareUiState(
                    stage = ShareStage.INVITED,
                    invite = PendingShareInvite(
                        invitation = join.invitation,
                        files = join.files,
                        label = join.label,
                        ownerEmail = join.ownerEmail,
                        role = join.files.firstOrNull()?.role
                            ?: join.invitation.requestedGrants.firstOrNull()?.role
                            ?: SharingRole.VIEWER,
                    ),
                )
            }
            return true
        }
        ShareLinks.parseResponse(url)?.let { response ->
            previewShareResponse(response)
            return true
        }
        /*
         * A keyring being handed over. Applied rather than shown as a screen
         * to confirm: there is nothing to decide, because the person receiving
         * it is already a member, the artifact is signed by the current owner,
         * and refusing it would leave the keyring owned by somebody who has
         * already decided to stop owning it. The toast says what happened.
         */
        ShareLinks.parseOwnership(url)?.let { transfer ->
            val engine = sharing ?: return@let
            viewModelScope.launch {
                try {
                    engine.acceptOwnership(transfer)
                    showToast("That keyring is yours now. You can invite and remove people on it.")
                } catch (cause: Exception) {
                    showToast(describeShare(cause))
                }
            }
            return true
        }
        return false
    }

    /**
     * Step one of joining: send the person to a browser to grant the file.
     *
     * The identity is unlocked *before* the browser opens, so the trip out and
     * back needs nothing further from them. If this is left until afterwards,
     * the prompt arrives when attention has moved on — or after Android has
     * discarded the process while the browser was in front.
     */
    fun beginShareGrant(activity: FragmentActivity) {
        val invite = _state.value.share.invite ?: return
        val engine = sharing ?: return
        setShare { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val fingerprint = engine.myFingerprint()
                val url = GrantBrowser.shareGrantUrl(
                    encodeSharingDatasetFilesV1(invite.files),
                )
                // Copied first: if nothing on the device takes an https link,
                // the fallback is pasting it rather than a dead end.
                GrantBrowser.copyLink(activity, url)
                val opened = GrantBrowser.open(activity, url)
                setShare {
                    it.copy(
                        stage = ShareStage.AWAITING_GRANT,
                        fingerprint = fingerprint,
                        busy = false,
                        error = if (opened) {
                            null
                        } else {
                            "Keyweb couldn't find a browser. The link is on your clipboard — " +
                                "paste it into one, then come back."
                        },
                    )
                }
            } catch (cause: Exception) {
                setShare { it.copy(busy = false, error = describeShare(cause)) }
            }
        }
    }

    /**
     * Step two: finish the join now the file has been granted.
     *
     * Separate from step one and driven by a tap, because there is no way to be
     * told the browser is finished. A 404 from Drive here means the file was
     * not actually picked, which is an ordinary mistake and is reported as one.
     */
    fun finishShareJoin() {
        val invite = _state.value.share.invite ?: return
        val engine = sharing ?: return
        setShare { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val link = engine.joinFromLink(
                    invitation = invite.invitation,
                    files = invite.files,
                    label = invite.label,
                    // Already done, in the browser. Nothing to ask for here.
                    grantAccess = {},
                )
                setShare { it.copy(stage = ShareStage.REPLY_READY, link = link, busy = false) }
            } catch (cause: Exception) {
                setShare {
                    it.copy(
                        stage = ShareStage.AWAITING_GRANT,
                        busy = false,
                        error = describeShare(cause),
                    )
                }
            }
        }
    }

    /** Show the fingerprint; wrapping waits until they confirm it. */
    fun previewShareResponse(response: SharingPublicKeyResponseV1) {
        val engine = sharing ?: return
        setShare { ShareUiState(stage = ShareStage.CONFIRM_ACCEPT, reply = response) }
        viewModelScope.launch {
            try {
                val preview = engine.previewResponse(response)
                setShare { it.copy(preview = preview) }
            } catch (cause: Exception) {
                setShare { it.copy(error = describeShare(cause)) }
            }
        }
    }

    /** The owner's last step: turn a reply link into access. */
    fun acceptShareResponse(response: SharingPublicKeyResponseV1) {
        val engine = sharing ?: return
        setShare { ShareUiState(stage = ShareStage.ACCEPTING, reply = response) }
        viewModelScope.launch {
            try {
                val accepted = engine.acceptResponse(response)
                setShare { it.copy(stage = ShareStage.ACCEPTED, accepted = accepted) }
            } catch (cause: Exception) {
                setShare { it.copy(stage = ShareStage.ACCEPTING, error = describeShare(cause)) }
            }
        }
    }

    /** Try the accept again with the reply already in hand. */
    fun retryAccept() {
        _state.value.share.reply?.let(::acceptShareResponse)
    }

    private fun describeShare(cause: Throwable): String = when {
        needsAuthentication(cause) -> "Keyweb needed you to unlock again."
        else -> cause.message ?: "Keyweb couldn't finish that."
    }

    // ---- Importing from KeePass ------------------------------------------

    /** Bytes of the file being opened, held only until the password arrives. */
    private var pendingBytes: ByteArray? = null
    private var pendingFile: KdbxFile? = null

    private fun setImport(update: (ImportUiState) -> ImportUiState) {
        _state.value = _state.value.copy(import = update(_state.value.import))
    }

    /**
     * Ask Drive which files this account has handed over.
     *
     * Under `drive.file` the answer is exactly the granted files, so this is
     * the same question the web app asks and gives the same answer. Nothing is
     * kept on the device and nothing needs syncing: a file picked in a browser
     * simply appears here.
     */
    fun refreshImportFiles(interactive: Boolean = true) {
        setImport { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val files = driveClient().listFiles()
                    // Keyweb's own backup carries a marker and is not something
                    // anyone wants to import into itself.
                    .filter { it.keywebMarker == null }
                    .filter { it.name.endsWith(".kdbx", ignoreCase = true) }
                    .sortedByDescending { it.modifiedAtMs ?: 0L }
                setImport { it.copy(files = files, busy = false) }
            } catch (cause: GoogleAuthorizer.ConsentRequired) {
                // Opening the screen must not throw a Google account prompt at
                // someone who has not asked for one -- especially since the
                // local-file option below needs no Google account at all. The
                // prompt belongs to the button that asks for Drive.
                if (interactive) {
                    _consent.value = cause.intentSender
                }
                setImport { it.copy(busy = false) }
            } catch (cause: Exception) {
                setImport {
                    it.copy(busy = false, error = if (interactive) describeBackup(cause) else null)
                }
            }
        }
    }

    /**
     * Open a file chosen from this phone.
     *
     * Android's own document picker, which reaches local storage and any
     * provider the phone has — the Drive app, Dropbox, a USB stick. Worth being
     * clear about what this is *not*: the permission it grants is to this app
     * on this device, and authorizes nothing at the Drive API, so a file opened
     * this way does not become visible to the website or to another phone. That
     * is what the Picker grant is for. This is for the file that is simply
     * here.
     */
    fun openLocalFile(uri: android.net.Uri) {
        setImport { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val name = withContext(Dispatchers.IO) {
                    resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME,
                        )
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    }
                } ?: "that file"
                pendingBytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("Keyweb couldn't open that file.")
                setImport {
                    it.copy(stage = ImportStage.PASSWORD, openingName = name, busy = false)
                }
            } catch (cause: Exception) {
                setImport {
                    it.copy(busy = false, error = cause.message ?: "Keyweb couldn't open that file.")
                }
            }
        }
    }

    /** Open the file, holding its bytes until a password is supplied. */
    fun openImportFile(fileId: String) {
        val name = _state.value.import.files.firstOrNull { it.fileId == fileId }?.name.orEmpty()
        setImport { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                pendingBytes = driveClient().readBytes(fileId)
                setImport {
                    it.copy(stage = ImportStage.PASSWORD, openingName = name, busy = false)
                }
            } catch (cause: Exception) {
                setImport { it.copy(busy = false, error = describeBackup(cause)) }
            }
        }
    }

    /**
     * Read the file with the master password.
     *
     * The password is used once and dropped. The parse happens off the main
     * thread because Argon2 is deliberately slow — that is the entire point of
     * it — and a KeePass file with strong settings can take seconds.
     */
    fun unlockImportFile(password: String) {
        val bytes = pendingBytes ?: return
        setImport { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.Default) { KdbxReader.read(bytes, password) }
                if (file.entries.isEmpty()) {
                    setImport {
                        it.copy(busy = false, error = "That file opened, but there were no passwords in it.")
                    }
                    return@launch
                }
                pendingFile = file
                val rings = sync?.state()?.keyrings?.values
                    ?.filter { !it.deleted.value }
                    ?.map { ring -> ring.id to ring.name.value }
                    .orEmpty()
                setImport {
                    it.copy(
                        stage = ImportStage.PREVIEW,
                        entryCount = file.entries.size,
                        keyringNames = file.keyringNames,
                        ungrouped = file.ungrouped,
                        // Suggested from the file that was actually opened, so
                        // it follows a second import rather than sticking.
                        ungroupedDestination = UngroupedDestination.New(
                            suggestedKeyringName(it.openingName),
                        ),
                        existingKeyrings = rings,
                        skipped = file.skipped,
                        oversized = file.oversized,
                        versions = file.versions,
                        busy = false,
                    )
                }
            } catch (cause: WrongMasterPassword) {
                setImport { it.copy(busy = false, error = cause.message) }
            } catch (cause: Exception) {
                setImport {
                    it.copy(busy = false, error = cause.message ?: "Keyweb couldn't read that file.")
                }
            }
        }
    }

    /**
     * Copy the entries in.
     *
     * Item ids are derived from the KeePass UUID, so importing the same file
     * again updates what changed rather than making a second copy of
     * everything. That is what makes re-importing safe to do repeatedly.
     */
    /** The user's answer to where the entries in no group should go. */
    fun setUngroupedDestination(destination: UngroupedDestination) {
        setImport { it.copy(ungroupedDestination = destination, error = null) }
    }

    fun confirmImport() {
        val engine = sync ?: return
        val file = pendingFile ?: return
        val choice = _state.value.import.ungroupedDestination
        if (file.ungrouped > 0 && choice is UngroupedDestination.New && choice.name.isBlank()) {
            setImport {
                it.copy(error = "Give the new keyring a name, or choose one you already have.")
            }
            return
        }
        setImport { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                // Insertion-ordered: `fallback` below takes the first
                // value, and a plain HashMap made "first" an arbitrary
                // consequence of string hashing — so an entry that missed the
                // map landed in a different folder depending on what the
                // groups happened to be called.
                val rings = LinkedHashMap<String, String>()
                val current = engine.state()
                val ops = mutableListOf<VaultOp>()

                // Remembers what it has already queued, not just what the
                // vault already had. Two callers can ask for the same name in
                // one import — the ungrouped keyring defaults to the file's
                // name, which may well match a group in it — and without this
                // they would each mint an id and the import would end with two
                // keyrings wearing the same name.
                val minted = mutableMapOf<String, String>()
                fun keyringFor(name: String): String {
                    minted[name]?.let { return it }
                    val existing = current.keyrings.values
                        .firstOrNull { !it.deleted.value && it.name.value == name }
                    if (existing != null) return existing.id.also { minted[name] = it }
                    val id = UUID.randomUUID().toString()
                    val stamp = engine.stamp()
                    ops += VaultOp.KeyringPut(stamp.opId, stamp.ts, id, name)
                    minted[name] = id
                    return id
                }

                for (name in file.keyringNames) rings[name] = keyringFor(name)
                // Where the entries in no group go. Resolved before any
                // entry is written, so a half-finished import cannot leave
                // them somewhere arbitrary.
                val ungroupedId = when {
                    file.ungrouped == 0 -> ""
                    choice is UngroupedDestination.Existing -> choice.keyringId
                    else -> keyringFor((choice as UngroupedDestination.New).name.trim())
                }

                // The same op builder the vault tests exercise, rather than a
                // second copy of the rules inline here. It throws on a keyring
                // it was not given, which is the behaviour those tests pin.
                ops += importOperations(
                    entries = file.entries,
                    keyringIds = rings,
                    ungroupedKeyringId = ungroupedId,
                    existing = current,
                    stamp = engine::stamp,
                )

                // One write. Importing a file of two hundred passwords used to
                // commit two hundred times, each re-encrypting the whole vault
                // and re-reading a growing outbox — the same cost that made
                // deleting a large keyring slow, on the path people meet
                // first. The keyring ops are ahead of the item ops, so a
                // device replaying the outbox never sees an item naming a
                // keyring that does not exist yet.
                val next = engine.commitAll(ops)
                pendingBytes = null
                pendingFile = null
                publish(next)
                setImport {
                    it.copy(
                        stage = ImportStage.DONE,
                        importedCount = file.entries.size,
                        busy = false,
                    )
                }
            } catch (cause: Exception) {
                setImport {
                    it.copy(busy = false, error = cause.message ?: "Keyweb couldn't finish the import.")
                }
            }
        }
    }

    // ---- Reading an authenticator's QR code ----------------------------

    private fun setScan(update: (ScanUiState) -> ScanUiState) {
        _state.value = _state.value.copy(scan = update(_state.value.scan))
    }

    fun closeScan() {
        _state.value = _state.value.copy(scan = ScanUiState())
    }

    /**
     * What one QR code turned out to hold.
     *
     * Accounts accumulate rather than replace: a large export is several codes
     * and the person is told how many are left. Scanning the same code twice
     * is harmless — the key is the account itself, so a repeat lands on the
     * row that is already there.
     */
    fun onCodeScanned(text: String) {
        val batch = try {
            Authenticator.read(text)
        } catch (cause: NotAnAccountCode) {
            setScan {
                it.copy(
                    error = cause.message ?: "That QR code isn't an authenticator code.",
                )
            }
            return
        }

        val current = _state.value.scan
        // A second export while one is half-scanned is a different set of
        // accounts, and mixing them would silently drop whichever codes did
        // not match. Starting over is the honest answer.
        val fresh = current.batchId != null && current.batchId != batch.batchId
        val base = if (fresh) ScanUiState() else current
        val items = _state.value.vault.items.values

        /*
         * Replanned over everything scanned so far, not just the new code.
         *
         * Where a code belongs depends on what else is in the batch: two codes
         * from one company must not both claim one password, and that is only
         * knowable once both have arrived. Planning each code as it landed
         * would have decided the first one's destination before the second one
         * existed to contradict it.
         */
        fun key(account: ScannedAccount) =
            "${account.issuer}\u0000${account.name}\u0000${account.otp}"

        val seenKeys = mutableSetOf<String>()
        val scanned = buildList {
            for (pending in base.accounts) if (seenKeys.add(pending.key)) add(pending.account)
            for (account in batch.accounts) if (seenKeys.add(key(account))) add(account)
        }
        // Whether a row was ticked is the person's, and survives a replan.
        val ticked = base.accounts.filter { !it.selected }.map { it.key }.toSet()

        setScan {
            base.copy(
                accounts = planAccounts(scanned, items).map { plan ->
                    PendingAccount(
                        key = key(plan.account),
                        selected = !ticked.contains(key(plan.account)),
                        plan = plan,
                    )
                },
                seen = base.seen + batch.index,
                total = maxOf(batch.total, base.total),
                batchId = batch.batchId,
                error = null,
                addedTo = null,
            )
        }
    }

    fun reportScanProblem(message: String) {
        setScan { it.copy(error = message, busy = false) }
    }

    /**
     * Say by hand where a scanned code should go.
     *
     * The automatic rules refuse to guess wherever guessing could lose a
     * second factor, which is right and also leaves the person who *knows* the
     * answer with no way to say it. This is that way.
     *
     * It does not relax the guarantees, it satisfies them differently. Pointing
     * a code at a password takes that password away from any other row holding
     * it, because two rows writing one `otp` field would still lose one of
     * them — a person choosing the destination is choosing, not overriding the
     * arithmetic. Passing null sends it back to being a new password.
     */
    fun retargetScanned(key: String, itemId: String?) {
        val item = itemId?.let { _state.value.vault.items[it] }
        setScan { state ->
            state.copy(
                error = null,
                accounts = state.accounts.map { row ->
                    when {
                        row.key == key -> row.copy(
                            plan = row.plan.copy(
                                existingItemId = item?.id,
                                existingTitle = item?.field(Fields.TITLE),
                                existingUsername = item?.field(Fields.USERNAME),
                                reason = if (item == null) {
                                    PlanReason.NEW
                                } else {
                                    PlanReason.ONTO_EXISTING
                                },
                            ),
                        )

                        // Guarantee 1 still holds: nothing else may keep it.
                        item != null && row.plan.existingItemId == item.id -> row.copy(
                            plan = row.plan.copy(
                                existingItemId = null,
                                reason = PlanReason.NEW,
                            ),
                        )

                        else -> row
                    }
                },
            )
        }
    }

    fun toggleScanned(key: String) {
        setScan { state ->
            state.copy(
                accounts = state.accounts.map {
                    if (it.key == key) it.copy(selected = !it.selected) else it
                },
            )
        }
    }

    fun setAllScanned(selected: Boolean) {
        setScan { it.copy(accounts = it.accounts.map { row -> row.copy(selected = selected) }) }
    }

    /**
     * Put the ticked accounts on a keyring, and take them off the list.
     *
     * Taking them off is what makes "some here, some there" possible without a
     * dropdown on every row: tick a few, choose where they go, and what is
     * left on screen is what still needs a decision.
     *
     * [newKeyringName] makes one on the way, because "all of them on a keyring
     * of their own" is the common case and having to leave for another screen
     * to prepare for it is how somebody loses their place mid-export.
     */
    fun addScanned(keyringId: String?, newKeyringName: String? = null) {
        val engine = sync ?: return
        val state = _state.value.scan
        val chosen = state.accounts.filter { it.selected }
        if (chosen.isEmpty()) {
            setScan { it.copy(error = "Tick the accounts you want to add first.") }
            return
        }
        setScan { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            try {
                val ops = mutableListOf<VaultOp>()
                val ring = when {
                    !newKeyringName.isNullOrBlank() -> {
                        val existing = engine.state().keyrings.values.firstOrNull {
                            !it.deleted.value && it.name.value == newKeyringName.trim()
                        }
                        existing?.id ?: UUID.randomUUID().toString().also { id ->
                            val stamp = engine.stamp()
                            ops += VaultOp.KeyringPut(
                                stamp.opId,
                                stamp.ts,
                                id,
                                newKeyringName.trim(),
                            )
                        }
                    }

                    keyringId != null -> keyringId
                    else -> {
                        setScan { it.copy(busy = false, error = "Choose a keyring first.") }
                        return@launch
                    }
                }

                // Guarantee 1, restated where the writes are actually made:
                // two puts on one item's `otp` would resolve to the later one
                // and lose the other, so a plan that somehow contained a
                // duplicate must stop here rather than half-work.
                val targets = chosen.mapNotNull { it.plan.existingItemId }
                check(targets.size == targets.toSet().size) {
                    "Two codes were pointed at the same password."
                }

                for (pending in chosen) {
                    val stamp = engine.stamp()
                    val onto = pending.plan.existingItemId
                    if (onto != null) {
                        // Only the code. Nothing else about the password they
                        // already have is this QR code's business.
                        ops += VaultOp.ItemPut(
                            opId = stamp.opId,
                            ts = stamp.ts,
                            itemId = onto,
                            keyringId = _state.value.vault.items[onto]?.keyring?.value ?: ring,
                            fields = mapOf(Fields.OTP to pending.account.otp),
                        )
                    } else {
                        ops += VaultOp.ItemPut(
                            opId = stamp.opId,
                            ts = stamp.ts,
                            itemId = UUID.randomUUID().toString(),
                            keyringId = ring,
                            fields = buildMap {
                                put(
                                    Fields.TITLE,
                                    pending.account.issuer.ifEmpty {
                                        pending.account.name.ifEmpty { "Second-factor code" }
                                    },
                                )
                                if (pending.account.name.isNotEmpty()) {
                                    put(Fields.USERNAME, pending.account.name)
                                }
                                put(Fields.OTP, pending.account.otp)
                            },
                        )
                    }
                }

                val next = engine.commitAll(ops)
                publish(next)
                val ringName = next.keyrings[ring]?.name?.value ?: "your vault"
                setScan { current ->
                    current.copy(
                        accounts = current.accounts.filter { !it.selected },
                        busy = false,
                        addedTo = "${chosen.size} added to $ringName.",
                    )
                }
            } catch (cause: Exception) {
                setScan {
                    it.copy(
                        busy = false,
                        error = cause.message ?: "Keyweb couldn't add those.",
                    )
                }
            }
        }
    }

    // ---- Taking everything back out ------------------------------------

    /**
     * What an export would contain, so the screen can say it before writing.
     *
     * Counted rather than estimated, and counted from the same function that
     * builds the file — a warning derived separately from the thing it warns
     * about is a warning that goes stale.
     */
    fun exportSummary(): Pair<Int, CsvOmissions> {
        val exported = exportVault(_state.value.vault, java.time.Instant.now().toString())
        return exported.items.size to csvOmissions(exported)
    }

    /**
     * Write a plaintext copy of everything to a file the person chose.
     *
     * Unencrypted on purpose: an encrypted export that only Keyweb can open is
     * the thing being escaped from. The screen says so, and so does the file.
     */
    fun writeExport(uri: android.net.Uri, asCsv: Boolean) {
        viewModelScope.launch {
            try {
                val exported = withContext(Dispatchers.Default) {
                    exportVault(_state.value.vault, java.time.Instant.now().toString())
                }
                val text = withContext(Dispatchers.Default) {
                    if (asCsv) exportCsv(exported) else exportJson(exported)
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")
                        ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Keyweb couldn't write to that file.")
                }
                showToast(
                    "Saved ${exported.items.size} password" +
                        (if (exported.items.size == 1) "" else "s") +
                        ". Remember it isn't encrypted.",
                )
            } catch (cause: Exception) {
                showToast(cause.message ?: "Keyweb couldn't save that file.")
            }
        }
    }

    /**
     * Put this phone's vault back into Drive, over a backup it cannot read.
     *
     * Deliberately a button somebody presses, not something the sync does on
     * its own. The engine now refuses to publish over a backup it could not
     * open — that refusal is the whole safety property, and automating a way
     * around it would give it back with extra steps.
     *
     * What it writes is this phone's local vault, which is untouched: the
     * failure was reading the *backup*, not the vault. The passkey envelope,
     * if the file has one, is carried forward as always, because this device
     * cannot read it and therefore has no business deciding it is worthless.
     */
    fun replaceBackupFromThisPhone() {
        val engine = sync ?: return
        viewModelScope.launch {
            try {
                val local = engine.state()
                // No expected version: this is the deliberate overwrite, and
                // the thing it is overwriting is by definition not ours.
                remote.write(local, null)
                showToast("The backup in Drive is this phone's vault again.")
                syncNow()
            } catch (cause: Exception) {
                showToast(cause.message ?: "Keyweb couldn't rewrite the backup.")
            }
        }
    }

    /**
     * Read out what is in the backup file, and what this phone holds.
     *
     * A diagnostic that exists because the alternative was another round of
     * inferring the file's contents from two apps' behaviour. One line
     * somebody can read back settles which file each device is on, which
     * copies exist and when each was written.
     */
    fun describeBackupFile() {
        viewModelScope.launch {
            val secret = storedSecret()
            if (secret == null) {
                showToast("No backup set up on this phone.")
                return@launch
            }
            try {
                val remote = DriveVaultRemote(driveClient(), VaultEnvelopeCipher.forRecoveryCode(secret))
                val local = sync?.state()
                val items = local?.let { visibleItems(it).size } ?: 0
                showToast("$items here · " + remote.describe())
            } catch (cause: Exception) {
                showToast(cause.message ?: "Couldn't read the backup file.")
            }
        }
    }

    fun reportImportProblem(message: String) {
        setImport { it.copy(error = message, busy = false) }
    }

    /** Leaving the screen drops the file and the parsed contents from memory. */
    fun closeImport() {
        pendingBytes = null
        pendingFile = null
        _state.value = _state.value.copy(import = ImportUiState())
    }

    private fun describeBackup(cause: Exception): String = when (cause) {
        is InvalidRecoveryCode -> cause.message ?: "That code isn't right."
        is GoogleAuthorizer.NotSignedIn ->
            "Keyweb couldn't reach your Google account. Check you're signed in on this phone."
        is app.keyweb.vault.EnvelopeDecryptException ->
            "That code didn't open the backup. Check it and try again."
        else -> cause.message ?: "Keyweb couldn't set up backup just now."
    }

    private companion object {
        const val RECOVERY_SECRET_KEY = "recovery-secret"
        const val CHOSEN_BACKUP_KEY = "chosen-backup-file"
    }
}



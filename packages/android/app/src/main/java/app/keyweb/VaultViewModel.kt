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
import app.keyweb.vault.ItemField
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
import app.keyweb.vault.kdbx.KdbxEntry
import app.keyweb.vault.kdbx.KdbxFile
import app.keyweb.vault.kdbx.KdbxReader
import app.keyweb.vault.kdbx.suggestedKeyringName
import app.keyweb.vault.kdbx.WrongMasterPassword
import app.keyweb.vault.emptyVault
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
    val importedCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
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
    /** The invitation this phone was sent, while it is being decided on. */
    val invite: PendingShareInvite? = null,
    val accepted: AcceptedShare? = null,
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

                is UnlockResult.Unlocked -> openVault()
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
            if (current.keyrings.isEmpty()) {
                current = engine.putKeyring(keyringId = "personal", name = "Just mine")
            }
            publish(current, phase = VaultPhase.READY)
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

    fun saveItem(
        itemId: String?,
        keyringId: String,
        fields: Map<ItemField, String>,
        onDone: () -> Unit = {},
    ) {
        val engine = sync ?: return
        viewModelScope.launch {
            val next = engine.putItem(itemId = itemId, keyringId = keyringId, fields = fields)
            publish(next, toast = "Saved on this phone.")
            onDone()
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

    private fun driveClient() = DriveClient { authorizer.accessToken() }

    /** Turn the remote back on at unlock, silently, if it was set up before. */
    private suspend fun restoreBackupIfConfigured() {
        val secret = storedSecret() ?: return
        try {
            val client = driveClient()
            // Reuse the salt already in Drive, or the derived key will differ
            // from the one that sealed the backup and open nothing.
            val existing = DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(secret))
                .fetchRecoverySealed()
            remote.attach(
                DriveVaultRemote(client, VaultEnvelopeCipher.forRecoveryCode(secret, existing)),
            )
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
        remote.attach(
            DriveVaultRemote(driveClient(), VaultEnvelopeCipher.forRecoveryCode(secret, existing)),
        )
        // Publish immediately, so "backup is on" is true the moment it is said
        // rather than at some later sync.
        syncNow()
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
                        ?.let { me -> me.role == SharingRole.OWNER },
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
            acceptShareResponse(response)
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
                engine.myFingerprint()
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

                for (entry in file.entries) {
                    // An entry either names a group, which is mapped above, or
                    // names none and goes where the user said. There is no
                    // third case and deliberately no fallback: a missing
                    // mapping used to be absorbed silently, which is how
                    // entries ended up in folders nobody put them in.
                    val keyringId = entry.keyringName?.let(rings::get) ?: ungroupedId
                    check(keyringId.isNotEmpty()) {
                        "No keyring was prepared for \"${entry.keyringName}\"."
                    }
                    val stamp = engine.stamp()
                    ops += VaultOp.ItemPut(
                        opId = stamp.opId,
                        ts = stamp.ts,
                        itemId = "kdbx:${entry.uuid}",
                        keyringId = keyringId,
                        fields = entry.toFields(),
                    )
                }

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
    }
}

/**
 * A KeePass entry, as Keyweb's fields.
 *
 * Custom fields the database carried are kept rather than dropped: a field
 * Keyweb has no name for is still somebody's account number. Anything
 * sensitive-sounding gets the `secret:` prefix so it stays masked.
 */
private fun KdbxEntry.toFields(): Map<ItemField, String> = buildMap {
    if (title.isNotEmpty()) put(Fields.TITLE, title)
    if (username.isNotEmpty()) put(Fields.USERNAME, username)
    if (password.isNotEmpty()) put(Fields.PASSWORD, password)
    if (url.isNotEmpty()) put(Fields.URL, url)
    if (note.isNotEmpty()) put(Fields.NOTE, note)
    if (folder.isNotEmpty()) put(Fields.FOLDER, folder)
    if (tags.isNotEmpty()) put(Fields.TAGS, tags)
    for ((key, value) in extra) {
        if (value.isEmpty()) continue
        // KeePass stores a TOTP key under one of these names; recognising it
        // means the code shows up rather than sitting there as opaque text.
        val looksLikeOtp = key.equals("otp", true) || key.equals("TOTP Seed", true) ||
            key.startsWith("TOTP", true)
        put(if (looksLikeOtp) Fields.OTP else "secret:$key", value)
    }
}

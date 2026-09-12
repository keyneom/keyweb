package app.keyweb

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.fragment.app.FragmentActivity
import app.keyweb.data.DriveClient
import app.keyweb.data.DriveVaultRemote
import app.keyweb.data.GoogleAuthorizer
import app.keyweb.data.RoomVaultStorage
import app.keyweb.data.SwitchableRemote
import app.keyweb.data.UnlockResult
import app.keyweb.data.VaultDatabase
import app.keyweb.data.VaultKeystore
import app.keyweb.data.VaultUnlock
import app.keyweb.data.needsAuthentication
import app.keyweb.vault.Clock
import app.keyweb.vault.InvalidRecoveryCode
import app.keyweb.vault.ItemField
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.RecoveryCode
import app.keyweb.vault.SyncStatus
import app.keyweb.vault.VaultEnvelopeCipher
import app.keyweb.vault.VaultState
import app.keyweb.vault.VaultSync
import app.keyweb.vault.emptyVault
import app.keyweb.vault.visibleItems
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    /** True when this device has no vault yet, so unlocking means setting up. */
    val firstRun: Boolean = false,
    val error: String? = null,
    val vault: VaultState = emptyVault(),
    val status: SyncStatus = SyncStatus(),
    val items: List<ItemRecord> = emptyList(),
    val backupConfigured: Boolean = false,
    val backup: BackupUiState = BackupUiState(),
    val toast: String? = null,
)

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("keyweb", android.content.Context.MODE_PRIVATE)
    private val database = VaultDatabase.open(app)

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var sync: VaultSync? = null
    private var storage: RoomVaultStorage? = null

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
        _state.value = _state.value.copy(
            phase = VaultPhase.LOCKED,
            firstRun = !VaultKeystore.exists(),
        )
    }

    /**
     * Authenticate, then open the vault.
     *
     * Deliberately driven by a button rather than fired on launch: a biometric
     * sheet appearing before anyone asked for one reads as something going
     * wrong.
     */
    fun unlock(activity: FragmentActivity) {
        val firstRun = _state.value.firstRun
        _state.value = _state.value.copy(phase = VaultPhase.UNLOCKING, error = null)
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
            val engine = VaultSync(store, remote, clock)
            sync = engine

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

    fun syncNow() {
        val engine = sync ?: return
        if (!remote.configured) return
        viewModelScope.launch {
            engine.sync()
            _state.value = _state.value.copy(status = engine.status())
        }
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

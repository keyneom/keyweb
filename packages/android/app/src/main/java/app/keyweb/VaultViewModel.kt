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
import app.keyweb.data.needsAuthentication
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
                var current = engine.state()
                for (name in file.keyringNames) {
                    val existing = current.keyrings.values
                        .firstOrNull { !it.deleted.value && it.name.value == name }
                    rings[name] = existing?.id ?: UUID.randomUUID().toString().also { id ->
                        current = engine.putKeyring(keyringId = id, name = name)
                    }
                }
                // Where the entries in no group go. Resolved before any
                // entry is written, so a half-finished import cannot leave
                // them somewhere arbitrary.
                val ungroupedId = when {
                    file.ungrouped == 0 -> ""
                    choice is UngroupedDestination.Existing -> choice.keyringId
                    else -> {
                        val name = (choice as UngroupedDestination.New).name.trim()
                        val already = current.keyrings.values
                            .firstOrNull { !it.deleted.value && it.name.value == name }
                        already?.id ?: UUID.randomUUID().toString().also { id ->
                            current = engine.putKeyring(keyringId = id, name = name)
                        }
                    }
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
                    current = engine.putItem(
                        itemId = "kdbx:${entry.uuid}",
                        keyringId = keyringId,
                        fields = entry.toFields(),
                    )
                }
                pendingBytes = null
                pendingFile = null
                publish(current)
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

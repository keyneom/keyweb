package app.keyweb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.fragment.app.FragmentActivity
import app.keyweb.data.RoomVaultStorage
import app.keyweb.data.UnlockResult
import app.keyweb.data.VaultDatabase
import app.keyweb.data.VaultKeystore
import app.keyweb.data.VaultUnlock
import app.keyweb.data.needsAuthentication
import app.keyweb.vault.Clock
import app.keyweb.vault.ItemField
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.RemoteUnavailableException
import app.keyweb.vault.RemoteVaultStore
import app.keyweb.vault.SyncStatus
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
 * A remote that is not configured yet. Encrypted Drive backup needs a Google
 * OAuth client; until one is supplied the app is fully usable local-first and
 * says so plainly rather than pretending to back anything up.
 */
private class UnconfiguredRemote : RemoteVaultStore {
    override suspend fun read() = throw RemoteUnavailableException("Encrypted backup is not set up yet.")
    override suspend fun write(state: VaultState, expectedVersion: String?): String =
        throw RemoteUnavailableException("Encrypted backup is not set up yet.")
}

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
    val toast: String? = null,
)

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("keyweb", android.content.Context.MODE_PRIVATE)
    private val database = VaultDatabase.open(app)

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var sync: VaultSync? = null

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
            val storage = RoomVaultStorage(database.dao(), VaultKeystore.cipher())
            val clock = Clock(deviceNode(), resume = storage.readClock())
            val engine = VaultSync(storage, UnconfiguredRemote(), clock)
            sync = engine

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
}

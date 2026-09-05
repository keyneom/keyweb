package app.keyweb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keyweb.data.RoomVaultStorage
import app.keyweb.data.VaultDatabase
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

data class VaultUiState(
    val ready: Boolean = false,
    val vault: VaultState = emptyVault(),
    val status: SyncStatus = SyncStatus(),
    val items: List<ItemRecord> = emptyList(),
    val backupConfigured: Boolean = false,
    val toast: String? = null,
)

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("keyweb", android.content.Context.MODE_PRIVATE)
    private val storage = RoomVaultStorage(VaultDatabase.open(app).dao())

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var sync: VaultSync? = null

    /** Stable per-device id, so causal ordering survives a reinstall-free restart. */
    private fun deviceNode(): String =
        prefs.getString("device-node", null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("device-node", it).apply()
        }

    init {
        viewModelScope.launch {
            val clock = Clock(deviceNode(), resume = storage.readClock())
            val engine = VaultSync(storage, UnconfiguredRemote(), clock)
            sync = engine

            var current = engine.state()
            // First run: give people somewhere to put things rather than an
            // empty screen with no obvious next step.
            if (current.keyrings.isEmpty()) {
                current = engine.putKeyring(keyringId = "personal", name = "Just mine")
            }
            publish(current, ready = true)
        }
    }

    private fun publish(vault: VaultState, ready: Boolean = true, toast: String? = null) {
        _state.value = _state.value.copy(
            ready = ready,
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

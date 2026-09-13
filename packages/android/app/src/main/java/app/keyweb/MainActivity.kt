package app.keyweb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keyweb.ui.BackupScreen
import app.keyweb.ui.ImportScreen
import app.keyweb.ui.ItemDetailScreen
import app.keyweb.ui.ItemEditScreen
import app.keyweb.ui.KeyringsScreen
import app.keyweb.data.GrantBrowser
import app.keyweb.ui.KeywebTheme
import app.keyweb.ui.relativeTime
import app.keyweb.ui.SettingsScreen
import app.keyweb.ui.UnlockScreen
import app.keyweb.ui.VaultListScreen

private sealed interface Route {
    data object List : Route
    data class Detail(val itemId: String) : Route
    data class Edit(val itemId: String?) : Route
    data object Keyrings : Route
    data object Settings : Route
    data object Backup : Route
    data object Import : Route
}

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KeywebApp(viewModel, this) }
    }
}

@Composable
private fun KeywebApp(viewModel: VaultViewModel, activity: FragmentActivity) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("keyweb", Context.MODE_PRIVATE)
    }
    var largeText by rememberSaveable { mutableStateOf(prefs.getBoolean("large-text", false)) }
    var darkOverride by rememberSaveable {
        mutableStateOf(
            if (prefs.contains("dark")) prefs.getBoolean("dark", false) else null,
        )
    }

    KeywebTheme(
        dark = darkOverride ?: isSystemInDarkTheme(),
        largeText = largeText,
    ) {
        val ui by viewModel.state.collectAsStateWithLifecycle()
        var route by rememberSaveable(
            stateSaver = androidx.compose.runtime.saveable.Saver(
                save = {
                    when (it) {
                        is Route.List -> "list"
                        is Route.Detail -> "detail:${it.itemId}"
                        is Route.Edit -> "edit:${it.itemId.orEmpty()}"
                        is Route.Keyrings -> "keyrings"
                        is Route.Settings -> "settings"
                        is Route.Backup -> "backup"
                        is Route.Import -> "import"
                    }
                },
                restore = {
                    when {
                        it == "keyrings" -> Route.Keyrings
                        it == "settings" -> Route.Settings
                        it == "backup" -> Route.Backup
                        it == "import" -> Route.Import
                        it.startsWith("detail:") -> Route.Detail(it.removePrefix("detail:"))
                        it.startsWith("edit:") ->
                            Route.Edit(it.removePrefix("edit:").ifEmpty { null })
                        else -> Route.List
                    }
                },
            ),
        ) { mutableStateOf<Route>(Route.List) }

        // Google's consent screen arrives as an IntentSender the ViewModel
        // cannot launch itself. Whatever it returns -- approved, declined, or
        // dismissed -- setup simply tries again and reports what it finds.
        val consent by viewModel.consent.collectAsStateWithLifecycle()
        val consentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { viewModel.consentHandled() }
        LaunchedEffect(consent) {
            consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }

        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(ui.toast) {
            ui.toast?.let {
                snackbar.showSnackbar(it)
                viewModel.clearToast()
            }
        }

        fun copy(value: String, label: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, value).apply {
                // Keep the value out of clipboard previews and history where the
                // platform supports it: a password on a shared screen is a leak.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
            }
            clipboard.setPrimaryClip(clip)
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (ui.phase != VaultPhase.READY) {
                    UnlockScreen(
                        phase = ui.phase,
                        firstRun = ui.firstRun,
                        error = ui.error,
                        onUnlock = { viewModel.unlock(activity) },
                    )
                    return@Box
                }

                val firstKeyring = ui.vault.keyrings.keys.firstOrNull() ?: "personal"

                /**
                 * One definition of "back", used by both the arrow and the
                 * system gesture.
                 *
                 * They were not the same thing before: the arrow moved the
                 * route, the gesture fell through to the activity and closed
                 * the app mid-task. Anything that is not the list goes back to
                 * where it was opened from; on the list itself, back is left
                 * alone so it still leaves the app.
                 */
                fun goBack() {
                    route = when (val current = route) {
                        is Route.Edit ->
                            current.itemId?.let { Route.Detail(it) } ?: Route.List
                        else -> Route.List
                    }
                }
                BackHandler(enabled = route != Route.List) { goBack() }

                when (val current = route) {
                    is Route.List -> VaultListScreen(
                        state = ui.vault,
                        items = ui.items,
                        status = ui.status,
                        backupConfigured = ui.backupConfigured,
                        onOpen = { route = Route.Detail(it) },
                        onAdd = { route = Route.Edit(null) },
                        onKeyrings = { route = Route.Keyrings },
                        onSettings = { route = Route.Settings },
                        onSetUpBackup = { route = Route.Backup },
                    )

                    is Route.Detail -> {
                        val item = ui.vault.items[current.itemId]
                        if (item == null) {
                            route = Route.List
                        } else {
                            ItemDetailScreen(
                                item = item,
                                state = ui.vault,
                                onBack = ::goBack,
                                onEdit = { route = Route.Edit(item.id) },
                                onDelete = {
                                    viewModel.deleteItem(item.id) { route = Route.List }
                                },
                                onCopy = ::copy,
                            )
                        }
                    }

                    is Route.Edit -> ItemEditScreen(
                        item = current.itemId?.let { ui.vault.items[it] },
                        state = ui.vault,
                        defaultKeyringId = firstKeyring,
                        savedRules = ui.savedRules,
                        lastRules = ui.lastRules,
                        onBack = ::goBack,
                        onSave = { itemId, keyringId, fields ->
                            viewModel.saveItem(itemId, keyringId, fields) { route = Route.List }
                        },
                        onSaveRules = viewModel::saveRules,
                        onRulesUsed = viewModel::rememberLastRules,
                    )

                    is Route.Keyrings -> KeyringsScreen(
                        state = ui.vault,
                        items = ui.items,
                        onBack = ::goBack,
                        onAdd = viewModel::addKeyring,
                    )

                    is Route.Settings -> SettingsScreen(
                        largeText = largeText,
                        darkMode = darkOverride,
                        onLargeText = {
                            largeText = it
                            prefs.edit().putBoolean("large-text", it).apply()
                        },
                        onDarkMode = {
                            darkOverride = it
                            prefs.edit().apply {
                                if (it == null) remove("dark") else putBoolean("dark", it)
                            }.apply()
                        },
                        onBack = ::goBack,
                        onBackup = { route = Route.Backup },
                        onImport = {
                            viewModel.refreshImportFiles()
                            route = Route.Import
                        },
                    )

                    is Route.Import -> ImportScreen(
                        state = ui.import,
                        onBack = {
                            viewModel.closeImport()
                            goBack()
                        },
                        onRefresh = viewModel::refreshImportFiles,
                        onGrantAccess = {
                            if (!GrantBrowser.open(activity)) {
                                viewModel.reportImportProblem(
                                    "Keyweb couldn't find a browser to open. " +
                                        "Open ${GrantBrowser.GRANT_URL} yourself and pick the " +
                                        "file there.",
                                )
                            }
                        },
                        onOpen = viewModel::openImportFile,
                        onUnlock = viewModel::unlockImportFile,
                        onConfirm = viewModel::confirmImport,
                    )

                    is Route.Backup -> BackupScreen(
                        backup = ui.backup,
                        lastBackedUp = ui.status.lastPublishedAtMs?.let(::relativeTime),
                        onBack = {
                            viewModel.dismissBackupError()
                            goBack()
                        },
                        onStart = viewModel::setUpBackup,
                        onUseExistingCode = viewModel::useExistingCode,
                        onCodeWrittenDown = {
                            viewModel.codeWrittenDown()
                            route = Route.List
                        },
                    )
                }
            }
        }
    }
}

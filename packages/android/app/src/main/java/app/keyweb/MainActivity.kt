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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.keyweb.ui.AcceptShareScreen
import app.keyweb.ui.JoinShareScreen
import app.keyweb.ui.KeyringsScreen
import app.keyweb.ui.ShareKeyringScreen
import app.keyweb.data.SecretClipboard
import app.keyweb.data.GrantBrowser
import app.keyweb.ui.KeywebTheme
import app.keyweb.ui.relativeTime
import app.keyweb.ui.SettingsScreen
import app.keyweb.ui.UnlockScreen
import app.keyweb.ui.VaultListScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private sealed interface Route {
    data object List : Route
    data class Detail(val itemId: String) : Route
    data class Edit(val itemId: String?) : Route
    data object Keyrings : Route
    data object Settings : Route
    data object Backup : Route
    data object Import : Route
    data class Share(val keyringId: String) : Route
}

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KeywebApp(viewModel, this) }
        viewModel.openShareLink(intent?.data?.toString())
    }

    /**
     * A link arriving while the app is already open.
     *
     * `singleTask` would be wrong here — it would tear down and rebuild the
     * whole screen for a link — so the activity stays as it is and the intent
     * is handed straight to the view model. Returning from the browser after a
     * file grant comes back this way too.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.openShareLink(intent.data?.toString())
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
                        is Route.Share -> "share:${it.keyringId}"
                    }
                },
                restore = {
                    when {
                        it == "keyrings" -> Route.Keyrings
                        it == "settings" -> Route.Settings
                        it == "backup" -> Route.Backup
                        it == "import" -> Route.Import
                        it.startsWith("share:") -> Route.Share(it.removePrefix("share:"))
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

        val clipboardScope = rememberCoroutineScope()

        fun copy(value: String, label: String) {
            SecretClipboard.copy(context, value, label)
            if (value.isEmpty()) return
            clipboardScope.launch {
                delay(SecretClipboard.CLEAR_AFTER_MS)
                // Only if it is still ours: someone who copied something else
                // in the meantime must not have it wiped by a timer they know
                // nothing about.
                SecretClipboard.clearIfStill(context, value)
            }
            viewModel.showToast("$label copied. It clears in a minute.")
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (ui.phase != VaultPhase.READY) {
                    // Opening the app is the request; this saves asking twice.
                    // Keyed on the flag, which the ViewModel clears as it acts,
                    // so a recomposition cannot raise a second sheet.
                    LaunchedEffect(ui.promptOnEntry) {
                        if (ui.promptOnEntry) viewModel.unlock(activity)
                    }
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

                // A link somebody sent takes over the screen. They opened it to
                // finish something, not to browse their passwords, and leaving
                // it behind a route would mean finding it again afterwards.
                val invite = ui.share.invite
                if (invite != null) {
                    JoinShareScreen(
                        invite = invite,
                        share = ui.share,
                        onContinue = { viewModel.beginShareGrant(activity) },
                        onFinish = viewModel::finishShareJoin,
                        onCopy = { copy(it, "The reply") },
                        onDone = {
                            viewModel.closeSharing()
                            route = Route.List
                        },
                    )
                    return@Box
                }
                if (ui.share.stage == ShareStage.CONFIRM_ACCEPT ||
                    ui.share.stage == ShareStage.ACCEPTING ||
                    ui.share.stage == ShareStage.ACCEPTED
                ) {
                    AcceptShareScreen(
                        share = ui.share,
                        onConfirm = viewModel::retryAccept,
                        onRetry = viewModel::retryAccept,
                        onDone = {
                            viewModel.closeSharing()
                            route = Route.List
                        },
                    )
                    return@Box
                }

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
                        onSync = viewModel::syncNow,
                        onDeleteMany = viewModel::deleteItems,
                        onMoveMany = viewModel::moveItems,
                        currentVersion = BuildConfig.VERSION_NAME,
                        // Through GrantBrowser, which knows the things that
                        // make an https intent fail silently on Android — the
                        // same ones that had the Picker handoff reporting no
                        // browser on a phone with three of them.
                        onOpenLink = { url ->
                            GrantBrowser.copyLink(activity, url)
                            if (!GrantBrowser.open(activity, url)) {
                                // On the screen the person is actually looking
                                // at. The import screen's error channel would
                                // have filed this where nobody would see it,
                                // then surprised them with it days later.
                                viewModel.showToast(
                                    "Keyweb couldn't open a browser. The link is copied — " +
                                        "paste it into any browser.",
                                )
                            }
                        },
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
                        readOnlyKeyrings = ui.readOnlyKeyrings,
                    )

                    is Route.Keyrings -> KeyringsScreen(
                        state = ui.vault,
                        items = ui.items,
                        onBack = ::goBack,
                        onAdd = viewModel::addKeyring,
                        onDelete = viewModel::deleteKeyring,
                        canShare = viewModel.canShare,
                        onShare = {
                            viewModel.openSharing(it)
                            route = Route.Share(it)
                        },
                        onPasteLink = {
                            if (!viewModel.openShareLink(it)) {
                                viewModel.showToast(
                                    "That doesn't look like a Keyweb sharing link.",
                                )
                            }
                        },
                    )

                    is Route.Share -> ShareKeyringScreen(
                        keyringName = ui.vault.keyrings[current.keyringId]?.name?.value
                            ?: "This keyring",
                        share = ui.share,
                        onBack = {
                            viewModel.closeSharing()
                            route = Route.Keyrings
                        },
                        onInvite = { email, role ->
                            viewModel.shareKeyring(current.keyringId, email, role)
                        },
                        onCancelInvite = viewModel::cancelInvite,
                        onRevoke = viewModel::revokeShare,
                        onStopSharing = {
                            viewModel.stopSharing(current.keyringId)
                            route = Route.Keyrings
                        },
                        onLeave = {
                            viewModel.leaveKeyring(current.keyringId)
                            route = Route.Keyrings
                        },
                        onCopy = { copy(it, "The link") },
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
                        canShare = viewModel.canShare,
                        sharingKey = ui.sharingKey,
                        onShowSharingKey = viewModel::showSharingKey,
                        onBackup = { route = Route.Backup },
                        onImport = {
                            // Quietly: if Drive access already exists the list
                            // fills in, and if it does not, nothing interrupts.
                            viewModel.refreshImportFiles(interactive = false)
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
                            // Copied first, so the recovery is already in hand
                            // if opening a browser turns out to be impossible.
                            GrantBrowser.copyLink(activity)
                            if (!GrantBrowser.open(activity)) {
                                viewModel.reportImportProblem(
                                    "Keyweb couldn't open a browser on this phone. The link " +
                                        "has been copied for you — paste it into any browser, " +
                                        "sign in with this same Google account, pick your file, " +
                                        "then come back and tap \"I've picked it\".",
                                )
                            }
                        },
                        onOpen = viewModel::openImportFile,
                        onLocalFile = viewModel::openLocalFile,
                        onUnlock = viewModel::unlockImportFile,
                        onUngroupedDestination = viewModel::setUngroupedDestination,
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

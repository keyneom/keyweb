package app.keyweb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import app.keyweb.ui.ScanScreen
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import app.keyweb.vault.liveKeyrings
import app.keyweb.vault.ItemSort
import app.keyweb.vault.KeyringSort
import app.keyweb.ui.ItemEditScreen
import app.keyweb.ui.AcceptShareScreen
import app.keyweb.ui.FileViewerScreen
import app.keyweb.vault.field
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
    data object Scan : Route
    data class Share(val keyringId: String) : Route
    data class File(val itemId: String, val blobId: String) : Route
}

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        /*
         * No screenshots, no recents thumbnail, no screen recording, and no
         * casting of this window.
         *
         * Set for the whole activity rather than only for the screens showing
         * a password, because the list already shows which accounts somebody
         * has and the recents thumbnail is taken by the system at a moment
         * this app does not choose. A flag that is on sometimes is a flag that
         * is off at the moment it mattered.
         *
         * The cost is real and accepted: screen sharing Keyweb to show
         * somebody how it works now shows them a black rectangle. That is the
         * correct trade for a window with passwords in it, and it is what
         * every other password manager does.
         */
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { KeywebApp(viewModel, this) }
        viewModel.openShareLink(intent?.data?.toString())
    }

    /**
     * A link arriving while the app is already open.
     *
     * This is where `singleTask` in the manifest earns its place. Without it
     * the activity is `standard`, so tapping a link while Keyweb is running
     * builds a *second* copy of the whole app on top of the first: a second
     * view model, locked, asking for a fingerprint again, with the screen
     * somebody was already looking at stranded underneath. The link did get
     * read — by an instance nobody could tell apart from the one they had —
     * and it looked exactly like being ignored.
     *
     * With one instance, the intent arrives here and goes straight to the view
     * model that is already unlocked. Returning from the browser after a file
     * grant comes back this way too.
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
    // Remembered rather than reset on every launch: somebody who prefers their
    // keyrings biggest-first means it every time, not once.
    var itemSort by rememberSaveable {
        mutableStateOf(prefs.getString("item-sort", null) ?: ItemSort.NAME_AZ.id)
    }
    var keyringSort by rememberSaveable {
        mutableStateOf(prefs.getString("keyring-sort", null) ?: KeyringSort.NAME_AZ.id)
    }
    var browseFlat by rememberSaveable { mutableStateOf(prefs.getBoolean("browse-flat", false)) }

    /*
     * Reading a QR code without asking for the camera.
     *
     * Play Services scans in its own process and hands back only the text, so
     * Keyweb never holds a camera permission. A password manager asking for
     * camera access is a thing people are right to hesitate over, and not
     * asking is a better answer than explaining.
     *
     * The module is fetched on demand, so the first scan on a phone that has
     * never done one can fail while it downloads. That is said in the same
     * words as any other failure rather than as an error code.
     */
    fun scanCode() {
        val scanner = GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val text = barcode.rawValue
                if (text.isNullOrEmpty()) {
                    viewModel.reportScanProblem("That square didn't have anything readable in it.")
                } else {
                    viewModel.onCodeScanned(text)
                }
            }
            .addOnCanceledListener { }
            .addOnFailureListener {
                viewModel.reportScanProblem(
                    "Keyweb couldn't open the scanner. If this is the first time, your phone " +
                        "may still be downloading it — try again in a moment.",
                )
            }
    }
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
                        is Route.Scan -> "scan"
                        is Route.Share -> "share:${it.keyringId}"
                        is Route.File -> "file:${it.itemId}:${it.blobId}"
                    }
                },
                restore = {
                    when {
                        it == "keyrings" -> Route.Keyrings
                        it == "settings" -> Route.Settings
                        it == "backup" -> Route.Backup
                        it == "import" -> Route.Import
                        it == "scan" -> Route.Scan
                        it.startsWith("share:") -> Route.Share(it.removePrefix("share:"))
                        it.startsWith("file:") -> {
                            val rest = it.removePrefix("file:")
                            Route.File(rest.substringBefore(':'), rest.substringAfter(':'))
                        }
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

        /*
         * Which password a picked file belongs to, and which file is being
         * saved out. Held beside the launchers because the result arrives
         * later, from the system, with nothing but a Uri attached.
         */
        var attaching by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf<String?>(null) }
        val pickFile = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            val itemId = attaching
            attaching = null
            if (uri != null && itemId != null) viewModel.attachFile(itemId, uri)
        }
        val saveFile = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            val blobId = saving
            saving = null
            if (uri != null && blobId != null) viewModel.saveAttachment(blobId, uri)
        }

        /*
         * A separate launcher from the attachment one, with its own MIME type,
         * so the system's file chooser offers the right name and the right
         * apps. Sharing one launcher would mean a `.csv` offered as
         * `application/octet-stream`, which several file apps then refuse to
         * open afterwards.
         */
        var exportingCsv by rememberSaveable { mutableStateOf(false) }
        val exportFile = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("*/*"),
        ) { uri ->
            if (uri != null) viewModel.writeExport(uri, exportingCsv)
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

                /*
                 * A keyring that is really there, not merely the first key in
                 * the map. `firstOrNull()` could be a keyring deleted on
                 * another device, and `?: "personal"` invented an id for a
                 * keyring this vault might never have had. Either one became
                 * the default on the add screen, and a password saved against
                 * it used to disappear from every screen on both platforms.
                 * Empty is honest when there is nothing; the save path makes a
                 * keyring rather than writing to a name nobody chose.
                 */
                val firstKeyring = liveKeyrings(ui.vault).firstOrNull()?.id ?: ""

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
                        onReplaceBackup = viewModel::replaceBackupFromThisPhone,
                        onEnterBackupCode = {
                            viewModel.askForBackupCode()
                            route = Route.Backup
                        },
                        backupPhoneOnly = ui.backupPhoneOnly,
                        onAddPasskey = viewModel::addPasskeyToBackup,
                        backupFiles = ui.backupFiles,
                        onChooseBackupFile = viewModel::chooseBackupFile,
                        onDeleteMany = viewModel::deleteItems,
                        onMoveMany = viewModel::moveItems,
                        currentVersion = BuildConfig.VERSION_NAME,
                        savedSort = itemSort,
                        onSortChanged = {
                            itemSort = it
                            prefs.edit().putString("item-sort", it).apply()
                        },
                        savedFlat = browseFlat,
                        onFlatChanged = {
                            browseFlat = it
                            prefs.edit().putBoolean("browse-flat", it).apply()
                        },
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
                                onOpenFile = { route = Route.File(item.id, it) },
                                onAttachFile = { attaching = item.id; pickFile.launch("*/*") },
                                onRemoveFile = { viewModel.removeAttachment(item.id, it) },
                                onRestore = { field, value ->
                                    viewModel.restoreValue(item.id, field, value)
                                },
                            )
                        }
                    }

                    is Route.File -> {
                        val blob = ui.vault.items[current.blobId]
                        FileViewerScreen(
                            name = blob?.field("name") ?: "File",
                            type = blob?.field("type"),
                            data = blob?.field("secret:data"),
                            onBack = { route = Route.Detail(current.itemId) },
                            onSave = {
                                saving = current.blobId
                                saveFile.launch(blob?.field("name") ?: "file")
                            },
                        )
                    }

                    is Route.Edit -> ItemEditScreen(
                        item = current.itemId?.let { ui.vault.items[it] },
                        state = ui.vault,
                        defaultKeyringId = firstKeyring,
                        savedRules = ui.savedRules,
                        lastRules = ui.lastRules,
                        onBack = ::goBack,
                        onSave = { itemId, keyringId, fields ->
                            /*
                             * To the password, not back to the list.
                             *
                             * The list is filtered and folded: it can be
                             * scoped to one keyring, sitting inside a folder,
                             * or showing folders at the top rather than
                             * passwords. Returning to it after a save meant
                             * the thing somebody had just written was
                             * routinely not on the screen they were returned
                             * to, which is indistinguishable from it not
                             * having been saved.
                             */
                            viewModel.saveItem(itemId, keyringId, fields) { saved ->
                                route = Route.Detail(saved)
                            }
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
                        savedSort = keyringSort,
                        onSortChanged = {
                            keyringSort = it
                            prefs.edit().putString("keyring-sort", it).apply()
                        },
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
                        onHandOver = { viewModel.proposeOwnership(it.keyId, it.email) },
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
                        onScanCodes = { route = Route.Scan },
                        onLock = {
                            viewModel.lock()
                            route = Route.List
                        },
                        onDescribeBackup = viewModel::describeBackupFile,
                        exportSummary = viewModel.exportSummary(),
                        onExport = { csv ->
                            exportingCsv = csv
                            val stamp = java.time.LocalDate.now()
                            exportFile.launch(
                                if (csv) "keyweb-$stamp.csv" else "keyweb-$stamp.json",
                            )
                        },
                        onImport = {
                            // Quietly: if Drive access already exists the list
                            // fills in, and if it does not, nothing interrupts.
                            viewModel.refreshImportFiles(interactive = false)
                            route = Route.Import
                        },
                    )

                    is Route.Scan -> ScanScreen(
                        scan = ui.scan,
                        state = ui.vault,
                        onBack = {
                            viewModel.closeScan()
                            goBack()
                        },
                        onScan = { scanCode() },
                        onToggle = viewModel::toggleScanned,
                        onSelectAll = viewModel::setAllScanned,
                        onAdd = viewModel::addScanned,
                        onRetarget = viewModel::retargetScanned,
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

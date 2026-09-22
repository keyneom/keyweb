package app.keyweb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.keyweb.vault.Fields
import app.keyweb.vault.PasswordRules
import app.keyweb.vault.SavedRules
import app.keyweb.vault.ItemField
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.KeyringRecord
import app.keyweb.vault.SyncStatus
import app.keyweb.vault.Totp
import app.keyweb.vault.VaultState
import app.keyweb.vault.datasetOf
import app.keyweb.vault.field
import app.keyweb.BackupFileChoice
import app.keyweb.vault.CsvOmissions
import app.keyweb.vault.FolderKind
import app.keyweb.vault.FolderView
import app.keyweb.vault.browseFolders
import app.keyweb.vault.ItemSort
import app.keyweb.vault.KeyringSort
import app.keyweb.vault.PastValue
import app.keyweb.vault.fieldLabel
import app.keyweb.vault.folderPath
import app.keyweb.vault.NO_KEYRING
import app.keyweb.vault.itemsWithoutKeyring
import app.keyweb.vault.keyringLabel
import app.keyweb.vault.liveKeyrings
import app.keyweb.vault.pastValues
import app.keyweb.vault.sortItems
import app.keyweb.vault.sortKeyrings
import app.keyweb.vault.storedFieldName

private val RING_COLORS = listOf(
    Color(0xFF2B7A6B),
    Color(0xFF7A5AA8),
    Color(0xFFA9632F),
    Color(0xFF3F6DA8),
    Color(0xFF8A4F6B),
    Color(0xFF5B6672),
)

fun ringColor(state: VaultState, keyringId: String): Color {
    val index = state.keyrings.keys.sorted().indexOf(keyringId)
    return if (index < 0) RING_COLORS.last() else RING_COLORS[index % RING_COLORS.size]
}

private fun initials(title: String): String =
    title.trim().take(2).uppercase().ifEmpty { "?" }

/**
 * Chip colours pinned to the Keyweb palette. Material's default selected
 * container is a purple that fights Brass & Slate, and a selected keyring must
 * read as the same steel as every other selected control.
 */
@Composable
internal fun keywebChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    state: VaultState,
    items: List<ItemRecord>,
    status: SyncStatus,
    backupConfigured: Boolean,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onKeyrings: () -> Unit,
    onSettings: () -> Unit,
    onSetUpBackup: () -> Unit,
    onSync: () -> Unit,
    onReplaceBackup: () -> Unit = {},
    onEnterBackupCode: () -> Unit = {},
    /** The backup has no passkey copy, so it opens on this phone alone. */
    backupPhoneOnly: Boolean = false,
    onAddPasskey: () -> Unit = {},
    /** Vault files to choose between, when the account holds more than one. */
    backupFiles: List<BackupFileChoice> = emptyList(),
    onChooseBackupFile: (String) -> Unit = {},
    onDeleteMany: (List<String>) -> Unit,
    onMoveMany: (List<String>, String) -> Unit,
    /** This build's version, so the update notice knows what to compare. */
    currentVersion: String,
    /** Opens a link in a browser. Routed through the caller, which has the Activity. */
    onOpenLink: (String) -> Unit,
    /** The ordering last chosen, remembered across launches. */
    savedSort: String = ItemSort.NAME_AZ.id,
    onSortChanged: (String) -> Unit = {},
    /** Whether the list was last left showing everything rather than folders. */
    savedFlat: Boolean = false,
    onFlatChanged: (Boolean) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var ring by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(savedSort) }
    /**
     * Where in the folder tree the list is looking.
     *
     * Saved as a joined string rather than a list because `rememberSaveable`
     * takes a Bundle, and this is the one place the separator has to be
     * reassembled by hand.
     */
    var folderPath by rememberSaveable { mutableStateOf("") }
    /**
     * Browsing the tree, or looking at every password at once.
     *
     * Two genuinely different questions — "what is in here" and "where is this
     * one thing" — and a list that only answers the first makes the second
     * take a walk through folders somebody did not build. Both keep the
     * keyring chips and the ordering, so a filter is a filter either way.
     */
    var flat by rememberSaveable { mutableStateOf(savedFlat) }
    val folder = folderPath.split('\u0000').filter { it.isNotEmpty() }
    val statusColors = LocalKeywebStatus.current

    /**
     * Selection mode.
     *
     * Entered by holding a row rather than by a switch in the corner, because
     * the thing being selected is what the finger is already on. Null means
     * not selecting, which differs from selecting nothing: an empty selection
     * keeps the bar, so clearing the last row does not throw you out mid-task.
     */
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val selecting = selected != null

    fun exitSelection() {
        selected = null
        confirming = false
        moving = false
    }

    // The system back gesture should leave selection before leaving the
    // screen; escaping a mode is what a person means by "back" while in one.
    BackHandler(enabled = selecting) { exitSelection() }

    val rings = state.keyrings.values.filter { !it.deleted.value }.sortedBy { it.name.value }
    val shown = items
        .filter { ring == null || it.keyring.value == ring }
        .filter { item ->
            val needle = query.trim().lowercase()
            needle.isEmpty() || listOfNotNull(
                item.field(Fields.TITLE),
                item.field(Fields.USERNAME),
                item.field(Fields.URL),
            ).joinToString(" ").lowercase().contains(needle)
        }
        .let { sortItems(it, ItemSort.of(sort)) }

    /*
     * Searching looks everywhere, on purpose.
     *
     * Somebody who types a name is asking "where is this", and answering only
     * from the folder they happen to be standing in is how a search reports
     * that a password they can see in the list does not exist.
     */
    val searching = query.isNotBlank()
    val view = if (searching || flat) {
        FolderView(emptyList(), shown)
    } else {
        browseFolders(shown, state, folder, ring)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val chosen = selected
                if (chosen != null) {
                    TextButton(onClick = { exitSelection() }) { Text("Done") }
                    Spacer(Modifier.weight(1f))
                    Text("${chosen.size} selected", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        // What is on screen, not everything the filters let
                        // through. Inside a folder those differ, and "select
                        // all" meaning "also the ones you cannot see" is how
                        // somebody deletes a keyring by mistake.
                        selected = if (chosen.size == view.items.size) {
                            emptySet()
                        } else {
                            view.items.map { it.id }.toSet()
                        }
                    }) {
                        Text(if (chosen.size == view.items.size) "Clear" else "Select all")
                    }
                } else {
                    Text(
                        "Keyweb",
                        style = MaterialTheme.typography.titleLarge,
                        color = statusColors.brass,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onKeyrings) { Text("Keyrings") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search for a website or app") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = ring == null,
                    onClick = {
                        ring = null
                        folderPath = ""
                    },
                    colors = keywebChipColors(),
                    label = { Text("All ${items.size}") },
                )
                rings.forEach { r ->
                    val count = items.count { it.keyring.value == r.id }
                    FilterChip(
                        selected = ring == r.id,
                        onClick = {
                            ring = if (ring == r.id) null else r.id
                            // The path means nothing in another keyring, and
                            // keeping it would land somebody in a folder that
                            // is empty because it is somebody else's.
                            folderPath = ""
                        },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(ringColor(state, r.id), RoundedCornerShape(3.dp)),
                            )
                        },
                        colors = keywebChipColors(),
                        label = { Text("${r.name.value} $count") },
                    )
                }
            }

            /*
             * Passwords with no keyring, and the offer to fix it.
             *
             * These used to be filtered out of every list on both platforms,
             * so a password saved against a keyring that had been deleted — or
             * one that had simply not arrived on this device yet — was in the
             * vault, in the backup and on the other device, and on no screen
             * anywhere. Now they are in the list like anything else, and this
             * says out loud that they want a home, because a row that reads
             * "Not in a keyring" with no way to act on it is only half an
             * answer.
             */
            val homeless = itemsWithoutKeyring(state)
            if (homeless.isNotEmpty() && !selecting) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = statusColors.attention.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (homeless.size == 1) {
                                "1 password is not in a keyring."
                            } else {
                                "${homeless.size} passwords are not in a keyring."
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (homeless.size == 1) {
                                "It is safe and backed up — the keyring it was saved to is " +
                                    "gone. Put it somewhere you will find it again."
                            } else {
                                "They are safe and backed up — the keyring they were saved " +
                                    "to is gone. Put them somewhere you will find them again."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColors.muted,
                        )
                        TextButton(onClick = {
                            selected = homeless.map { it.id }.toSet()
                            moving = true
                        }) {
                            Text(
                                if (homeless.size == 1) {
                                    "Put it in a keyring"
                                } else {
                                    "Put them in a keyring"
                                },
                            )
                        }
                    }
                }
            }

            // Under the filters rather than beside the search box: filtering
            // narrows what is in the list and ordering decides where in it to
            // look, and reading them in that order matches doing them in it.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortPicker(
                    current = sort,
                    options = ItemSort.entries.map { it.id to it.label },
                    onPick = {
                        sort = it
                        onSortChanged(it)
                    },
                )
                Spacer(Modifier.weight(1f))
                // Two chips rather than one button whose label is its state:
                // "In folders" on a button could mean "you are" or "make it
                // so", and there is no way to tell from looking.
                FilterChip(
                    selected = !flat,
                    onClick = {
                        flat = false
                        onFlatChanged(false)
                    },
                    colors = keywebChipColors(),
                    label = { Text("In folders") },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = flat,
                    onClick = {
                        flat = true
                        folderPath = ""
                        onFlatChanged(true)
                    },
                    colors = keywebChipColors(),
                    label = { Text("Everything") },
                )
            }

            // Above the backup notice: a stale build is the more urgent of the
            // two, since it can be the reason the rest is misbehaving.
            UpdateNotice(
                currentVersion = currentVersion,
                onGet = onOpenLink,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            BackupStatusLine(
                status,
                backupConfigured,
                Modifier.padding(bottom = 10.dp),
                onSetUpBackup = onSetUpBackup,
                onSync = onSync,
                onReplaceBackup = onReplaceBackup,
                onEnterBackupCode = onEnterBackupCode,
                phoneOnly = backupPhoneOnly,
                onAddPasskey = onAddPasskey,
                backupFiles = backupFiles,
                onChooseBackupFile = onChooseBackupFile,
            )

            /*
             * Where you are, and the way back up.
             *
             * A breadcrumb rather than a back arrow, because the levels above
             * are each one tap away — going from "Banks / Cards" to the top of
             * a keyring should not be two gestures and a guess about how deep
             * you were.
             */
            if (!searching && !flat && folder.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { folderPath = "" }) { Text("All") }
                    folder.forEachIndexed { index, name ->
                        Text("/", color = statusColors.muted)
                        TextButton(
                            onClick = {
                                folderPath = folder.take(index + 1).joinToString("\u0000")
                            },
                        ) { Text(name) }
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                if (view.folders.isEmpty() && view.items.isEmpty()) {
                    Text(
                        when {
                            items.isEmpty() ->
                                "No passwords saved yet. Add your first one below."
                            searching -> "Nothing matches that search."
                            folder.isNotEmpty() -> "This folder is empty."
                            else -> "Nothing here."
                        },
                        color = statusColors.muted,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        LazyColumn {
                            // Folders first, because a folder is a place and
                            // the things in this one are its contents.
                            items(view.folders, key = { "folder:" + it.path.joinToString("/") }) { child ->
                                // A keyring is not a folder somebody made, so
                                // it keeps its own colour and its own word
                                // rather than wearing a folder icon that
                                // implies it could be renamed or nested.
                                val isKeyring = child.kind == FolderKind.KEYRING
                                val ringId = if (isKeyring) {
                                    state.keyrings.values
                                        .firstOrNull { !it.deleted.value && it.name.value == child.name }
                                        ?.id
                                } else {
                                    null
                                }
                                VaultRow(
                                    initials = if (isKeyring) "\u25CF" else "\uD83D\uDCC1",
                                    title = child.name,
                                    subtitle = (if (isKeyring) "Keyring · " else "") +
                                        "${child.count} password" +
                                        if (child.count == 1) "" else "s",
                                    onClick = {
                                        folderPath = child.path.joinToString("\u0000")
                                    },
                                    accent = if (ringId != null) {
                                        ringColor(state, ringId)
                                    } else {
                                        statusColors.muted
                                    },
                                )
                                HorizontalDivider(color = statusColors.line)
                            }
                            items(view.items, key = { it.id }) { item ->
                                val title = item.field(Fields.TITLE) ?: "Untitled"
                                // Named through `keyringLabel`, so a keyring
                                // that was deleted reads the same as one that
                                // was never there rather than sending somebody
                                // looking for a keyring that is gone.
                                val ringName = keyringLabel(state, item.keyring.value)
                                val user = item.field(Fields.USERNAME)
                                val chosen = selected?.contains(item.id)
                                VaultRow(
                                    initials = initials(title),
                                    title = title,
                                    subtitle = if (user.isNullOrBlank()) {
                                        ringName
                                    } else {
                                        "$ringName · $user"
                                    },
                                    onClick = {
                                        val current = selected
                                        if (current == null) {
                                            onOpen(item.id)
                                        } else {
                                            selected = if (item.id in current) {
                                                current - item.id
                                            } else {
                                                current + item.id
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (selected == null) selected = setOf(item.id)
                                    },
                                    selected = chosen,
                                )
                                HorizontalDivider(color = statusColors.line)
                            }
                        }
                    }
                }
            }

            val chosen = selected
            if (chosen != null) {
                SelectionActions(
                    count = chosen.size,
                    keyrings = rings,
                    confirming = confirming,
                    moving = moving,
                    onAskDelete = { confirming = true },
                    onAskMove = { moving = true },
                    onCancel = { confirming = false; moving = false },
                    onDelete = {
                        onDeleteMany(chosen.toList())
                        exitSelection()
                    },
                    onMove = { keyringId ->
                        onMoveMany(chosen.toList(), keyringId)
                        exitSelection()
                    },
                )
                Spacer(Modifier.height(12.dp))
                return@Column
            }

            PrimaryButton(
                "Add a password",
                onAdd,
                Modifier.padding(vertical = 12.dp),
                icon = Icons.Filled.Add,
            )
        }
    }
}

@Composable
fun ItemDetailScreen(
    item: ItemRecord,
    state: VaultState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenFile: (String) -> Unit = {},
    onAttachFile: () -> Unit = {},
    onRemoveFile: (String) -> Unit = {},
    onRestore: (String, String) -> Unit = { _, _ -> },
) {
    var revealed by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val statusColors = LocalKeywebStatus.current
    val title = item.field(Fields.TITLE) ?: "Untitled"
    val ringName = keyringLabel(state, item.keyring.value)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                if (ringName == NO_KEYRING) NO_KEYRING else "On the $ringName keyring",
                color = statusColors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            item.field(Fields.USERNAME)?.takeIf { it.isNotBlank() }?.let { user ->
                ReadOnlyField("Username", user, trailing = {
                    IconButton(onClick = { onCopy(user, "Username") }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy username")
                    }
                })
            }

            val password = item.field(Fields.PASSWORD).orEmpty()
            ReadOnlyField(
                label = "Password",
                value = password,
                mono = true,
                mask = !revealed,
                trailing = {
                    Row {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (revealed) "Hide password" else "Show password",
                            )
                        }
                        IconButton(onClick = { onCopy(password, "Password") }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy password")
                        }
                    }
                },
            )
            if (revealed) CodeLegend(Modifier.padding(bottom = 10.dp))
            Text(
                if (revealed) "Tap the eye again to hide it." else "Hidden until you choose to show it.",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            item.field(Fields.URL)?.takeIf { it.isNotBlank() }?.let {
                ReadOnlyField("Website", it)
            }
            item.field(Fields.NOTE)?.takeIf { it.isNotBlank() }?.let {
                ReadOnlyField("Note", it)
            }

            item.field(Fields.OTP)?.takeIf { it.isNotBlank() }?.let { secret ->
                OtpCode(secret) { onCopy(it, "The code") }
            }

            /*
             * Everything else this item carries.
             *
             * The screen used to render five fields and no more, which meant a
             * vault could hold a security answer or a backup PIN — imported,
             * synced, backed up — that its owner could never see. Field keys
             * have always been open precisely so a field nobody anticipated
             * survives; showing them is the other half of that promise.
             */
            item.fields.keys
                .filter { it !in PRESENTED_FIELDS }
                // The pointer at an attached file is plumbing, not a field
                // somebody wrote. `FilesSection` renders it as the file it names.
                .filter { !it.startsWith("file:") }
                .filter { item.field(it)?.isNotBlank() == true }
                // By the name on screen, not the key behind it: sorting on the
                // key put every hidden field in a block of its own under "s",
                // which is an ordering nobody typing these names would expect.
                .sortedBy { fieldLabel(it).lowercase() }
                .forEach { name ->
                    ExtraField(
                        label = fieldLabel(name),
                        value = item.field(name).orEmpty(),
                        secret = Fields.isSecret(name),
                        onCopy = onCopy,
                    )
                }

            FilesSection(
                item = item,
                state = state,
                onOpen = onOpenFile,
                onAttach = onAttachFile,
                onRemove = onRemoveFile,
            )

            Spacer(Modifier.height(8.dp))
            SecondaryButton("Edit", onEdit, Modifier.padding(bottom = 10.dp))

            val past = item.pastValues()
            if (past.isNotEmpty()) {
                Disclosure(
                    label = "What this used to be",
                    hint = "${past.size} earlier " +
                        if (past.size == 1) "value" else "values",
                ) {
                    PastValues(past, onCopy, onRestore)
                }
            }

            SecondaryButton("Delete this password", { confirmingDelete = true }, danger = true)
            if (confirmingDelete) {
                AlertDialog(
                    onDismissRequest = { confirmingDelete = false },
                    title = { Text("Delete $title?") },
                    text = { Text("This cannot be undone on this phone, and it syncs.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmingDelete = false
                            onDelete()
                        }) { Text("Yes, delete it", color = statusColors.risk) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The rotating second-factor code for a password.
 *
 * Behind a tap rather than on screen when the page opens, matching the
 * password above it. `docs/two-factor.md` sets the rule: producing a code is a
 * separate deliberate act, never something that happens alongside a password
 * in one gesture — an attacker who gets one keystroke of assent should not get
 * a whole sign-in.
 *
 * The countdown is there because a code with three seconds left will be
 * rejected by the time somebody has typed it, and being told beforehand is the
 * difference between waiting four seconds and thinking the code is wrong.
 */
@Composable
private fun OtpCode(secret: String, onCopy: (String) -> Unit) {
    val colors = LocalKeywebStatus.current
    val config = remember(secret) { runCatching { Totp.parse(secret) }.getOrNull() }
    var shown by remember(secret) { mutableStateOf(false) }
    var code by remember(secret) { mutableStateOf("") }
    var seconds by remember(secret) { mutableIntStateOf(0) }

    if (config == null) {
        // A seed that cannot be read is still the person's data, and is shown
        // as the text it is rather than disappearing because we cannot use it.
        ReadOnlyField("Second-factor code", secret)
        Text(
            "Keyweb can't turn this into a code. It came across from your other app exactly " +
                "as it was, so nothing is lost.",
            color = colors.attention,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        return
    }

    // Once a second, so the countdown moves and the code changes the moment the
    // window rolls over rather than up to thirty seconds late.
    LaunchedEffect(shown, secret) {
        while (shown) {
            val now = System.currentTimeMillis()
            code = runCatching { Totp.at(config, now) }.getOrDefault("")
            seconds = Totp.secondsRemaining(config, now)
            kotlinx.coroutines.delay(1000)
        }
    }

    Column {
        ReadOnlyField(
            "Second-factor code",
            if (shown && code.isNotEmpty()) Totp.group(code) else "••• •••",
        )
        Row {
            TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") }
            if (shown && code.isNotEmpty()) {
                TextButton(onClick = { onCopy(code) }) { Text("Copy") }
            }
        }
        Text(
            when {
                !shown -> "A new code every 30 seconds. Type it after your password."
                seconds <= 5 ->
                    "About to change — wait $seconds second${if (seconds == 1) "" else "s"} " +
                        "for a fresh one."
                else -> "Changes in $seconds seconds."
            },
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

/**
 * What an item used to hold, and the way back to it.
 *
 * The vault has kept superseded values per field since the CRDT was written,
 * and nothing ever showed them. Two things changed that: the KeePass import
 * now replays years of somebody's earlier passwords into the same place, and
 * the reason people look here at all is the reason they need the button —
 * a password was changed, the change turned out to be wrong, and the old one
 * is the thing they are trying to get back.
 *
 * Restoring writes the old value as a new one rather than rewinding anything.
 * The value being replaced goes into history in its turn, so the way back is
 * never a one-way door.
 */
@Composable
private fun PastValues(
    past: List<PastValue>,
    onCopy: (String, String) -> Unit,
    onRestore: (String, String) -> Unit,
) {
    val colors = LocalKeywebStatus.current
    Column {
        Text(
            "Keyweb keeps what a field held before you changed it, so a change " +
                "you did not mean to make is not the end of it.",
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        past.forEach { value ->
            var shown by remember(value.field, value.atMs) { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(value.label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        whenChanged(value.atMs),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (value.secret && !shown) {
                        Text(
                            "\u2022".repeat(minOf(value.value.length, 24)),
                            style = MaterialTheme.typography.bodyLarge
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    } else {
                        Text(
                            codeGlyphs(value.value),
                            style = MaterialTheme.typography.bodyLarge
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    Row {
                        if (value.secret) {
                            TextButton(onClick = { shown = !shown }) {
                                Text(if (shown) "Hide" else "Show")
                            }
                        }
                        TextButton(onClick = { onCopy(value.value, value.label) }) {
                            Text("Copy")
                        }
                        TextButton(onClick = { onRestore(value.field, value.value) }) {
                            Text("Put this back")
                        }
                    }
                }
            }
        }
    }
}

/**
 * When a value was replaced, in words rather than a timestamp.
 *
 * An imported KeePass history can reach back a decade, and "2019-03-04
 * 10:00:00Z" is not how anybody remembers changing their bank password.
 */
private fun whenChanged(atMs: Long): String {
    if (atMs <= 0) return "Changed at some point"
    val days = (System.currentTimeMillis() - atMs) / 86_400_000L
    return when {
        days < 0L -> "Changed just now"
        days == 0L -> "Changed today"
        days == 1L -> "Changed yesterday"
        days < 30L -> "Changed $days days ago"
        days < 365L -> "Changed ${days / 30} month${if (days / 30 == 1L) "" else "s"} ago"
        else -> "Changed ${days / 365} year${if (days / 365 == 1L) "" else "s"} ago"
    }
}

/**
 * The fields this screen already lays out by hand, above.
 *
 * `folder` and `tags` are structure rather than content and are shown in the
 * list instead; `kind` picks the template. Everything not named here gets the
 * generic treatment, which is what makes an imported field visible at all.
 */
private val PRESENTED_FIELDS = setOf(
    Fields.TITLE, Fields.USERNAME, Fields.PASSWORD, Fields.URL, Fields.NOTE,
    // Shown as a rotating code by `OtpCode`, not as a field of text.
    Fields.OTP,
    Fields.FOLDER, Fields.TAGS, "kind",
)

/**
 * A field Keyweb has no special presentation for.
 *
 * Hidden behind a tap when the name says it is a secret, which for anything
 * imported from KeePass means the field its owner marked protected: those
 * arrive as `secret:<name>`, so an answer to "first pet's name" is masked here
 * exactly as it was masked there.
 *
 * Otherwise the same controls as the password above it, deliberately. A backup
 * PIN, a security answer and a recovery code are secrets that get *used* the
 * same way a password does — read off the screen, or copied into a box — and
 * the first version of this screen offered neither. Somebody looking at their
 * own account number had to select it by hand; somebody looking at a masked
 * security answer got a "Show" link and still no way to copy it. Nothing about
 * the field being custom makes it need less.
 */
@Composable
private fun ExtraField(
    label: String,
    value: String,
    secret: Boolean,
    onCopy: (String, String) -> Unit,
) {
    var revealed by remember(label) { mutableStateOf(false) }
    Column {
        ReadOnlyField(
            label = label,
            value = value,
            // Revealed to be read off the screen and typed elsewhere, which is
            // where a zero gets copied down as a letter O.
            mono = secret && revealed,
            mask = secret && !revealed,
            trailing = {
                Row {
                    if (secret) {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (revealed) "Hide $label" else "Show $label",
                            )
                        }
                    }
                    IconButton(onClick = { onCopy(value, label) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label")
                    }
                }
            },
        )
        if (secret && revealed) CodeLegend(Modifier.padding(bottom = 10.dp))
    }
}

@Composable
private fun ReadOnlyField(
    label: String,
    value: String,
    mono: Boolean = false,
    mask: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (mask) {
            PasswordVisualTransformation()
        } else if (mono) {
            // Revealed to be read off the screen and typed elsewhere, which is
            // where a zero gets copied down as a letter O.
            rememberGlyphColors()
        } else {
            VisualTransformation.None
        },
        textStyle = if (mono) {
            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}


@Composable
fun ItemEditScreen(
    item: ItemRecord?,
    state: VaultState,
    defaultKeyringId: String,
    savedRules: List<SavedRules>,
    lastRules: PasswordRules,
    onBack: () -> Unit,
    onSave: (String?, String, Map<ItemField, String>) -> Unit,
    onSaveRules: (String, PasswordRules) -> Unit,
    onRulesUsed: (PasswordRules) -> Unit,
    /**
     * Keyrings somebody shared as a reader.
     *
     * Saving into one would be accepted here and refused at the Drive file, so
     * it would look exactly like saving and never arrive — and the change would
     * sit in the outbox with the status line reporting it forever. Better to
     * say so before the typing than after it.
     */
    readOnlyKeyrings: Set<String> = emptySet(),
) {
    var title by remember { mutableStateOf(item?.field(Fields.TITLE).orEmpty()) }
    var username by remember { mutableStateOf(item?.field(Fields.USERNAME).orEmpty()) }
    var password by remember { mutableStateOf(item?.field(Fields.PASSWORD).orEmpty()) }
    var url by remember { mutableStateOf(item?.field(Fields.URL).orEmpty()) }
    var note by remember { mutableStateOf(item?.field(Fields.NOTE).orEmpty()) }
    /*
     * The second-factor seed was readable on the detail screen and editable
     * nowhere: it could arrive from a KeePass file and then never be added,
     * corrected or removed by hand. It sits behind the disclosure because most
     * logins do not have one, not because it is difficult.
     */
    var otp by remember { mutableStateOf(item?.field(Fields.OTP).orEmpty()) }
    /*
     * The folder, relative to the keyring.
     *
     * Stored and shown without the keyring's own name at the front, even
     * though an import writes it that way — `folderPath` strips it either way,
     * and putting "Leslie / " in front of every folder on the Leslie keyring
     * is noise somebody would have to delete to type anything.
     */
    var folder by remember(item?.id) {
        mutableStateOf(
            item?.let { folderPath(it, state).joinToString(" / ") }.orEmpty(),
        )
    }
    var keyringId by remember { mutableStateOf(item?.keyring?.value ?: defaultKeyringId) }
    var generating by remember { mutableStateOf<GenerateInto?>(null) }
    /**
     * Fields this person added themselves, or that came in from another app.
     *
     * A list rather than a map so a row keeps its identity while its name is
     * being typed — keying on the name would rebuild the field on every
     * keystroke, and take the cursor with it.
     */
    var extras by remember(item?.id) { mutableStateOf(editableFields(item)) }

    generating?.let { target ->
        GeneratorSheet(
            initial = lastRules,
            saved = savedRules,
            onDismiss = { generating = null },
            onUse = { made, rules ->
                when (target) {
                    is GenerateInto.Password -> password = made
                    is GenerateInto.Extra -> {
                        extras = extras.mapIndexed { i, row ->
                            // Hidden as well as filled. A value nobody has ever
                            // read is a secret by construction, and leaving it
                            // in plain text on the detail screen because the
                            // row happened to say "Shown" would be a leak
                            // created by the act of generating it.
                            if (i == target.index) row.copy(value = made, secret = true) else row
                        }
                    }
                }
                onRulesUsed(rules)
                generating = null
            },
            onSaveRules = onSaveRules,
        )
    }
    val statusColors = LocalKeywebStatus.current

    val rings = liveKeyrings(state)
    /*
     * What the chips are actually showing.
     *
     * A selection that matches no chip leaves every chip unselected and
     * reports nothing wrong — so when the default was a keyring that had been
     * deleted, or the invented "personal", the screen showed no keyring while
     * the save went to one that did not exist, and the password was never seen
     * again. Reading the selection back from the chips that exist makes the
     * two agree by construction.
     */
    val chosen = if (rings.any { it.id == keyringId }) keyringId else rings.firstOrNull()?.id ?: ""
    /*
     * A name is the whole requirement.
     *
     * Requiring a password too made every entry that has none impossible to
     * edit — the same rule that blocks creating one blocks saving any change
     * to one that exists. A second-factor code scanned from a QR has a title
     * and no password by construction; so does a note or a card. Their names
     * could never be corrected, and nothing on the screen said why.
     */
    val canSave = title.isNotBlank()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                if (item == null) "Add a password" else "Edit password",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Only the name is required.",
                color = statusColors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            EditField("What is it for?", title, { title = it }, "Chase Bank")
            EditField("Username or email", username, { username = it }, "you@example.com")

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions.Default,
                trailingIcon = {
                    TextButton(onClick = { generating = GenerateInto.Password }) { Text("Make one") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Keyweb can make one to whatever rules the site demands.",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            EditField("Website", url, { url = it }, "chase.com")
            EditField("Note", note, { note = it }, "Anything you want to remember")

            Text(
                "Which keyring?",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rings.forEach { r ->
                    FilterChip(
                        selected = chosen == r.id,
                        onClick = { keyringId = r.id },
                        colors = keywebChipColors(),
                        label = {
                            Text(
                                if (readOnlyKeyrings.contains(r.id)) {
                                    "${r.name.value} · look only"
                                } else {
                                    r.name.value
                                },
                            )
                        },
                    )
                }
            }
            // Shown rather than hidden, so an item that is already in one still
            // shows where it lives — but choosing it says why it cannot be saved.
            val readOnly = readOnlyKeyrings.contains(chosen)
            Text(
                if (readOnly) {
                    "This keyring was shared with you to look at. Ask the person who shared " +
                        "it if you need to change something, or choose a keyring of your own."
                } else if (rings.isEmpty()) {
                    "You have no keyrings yet. Keyweb will make one called Just mine and " +
                        "put this in it."
                } else {
                    "Everyone on a keyring can see everything on it. You share a keyring, " +
                        "never one password."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (readOnly) statusColors.attention else statusColors.muted,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            Spacer(Modifier.height(8.dp))
            Disclosure(
                label = "Anything else this login needs",
                hint = "Security questions, a backup PIN, a second-factor code",
                // Opened for an item that already has some. Hiding a field
                // somebody can see today, on the grounds that it is advanced,
                // is how these fields went missing in the first place.
                initiallyOpen = extras.isNotEmpty() || otp.isNotEmpty() || folder.isNotEmpty(),
            ) {
            EditField("Folder", folder, { folder = it }, "Banks")
            Text(
                "A folder is just a way of finding things later. Use a slash for a folder " +
                    "inside a folder, like \"Banks / Cards\". Leave it blank to keep this at " +
                    "the top of the keyring.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            EditField(
                "Second-factor code",
                otp,
                { otp = it },
                "Paste the setup code the site gave you",
            )
            Text(
                "The long code a site shows you next to a QR square. Keyweb turns it into " +
                    "the six digits that change every thirty seconds.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Text("Anything else", style = MaterialTheme.typography.labelLarge)
            Text(
                "Security questions, a backup PIN, an account number — whatever this login " +
                    "needs that a username and password don't cover.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            extras.forEachIndexed { index, field ->
                EditField(
                    "What is it called?",
                    field.name,
                    { value ->
                        extras = extras.mapIndexed { i, row ->
                            if (i == index) row.copy(name = value) else row
                        }
                    },
                    "Security question",
                )
                EditField(
                    if (field.secret) "What is it? (hidden)" else "What is it?",
                    field.value,
                    { value ->
                        extras = extras.mapIndexed { i, row ->
                            if (i == index) row.copy(value = value) else row
                        }
                    },
                    "",
                    secret = field.secret,
                    // The same generator the password has. A security answer
                    // should be a random string rather than the name of a dog
                    // three other sites already know, and a backup PIN is a
                    // password wearing a different name — there was no reason
                    // beyond oversight for this to be the one place in the app
                    // where Keyweb would not make one for you.
                    trailing = {
                        TextButton(onClick = { generating = GenerateInto.Extra(index) }) {
                            Text("Make one")
                        }
                    },
                )
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    TextButton(
                        onClick = {
                            extras = extras.mapIndexed { i, row ->
                                if (i == index) row.copy(secret = !row.secret) else row
                            }
                        },
                    ) { Text(if (field.secret) "Hidden" else "Shown") }
                    TextButton(
                        onClick = { extras = extras.filterIndexed { i, _ -> i != index } },
                    ) { Text("Remove", color = statusColors.risk) }
                }
            }

            SecondaryButton(
                "Add another field",
                onClick = { extras = extras + EditableField("", "", false) },
                Modifier.padding(bottom = 16.dp),
            )
            }

            PrimaryButton(
                "Save",
                onClick = {
                    onSave(
                        item?.id,
                        chosen,
                        mapOf(
                            Fields.TITLE to title.trim(),
                            Fields.USERNAME to username,
                            Fields.PASSWORD to password,
                            Fields.URL to url,
                            Fields.NOTE to note,
                            Fields.OTP to otp.trim(),
                            Fields.FOLDER to folder.split('/')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .joinToString(" / "),
                        ) + customFields(item, extras),
                    )
                },
                enabled = canSave && !readOnly,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Where a generated password is about to go. */
private sealed interface GenerateInto {
    data object Password : GenerateInto

    /** A custom field, by its position in the list being edited. */
    data class Extra(val index: Int) : GenerateInto
}

@Composable
internal fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    /** Masks what is typed, for a field its owner marked as hidden. */
    secret: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        trailingIcon = trailing,
        visualTransformation = if (secret) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
fun KeyringsScreen(
    state: VaultState,
    items: List<ItemRecord>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** False when this build has no Google account and so cannot share at all. */
    canShare: Boolean = false,
    onShare: (String) -> Unit = {},
    /** Invite somebody to everything that is selected, on one link. */
    onShareMany: (List<String>) -> Unit = {},
    onPasteLink: (String) -> Unit = {},
    /** The ordering last chosen, remembered across launches. */
    savedSort: String = KeyringSort.NAME_AZ.id,
    onSortChanged: (String) -> Unit = {},
) {
    var pastedLink by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    /*
     * Keyrings picked to share together.
     *
     * Entered by holding a row, the same gesture that selects passwords in the
     * list, because it is the same idea: the thing being selected is the thing
     * already under your finger. Null is not selecting at all, which differs
     * from selecting nothing — an empty selection keeps the bar on screen so
     * unticking the last row does not throw you out mid-task.
     */
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var sort by rememberSaveable { mutableStateOf(savedSort) }
    /** The keyring being deleted, held until the count has been acknowledged. */
    var confirming by remember { mutableStateOf<String?>(null) }
    val statusColors = LocalKeywebStatus.current
    val counts = items.groupingBy { it.keyring.value }.eachCount()
    val rings = sortKeyrings(
        state.keyrings.values.filter { !it.deleted.value },
        counts,
        KeyringSort.of(sort),
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Keyrings", style = MaterialTheme.typography.titleLarge)
            Text(
                "A keyring is a group of passwords you can share as one. " +
                    "You share a keyring, never a single password.",
                color = statusColors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (rings.size > 1) {
                SortPicker(
                    current = sort,
                    options = KeyringSort.entries.map { it.id to it.label },
                    onPick = {
                        sort = it
                        onSortChanged(it)
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    rings.forEach { r ->
                        val count = counts[r.id] ?: 0
                        // A keyring in its own file is one that is shared, or is
                        // being got ready to be. Whether *you* shared it or
                        // somebody shared it with you is not something this list
                        // can know without asking Drive, so it says the part it
                        // is sure of and the sharing screen says the rest.
                        val shared = datasetOf(r) != null
                        val selecting = selected != null
                        val ticked = selected?.contains(r.id) == true
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                VaultRow(
                                    initials = "●",
                                    title = r.name.value,
                                    subtitle = "$count password${if (count == 1) "" else "s"} · " +
                                        if (shared) "shared" else "only you",
                                    onClick = {
                                        if (selecting) {
                                            selected = selected.orEmpty().let { current ->
                                                if (ticked) current - r.id else current + r.id
                                            }
                                        }
                                    },
                                    onLongClick = if (canShare && rings.size > 1) {
                                        { selected = setOf(r.id) }
                                    } else {
                                        null
                                    },
                                    selected = if (selecting) ticked else null,
                                    accent = ringColor(state, r.id),
                                )
                            }
                            if (!selecting) {
                                if (canShare) {
                                    TextButton(onClick = { onShare(r.id) }) {
                                        Text(if (shared) "Sharing" else "Share")
                                    }
                                }
                                // Never the last one: every password lives in a
                                // keyring, so a vault with none has nowhere to
                                // put the next one.
                                if (rings.size > 1) {
                                    TextButton(onClick = { confirming = r.id }) {
                                        Text("Delete", color = statusColors.risk)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = statusColors.line)
                    }
                }
            }

            selected?.let { chosen ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(onClick = { selected = null }) { Text("Done") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${chosen.size} selected",
                        color = statusColors.muted,
                    )
                    PrimaryButton(
                        text = if (chosen.size == 1) "Share it" else "Share these ${chosen.size}",
                        onClick = { onShareMany(chosen.toList()) },
                        enabled = chosen.isNotEmpty(),
                    )
                }
            }

            confirming?.let { keyringId ->
                val ring = rings.firstOrNull { it.id == keyringId }
                val count = items.count { it.keyring.value == keyringId }
                if (ring == null) {
                    confirming = null
                } else {
                    AlertDialog(
                        onDismissRequest = { confirming = null },
                        title = {
                            Text(
                                if (count > 0) {
                                    "Delete ${ring.name.value} and its $count " +
                                        "password${if (count == 1) "" else "s"}?"
                                } else {
                                    "Delete ${ring.name.value}?"
                                },
                            )
                        },
                        text = {
                            Text(
                                if (count > 0) {
                                    "The passwords in it are deleted too. This cannot be " +
                                        "undone on this device."
                                } else {
                                    "This keyring is empty. This cannot be undone on this device."
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                onDelete(keyringId)
                                confirming = null
                            }) {
                                Text(
                                    if (count > 0 && count != 1) "Yes, delete them" else "Yes, delete it",
                                    color = statusColors.risk,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirming = null }) { Text("Keep it") }
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            EditField("Make a new keyring", name, { name = it }, "Household")
            Text(
                "This name is not encrypted. Keep it general — no names of people or websites.",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColors.attention,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            PrimaryButton(
                "Make this keyring",
                onClick = {
                    onAdd(name.trim())
                    name = ""
                },
                enabled = name.isNotBlank(),
            )

            if (canShare) {
                Spacer(Modifier.height(28.dp))
                EditField(
                    "Somebody sent you a link",
                    pastedLink,
                    { pastedLink = it },
                    "Paste it here",
                )
                // Tapping the link ought to open Keyweb, and does once Android
                // has verified this app owns the address. Until then — and on
                // any phone where the person has not turned that on — pasting
                // it here is the path that always works.
                Text(
                    "If tapping the link opened a browser instead of Keyweb, paste it in here.",
                    color = statusColors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SecondaryButton(
                    "Open that link",
                    onClick = {
                        onPasteLink(pastedLink.trim())
                        pastedLink = ""
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsScreen(
    largeText: Boolean,
    darkMode: Boolean?,
    onLargeText: (Boolean) -> Unit,
    onDarkMode: (Boolean?) -> Unit,
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onScanCodes: () -> Unit = {},
    onLock: () -> Unit = {},
    onExport: (csv: Boolean) -> Unit = {},
    onDescribeBackup: () -> Unit = {},
    /** Every Keyweb backup in this Google account, once somebody has looked. */
    backupFiles: List<BackupFileChoice> = emptyList(),
    onShowBackupFiles: () -> Unit = {},
    onUseBackupFile: (String) -> Unit = {},
    onDeleteBackupFile: (String) -> Unit = {},
    /** How many passwords, and what a CSV would leave behind. */
    exportSummary: Pair<Int, CsvOmissions>? = null,
    /** Null when this build has no Google account and so cannot share at all. */
    sharingKey: String? = null,
    canShare: Boolean = false,
    onShowSharingKey: () -> Unit = {},
) {
    val statusColors = LocalKeywebStatus.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Text(
                "These change straight away. You can come back and change them again.",
                color = statusColors.muted,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            Text("Backup", style = MaterialTheme.typography.labelLarge)
            Text(
                "Keep a copy of your passwords in your own Google Drive, so a lost " +
                    "phone doesn't mean lost passwords.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SecondaryButton("Backup and recovery", onBackup)

            if (canShare) {
                Spacer(Modifier.height(24.dp))
                Text("Your sharing key", style = MaterialTheme.typography.labelLarge)
                Text(
                    "When somebody shares a keyring with you, this is how their Keyweb knows " +
                        "it is really you. It is the same on every device you sign in to with " +
                        "this Google account.",
                    color = statusColors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (sharingKey != null) {
                    Text(sharingKey, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Read these out to the person you are sharing with. If what they see " +
                            "doesn't match, the link was tampered with on the way.",
                        color = statusColors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Behind a button rather than shown on arrival: reading it
                    // needs the vault open, and a settings screen that demands
                    // that the moment it opens teaches people to tap past
                    // prompts without reading them.
                    SecondaryButton("Show my sharing key", onShowSharingKey)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Coming from another password app", style = MaterialTheme.typography.labelLarge)
            Text(
                "Bring in a KeePass or KeeWeb file, keeping your folders and tags.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SecondaryButton(
                "Import from KeePass or KeeWeb",
                onImport,
                Modifier.padding(bottom = 8.dp),
            )
            Text(
                "Or bring your six-digit codes over from Google Authenticator, so they live " +
                    "beside the passwords they belong to.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SecondaryButton("Codes from another app", onScanCodes)

            /*
             * First, because it is the thing somebody comes here in a hurry
             * to do — handing the phone to someone, or putting it down.
             *
             * Locking existed from the beginning and was reachable from
             * nowhere: the only way to lock was to force-stop the app or wait
             * for the Keystore window to expire, neither of which is something
             * a person does deliberately.
             */
            Spacer(Modifier.height(16.dp))
            Text("Lock Keyweb", style = MaterialTheme.typography.labelLarge)
            Text(
                "Closes your passwords straight away. You'll need your face, fingerprint or " +
                    "PIN to open them again.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SecondaryButton("Lock now", onLock)

            /*
             * A diagnostic, in the open rather than behind a gesture.
             *
             * "Both devices say they are synced and show different things" is
             * unanswerable from the outside and trivial from here: which file,
             * which copies, when each was sealed. It is a Drive file id and
             * two timestamps — nothing secret — and having it beats another
             * round of inferring the file's contents from behaviour.
             */
            Spacer(Modifier.height(24.dp))
            Text("If something looks wrong", style = MaterialTheme.typography.labelLarge)
            Text(
                "Shows which backup file this phone is using and when each copy in it was " +
                    "last written. Useful when this phone and a browser disagree.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SecondaryButton("Check the backup file", onDescribeBackup)

            /*
             * What is actually in this Google account.
             *
             * The list existed only as a refusal: when the account held two
             * files Keyweb would not guess and offered the choice at that
             * moment. So somebody who suspected a stray file — a setup that
             * ran twice, a restore that went sideways — could not look, and
             * could not tidy up.
             *
             * Nothing shown needs a key. The date is the envelope header, the
             * size is the file's own, and the size is the honest answer to "is
             * that one empty": a sealed empty vault is about a kilobyte.
             */
            Spacer(Modifier.height(20.dp))
            Text("Backups in this Google account", style = MaterialTheme.typography.labelLarge)
            Text(
                "Keyweb keeps one file. If there are more, one is being read and the rest " +
                    "are strays.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (backupFiles.isEmpty()) {
                SecondaryButton("Show me what is there", onShowBackupFiles)
            } else {
                var confirming by remember { mutableStateOf<String?>(null) }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        backupFiles.forEach { file ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    VaultRow(
                                        initials = if (file.inUse) "●" else "○",
                                        title = "Last changed " + whenChanged(file.modifiedAtMs) +
                                            if (file.inUse) " · in use" else "",
                                        subtitle = humanSize(file.bytes) + " · " +
                                            file.fileId.takeLast(6),
                                        onClick = {},
                                    )
                                }
                                if (!file.inUse) {
                                    TextButton(onClick = { onUseBackupFile(file.fileId) }) {
                                        Text("Use")
                                    }
                                    TextButton(onClick = { confirming = file.fileId }) {
                                        Text("Delete", color = statusColors.risk)
                                    }
                                }
                            }
                            HorizontalDivider(color = statusColors.line)
                        }
                    }
                }

                confirming?.let { fileId ->
                    AlertDialog(
                        onDismissRequest = { confirming = null },
                        title = { Text("Delete that backup file?") },
                        text = {
                            Text(
                                "Keyweb will not be able to open it again. Google keeps deleted " +
                                    "files in your Drive bin for thirty days, so this is undoable " +
                                    "there and nowhere else.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                onDeleteBackupFile(fileId)
                                confirming = null
                            }) { Text("Delete it", color = statusColors.risk) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirming = null }) { Text("Keep it") }
                        },
                    )
                }
            }

            /*
             * The door has to swing both ways.
             *
             * The import was built on the promise that somebody could bring
             * their KeePass file in and delete the original. Without this,
             * that promise reads "your data is yours as long as you keep using
             * Keyweb" — the Drive backup is a sealed envelope only Keyweb can
             * open, which is a safety net and not a way out.
             */
            Spacer(Modifier.height(24.dp))
            Text("Take a copy of everything", style = MaterialTheme.typography.labelLarge)
            Text(
                "Your passwords in a plain file you can read, print, or load into another " +
                    "password app.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Before the buttons, not after the file exists. Somebody who
            // decides this is a bad idea should be able to decide it while
            // there is still nothing on disk.
            StatusLine(
                tone = Tone.ATTENTION,
                headline = "This file is not locked",
                detail = "Anyone who opens it can read every password in it. Save it somewhere " +
                    "only you can reach, and delete it when you're done.",
                modifier = Modifier.padding(bottom = 10.dp),
            )
            SecondaryButton(
                "Save everything (keeps files and history)",
                { onExport(false) },
                Modifier.padding(bottom = 8.dp),
            )
            SecondaryButton("Save for another password app", { onExport(true) })
            exportSummary?.let { (count, omissions) ->
                val losses = buildList {
                    if (omissions.files > 0) add("${omissions.files} attached file" + if (omissions.files == 1) "" else "s")
                    if (omissions.customFields > 0) add("${omissions.customFields} of your own field" + if (omissions.customFields == 1) "" else "s")
                    if (omissions.history > 0) add("${omissions.history} earlier value" + if (omissions.history == 1) "" else "s")
                }
                Text(
                    "$count password" + (if (count == 1) "" else "s") + " either way. " +
                        if (losses.isEmpty()) {
                            "The second file is the one other apps can read."
                        } else {
                            "The second file is the one other apps can read, and it leaves " +
                                "behind " + losses.joinToString(", ") +
                                " — those only fit in the first."
                        },
                    color = statusColors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("Text size", style = MaterialTheme.typography.labelLarge)
            Text(
                "Makes everything in Keyweb bigger, including the buttons.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (largeText) {
                SecondaryButton("Normal text", { onLargeText(false) }, Modifier.padding(bottom = 8.dp))
                PrimaryButton("Larger text", { onLargeText(true) })
            } else {
                PrimaryButton("Normal text", { onLargeText(false) }, Modifier.padding(bottom = 8.dp))
                SecondaryButton("Larger text", { onLargeText(true) })
            }

            Spacer(Modifier.height(24.dp))
            Text("Light or dark", style = MaterialTheme.typography.labelLarge)
            Text(
                "Match device follows whatever your phone is set to.",
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ChoiceButton("Match my phone", darkMode == null) { onDarkMode(null) }
            ChoiceButton("Always light", darkMode == false) { onDarkMode(false) }
            ChoiceButton("Always dark", darkMode == true) { onDarkMode(true) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        PrimaryButton(text, onClick, Modifier.padding(bottom = 8.dp))
    } else {
        SecondaryButton(text, onClick, Modifier.padding(bottom = 8.dp))
    }
}

/**
 * What can be done to the selection.
 *
 * Delete asks first and says the number rather than "these": a count is the
 * one fact that makes the size of the mistake visible before it is made.
 */
@Composable
private fun SelectionActions(
    count: Int,
    keyrings: List<KeyringRecord>,
    confirming: Boolean,
    moving: Boolean,
    onAskDelete: () -> Unit,
    onAskMove: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onMove: (String) -> Unit,
) {
    val status = LocalKeywebStatus.current
    val plural = if (count == 1) "" else "s"

    if (count == 0) {
        Text(
            "Choose some passwords, or hold another to select it.",
            color = status.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Delete $count password$plural?") },
            text = { Text("This cannot be undone on this device.") },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text("Yes, delete $count", color = status.risk)
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Keep them") } },
        )
    }

    if (moving) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Move $count password$plural to:") },
            text = {
                Column {
                    for (ring in keyrings) {
                        TextButton(
                            onClick = { onMove(ring.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(ring.name.value, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Delete is marked as destructive and Move is not, because one of them
        // cannot be taken back. Two identical buttons side by side is how the
        // wrong one gets pressed.
        SecondaryButton("Move to…", onAskMove, Modifier.weight(1f))
        SecondaryButton("Delete", onAskDelete, Modifier.weight(1f), danger = true)
    }
}

/** A custom field while it is being edited. */
data class EditableField(val name: String, val value: String, val secret: Boolean)

/**
 * The fields Keyweb lays out itself, which are not editable as custom ones.
 *
 * `folder` and `tags` are structure the import maintains and `kind` picks the
 * template; offering them as free text would let somebody move an item by
 * typing in a box labelled "what is it called?".
 */
private val RESERVED_FIELDS = Fields.KNOWN

private fun editableFields(item: ItemRecord?): List<EditableField> {
    if (item == null) return emptyList()
    return item.fields.entries
        .filter { !RESERVED_FIELDS.contains(it.key) && !it.key.startsWith("file:") }
        .map { (name, value) ->
            EditableField(
                // `secret:` and `custom:` are how the field is *stored*;
                // neither is part of what it is called, and showing them would
                // invite somebody to delete the prefix and wonder why the field
                // stopped being hidden.
                name = fieldLabel(name),
                value = value.value,
                secret = Fields.isSecret(name),
            )
        }
        .sortedBy { it.name }
}

/**
 * The custom fields to write, including the ones being removed.
 *
 * A removed field is written empty rather than left out. A CRDT has no way to
 * say "this field is gone" except by writing a later value, and leaving it out
 * would mean the old value stays and quietly comes back.
 */
private fun customFields(
    item: ItemRecord?,
    extras: List<EditableField>,
): Map<ItemField, String> = buildMap {
    for (before in editableFields(item)) put(storedName(before), "")
    for (field in extras) {
        if (field.name.isBlank()) continue
        put(storedName(field), field.value)
    }
}

/**
 * Where a field is stored: hidden ones under `secret:`, which is what makes
 * them masked, and anything colliding with a name Keyweb uses under `custom:`
 * so it cannot act like the real one.
 */
private fun storedName(field: EditableField): ItemField =
    storedFieldName(field.name.trim(), field.secret)

/** A file size somebody can judge a vault by. */
private fun humanSize(bytes: Long?): String = when {
    bytes == null -> "size unknown"
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

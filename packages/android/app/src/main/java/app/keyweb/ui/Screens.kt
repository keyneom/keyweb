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
import app.keyweb.vault.ItemSort
import app.keyweb.vault.KeyringSort
import app.keyweb.vault.PastValue
import app.keyweb.vault.fieldLabel
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
    onDeleteMany: (List<String>) -> Unit,
    onMoveMany: (List<String>, String) -> Unit,
    /** This build's version, so the update notice knows what to compare. */
    currentVersion: String,
    /** Opens a link in a browser. Routed through the caller, which has the Activity. */
    onOpenLink: (String) -> Unit,
    /** The ordering last chosen, remembered across launches. */
    savedSort: String = ItemSort.NAME_AZ.id,
    onSortChanged: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var ring by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(savedSort) }
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
                        selected = if (chosen.size == shown.size) {
                            emptySet()
                        } else {
                            shown.map { it.id }.toSet()
                        }
                    }) {
                        Text(if (chosen.size == shown.size) "Clear" else "Select all")
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
                    onClick = { ring = null },
                    colors = keywebChipColors(),
                    label = { Text("All ${items.size}") },
                )
                rings.forEach { r ->
                    val count = items.count { it.keyring.value == r.id }
                    FilterChip(
                        selected = ring == r.id,
                        onClick = { ring = if (ring == r.id) null else r.id },
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

            // Under the filters rather than beside the search box: filtering
            // narrows what is in the list and ordering decides where in it to
            // look, and reading them in that order matches doing them in it.
            SortPicker(
                current = sort,
                options = ItemSort.entries.map { it.id to it.label },
                onPick = {
                    sort = it
                    onSortChanged(it)
                },
                modifier = Modifier.padding(bottom = 4.dp),
            )

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
            )

            Box(Modifier.weight(1f)) {
                if (shown.isEmpty()) {
                    Text(
                        if (items.isEmpty()) {
                            "No passwords saved yet. Add your first one below."
                        } else {
                            "Nothing matches that search."
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
                            items(shown, key = { it.id }) { item ->
                                val title = item.field(Fields.TITLE) ?: "Untitled"
                                val ringName = state.keyrings[item.keyring.value]?.name?.value
                                    ?: "No keyring"
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
    val statusColors = LocalKeywebStatus.current
    val title = item.field(Fields.TITLE) ?: "Untitled"
    val ringName = state.keyrings[item.keyring.value]?.name?.value

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                ringName?.let { "On the $it keyring" } ?: "No keyring",
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

            SecondaryButton("Delete this password", onDelete, danger = true)
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

    val rings = state.keyrings.values.filter { !it.deleted.value }
    val canSave = title.isNotBlank() && password.isNotEmpty()

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
                "Only the name and the password are required.",
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
                        selected = keyringId == r.id,
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
            val readOnly = readOnlyKeyrings.contains(keyringId)
            Text(
                if (readOnly) {
                    "This keyring was shared with you to look at. Ask the person who shared " +
                        "it if you need to change something, or choose a keyring of your own."
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
                initiallyOpen = extras.isNotEmpty() || otp.isNotEmpty(),
            ) {
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
                        keyringId,
                        mapOf(
                            Fields.TITLE to title.trim(),
                            Fields.USERNAME to username,
                            Fields.PASSWORD to password,
                            Fields.URL to url,
                            Fields.NOTE to note,
                            Fields.OTP to otp.trim(),
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
    onPasteLink: (String) -> Unit = {},
    /** The ordering last chosen, remembered across launches. */
    savedSort: String = KeyringSort.NAME_AZ.id,
    onSortChanged: (String) -> Unit = {},
) {
    var pastedLink by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                VaultRow(
                                    initials = "●",
                                    title = r.name.value,
                                    subtitle = "$count password${if (count == 1) "" else "s"} · " +
                                        if (shared) "shared" else "only you",
                                    onClick = {},
                                    accent = ringColor(state, r.id),
                                )
                            }
                            if (canShare) {
                                TextButton(onClick = { onShare(r.id) }) {
                                    Text(if (shared) "Sharing" else "Share")
                                }
                            }
                            // Never the last one: every password lives in a
                            // keyring, so a vault with none has nowhere to put
                            // the next one.
                            if (rings.size > 1) {
                                TextButton(onClick = { confirming = r.id }) {
                                    Text("Delete", color = statusColors.risk)
                                }
                            }
                        }
                        HorizontalDivider(color = statusColors.line)
                    }
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

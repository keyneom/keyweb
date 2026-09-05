package app.keyweb.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.keyweb.vault.ItemField
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.SyncStatus
import app.keyweb.vault.VaultState
import app.keyweb.vault.field

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
private fun keywebChipColors() = FilterChipDefaults.filterChipColors(
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
) {
    var query by remember { mutableStateOf("") }
    var ring by remember { mutableStateOf<String?>(null) }
    val statusColors = LocalKeywebStatus.current

    val rings = state.keyrings.values.filter { !it.deleted.value }.sortedBy { it.name.value }
    val shown = items
        .filter { ring == null || it.keyring.value == ring }
        .filter { item ->
            val needle = query.trim().lowercase()
            needle.isEmpty() || listOfNotNull(
                item.field(ItemField.TITLE),
                item.field(ItemField.USERNAME),
                item.field(ItemField.URL),
            ).joinToString(" ").lowercase().contains(needle)
        }
        .sortedBy { it.field(ItemField.TITLE).orEmpty().lowercase() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

            BackupStatusLine(status, backupConfigured, Modifier.padding(bottom = 10.dp))

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
                                val title = item.field(ItemField.TITLE) ?: "Untitled"
                                val ringName = state.keyrings[item.keyring.value]?.name?.value
                                    ?: "No keyring"
                                val user = item.field(ItemField.USERNAME)
                                VaultRow(
                                    initials = initials(title),
                                    title = title,
                                    subtitle = if (user.isNullOrBlank()) {
                                        ringName
                                    } else {
                                        "$ringName · $user"
                                    },
                                    onClick = { onOpen(item.id) },
                                )
                                HorizontalDivider(color = statusColors.line)
                            }
                        }
                    }
                }
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
) {
    var revealed by remember { mutableStateOf(false) }
    val statusColors = LocalKeywebStatus.current
    val title = item.field(ItemField.TITLE) ?: "Untitled"
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

            item.field(ItemField.USERNAME)?.takeIf { it.isNotBlank() }?.let { user ->
                ReadOnlyField("Username", user, trailing = {
                    IconButton(onClick = { onCopy(user, "Username") }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy username")
                    }
                })
            }

            val password = item.field(ItemField.PASSWORD).orEmpty()
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
            Text(
                if (revealed) "Tap the eye again to hide it." else "Hidden until you choose to show it.",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            item.field(ItemField.URL)?.takeIf { it.isNotBlank() }?.let {
                ReadOnlyField("Website", it)
            }
            item.field(ItemField.NOTE)?.takeIf { it.isNotBlank() }?.let {
                ReadOnlyField("Note", it)
            }

            Spacer(Modifier.height(8.dp))
            SecondaryButton("Edit", onEdit, Modifier.padding(bottom = 10.dp))
            SecondaryButton("Delete this password", onDelete, danger = true)
            Spacer(Modifier.height(24.dp))
        }
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

/** Readable and strong: no ambiguous characters, grouped for reading aloud. */
fun generatePassword(groups: Int = 4, size: Int = 4): String {
    val alphabet = "abcdefghijkmnopqrstuvwxyz23456789ACDEFGHJKLMNPQRSTUVWXYZ"
    val random = java.security.SecureRandom()
    return (0 until groups).joinToString("-") {
        (0 until size).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}

@Composable
fun ItemEditScreen(
    item: ItemRecord?,
    state: VaultState,
    defaultKeyringId: String,
    onBack: () -> Unit,
    onSave: (String?, String, Map<ItemField, String>) -> Unit,
) {
    var title by remember { mutableStateOf(item?.field(ItemField.TITLE).orEmpty()) }
    var username by remember { mutableStateOf(item?.field(ItemField.USERNAME).orEmpty()) }
    var password by remember { mutableStateOf(item?.field(ItemField.PASSWORD).orEmpty()) }
    var url by remember { mutableStateOf(item?.field(ItemField.URL).orEmpty()) }
    var note by remember { mutableStateOf(item?.field(ItemField.NOTE).orEmpty()) }
    var keyringId by remember { mutableStateOf(item?.keyring?.value ?: defaultKeyringId) }
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
                    TextButton(onClick = { password = generatePassword() }) { Text("Suggest") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "A suggested password is easy to read aloud over the phone.",
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
                        label = { Text(r.name.value) },
                    )
                }
            }
            Text(
                "Everyone on a keyring can see everything on it. You share a keyring, never one password.",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColors.muted,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            PrimaryButton(
                "Save",
                onClick = {
                    onSave(
                        item?.id,
                        keyringId,
                        mapOf(
                            ItemField.TITLE to title.trim(),
                            ItemField.USERNAME to username,
                            ItemField.PASSWORD to password,
                            ItemField.URL to url,
                            ItemField.NOTE to note,
                        ),
                    )
                },
                enabled = canSave,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
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
) {
    var name by remember { mutableStateOf("") }
    val statusColors = LocalKeywebStatus.current
    val rings = state.keyrings.values.filter { !it.deleted.value }

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

            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    rings.forEach { r ->
                        val count = items.count { it.keyring.value == r.id }
                        VaultRow(
                            initials = "●",
                            title = r.name.value,
                            subtitle = "$count password${if (count == 1) "" else "s"} · only you",
                            onClick = {},
                            accent = ringColor(state, r.id),
                        )
                        HorizontalDivider(color = statusColors.line)
                    }
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
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        PrimaryButton(text, onClick, Modifier.padding(bottom = 8.dp))
    } else {
        SecondaryButton(text, onClick, Modifier.padding(bottom = 8.dp))
    }
}

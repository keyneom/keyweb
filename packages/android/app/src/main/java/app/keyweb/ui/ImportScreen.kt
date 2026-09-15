package app.keyweb.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.keyweb.ImportStage
import app.keyweb.ImportUiState
import app.keyweb.UngroupedDestination
import app.keyweb.vault.kdbx.suggestedKeyringName
import java.text.DateFormat
import java.util.Date

/**
 * Bringing a KeePass file in, on the phone.
 *
 * The list is not something Keyweb keeps — it is what Google says this account
 * has handed over. That is why a file picked in a browser appears here without
 * anything being synced, and why the same list shows on every device signed
 * into the same account.
 *
 * Nothing is written to the vault until the last step, and nothing at all is
 * ever written to the KeePass file.
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpen: (String) -> Unit,
    onLocalFile: (Uri) -> Unit,
    onUnlock: (String) -> Unit,
    onUngroupedDestination: (UngroupedDestination) -> Unit,
    onConfirm: () -> Unit,
) {
    // A .kdbx has no registered media type, and providers label it variously as
    // octet-stream or nothing at all. Filtering by type would hide the very
    // file being looked for, so the filter is left open.
    val localFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onLocalFile) }
    val status = LocalKeywebStatus.current
    var password by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Bring in your KeePass passwords", style = MaterialTheme.typography.titleLarge)

            state.error?.let {
                StatusLine(Tone.RISK, "That didn't work.", it, Modifier.padding(vertical = 12.dp))
            }

            when (state.stage) {
                ImportStage.CHOOSING -> Choosing(
                    state = state,
                    onRefresh = onRefresh,
                    onGrantAccess = onGrantAccess,
                    onOpen = onOpen,
                    onBrowseThisPhone = { localFile.launch(arrayOf("*/*")) },
                )

                ImportStage.PASSWORD -> {
                    Text(
                        "Reading ${state.openingName}. Enter the master password you use to " +
                            "open it.",
                        color = status.muted,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(14.dp),
                        // Enter submits. Without this the key falls through to
                        // the back handler and walks out of the import.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (!state.busy && password.isNotEmpty()) {
                                    onUnlock(password)
                                    password = ""
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Used once to open the file. Keyweb doesn't keep it.",
                        color = status.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    PrimaryButton(
                        if (state.busy) "Opening…" else "Open the file",
                        { onUnlock(password); password = "" },
                        Modifier.fillMaxWidth(),
                        enabled = !state.busy && password.isNotEmpty(),
                    )
                }

                ImportStage.PREVIEW -> {
                    // Nothing has been copied yet. A bulk change nobody has seen
                    // the shape of is not something to ask anyone to trust.
                    Text(
                        "Found ${state.entryCount} passwords in ${state.keyringNames.size} " +
                            "folders. Nothing has been copied yet.",
                        color = status.muted,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            for (name in state.keyringNames) {
                                Text(
                                    "$name  ·  becomes a keyring",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    if (state.ungrouped > 0) {
                        UngroupedChoice(
                            state = state,
                            onChoose = onUngroupedDestination,
                        )
                    }

                    if (state.skipped > 0) {
                        Text(
                            "${state.skipped} empty or deleted " +
                                (if (state.skipped == 1) "entry was" else "entries were") +
                                " skipped, along with anything in your KeePass recycle bin.",
                            color = status.muted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    PrimaryButton(
                        if (state.busy) "Copying…" else "Copy these ${state.entryCount} in",
                        onConfirm,
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        enabled = !state.busy,
                    )
                }

                ImportStage.DONE -> {
                    StatusLine(
                        Tone.SAFE,
                        "${state.importedCount} passwords are now in Keyweb.",
                        "Your folders came across as keyrings. Your KeePass file is " +
                            "untouched — Keyweb only ever read it.",
                        Modifier.padding(vertical = 12.dp),
                    )
                    Text(
                        "If you keep using KeePass, bring the same file in again whenever you " +
                            "like. Keyweb updates what changed instead of making copies.",
                        color = status.muted,
                    )
                    PrimaryButton(
                        "See my passwords",
                        onBack,
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Choosing(
    state: ImportUiState,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit,
    onOpen: (String) -> Unit,
    onBrowseThisPhone: () -> Unit,
) {
    val status = LocalKeywebStatus.current
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Text(
        "Keyweb can read a KeePass or KeeWeb file — the kind ending in .kdbx. " +
            "Your folders and tags come across with it.",
        color = status.muted,
        modifier = Modifier.padding(vertical = 10.dp),
    )

    if (state.busy && state.files.isEmpty()) {
        CircularProgressIndicator(Modifier.padding(vertical = 12.dp))
        Text("Asking Google Drive…", color = status.muted)
        return
    }

    if (state.files.isNotEmpty()) {
        Text("KeePass files in your Google Drive", fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column {
                for (file in state.files) {
                    VaultRow(
                        initials = "KP",
                        title = file.name,
                        subtitle = file.modifiedAtMs
                            ?.let { "Changed ${dates.format(Date(it))}" }
                            ?: "In your Drive",
                        onClick = { onOpen(file.fileId) },
                    )
                }
            }
        }
        Text(
            "Keyweb reads these straight out of Drive, so you can bring in changes as often " +
                "as you like. This list comes from Google, not from this phone — it is the " +
                "same one the website shows.",
            color = status.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    } else {
        StatusLine(
            Tone.CALM,
            "No KeePass files yet.",
            "Google only lets Keyweb see files you have pointed it at. Choosing one takes " +
                "you to your browser for a moment, and then it stays available here.",
            Modifier.padding(vertical = 10.dp),
        )
    }

    PrimaryButton("Choose a file from Google Drive", onGrantAccess, Modifier.fillMaxWidth())
    Text(
        // Said plainly, because being sent to a browser mid-task looks like
        // something has gone wrong unless it is explained first.
        "Google's file chooser only runs in a browser, so Keyweb opens one. Pick your file " +
            "there, then come back — it will be waiting in the list.",
        color = status.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
    )
    SecondaryButton("I've picked it — check again", onRefresh, Modifier.fillMaxWidth())

    Spacer(Modifier.height(20.dp))
    Text("Or open a file on this phone", fontWeight = FontWeight.SemiBold)
    Text(
        "Anywhere this phone can reach — Downloads, a memory card, or another app's " +
            "storage. A file opened this way is read once; it won't appear on your other " +
            "devices the way a Drive file does.",
        color = status.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    SecondaryButton("Browse this phone", onBrowseThisPhone, Modifier.fillMaxWidth())

    StatusLine(
        Tone.SAFE,
        "Keyweb only ever reads your KeePass file.",
        "It is opened, copied from, and left exactly as it was. Keyweb cannot write to it.",
        Modifier.padding(top = 14.dp),
    )
}

/**
 * Where the passwords that are in no folder should go.
 *
 * Asked rather than guessed. In a KeePass file these sit loose at the top with
 * no group of their own, and Keyweb has to put them somewhere — so the choice
 * is the user's, made while they can see how many are involved. The suggested
 * name is the file's, because that is what they call this set of passwords and
 * it is already on the screen above.
 */
@Composable
private fun UngroupedChoice(
    state: ImportUiState,
    onChoose: (UngroupedDestination) -> Unit,
) {
    val status = LocalKeywebStatus.current
    val choice = state.ungroupedDestination
    val plural = if (state.ungrouped == 1) "" else "s"

    HorizontalDivider(Modifier.padding(top = 18.dp, bottom = 14.dp))

    Text(
        "${state.ungrouped} password$plural ${if (state.ungrouped == 1) "isn't" else "aren't"} " +
            "in a folder",
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        "In your KeePass file these sit loose at the top rather than inside a folder. Keyweb " +
            "keeps every password in a keyring, so choose where these should go.",
        color = status.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )

    // Radio rows rather than a dropdown: there are rarely more than a handful
    // of keyrings, and a list you can see beats one you have to open.
    DestinationRow(
        label = "A new keyring",
        selected = choice is UngroupedDestination.New,
        onSelect = { onChoose(UngroupedDestination.New(suggestedKeyringName(state.openingName))) },
    )

    if (choice is UngroupedDestination.New) {
        OutlinedTextField(
            value = choice.name,
            onValueChange = { onChoose(UngroupedDestination.New(it)) },
            label = { Text("Name the new keyring") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 34.dp, top = 4.dp, bottom = 4.dp),
        )
        Text(
            "Named after your file to start with. Change it if you like.",
            color = status.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 34.dp, bottom = 6.dp),
        )
    }

    for ((id, name) in state.existingKeyrings) {
        DestinationRow(
            label = name,
            selected = choice is UngroupedDestination.Existing && choice.keyringId == id,
            onSelect = { onChoose(UngroupedDestination.Existing(id)) },
        )
    }
}

@Composable
private fun DestinationRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target, not just the button: a 20dp circle
            // is a poor thing to ask anyone to hit, and worse with shaky hands.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 10.dp))
    }
}

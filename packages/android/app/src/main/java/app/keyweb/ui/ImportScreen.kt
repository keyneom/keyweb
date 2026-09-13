package app.keyweb.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.keyweb.ImportStage
import app.keyweb.ImportUiState
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
    onUnlock: (String) -> Unit,
    onConfirm: () -> Unit,
) {
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

    StatusLine(
        Tone.SAFE,
        "Keyweb only ever reads your KeePass file.",
        "It is opened, copied from, and left exactly as it was. Keyweb cannot write to it.",
        Modifier.padding(top = 14.dp),
    )
}

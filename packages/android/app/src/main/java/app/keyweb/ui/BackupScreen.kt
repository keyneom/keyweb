package app.keyweb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keyweb.BackupStage
import app.keyweb.BackupUiState

/**
 * Setting up the encrypted backup.
 *
 * Written for someone who is not thinking about cryptography and should not
 * have to. Every screen says what will happen in one sentence, and the one
 * screen that matters — the recovery code — refuses to move on casually,
 * because it is shown exactly once and cannot be recovered afterwards.
 */
@Composable
fun BackupScreen(
    backup: BackupUiState,
    lastBackedUp: String?,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onUseExistingCode: (String) -> Unit,
    onCodeWrittenDown: () -> Unit,
) {
    val muted = LocalKeywebStatus.current.muted

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // The code screen deliberately has no way back: leaving without
            // writing it down loses it permanently.
            if (backup.stage != BackupStage.SHOW_CODE) {
                TextButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(" Back", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }

            backup.error?.let {
                StatusLine(
                    Tone.RISK,
                    "That didn't work.",
                    it,
                    Modifier.padding(bottom = 14.dp),
                )
            }

            when (backup.stage) {
                BackupStage.OFF, BackupStage.CONNECTING -> Intro(
                    busy = backup.stage == BackupStage.CONNECTING || backup.busy,
                    onStart = onStart,
                    muted = muted,
                )

                BackupStage.NEEDS_CODE -> ExistingCode(
                    busy = backup.busy,
                    onSubmit = onUseExistingCode,
                    muted = muted,
                )

                BackupStage.SHOW_CODE -> ShowCode(
                    code = backup.newCode.orEmpty(),
                    onDone = onCodeWrittenDown,
                    muted = muted,
                )

                BackupStage.ON -> AlreadyOn(lastBackedUp = lastBackedUp, muted = muted)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Intro(busy: Boolean, onStart: () -> Unit, muted: androidx.compose.ui.graphics.Color) {
    Text("Back up your passwords", style = MaterialTheme.typography.titleLarge)
    Text(
        "Keyweb keeps a copy in your own Google Drive, so if this phone is lost " +
            "or broken you can get your passwords back.",
        color = muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
    )

    StatusLine(
        Tone.SAFE,
        "Only you can read it.",
        "Keyweb locks the copy before it leaves this phone. Google stores it " +
            "but cannot open it, and neither can we.",
        Modifier.padding(bottom = 12.dp),
    )
    StatusLine(
        Tone.CALM,
        "You'll get a recovery code.",
        "A short code to write down and keep somewhere safe. It's how you get " +
            "back in if you ever lose this phone.",
        Modifier.padding(bottom = 20.dp),
    )

    if (busy) {
        CircularProgressIndicator(Modifier.padding(vertical = 8.dp))
        Text("Connecting to Google…", color = muted)
    } else {
        PrimaryButton("Turn on backup", onStart, Modifier.fillMaxWidth())
        Text(
            "Google will ask you to choose an account and allow Keyweb to use Drive.",
            color = muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun ExistingCode(
    busy: Boolean,
    onSubmit: (String) -> Unit,
    muted: androidx.compose.ui.graphics.Color,
) {
    var typed by remember { mutableStateOf("") }

    Text("Enter your recovery code", style = MaterialTheme.typography.titleLarge)
    Text(
        "This Google account already has a Keyweb backup. Type the recovery code " +
            "you wrote down when you set it up, and this phone will join it.",
        color = muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text("Recovery code") },
        placeholder = { Text("H7K2-9MNP-4RTV-8XZ3-QWC6-JD5F-P2TM-6BKX") },
        singleLine = false,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Dashes and capitals don't matter. There is no letter O, I, L or U in a " +
            "recovery code, so anything that looks like one is a 0 or a 1 — and " +
            "Keyweb takes it either way.",
        color = muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    PrimaryButton(
        if (busy) "Checking…" else "Join this backup",
        { onSubmit(typed) },
        Modifier.fillMaxWidth(),
        enabled = !busy && typed.isNotBlank(),
    )

    // Said plainly rather than hidden, because the alternative -- minting a new
    // code here -- would quietly stop the written-down one from working.
    StatusLine(
        Tone.ATTENTION,
        "Don't have the code?",
        "Then this phone can't join that backup. Your passwords here are still " +
            "safe and still work; only the Drive copy needs the code.",
        Modifier.padding(top = 20.dp),
    )
}

@Composable
private fun ShowCode(
    code: String,
    onDone: () -> Unit,
    muted: androidx.compose.ui.graphics.Color,
) {
    var confirmed by remember { mutableStateOf(false) }

    Text("Write this down now", style = MaterialTheme.typography.titleLarge)
    Text(
        "This is the only time Keyweb can show you this code. It's what gets " +
            "your passwords back if you lose this phone.",
        color = muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            // Four groups a line, so it can be copied onto paper without
            // losing your place.
            codeGlyphs(code.split("-").chunked(4).joinToString("\n") { it.joinToString("-") }),
            fontFamily = FontFamily.Monospace,
            fontSize = 22.sp,
            lineHeight = 36.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
        )
    }

    CodeLegend(Modifier.padding(top = 10.dp))

    StatusLine(
        Tone.RISK,
        "Keep it away from your phone.",
        "A drawer at home, or with your important papers. Not a photo on this " +
            "phone -- if the phone is gone, so is the photo.",
        Modifier.padding(top = 14.dp, bottom = 8.dp),
    )

    ChoiceRow(
        text = "I've written it down and put it somewhere safe",
        selected = confirmed,
        onClick = { confirmed = !confirmed },
    )

    PrimaryButton(
        "Finish",
        onDone,
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = confirmed,
    )
}

@Composable
private fun AlreadyOn(lastBackedUp: String?, muted: androidx.compose.ui.graphics.Color) {
    Text("Backup is on", style = MaterialTheme.typography.titleLarge)
    StatusLine(
        Tone.SAFE,
        "Your passwords are backed up.",
        lastBackedUp?.let { "Last backed up $it." }
            ?: "Keyweb backs them up whenever you make a change.",
        Modifier.padding(top = 12.dp, bottom = 12.dp),
    )
    Text(
        "Keep your recovery code somewhere safe. Keyweb can't show it again, and " +
            "without it nobody -- including us -- can open your backup.",
        color = muted,
    )
}

/** A tappable confirmation that reads as a sentence rather than a tiny box. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        PrimaryButton("✓  $text", onClick, Modifier.fillMaxWidth())
    } else {
        SecondaryButton(text, onClick, Modifier.fillMaxWidth())
    }
}

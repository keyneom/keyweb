package app.keyweb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keyweb.VaultPhase

/**
 * The unlock gate.
 *
 * For a returning person the biometric sheet is raised on entry: opening the
 * app is already the request, and the only way past this screen is that sheet,
 * so asking them to press a button first is asking twice.
 *
 * The button stays regardless, and is not decoration. Once the sheet has been
 * dismissed it is the only way back — the prompt cannot simply be raised again,
 * or cancelling it would summon it afresh and the app could not be put down.
 * It is also what first run uses, where the prompt would be creating the key
 * rather than checking one, and the text explaining that deserves to be read
 * before a system dialog covers it.
 */
@Composable
fun UnlockScreen(
    phase: VaultPhase,
    firstRun: Boolean,
    error: String?,
    onUnlock: () -> Unit,
) {
    val statusColors = LocalKeywebStatus.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (phase == VaultPhase.UNSUPPORTED) {
                StatusLine(
                    Tone.RISK,
                    "Keyweb needs a screen lock.",
                    "Set a PIN, pattern, password or fingerprint in your phone's settings, " +
                        "then come back. Without one, there is no safe place to keep the key.",
                )
                return@Column
            }

            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = statusColors.brass,
                modifier = Modifier.size(52.dp).padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(12.dp))

            Text(
                if (firstRun) "Set up Keyweb" else "Welcome back",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (firstRun) {
                    "Keyweb locks your passwords with the same face, fingerprint or PIN you " +
                        "use to unlock this phone. Nothing new to remember."
                } else {
                    "Unlock your passwords with your face, fingerprint or PIN."
                },
                color = statusColors.muted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(20.dp))

            if (error != null) {
                StatusLine(Tone.ATTENTION, error, "You can try again below.")
                Spacer(Modifier.height(16.dp))
            }

            PrimaryButton(
                text = when {
                    phase == VaultPhase.UNLOCKING -> "Waiting for you…"
                    firstRun -> "Set up Keyweb"
                    else -> "Unlock"
                },
                onClick = onUnlock,
                enabled = phase != VaultPhase.UNLOCKING,
            )

            if (firstRun) {
                Spacer(Modifier.height(20.dp))
                StatusLine(
                    Tone.SAFE,
                    "Your passwords are scrambled on this phone.",
                    "The key lives in this device's secure hardware and never leaves it. " +
                        "Without your face, fingerprint or PIN, what's stored here is unreadable.",
                )
            }

            Spacer(Modifier.height(24.dp).align(Alignment.CenterHorizontally))
        }
    }
}

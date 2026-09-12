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
 * Deliberately does not raise the biometric sheet on launch. A system dialog
 * appearing before anyone has asked for one reads as something going wrong; the
 * button says what will happen, then it happens.
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

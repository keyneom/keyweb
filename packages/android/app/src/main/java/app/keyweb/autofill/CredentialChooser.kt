package app.keyweb.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyweb.ui.LocalKeywebStatus
import app.keyweb.ui.SecondaryButton
import app.keyweb.vault.Fields
import app.keyweb.vault.ItemRecord
import app.keyweb.vault.field

/**
 * Who is asking, what they would get, and a deliberate tap to allow it.
 *
 * Every word here is chosen so somebody can refuse. That means naming the
 * recipient before the password, saying plainly when Keyweb is only guessing
 * who the recipient is, and never pre-selecting anything — a screen that can
 * only be agreed with is not a confirmation.
 */
@Composable
fun CredentialChooser(
    asking: String,
    /** True when nothing identified the caller but its own package name. */
    guessed: Boolean,
    items: List<ItemRecord>,
    onPick: (ItemRecord) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalKeywebStatus.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                if (items.size == 1) "Sign in to $asking?" else "Which one for $asking?",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Tap a password to hand it to the app that asked. Nothing leaves your vault " +
                    "until you do.",
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            /*
             * Said out loud rather than smoothed over.
             *
             * Android tells a non-privileged provider the caller's package name
             * and nothing else, so "chase.com" here is `com.chase` reversed —
             * a guess, and one the caller chose. Somebody who knows they are
             * not in the Chase app can stop; nobody can stop what they were
             * never shown.
             */
            if (guessed) {
                Text(
                    "Keyweb is going by the app's own name for itself, which any app can " +
                        "choose. If you didn't just tap sign in somewhere, close this.",
                    color = colors.attention,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    items.forEach { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(item) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                item.field(Fields.TITLE) ?: "Untitled",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            // The username, never the password. This screen
                            // exists to authorise handing one over, so showing
                            // it here would defeat the point of asking.
                            item.field(Fields.USERNAME)?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    color = colors.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        HorizontalDivider(color = colors.line)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton("Not now", onCancel)
        }
    }
}

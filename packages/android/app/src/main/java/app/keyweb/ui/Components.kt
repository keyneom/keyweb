package app.keyweb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keyweb.vault.SyncStatus

enum class Tone { SAFE, ATTENTION, RISK, CALM }

/**
 * Backup state as a sentence, never a coloured dot.
 *
 * The rule this holds: we only say "backed up" when every saved edit is
 * provably in a published revision. Anything weaker gets a sentence saying what
 * is true and what happens next. The icon carries the same meaning as the
 * colour, so this reads correctly without colour vision.
 */
@Composable
fun StatusLine(
    tone: Tone,
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    /** Text for an action inside the card. Null leaves it as a plain sentence. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val status = LocalKeywebStatus.current
    val (fg, bg, icon) = when (tone) {
        Tone.SAFE -> Triple(status.safe, status.safeContainer, Icons.Filled.Shield)
        Tone.ATTENTION -> Triple(status.attention, status.attentionContainer, Icons.Filled.Schedule)
        Tone.RISK -> Triple(status.risk, status.riskContainer, Icons.Filled.Warning)
        Tone.CALM -> Triple(
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Filled.CloudQueue,
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(14.dp),
        border = if (tone == Tone.CALM) null else BorderStroke(1.dp, fg),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, Modifier.size(22.dp))
            Column {
                Text(
                    headline,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                // Telling someone what is wrong without telling them where to
                // go is the same as not telling them.
                if (actionLabel != null && onAction != null) {
                    PrimaryButton(
                        actionLabel,
                        onAction,
                        Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** Maps sync state to the one fixed sentence for that state. */
@Composable
fun BackupStatusLine(
    status: SyncStatus,
    backupConfigured: Boolean,
    modifier: Modifier = Modifier,
    onSetUpBackup: (() -> Unit)? = null,
) {
    val error = status.lastError
    val published = status.lastPublishedAtMs
    when {
        !backupConfigured -> StatusLine(
            Tone.ATTENTION,
            "Saved on this phone only.",
            "If you lose this phone, these passwords go with it. Backing up to " +
                "your Google Drive keeps a copy only you can open.",
            modifier,
            actionLabel = "Set up backup",
            onAction = onSetUpBackup,
        )

        status.pending > 0 -> StatusLine(
            Tone.ATTENTION,
            "Saved on this phone. ${changes(status.pending)} still to back up.",
            error ?: "We'll back them up as soon as we can reach your backup.",
            modifier,
        )

        error != null -> StatusLine(
            Tone.RISK,
            "Everything is saved here, but backup had a problem.",
            error,
            modifier,
        )

        published == null -> StatusLine(
            Tone.CALM,
            "Saved on this phone.",
            "Nothing has been backed up yet.",
            modifier,
        )

        else -> StatusLine(
            Tone.SAFE,
            "Saved here and backed up.",
            "Last checked ${relativeTime(published)}.",
            modifier,
        )
    }
}

private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"

fun relativeTime(thenMs: Long): String {
    val seconds = ((System.currentTimeMillis() - thenMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} minute${plural(seconds / 60)} ago"
        seconds < 86_400 -> "${seconds / 3600} hour${plural(seconds / 3600)} ago"
        else -> "${seconds / 86_400} day${plural(seconds / 86_400)} ago"
    }
}

private fun plural(n: Long) = if (n == 1L) "" else "s"

/** Primary action. Full width and comfortably taller than the tap minimum. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val scale = LocalKeywebScale.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = scale.tap + 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp))
            Text("  ")
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val scale = LocalKeywebScale.current
    val status = LocalKeywebStatus.current
    val color = if (danger) status.risk else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, if (danger) status.risk else status.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = scale.tap + 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A tappable row. The whole row is the target, never just a chevron. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultRow(
    initials: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accent: Color? = null,
    onLongClick: (() -> Unit)? = null,
    /** Null when not selecting at all, which differs from selected = false. */
    selected: Boolean? = null,
) {
    val scale = LocalKeywebScale.current
    val status = LocalKeywebStatus.current
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = scale.row)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (selected == true) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected != null) {
            // In the avatar's place, so switching modes does not shift the
            // text sideways under the reader's eye.
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        accent ?: MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initials,
                    color = if (accent != null) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                subtitle,
                color = status.muted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

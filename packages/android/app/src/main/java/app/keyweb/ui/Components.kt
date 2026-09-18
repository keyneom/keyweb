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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keyweb.BackupFileChoice
import app.keyweb.vault.SyncStatus

enum class Tone { SAFE, ATTENTION, RISK, CALM }

/** One option on a status card that asks somebody to choose. */
data class StatusChoice(val label: String, val detail: String, val onPick: () -> Unit)

/**
 * When something last changed, in words.
 *
 * Somebody choosing between two vaults is deciding which date looks like the
 * last time they used the app. "2 hours ago" answers that; a timestamp makes
 * them do arithmetic while worried.
 */
fun whenChanged(atMs: Long?): String {
    if (atMs == null || atMs <= 0) return "at some point"
    val minutes = (System.currentTimeMillis() - atMs) / 60_000L
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 1440 -> "${minutes / 60} hour" + (if (minutes / 60 == 1L) "" else "s") + " ago"
        minutes < 2880 -> "yesterday"
        else -> "${minutes / 1440} days ago"
    }
}

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
    /**
     * False greys the action out rather than removing it.
     *
     * A control that disappears while it is working is worse than one that is
     * merely disabled: the card reflows, and the thing the person just pressed
     * is no longer where they pressed it.
     */
    actionEnabled: Boolean = true,
    /**
     * Several things to pick between, rather than one thing to do.
     *
     * A card that says "there are two of these and I won't choose" has to
     * carry the choice, or it is a true statement somebody can do nothing
     * with.
     */
    choices: List<StatusChoice> = emptyList(),
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

                choices.forEach { choice ->
                    SecondaryButton(
                        choice.label + "  ·  " + choice.detail,
                        choice.onPick,
                        Modifier.padding(top = 6.dp),
                    )
                }
                // Telling someone what is wrong without telling them where to
                // go is the same as not telling them.
                //
                // Weighted by the card's own urgency: when something needs
                // doing the action is a filled button, and when everything is
                // already fine it is an offer, not an instruction. A primary
                // button inside a "backed up" card asks for attention that the
                // state does not deserve, and spends it where it is not needed.
                if (actionLabel != null && onAction != null) {
                    if (tone == Tone.SAFE) {
                        TextButton(
                            onClick = onAction,
                            modifier = Modifier.padding(top = 2.dp),
                            enabled = actionEnabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = fg),
                        ) {
                            Text(actionLabel, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        PrimaryButton(
                            actionLabel,
                            onAction,
                            Modifier.padding(top = 10.dp),
                            enabled = actionEnabled,
                        )
                    }
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
    /**
     * Back up on demand.
     *
     * Syncing is automatic after every edit, but "automatic" is not the same
     * as "visibly finished" — and when the last attempt failed, or another
     * device has changes this one has not seen, waiting is the one thing a
     * person cannot do anything with. The action belongs on this line rather
     * than in Settings because this is where the doubt is: it is the sentence
     * that just said something was not backed up yet.
     */
    onSync: (() -> Unit)? = null,
    /** Overwrite a backup this vault cannot read with this device's copy. */
    onReplaceBackup: (() -> Unit)? = null,
    /** True when the backup has no passkey copy this device can open. */
    phoneOnly: Boolean = false,
    onAddPasskey: (() -> Unit)? = null,
    /** Vault files to choose between, when the account holds more than one. */
    backupFiles: List<BackupFileChoice> = emptyList(),
    onChooseBackupFile: ((String) -> Unit)? = null,
) {
    val error = status.lastError
    val published = status.lastPublishedAtMs
    // A run already in flight must not be able to start a second, but the
    // button stays put and greys out rather than vanishing under the finger.
    val label = { text: String -> if (status.syncing) "Checking…" else text }
    val sync = onSync
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
            actionLabel = label("Back up now"),
            onAction = sync,
            actionEnabled = !status.syncing,
        )

        /*
         * Before the generic error branch, because "Try again" is exactly the
         * wrong offer here: the next attempt reads the same unreadable file
         * and fails the same way. The only thing that helps is replacing it,
         * and that is destructive enough to be named rather than implied.
         */
        /*
         * Said where the doubt is, and only while it is true.
         *
         * A backup with no passkey copy opens on this phone and nowhere else:
         * a browser needs the printed code every single time and never sees a
         * change made here. That is not a setting somebody should have to go
         * looking for under a name describing how it works — it is a thing
         * that is wrong with their backup, so it says what is wrong, in the
         * terms they would notice it in, and offers the one action that fixes
         * it. Then it goes away for good.
         */
        /*
         * Which of these is your vault?
         *
         * Before the other backup states, because until it is answered none of
         * them mean anything: each file is a whole vault sealed on its own, so
         * "backed up" is a claim about one of two different things. They
         * cannot be merged and the names are identical, so the date is the
         * only thing anybody can choose on.
         */
        backupFiles.size > 1 -> StatusLine(
            Tone.RISK,
            "There are ${backupFiles.size} Keyweb backups in this Google account.",
            "These are separate vaults, not parts of one, so Keyweb won't merge them or pick " +
                "for you. The most recently changed is usually the one you want.",
            modifier,
            choices = backupFiles
                .sortedByDescending { it.modifiedAtMs ?: 0L }
                .map { file ->
                    StatusChoice(
                        label = "Last changed " + whenChanged(file.modifiedAtMs),
                        detail = file.fileId.takeLast(6),
                        onPick = { onChooseBackupFile?.invoke(file.fileId) },
                    )
                },
        )

        phoneOnly && onAddPasskey != null -> StatusLine(
            Tone.ATTENTION,
            "This backup only opens on this phone.",
            "Keyweb can use the same face or fingerprint for the copy in Google Drive that a " +
                "browser does. Without it, opening your passwords on a computer needs your " +
                "recovery code every time, and changes you make here never show up there.",
            modifier,
            actionLabel = label("Fix this"),
            onAction = onAddPasskey,
            actionEnabled = !status.syncing,
        )

        status.backupUnreadable -> StatusLine(
            Tone.RISK,
            "The backup in Drive isn't this phone's.",
            (error ?: "This phone's code doesn't open it.") +
                " Everything on this phone is fine and unchanged. You can put this phone's " +
                "copy back — which replaces whatever is in Drive now.",
            modifier,
            actionLabel = label("Replace the backup"),
            onAction = onReplaceBackup,
            actionEnabled = !status.syncing && onReplaceBackup != null,
        )

        error != null -> StatusLine(
            Tone.RISK,
            "Everything is saved here, but backup had a problem.",
            error,
            modifier,
            actionLabel = label("Try again"),
            onAction = sync,
            actionEnabled = !status.syncing,
        )

        published == null -> StatusLine(
            Tone.CALM,
            "Saved on this phone.",
            "Nothing has been backed up yet.",
            modifier,
            actionLabel = label("Back up now"),
            onAction = sync,
            actionEnabled = !status.syncing,
        )

        else -> StatusLine(
            Tone.SAFE,
            "Saved here and backed up.",
            "Last checked ${relativeTime(published)}.",
            modifier,
            // Nothing is waiting to go up, but a check also brings down
            // whatever another device has published since.
            actionLabel = label("Check now"),
            onAction = sync,
            actionEnabled = !status.syncing,
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

/**
 * A section that stays out of the way until somebody asks for it.
 *
 * The app has grown a lot of capability — files, one-time codes, fields you
 * name yourself, earlier values, sharing — and all of it arrived on the same
 * two screens. The answer is deliberately *not* an "advanced mode" switch in
 * settings: a mode is invisible state, so somebody who flipped it last month
 * meets a different app than the one they learned, somebody who never finds it
 * never gets the feature, and every screen has to be designed twice.
 *
 * Disclosure in place costs one tap, is discoverable exactly where it is
 * relevant, and leaves the common path as short as it was. The label says what
 * is inside in the words the person would use, never "Advanced" — that is a
 * word that tells somebody the thing they are looking for is not for them.
 *
 * Closed by default, but [initiallyOpen] opens it for an item that already has
 * something in there: hiding a field somebody can see today, on the grounds
 * that it is advanced, is how the fields went missing in the first place.
 */
@Composable
fun Disclosure(
    label: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    /** A line under the label, for saying what is inside before it is opened. */
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(label) { mutableStateOf(initiallyOpen) }
    val colors = LocalKeywebStatus.current

    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 12.dp),
        ) {
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (hint != null && !open) {
                    Text(
                        hint,
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (open) content()
    }
}

/**
 * Choosing how a list is ordered.
 *
 * One control, not a field and a direction arrow. "Descending" means nothing
 * until you also know what it applies to, so each ordering names both of its
 * ends — "Name (A–Z)" and "Name (Z–A)" — and what will happen is legible
 * before it happens.
 *
 * It shows the *current* ordering rather than the word "Sort", so a list that
 * is not in the order somebody expected explains itself without being asked.
 */
@Composable
fun SortPicker(
    current: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: current

    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.SwapVert, contentDescription = null)
            Text(" $label")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                    leadingIcon = {
                        if (value == current) {
                            Icon(Icons.Filled.Check, contentDescription = "Current order")
                        }
                    },
                )
            }
        }
    }
}

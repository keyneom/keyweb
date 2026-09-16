package app.keyweb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keyweb.sharing.Member
import app.keyweb.PendingShareInvite
import app.keyweb.ShareStage
import app.keyweb.ShareUiState
import com.keyneom.synckit.sharing.SharingRole

/**
 * The sharing screens.
 *
 * Counterparts of `packages/web/src/screens/ShareKeyring.tsx`,
 * `JoinShare.tsx` and `AcceptShare.tsx`. The copy is deliberately the same
 * where the situation is the same: a share has two ends, they are frequently on
 * different platforms, and two people comparing what their screens say is how
 * the one attack this design allows gets caught.
 *
 * The copy also avoids promising more than the cryptography delivers. Sharing
 * hands over passwords, and taking access away later cannot take back what
 * somebody already read — so the screen says that at the moment of revoking,
 * rather than in small print nobody reaches.
 */

@Composable
fun ShareKeyringScreen(
    keyringName: String,
    share: ShareUiState,
    onBack: () -> Unit,
    onInvite: (String, SharingRole) -> Unit,
    onCancelInvite: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onStopSharing: () -> Unit,
    onLeave: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = LocalKeywebStatus.current
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(SharingRole.VIEWER) }
    var revoking by remember { mutableStateOf<Member?>(null) }

    val others = share.members.filterNot { it.you }
    val you = share.members.firstOrNull { it.you }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }

            // A keyring somebody else shared. There is nothing here to invite
            // anyone to and nothing to revoke — the only thing this person can
            // decide is whether to keep carrying it.
            if (share.youOwnIt == false) {
                Text(keyringName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Somebody shared this keyring with you. " +
                        if (you?.role == SharingRole.VIEWER) {
                            "You can see the passwords in it. You can't change them."
                        } else {
                            "You can see the passwords in it, and add and change them."
                        },
                    color = colors.muted,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (others.isNotEmpty()) {
                    Text("Other people who can see it", style = MaterialTheme.typography.labelLarge)
                    MemberList(others, onRemove = null)
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "These passwords aren't yours to delete. Removing this keyring takes it " +
                        "off this phone only — the person who shared it keeps their copy, and " +
                        "so does everyone else they shared it with.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SecondaryButton("Remove it from my vault", onLeave)
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Text("Share $keyringName", style = MaterialTheme.typography.titleLarge)
            Text(
                "Everyone you share this keyring with sees every password in it, and any " +
                    "you add later.",
                color = colors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            share.error?.let {
                Text(
                    it,
                    color = colors.risk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            share.link?.let { link ->
                Text("Send them this link", style = MaterialTheme.typography.labelLarge)
                Text(
                    "They open it, choose the keyring in Google's file chooser, and send you a " +
                        "reply link back. You are not finished until you have opened their reply.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                PrimaryButton("Copy the link", { onCopy(link) })
                Spacer(Modifier.height(20.dp))
            }

            if (others.isNotEmpty()) {
                Text("People who can see it", style = MaterialTheme.typography.labelLarge)
                MemberList(others, onRemove = { revoking = it })
                Spacer(Modifier.height(20.dp))
            }

            if (share.pending.isNotEmpty()) {
                Text("Waiting for a reply", style = MaterialTheme.typography.labelLarge)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        share.pending.forEach { invite ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    VaultRow(
                                        initials = "…",
                                        title = invite.email,
                                        subtitle = "Hasn't sent their reply link back yet",
                                        onClick = {},
                                    )
                                }
                                TextButton(
                                    onClick = { onCancelInvite(invite.invitation.exchangeId) },
                                ) { Text("Cancel") }
                            }
                            HorizontalDivider(color = colors.line)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            EditField("Share it with someone", email, { email = it }, "their.name@gmail.com")
            Text(
                "It has to be the Google account they use, because that is how Google lets " +
                    "them at the file.",
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            RoleChoice("They can look", "They see the passwords. They can't change or add any.",
                selected = role == SharingRole.VIEWER) { role = SharingRole.VIEWER }
            RoleChoice("They can look and change",
                "They can add passwords and edit the ones that are here.",
                selected = role == SharingRole.WRITER) { role = SharingRole.WRITER }

            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                if (share.busy) "Getting it ready…" else "Make a link to send them",
                onClick = { onInvite(email.trim(), role) },
                enabled = email.isNotBlank() && !share.busy,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "Google never sees these passwords. The keyring is locked before it leaves " +
                    "your phone, and only the people here have a key to it.",
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (share.datasetId != null) {
                SecondaryButton("Stop sharing this keyring with everyone", onStopSharing)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    revoking?.let { member ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Stop sharing with ${member.email ?: "them"}?") },
            text = {
                Text(
                    "They stop seeing anything you change from now on. They have already " +
                        "seen the passwords in here, so change any that matter.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRevoke(member.keyId)
                    revoking = null
                }) {
                    Text("Yes, stop sharing with them", color = LocalKeywebStatus.current.risk)
                }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) { Text("Leave it as it is") }
            },
        )
    }
}

@Composable
private fun MemberList(members: List<Member>, onRemove: ((Member) -> Unit)?) {
    val colors = LocalKeywebStatus.current
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            members.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        VaultRow(
                            initials = if (member.role == SharingRole.OWNER) "★" else "●",
                            title = member.email ?: "Key ${member.fingerprint}",
                            subtitle = when (member.role) {
                                SharingRole.OWNER -> "Shared it with you"
                                SharingRole.VIEWER -> "Can look, can't change · key ${member.fingerprint}"
                                else -> "Can add and change · key ${member.fingerprint}"
                            },
                            onClick = {},
                        )
                    }
                    if (onRemove != null && member.role != SharingRole.OWNER) {
                        TextButton(onClick = { onRemove(member) }) {
                            Text("Remove", color = colors.risk)
                        }
                    }
                }
                HorizontalDivider(color = colors.line)
            }
        }
    }
}

@Composable
private fun RoleChoice(title: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = LocalKeywebStatus.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(hint, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Somebody has shared a keyring with you.
 *
 * Three steps, and only the middle one asks anything: Google's file chooser,
 * which runs in a browser because Android has no native `drive.file` UI. The
 * identity is unlocked *before* the browser opens, so the trip out and back
 * needs nothing further — a prompt arriving after the hand-off arrives when
 * attention has moved on, or after Android has discarded the process while the
 * browser was in front.
 */
@Composable
fun JoinShareScreen(
    invite: PendingShareInvite,
    share: ShareUiState,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
    onCopy: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalKeywebStatus.current
    val label = invite.label ?: "a keyring"

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            when (share.stage) {
                ShareStage.REPLY_READY -> {
                    Text("One last thing", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Send this reply back to ${invite.ownerEmail ?: "the person who invited " +
                            "you"}. Until they open it, $label won't appear here.",
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    share.link?.let { PrimaryButton("Copy the reply", { onCopy(it) }) }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Nothing has been handed over yet. This reply carries only your public " +
                            "key — the half that locks things, never the half that opens them.",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    SecondaryButton("I've sent it", onDone)
                }

                ShareStage.AWAITING_GRANT -> {
                    Text("Did the browser let you pick it?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Keyweb opened a browser so you could choose $label in Google's file " +
                            "chooser. Come back here when you have.",
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    share.error?.let {
                        Text(
                            it,
                            color = colors.risk,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    PrimaryButton(
                        if (share.busy) "Finishing…" else "I've picked it — carry on",
                        onClick = onFinish,
                        enabled = !share.busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Open the browser again", onContinue)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Not now", onDone)
                }

                else -> {
                    Text(
                        "${invite.ownerEmail ?: "Someone"} shared $label with you",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (invite.role == SharingRole.WRITER) {
                            "You'll be able to see the passwords in it, and add and change them."
                        } else {
                            "You'll be able to see the passwords in it. You won't be able to " +
                                "change them."
                        },
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    share.error?.let {
                        Text(
                            it,
                            color = colors.risk,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    PrimaryButton(
                        if (share.busy) "Confirming it's you…" else "Continue",
                        onClick = onContinue,
                        enabled = !share.busy,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Google only lets you grant one file at a time, and only in a browser — " +
                            "so Keyweb will open one, and you come straight back here.",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    SecondaryButton("Not now", onDone)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * They replied. This is the step that actually lets them in.
 *
 * The key fingerprint is on the screen rather than hidden, because the two
 * links travel over ordinary chat and the one thing an attacker on that channel
 * can do is substitute their own key. Six characters read out loud is the whole
 * defence, so it belongs where it can still be acted on.
 */
@Composable
fun AcceptShareScreen(share: ShareUiState, onRetry: () -> Unit, onDone: () -> Unit) {
    val colors = LocalKeywebStatus.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            val accepted = share.accepted
            if (accepted != null) {
                Text(
                    "${accepted.email} can see ${accepted.label} now",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "It will show up on their device the next time Keyweb saves. There is " +
                        "nothing else to send.",
                    color = colors.muted,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    "Their key is ${accepted.fingerprint}. If you want to be certain the reply " +
                        "came from them and not from someone who got hold of the link, ask them " +
                        "to read those six characters out. Keyweb shows them the same ones.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                PrimaryButton("Done", onDone)
            } else if (share.error != null) {
                Text("That didn't work", style = MaterialTheme.typography.titleLarge)
                Text(
                    share.error,
                    color = colors.risk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                PrimaryButton("Try again", onRetry)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Not now", onDone)
            } else {
                Text("Letting them in…", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Confirming it's you, then giving them the key.",
                    color = colors.muted,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

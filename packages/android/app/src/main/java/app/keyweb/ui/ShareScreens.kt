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
    onHandOver: (Member) -> Unit = {},
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
                MemberList(
                    others,
                    onRemove = { revoking = it },
                    onHandOver = if (you?.role == SharingRole.OWNER) {
                        { member -> onHandOver(member) }
                    } else {
                        null
                    },
                )
                share.handoverLink?.let { link ->
                    StatusLine(
                        tone = Tone.ATTENTION,
                        headline = "Send them this link",
                        detail = "Nothing changes until they open it. Until then you are still " +
                            "the owner, so there is no moment where the keyring belongs to " +
                            "nobody.",
                        modifier = Modifier.padding(top = 8.dp),
                        actionLabel = "Copy the link",
                        onAction = { onCopy(link) },
                    )
                }
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

            // Driven off the list of roles rather than written out, so a role
            // cannot exist in the protocol and be missing from the screen —
            // which is exactly how "admin" spent its life unoffered.
            SHARE_ROLES.forEach { choice ->
                RoleChoice(choice.label, choice.detail, selected = role == choice.role) {
                    role = choice.role
                }
            }

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
private fun MemberList(
    members: List<Member>,
    onRemove: ((Member) -> Unit)?,
    /**
     * Only the owner, and only for somebody who is not already one.
     *
     * Handing a keyring over is the one thing an admin cannot do: there is
     * exactly one owner, and it moves by a decision rather than by a
     * permission somebody else granted.
     */
    onHandOver: ((Member) -> Unit)? = null,
) {
    val colors = LocalKeywebStatus.current
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            members.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        VaultRow(
                            initials = if (member.role == SharingRole.OWNER) "★" else "●",
                            title = member.email ?: "Key ${member.fingerprint}",
                            subtitle = describeRole(member.role) +
                                " · key ${member.fingerprint}",
                            onClick = {},
                        )
                    }
                    if (onHandOver != null && member.role != SharingRole.OWNER) {
                        TextButton(onClick = { onHandOver(member) }) { Text("Make owner") }
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
                    share.fingerprint?.let { fingerprint ->
                        Text(
                            "Your key is $fingerprint. They'll ask you to read those out " +
                                "before they let you in. If what they see doesn't match, the " +
                                "reply was tampered with on the way.",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
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
                        when (invite.role) {
                            SharingRole.ADMIN ->
                                "You'll be able to see the passwords in it, add and change " +
                                    "them, and invite other people to it."
                            SharingRole.WRITER ->
                                "You'll be able to see the passwords in it, and add and " +
                                    "change them."
                            else ->
                                "You'll be able to see the passwords in it. You won't be able " +
                                    "to change them."
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
 * The wrap waits behind a button. The two links travel over ordinary chat
 * and the one thing an attacker on that channel can do is substitute their
 * own key. Six characters read out loud is the whole defence, so they belong
 * on screen *before* the content key is wrapped to whoever presented it.
 */
@Composable
fun AcceptShareScreen(
    share: ShareUiState,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalKeywebStatus.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            val accepted = share.accepted
            val preview = share.preview
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
            } else if (share.stage == ShareStage.ACCEPTING) {
                Text("Letting them in…", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Confirming it's you, then giving them the key.",
                    color = colors.muted,
                )
            } else if (preview != null) {
                Text(
                    "Let ${preview.email} into ${preview.label}?",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Ask them to read their key out. If it doesn't match, don't let them in — " +
                        "someone else may have got hold of the link.",
                    color = colors.muted,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    "Their key is ${preview.fingerprint}. Keyweb shows them the same ones. " +
                        "This is the check, not a formality after it.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                PrimaryButton("Their key matches — let them in", onConfirm)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Not this person", onDone)
            } else {
                Text("Checking the reply…", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Confirming the invitation this reply belongs to.",
                    color = colors.muted,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * What somebody else may do with a keyring you shared.
 *
 * `OWNER` is absent because it is not something you grant: exactly one person
 * holds it, and it moves by an ownership transfer rather than by a tap.
 *
 * `ADMIN` is here now and was not before. Leaving it out meant a shared
 * keyring had exactly one person who could invite anyone else — so a couple
 * sharing their household passwords had a household only one of them could add
 * anybody to, and losing that person's account meant nobody could ever add
 * anyone again. That is a worse failure than the one the omission avoided,
 * which was "a second person who can invite people is a bigger decision than a
 * checkbox". It is a bigger decision, so it gets a sentence saying what it
 * means rather than being hidden.
 *
 * The mirror of the web's `SHARE_ROLES`.
 */
data class RoleOption(val role: SharingRole, val label: String, val detail: String)

val SHARE_ROLES = listOf(
    RoleOption(
        SharingRole.VIEWER,
        "Can look",
        "They see everything on this keyring. They cannot change it.",
    ),
    RoleOption(
        SharingRole.WRITER,
        "Can change",
        "They see everything and can add, edit and delete passwords on it.",
    ),
    RoleOption(
        SharingRole.ADMIN,
        "Can change and invite",
        "Everything above, and they can invite other people and take their access away " +
            "again. Give this to someone you would trust to run the keyring if you could not.",
    ),
)

/**
 * What a role means, in one line, wherever a role is shown.
 *
 * One function rather than a `when` at each call site: the places that
 * described roles had already drifted into saying different things about the
 * same role, which is how somebody ends up believing a writer can invite
 * people because one screen implied it.
 */
fun describeRole(role: SharingRole): String = when (role) {
    SharingRole.OWNER -> "Shared it with you"
    SharingRole.ADMIN -> "Can change, and invite others"
    SharingRole.WRITER -> "Can add and change"
    else -> "Can look, can't change"
}

/**
 * Inviting one person to several keyrings, on one link.
 *
 * A sheet rather than a screen, because there is one question here — who, and
 * what may they do — and the answer produces one thing to send. Everything
 * else about a keyring (who is on it, what is outstanding, handing it over)
 * belongs to that keyring's own screen and stays there.
 *
 * The roles come from the same list the single-keyring form uses, so a role
 * cannot exist in one place and be missing from the other. One role covers
 * every keyring in the invitation: two people's worth of different answers on
 * one link is a second question, and nobody has asked for it.
 */
@Composable
fun MultiShareSheet(
    names: List<String>,
    busy: Boolean,
    link: String?,
    error: String?,
    onInvite: (String, SharingRole) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalKeywebStatus.current
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(SharingRole.VIEWER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (names.size == 1) {
                    "Share ${names.first()}"
                } else {
                    "Share ${names.size} keyrings"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    names.joinToString(", "),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    "They see every password in " +
                        (if (names.size == 1) "it" else "these") +
                        ", and any you add later. One link covers all of them.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                error?.let {
                    Text(
                        it,
                        color = colors.risk,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                if (link != null) {
                    Text("Send them this link", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "They open it, choose each keyring in Google's file chooser, and send " +
                            "you a reply link back. Google grants one file at a time, so there " +
                            "will be one chooser per keyring — but only one reply to open.",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    PrimaryButton("Copy the link", { onCopy(link) })
                } else {
                    EditField("Share with", email, { email = it }, "their.name@gmail.com")
                    Text(
                        "It has to be the Google account they use, because that is how Google " +
                            "lets them at the files.",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    SHARE_ROLES.forEach { choice ->
                        RoleChoice(choice.label, choice.detail, selected = role == choice.role) {
                            role = choice.role
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (link == null) {
                TextButton(
                    onClick = { onInvite(email.trim(), role) },
                    enabled = email.isNotBlank() && !busy,
                ) {
                    Text(if (busy) "Getting it ready…" else "Make a link")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (link == null) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

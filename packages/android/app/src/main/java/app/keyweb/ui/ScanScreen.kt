package app.keyweb.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyweb.ScanUiState
import app.keyweb.vault.AccountPlan
import app.keyweb.vault.PlanReason
import app.keyweb.vault.VaultState

/**
 * Bringing second-factor codes in from an authenticator app.
 *
 * The shape of the screen follows the shape of the problem. Google
 * Authenticator's "Transfer accounts" gives you *several* QR codes when you
 * have more than a handful of accounts, and says nothing on screen about how
 * many — so people scan one, see a third of their accounts, and stop. This
 * screen counts them and keeps asking until they are all in.
 *
 * Where each account goes is chosen by ticking rows and naming a destination,
 * rather than by a keyring dropdown on every row. Ticked accounts leave the
 * list when they are added, so "these on a new keyring, those with my existing
 * passwords" is the same gesture done twice, and what is left on screen is
 * always exactly what still needs deciding.
 */
@Composable
fun ScanScreen(
    scan: ScanUiState,
    state: VaultState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onAdd: (keyringId: String?, newKeyringName: String?) -> Unit,
) {
    val colors = LocalKeywebStatus.current
    val rings = state.keyrings.values.filter { !it.deleted.value }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var newRingName by rememberSaveable { mutableStateOf("") }
    val ticked = scan.accounts.count { it.selected }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text(" Back", style = MaterialTheme.typography.bodyLarge)
            }
            Text("Codes from another app", style = MaterialTheme.typography.titleLarge)
            Text(
                "In Google Authenticator, tap the three dots, then \"Transfer accounts\", then " +
                    "\"Export accounts\". Point this at the square it shows you.",
                color = colors.muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            PrimaryButton(
                if (scan.accounts.isEmpty()) "Scan the square" else "Scan another square",
                onScan,
                Modifier.padding(bottom = 10.dp),
            )
            Text(
                "Keyweb doesn't get access to your camera. Your phone reads the square and " +
                    "hands over what it says.",
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            scan.error?.let {
                StatusLine(Tone.RISK, "That didn't work", it, Modifier.padding(bottom = 10.dp))
            }
            scan.addedTo?.let {
                StatusLine(Tone.SAFE, "Added", it, Modifier.padding(bottom = 10.dp))
            }

            // The thing an authenticator export does not tell you itself.
            if (scan.total > 1) {
                StatusLine(
                    tone = if (scan.outstanding > 0) Tone.ATTENTION else Tone.SAFE,
                    headline = "Square ${scan.seen.size} of ${scan.total} read",
                    detail = if (scan.outstanding > 0) {
                        "Your authenticator split this export across ${scan.total} squares. " +
                            "Swipe to the next one and scan it too, or the rest of your " +
                            "accounts stay behind."
                    } else {
                        "All ${scan.total} squares are in."
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (scan.accounts.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${scan.accounts.size} account${if (scan.accounts.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onSelectAll(ticked != scan.accounts.size) }) {
                    Text(if (ticked == scan.accounts.size) "Untick all" else "Tick all")
                }
            }

            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    scan.accounts.forEach { pending ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Checkbox(
                                checked = pending.selected,
                                onCheckedChange = { onToggle(pending.key) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pending.account.issuer.ifEmpty {
                                        pending.account.name.ifEmpty { "Second-factor code" }
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    pending.account.name,
                                    color = colors.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // Said before it happens, never discovered
                                // after. Where a code goes is a decision with
                                // no safe guess in it — two accounts at one
                                // company is the case that proves it — so the
                                // row states the destination *and* the reason
                                // while it can still be argued with.
                                Text(
                                    destinationOf(pending.plan),
                                    color = when (pending.plan.reason) {
                                        PlanReason.ONTO_EXISTING -> colors.safe
                                        PlanReason.NEW -> colors.muted
                                        else -> colors.attention
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (pending.account.counterBased) {
                                    Text(
                                        "This one counts up instead of using the clock. " +
                                            "Keyweb will keep it, but can't produce its codes " +
                                            "— keep your other app for this account.",
                                        color = colors.attention,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = colors.line)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Where should these go?", style = MaterialTheme.typography.labelLarge)
            Text(
                "Only the ticked ones move. Whatever is left stays here, so you can send the " +
                    "rest somewhere else.",
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rings.forEach { ring ->
                    FilterChip(
                        selected = destination == ring.id,
                        onClick = { destination = ring.id },
                        colors = keywebChipColors(),
                        label = { Text(ring.name.value) },
                    )
                }
                FilterChip(
                    selected = destination == NEW_KEYRING,
                    onClick = { destination = NEW_KEYRING },
                    colors = keywebChipColors(),
                    label = { Text("A new keyring") },
                )
            }

            if (destination == NEW_KEYRING) {
                Spacer(Modifier.height(10.dp))
                EditField(
                    "What should the new keyring be called?",
                    newRingName,
                    { newRingName = it },
                    "Codes",
                )
            }

            Spacer(Modifier.height(6.dp))
            PrimaryButton(
                if (ticked == 1) "Add this one" else "Add these $ticked",
                onClick = {
                    if (destination == NEW_KEYRING) {
                        onAdd(null, newRingName)
                    } else {
                        onAdd(destination, null)
                    }
                },
                enabled = ticked > 0 && !scan.busy &&
                    destination != null &&
                    (destination != NEW_KEYRING || newRingName.isNotBlank()),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Where this code is about to go, and why.
 *
 * The "why" is not decoration. A person who sees "added as a new password" for
 * one of their two Carta codes needs to know it is because Keyweb refused to
 * guess between them, not because it failed to notice the password they
 * already have — the first is a decision they can accept, the second would be
 * a bug they should report.
 */
private fun destinationOf(plan: AccountPlan): String = when (plan.reason) {
    PlanReason.ONTO_EXISTING -> {
        val who = plan.existingUsername?.takeIf { it.isNotEmpty() }
        "Goes onto your existing \"${plan.existingTitle}\" password" +
            if (who != null) " for $who" else ""
    }

    PlanReason.NEW -> "Added as a new password"

    PlanReason.NEW_SEVERAL_FROM_ISSUER ->
        "Added as a new password. You have more than one code from " +
            "${plan.account.issuer}, so Keyweb won't guess which of your passwords " +
            "each one belongs to — move it yourself afterwards if it should sit " +
            "with one of them."

    PlanReason.NEW_ALREADY_HAS_A_CODE ->
        "Added as a new password. Your \"${plan.existingTitle}\" password already has " +
            "a different code, and Keyweb won't replace one that works."
}

/** Not a keyring id, and cannot collide with one: ids are UUIDs. */
private const val NEW_KEYRING = "new"

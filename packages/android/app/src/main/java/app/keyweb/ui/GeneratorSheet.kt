package app.keyweb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keyweb.vault.CharacterSet
import app.keyweb.vault.PasswordGenerator
import app.keyweb.vault.PasswordRules
import app.keyweb.vault.SavedRules
import kotlin.math.roundToInt

/**
 * Making a password to a site's rules.
 *
 * Opened from the password field rather than buried in settings, because the
 * moment someone needs it is the moment a site has just refused what they had.
 *
 * Every control restates its effect in words — strength as a sentence, not a
 * coloured bar — and an impossible combination says what is wrong instead of
 * quietly producing something weaker than asked for.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeneratorSheet(
    initial: PasswordRules,
    saved: List<SavedRules>,
    onDismiss: () -> Unit,
    onUse: (String, PasswordRules) -> Unit,
    onSaveRules: (String, PasswordRules) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val status = LocalKeywebStatus.current

    var rules by remember { mutableStateOf(initial) }
    var candidate by remember {
        mutableStateOf(
            runCatching { PasswordGenerator.generate(initial) }.getOrDefault(""),
        )
    }
    var namingPreset by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    fun reroll(next: PasswordRules) {
        rules = next
        candidate = runCatching { PasswordGenerator.generate(next) }.getOrDefault("")
    }

    val problem = PasswordGenerator.problem(rules)
    val strength = PasswordGenerator.describeStrength(rules)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Make a password", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            if (problem == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        codeGlyphs(candidate),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 19.sp,
                        lineHeight = 30.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                CodeLegend(Modifier.padding(top = 10.dp, bottom = 4.dp))
                Text(
                    strength.label,
                    fontWeight = FontWeight.Bold,
                    color = if (strength.bits < 60) status.attention else status.safe,
                )
                Text(
                    strength.detail,
                    color = status.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { reroll(rules) }) { Text("Make another") }
            } else {
                StatusLine(Tone.ATTENTION, "These rules can't work.", problem)
            }

            Spacer(Modifier.height(8.dp))
            Text("Length: ${rules.length}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = rules.length.toFloat(),
                onValueChange = { reroll(rules.copy(length = it.roundToInt())) },
                valueRange = 4f..64f,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Must include", fontWeight = FontWeight.SemiBold)
            Text(
                "Sites often insist on a number or a symbol. Turning one on " +
                    "guarantees at least one appears.",
                color = status.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (set in CharacterSet.entries) {
                    val on = set in rules.require
                    FilterChip(
                        selected = on,
                        onClick = {
                            // Off means "may appear"; on means "must". Removing
                            // it entirely is a separate switch below, so one tap
                            // never silently narrows the alphabet.
                            reroll(
                                rules.copy(
                                    require = if (on) rules.require - set else rules.require + set,
                                    include = rules.include + set,
                                ),
                            )
                        },
                        colors = chipColors(),
                        leadingIcon = { if (on) Text("✓") },
                        label = { Text(setLabel(set)) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Allowed at all", fontWeight = FontWeight.SemiBold)
            Text(
                "Turning one off keeps that kind out of the password entirely.",
                color = status.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (set in CharacterSet.entries) {
                    val on = set in rules.include
                    FilterChip(
                        selected = on,
                        onClick = {
                            reroll(
                                if (on) {
                                    rules.copy(
                                        include = rules.include - set,
                                        require = rules.require - set,
                                    )
                                } else {
                                    rules.copy(include = rules.include + set)
                                },
                            )
                        },
                        colors = chipColors(),
                        leadingIcon = { if (on) Text("✓") },
                        label = { Text(setLabel(set)) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = rules.symbols ?: PasswordGenerator.DEFAULT_SYMBOLS,
                onValueChange = { reroll(rules.copy(symbols = it)) },
                label = { Text("Symbols this site allows") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Delete the ones your site rejects. Some accept only a few and " +
                    "won't tell you which.",
                color = status.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            // The characters have to be shown in the mono face with their
            // category colours. Printed in the UI font they look identical,
            // which demonstrates the problem rather than explaining it.
            LookalikeToggle(
                on = rules.avoidLookalikes,
                onToggle = { reroll(rules.copy(avoidLookalikes = !rules.avoidLookalikes)) },
            )

            Spacer(Modifier.height(14.dp))
            Text("Saved rules", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in PasswordGenerator.presets + saved) {
                    FilterChip(
                        selected = preset.rules == rules,
                        onClick = { reroll(preset.rules) },
                        colors = chipColors(),
                        label = { Text(preset.name) },
                    )
                }
            }

            if (namingPreset) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Name these rules") },
                    placeholder = { Text("For example, My bank") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                PrimaryButton(
                    "Save these rules",
                    {
                        onSaveRules(presetName.trim(), rules)
                        presetName = ""
                        namingPreset = false
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = presetName.isNotBlank(),
                )
            } else {
                TextButton(onClick = { namingPreset = true }) { Text("Save these rules") }
            }

            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "Use this password",
                { onUse(candidate, rules) },
                Modifier.fillMaxWidth(),
                enabled = problem == null && candidate.isNotEmpty(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * Drawn here rather than through the shared buttons, because the characters it
 * names have to appear in the mono face with their category colours. Set in the
 * UI font they look identical, which demonstrates the problem instead of
 * explaining it.
 */
@Composable
private fun LookalikeToggle(on: Boolean, onToggle: () -> Unit) {
    val scale = LocalKeywebScale.current
    val label = buildAnnotatedString {
        append(if (on) "✓  Leaving out look-alikes: " else "Leave out look-alikes: ")
        appendInline(codeGlyphs("l 1 I O 0"))
    }
    if (on) {
        Button(
            onClick = onToggle,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = scale.tap),
        ) { Text(label, fontFamily = FontFamily.Monospace) }
    } else {
        OutlinedButton(
            onClick = onToggle,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = scale.tap),
        ) { Text(label, fontFamily = FontFamily.Monospace) }
    }
}

private fun AnnotatedString.Builder.appendInline(value: AnnotatedString) {
    val start = length
    append(value.text)
    for (span in value.spanStyles) {
        addStyle(span.item, start + span.start, start + span.end)
    }
}

private fun setLabel(set: CharacterSet): String = when (set) {
    CharacterSet.LOWER -> "small letters"
    CharacterSet.UPPER -> "CAPITALS"
    CharacterSet.DIGITS -> "numbers"
    CharacterSet.SYMBOLS -> "symbols"
}

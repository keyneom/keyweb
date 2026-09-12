package app.keyweb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Characters that must not be mistaken for one another.
 *
 * Someone copying a recovery code onto paper has to decide, for every glyph,
 * whether they are looking at a zero or a letter O. Getting it wrong is not a
 * typo they notice — it surfaces months later, on the one day the code matters,
 * as "that code isn't right".
 *
 * The alphabet already rules out the worst pairs: Crockford base32 omits I, L,
 * O and U entirely. That guarantee is useless to a reader who cannot see it, so
 * digits are drawn visibly apart from letters and the guarantee is stated in
 * words underneath. Colour alone would fail anyone who cannot see it, so weight
 * carries the same distinction.
 */
@Composable
@ReadOnlyComposable
fun codeGlyphs(value: String): AnnotatedString {
    val status = LocalKeywebStatus.current
    val digit = SpanStyle(color = status.brass, fontWeight = FontWeight.Black)
    val letter = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
    )
    return buildAnnotatedString {
        for (character in value) {
            when {
                character.isDigit() -> withStyle(digit) { append(character) }
                character.isLetter() -> withStyle(letter) { append(character) }
                else -> append(character)
            }
        }
    }
}

/**
 * The reading key.
 *
 * Written as a fact about this code rather than as advice, because "be careful"
 * asks the reader to do the work and this sentence does it for them.
 */
@Composable
fun CodeLegend(modifier: Modifier = Modifier) {
    val status = LocalKeywebStatus.current
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                codeGlyphs("123"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("numbers", color = status.muted, style = MaterialTheme.typography.bodyMedium)
            Text(
                codeGlyphs("ABC"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("letters", color = status.muted, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "There is no letter O, I, L or U in this code — so 0 is always zero " +
                "and 1 is always one.",
            color = status.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Colours digits apart from letters inside a text field.
 *
 * A stored password is not drawn from Keyweb's own alphabet — it may well
 * contain both a zero and a capital O — so reading one off the screen to type
 * somewhere else is exactly where the confusion bites. The offsets are
 * unchanged, so selection, the cursor and copy all behave normally.
 */
class GlyphColors(private val digit: Color, private val letter: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        buildAnnotatedString {
            for (character in text.text) {
                when {
                    character.isDigit() ->
                        withStyle(SpanStyle(color = digit, fontWeight = FontWeight.Black)) {
                            append(character)
                        }
                    character.isLetter() ->
                        withStyle(SpanStyle(color = letter)) { append(character) }
                    // Punctuation is already visually distinct from both.
                    else -> append(character)
                }
            }
        },
        OffsetMapping.Identity,
    )
}

@Composable
fun rememberGlyphColors(): GlyphColors {
    val digit = LocalKeywebStatus.current.brass
    val letter = MaterialTheme.colorScheme.onSurface
    return remember(digit, letter) { GlyphColors(digit, letter) }
}

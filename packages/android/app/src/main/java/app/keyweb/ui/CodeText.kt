package app.keyweb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Characters that must not be mistaken for one another.
 *
 * Someone copying a code or a password onto paper has to decide, for every
 * glyph, what they are looking at. Zero against letter O is the famous pair,
 * but the worse ones are the case twins — `c` and `C`, `s` and `S`, `v` and `V`
 * — which differ only in size, so an isolated glyph carries no cue at all.
 * Getting one wrong is not a typo anyone notices; it surfaces later as a code
 * that simply does not work, with nothing to say which character was wrong.
 *
 * The fix is display rather than alphabet. Dropping the ambiguous characters
 * would also work, but it spends real entropy to solve a rendering problem. So
 * every character is drawn in the colour of its category, with a legend saying
 * which is which.
 *
 * Colour carries the category, weight carries the case, and lightness backs up
 * both: the palette in `Theme.kt` is chosen so no two categories collapse
 * together under any common colour blindness.
 */
private data class GlyphStyles(
    val upper: SpanStyle,
    val lower: SpanStyle,
    val digit: SpanStyle,
    val symbol: SpanStyle,
)

@Composable
@ReadOnlyComposable
private fun glyphStyles(): GlyphStyles {
    val status = LocalKeywebStatus.current
    return GlyphStyles(
        upper = SpanStyle(color = status.glyphUpper, fontWeight = FontWeight.Bold),
        lower = SpanStyle(color = status.glyphLower, fontWeight = FontWeight.Medium),
        digit = SpanStyle(color = status.glyphDigit, fontWeight = FontWeight.ExtraBold),
        symbol = SpanStyle(color = status.glyphSymbol, fontWeight = FontWeight.Bold),
    )
}

private fun AnnotatedString.Builder.appendCategorised(value: String, styles: GlyphStyles) {
    for (character in value) {
        val style = when {
            character.isDigit() -> styles.digit
            character.isUpperCase() -> styles.upper
            character.isLetter() -> styles.lower
            else -> styles.symbol
        }
        withStyle(style) { append(character) }
    }
}

@Composable
@ReadOnlyComposable
fun codeGlyphs(value: String): AnnotatedString {
    val styles = glyphStyles()
    return buildAnnotatedString { appendCategorised(value, styles) }
}

/**
 * The reading key.
 *
 * Written as facts about the characters rather than as advice, because "be
 * careful" asks the reader to do the work and these sentences do it for them.
 *
 * The recovery variant can promise more than the password one: that alphabet
 * genuinely has no lower-case letter and no O, I, L or U, so those ambiguities
 * do not merely look resolved, they do not exist. A password made of arbitrary
 * characters gets the honest version instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodeLegend(modifier: Modifier = Modifier, recovery: Boolean = false) {
    val status = LocalKeywebStatus.current
    val styles = glyphStyles()

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendEntry("AB", "cd", "letters", styles)
            LegendEntry("123", null, "numbers", styles)
            LegendEntry("-@#", null, "symbols", styles)
        }
        Text(
            if (recovery) {
                "Every letter is a capital, and there is no letter O, I, L or U — " +
                    "so 0 is always zero and 1 is always one."
            } else {
                "CAPITALS are darker and bolder, small letters lighter. They are not " +
                    "interchangeable — copy them exactly as they appear."
            },
            color = status.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun LegendEntry(first: String, second: String?, label: String, styles: GlyphStyles) {
    Text(
        buildAnnotatedString {
            appendCategorised(first, styles)
            second?.let { appendCategorised(it, styles) }
            withStyle(SpanStyle(color = LocalKeywebStatus.current.muted)) { append("  $label") }
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Colours a text field's contents by category without moving any offsets, so
 * selection, the cursor and copy all behave exactly as before.
 */
class GlyphColors(
    private val upper: Color,
    private val lower: Color,
    private val digit: Color,
    private val symbol: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        buildAnnotatedString {
            for (character in text.text) {
                val style = when {
                    character.isDigit() ->
                        SpanStyle(color = digit, fontWeight = FontWeight.ExtraBold)
                    character.isUpperCase() ->
                        SpanStyle(color = upper, fontWeight = FontWeight.Bold)
                    character.isLetter() ->
                        SpanStyle(color = lower, fontWeight = FontWeight.Medium)
                    else -> SpanStyle(color = symbol, fontWeight = FontWeight.Bold)
                }
                withStyle(style) { append(character) }
            }
        },
        OffsetMapping.Identity,
    )
}

@Composable
fun rememberGlyphColors(): GlyphColors {
    val status = LocalKeywebStatus.current
    return remember(status) {
        GlyphColors(status.glyphUpper, status.glyphLower, status.glyphDigit, status.glyphSymbol)
    }
}

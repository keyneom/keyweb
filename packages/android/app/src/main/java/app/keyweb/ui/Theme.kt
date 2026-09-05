package app.keyweb.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Brass & Slate", the same palette as the web app.
 *
 * Two rules this file exists to hold:
 *
 *  1. The brand accent (steel, brass) and the status colours (safe, attention,
 *     risk) never share a hue. In a password manager green has to be free to
 *     mean "safe", so it can never also mean "branded". Brass appears only in
 *     the wordmark and the focus ring.
 *
 *  2. Text size is a setting, not an accessibility afterthought. Everything
 *     sizes off [KeywebScale], which the Larger text switch changes; no
 *     component hard-codes a dp or sp value for a target or a body style.
 */

@Immutable
data class KeywebStatusColors(
    val safe: Color,
    val safeContainer: Color,
    val attention: Color,
    val attentionContainer: Color,
    val risk: Color,
    val riskContainer: Color,
    val brass: Color,
    val muted: Color,
    val line: Color,
)

private val LightStatus = KeywebStatusColors(
    safe = Color(0xFF1D6B4F),
    safeContainer = Color(0xFFE0F0E7),
    attention = Color(0xFF8A4F0B),
    attentionContainer = Color(0xFFFAEBD8),
    risk = Color(0xFFA32C22),
    riskContainer = Color(0xFFF9E2E0),
    brass = Color(0xFFA9832F),
    muted = Color(0xFF5B6672),
    line = Color(0xFFDCD8CF),
)

private val DarkStatus = KeywebStatusColors(
    safe = Color(0xFF63C79A),
    safeContainer = Color(0xFF153029),
    attention = Color(0xFFE3A551),
    attentionContainer = Color(0xFF332714),
    risk = Color(0xFFF0857B),
    riskContainer = Color(0xFF3A1D1B),
    brass = Color(0xFFD7B45E),
    muted = Color(0xFF93A0AD),
    line = Color(0xFF2C3742),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B3F53),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3EAF1),
    onPrimaryContainer = Color(0xFF2B3F53),
    background = Color(0xFFF4F2ED),
    onBackground = Color(0xFF16202B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16202B),
    surfaceVariant = Color(0xFFEAE7DF),
    onSurfaceVariant = Color(0xFF5B6672),
    outline = Color(0xFFDCD8CF),
    error = Color(0xFFA32C22),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB3D4),
    onPrimary = Color(0xFF0E151C),
    primaryContainer = Color(0xFF22303D),
    onPrimaryContainer = Color(0xFF8FB3D4),
    background = Color(0xFF11161C),
    onBackground = Color(0xFFE9ECEF),
    surface = Color(0xFF1A222B),
    onSurface = Color(0xFFE9ECEF),
    surfaceVariant = Color(0xFF0D1218),
    onSurfaceVariant = Color(0xFF93A0AD),
    outline = Color(0xFF2C3742),
    error = Color(0xFFF0857B),
)

/** Sizes that the text-size setting scales together. */
@Immutable
data class KeywebScale(
    val body: androidx.compose.ui.unit.TextUnit,
    val secondary: androidx.compose.ui.unit.TextUnit,
    val label: androidx.compose.ui.unit.TextUnit,
    val title: androidx.compose.ui.unit.TextUnit,
    val display: androidx.compose.ui.unit.TextUnit,
    val tap: Dp,
    val row: Dp,
)

val NormalScale = KeywebScale(
    body = 17.sp,
    secondary = 15.sp,
    label = 13.sp,
    title = 22.sp,
    display = 28.sp,
    tap = 48.dp,
    row = 64.dp,
)

val LargeScale = KeywebScale(
    body = 20.sp,
    secondary = 18.sp,
    label = 15.sp,
    title = 26.sp,
    display = 32.sp,
    tap = 56.dp,
    row = 80.dp,
)

val LocalKeywebStatus = staticCompositionLocalOf { LightStatus }
val LocalKeywebScale = staticCompositionLocalOf { NormalScale }

@Composable
fun KeywebTheme(
    dark: Boolean = isSystemInDarkTheme(),
    largeText: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scale = if (largeText) LargeScale else NormalScale
    val typography = Typography(
        headlineMedium = TextStyle(fontSize = scale.display, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = scale.title, fontWeight = FontWeight.Bold),
        bodyLarge = TextStyle(fontSize = scale.body),
        bodyMedium = TextStyle(fontSize = scale.secondary),
        labelLarge = TextStyle(fontSize = scale.label, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(
        LocalKeywebStatus provides if (dark) DarkStatus else LightStatus,
        LocalKeywebScale provides scale,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = typography,
            content = content,
        )
    }
}

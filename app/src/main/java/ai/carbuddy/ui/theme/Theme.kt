package ai.carbuddy.ui.theme

import ai.carbuddy.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UX_SPEC 5.1 colour tokens. */
@Immutable
data class CbColors(
    val bgBase: Color, val bgSurface: Color, val bgSurface2: Color,
    val textPrimary: Color, val textSecondary: Color,
    val amber: Color, val cyan: Color, val teal: Color, val warn: Color,
    val paper: Color, val onPaper: Color, val onAccent: Color, val outline: Color,
    val isDark: Boolean,
)

val DarkColors = CbColors(
    bgBase = Color(0xFF0B1528), bgSurface = Color(0xFF12203A), bgSurface2 = Color(0xFF1B2D4F),
    textPrimary = Color(0xFFF6F3EC), textSecondary = Color(0xFFB9C4D6),
    amber = Color(0xFFF2A541), cyan = Color(0xFF5FD4E0), teal = Color(0xFF3FA7A3), warn = Color(0xFFF28B6B),
    paper = Color(0xFFF6F3EC), onPaper = Color(0xFF12203A), onAccent = Color(0xFF0B1528), outline = Color(0xFF2E4469),
    isDark = true,
)

val LightColors = CbColors(
    bgBase = Color(0xFFF6F3EC), bgSurface = Color(0xFFFFFFFF), bgSurface2 = Color(0xFFEFEBE2),
    textPrimary = Color(0xFF12203A), textSecondary = Color(0xFF4A5568),
    amber = Color(0xFFC97A12), cyan = Color(0xFF1F8A96), teal = Color(0xFF2B7F7C), warn = Color(0xFFC4512E),
    paper = Color(0xFFFFFFFF), onPaper = Color(0xFF12203A), onAccent = Color(0xFFFFFFFF), outline = Color(0xFFD5CFC2),
    isDark = false,
)

val AstaSans = FontFamily(
    Font(R.font.asta_sans_400, FontWeight.Normal),
    Font(R.font.asta_sans_500, FontWeight.Medium),
    Font(R.font.asta_sans_700, FontWeight.Bold),
)

/** UX_SPEC 5.2 type scale; Landscape (2 windows) is one step larger. Line height ≥ 1.5. */
@Immutable
data class CbType(val display: TextStyle, val title: TextStyle, val body: TextStyle, val label: TextStyle, val caption: TextStyle, val button: TextStyle)

private fun style(size: TextUnit, weight: FontWeight, lh: Float = 1.5f) = TextStyle(
    fontFamily = AstaSans, fontSize = size, fontWeight = weight, lineHeight = size * lh,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

fun cbType(landscape: Boolean) = if (landscape) CbType(
    display = style(40.sp, FontWeight.Bold, 1.3f), title = style(28.sp, FontWeight.Bold, 1.35f),
    body = style(20.sp, FontWeight.Normal), label = style(15.sp, FontWeight.Medium), caption = style(14.sp, FontWeight.Normal),
    button = style(20.sp, FontWeight.Bold, 1.2f),
) else CbType(
    display = style(32.sp, FontWeight.Bold, 1.3f), title = style(24.sp, FontWeight.Bold, 1.35f),
    body = style(18.sp, FontWeight.Normal), label = style(14.sp, FontWeight.Medium), caption = style(13.sp, FontWeight.Normal),
    button = style(18.sp, FontWeight.Bold, 1.2f),
)

/** UX_SPEC 5.3 spacing & shape. */
object Dimens {
    val gap8 = 8.dp; val gap16 = 16.dp; val gap24 = 24.dp; val gap32 = 32.dp; val gap48 = 48.dp
    val edgePortrait = 24.dp; val edgeLandscape = 40.dp
    val radiusCard = 28.dp; val radiusPanel = 20.dp; val radiusButton = 20.dp
    val primaryButton = 72.dp; val secondaryButton = 64.dp; val touch = 64.dp
    fun edge(landscape: Boolean): Dp = if (landscape) edgeLandscape else edgePortrait
}

/** UX_SPEC 5.5 motion. */
object Motion { const val SHORT = 150; const val MEDIUM = 300; const val LONG = 500 }

/**
 * System "애니메이션 줄이기" (animator duration scale 0, UX_SPEC 접근성): decorative motion — route
 * drawing, episode visuals, slide fades, card flip — becomes instant. Touch feedback (95 %) stays.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** [ms], or 0 when the system asks for reduced motion. */
@androidx.compose.runtime.Composable
fun motionMs(ms: Int): Int = if (LocalReduceMotion.current) 0 else ms

val LocalCbColors = staticCompositionLocalOf { DarkColors }
val LocalCbType = staticCompositionLocalOf { cbType(false) }
val LocalLandscape = staticCompositionLocalOf { false }

object Cb {
    val colors: CbColors @Composable get() = LocalCbColors.current
    val type: CbType @Composable get() = LocalCbType.current
    val landscape: Boolean @Composable get() = LocalLandscape.current
}

@Composable
fun CarBuddyTheme(landscape: Boolean, dark: Boolean = true /* UX_SPEC 5.1: 다크 기본 */, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(background = colors.bgBase, surface = colors.bgSurface, primary = colors.cyan)
    else lightColorScheme(background = colors.bgBase, surface = colors.bgSurface, primary = colors.cyan)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalCbColors provides colors,
            LocalCbType provides cbType(landscape),
            LocalLandscape provides landscape,
            content = content,
        )
    }
}

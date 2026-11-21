package com.deepseek.mobilechat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// One ink-and-blue family, per docs/ui-spec-v2.md §1.
//
// The palette is deliberately small: a single blue accent, one warning red, and
// a neutral ramp. Dark mode is *pure black* (spec), which means surfaces cannot
// be "a slightly lighter black" everywhere — elevation is expressed with the
// hairline divider and card surfaces (#141414) instead of shadows.
// ---------------------------------------------------------------------------

/** The brand blue. One accent, everywhere. */
val AccentBlue = Color(0xFF4D6BFE)

/** Dark-mode step of the accent: #4D6BFE is too dark against pure black. */
val AccentBlueDark = Color(0xFF7C93FF)

/** Warning red (spec §1). */
val WarningRed = Color(0xFFE5484D)

/**
 * Dark-mode warning red. The spec names one red; on #000000 the #E5484D step
 * only reaches ~4.4:1, which is below the contrast the 12sp labels need, so the
 * dark scheme lifts it by one step. Same hue, readable weight.
 */
val WarningRedDark = Color(0xFFFF6B6E)

/** Pure black page in dark mode, white in light mode. */
val PageLight = Color(0xFFFFFFFF)
val PageDark = Color(0xFF000000)

// ---------------------------------------------------------------------------
// Chat specific colour roles.
//
// Material's ColorScheme has no notion of "the bubble the user typed" or "the
// code block background", and inventing them ad hoc inside screens is exactly
// how a design system rots. They live here instead, keyed to light/dark.
//
// API note: field *names* are frozen — other screens read them. Only the values
// moved to the v2 palette.
// ---------------------------------------------------------------------------

@Immutable
data class ChatColors(
    /** Accent-filled pill behind the text the user typed. */
    val userBubble: Color,
    val userBubbleText: Color,
    /**
     * Assistant rows are deliberately unbubbled in v2; this is now the page
     * colour (kept so older call sites still compile and stay correct).
     */
    val assistantBubble: Color,
    val assistantBubbleText: Color,
    val codeBackground: Color,
    val codeBorder: Color,
    val caret: Color,
    val mutedText: Color,
    val divider: Color,
    /** Background of the composer tray (below the input container). */
    val composerBackground: Color,
    val bubbleShadow: Color,
    val scrim: Color,
    /** Soft accent wash for selected chips / highlight rows. */
    val accentSoft: Color = Color(0x00000000),
    /** Hairline used on top of card surfaces instead of elevation shadows. */
    val hairline: Color = Color(0x00000000),
    /** Icon+label colour that sits *on* the accent fill. */
    val onAccent: Color = Color(0xFFFFFFFF),
) {
    /**
     * Disc a model/brand logo sits on. The assistant side is the blue one, so
     * this is the accent in both themes (`BrandAvatar` also reads
     * `colorScheme.primary` directly).
     */
    val brandDisc: Color get() = AccentBlue
    val brandDiscDark: Color get() = AccentBlueDark
}

private val LightChatColors = ChatColors(
    userBubble = AccentBlue,
    userBubbleText = Color(0xFFFFFFFF),
    assistantBubble = Color(0xFFFFFFFF),
    assistantBubbleText = Color(0xFF111111),
    codeBackground = Color(0xFFF5F6F8),
    codeBorder = Color(0xFFE6E8EC),
    caret = AccentBlue,
    mutedText = Color(0xFF6B7280),
    divider = Color(0xFFE6E8EC),
    composerBackground = Color(0xFFFFFFFF),
    bubbleShadow = Color(0x14000000),
    scrim = Color(0x52000000),
    accentSoft = Color(0xFFEEF1FF),
    hairline = Color(0xFFE6E8EC),
    onAccent = Color(0xFFFFFFFF),
)

private val DarkChatColors = ChatColors(
    userBubble = Color(0xFF3D5AF1),
    userBubbleText = Color(0xFFFFFFFF),
    assistantBubble = PageDark,
    assistantBubbleText = Color(0xFFEDEDED),
    codeBackground = Color(0xFF141414),
    codeBorder = Color(0xFF262626),
    caret = AccentBlueDark,
    mutedText = Color(0xFF9CA3AF),
    divider = Color(0xFF262626),
    composerBackground = PageDark,
    bubbleShadow = Color(0x66000000),
    scrim = Color(0x99000000),
    accentSoft = Color(0xFF1B2450),
    hairline = Color(0xFF262626),
    onAccent = Color(0xFFFFFFFF),
)

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

/** Monospace style used by code blocks and inline code. */
val LocalCodeTextStyle = staticCompositionLocalOf {
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
}

// ---------------------------------------------------------------------------
// Colour schemes
// ---------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E9FF),
    onPrimaryContainer = Color(0xFF16224D),
    secondary = Color(0xFF5A6478),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAEDF3),
    onSecondaryContainer = Color(0xFF1C2433),
    tertiary = Color(0xFF7A6B4E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3ECDF),
    onTertiaryContainer = Color(0xFF2C2412),
    error = WarningRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E8),
    onErrorContainer = Color(0xFF4B0E11),
    background = PageLight,
    onBackground = Color(0xFF111111),
    surface = PageLight,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF5F6F8),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceTint = AccentBlue,
    inverseSurface = Color(0xFF111111),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = AccentBlueDark,
    outline = Color(0xFFD6DAE1),
    outlineVariant = Color(0xFFE6E8EC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF0F2F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF5F6F8),
    surfaceContainerHigh = Color(0xFFEEF0F4),
    surfaceContainerHighest = Color(0xFFE7EAEF),
)

private val DarkColors = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color(0xFF0A1330),
    primaryContainer = Color(0xFF1E2A5C),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF9AA6C4),
    onSecondary = Color(0xFF11182B),
    secondaryContainer = Color(0xFF20263A),
    onSecondaryContainer = Color(0xFFD5DBEE),
    tertiary = Color(0xFF9CA3AF),
    onTertiary = Color(0xFF14171D),
    tertiaryContainer = Color(0xFF262A33),
    onTertiaryContainer = Color(0xFFDDE0E6),
    error = WarningRedDark,
    onError = Color(0xFF2A0607),
    errorContainer = Color(0xFF3A1214),
    onErrorContainer = Color(0xFFFFD9DA),
    background = PageDark,
    onBackground = Color(0xFFEDEDED),
    surface = PageDark,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceTint = AccentBlueDark,
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = AccentBlue,
    outline = Color(0xFF3A3D42),
    outlineVariant = Color(0xFF262626),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1C1C1C),
    surfaceDim = PageDark,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
)

// ---------------------------------------------------------------------------
// Typography — three jobs: headings, body, code. Spec §1: title 22sp SemiBold,
// body 16sp, auxiliary 13sp. Numbers (tokens, timestamps) use tabular figures
// so a streaming counter does not jitter.
// ---------------------------------------------------------------------------

private val MobileChatTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/** Tabular figures for anything that counts: tokens, cost, timestamps. */
val TabularNumbers: TextStyle = TextStyle(fontFeatureSettings = "tnum")

/** `style` with tabular figures, so numbers do not jitter while streaming. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

// ---------------------------------------------------------------------------
// Shapes + spacing. Spec §1: composer 24dp, cards 20dp, buttons fully rounded.
// ---------------------------------------------------------------------------

private val MobileChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** One spacing scale, so paddings cannot drift between screens. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

/** Radii the spec pins by name, so nobody re-types `RoundedCornerShape(24.dp)`. */
object ShapeTokens {
    /** Input container (§1: 24dp). */
    val Composer = RoundedCornerShape(24.dp)
    /** Cards, photo thumbnails-in-cards, panels. */
    val Card = RoundedCornerShape(20.dp)
    /** Fully rounded chips/buttons (999dp). */
    val Pill = RoundedCornerShape(percent = 50)
    /** Bottom sheet top corners. */
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** Recent-photo grid cell (§5: 12dp). */
    val Thumbnail = RoundedCornerShape(12.dp)
}

/** Bubble corner radii: the "tail" corner is deliberately tighter. */
object BubbleShapes {
    val user = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    val assistant = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
}

/**
 * App theme.
 *
 * @param darkTheme `null` follows the system (default). The composition root
 *   resolves the user's `AppSettings.themeMode` ("system" | "light" | "dark")
 *   into an explicit `Boolean` and passes it here; every other caller keeps
 *   calling `MobileChatTheme { … }` exactly as before.
 */
@Composable
fun MobileChatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = darkTheme ?: systemDark
    val colorScheme = if (isDark) DarkColors else LightColors
    val chatColors = if (isDark) DarkChatColors else LightChatColors
    CompositionLocalProvider(
        LocalChatColors provides chatColors,
        LocalCodeTextStyle provides TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = colorScheme.onSurface,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MobileChatTypography,
            shapes = MobileChatShapes,
            content = content,
        )
    }
}

package com.engabd.sendpin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * The desktop fork of :app's Theme.kt — identical scheme, shapes and Expressive
 * motion, with two Android-only pieces removed:
 *
 *  1. [AccentChoice.DYNAMIC] is gone: `dynamicDarkColorScheme(LocalContext.current)`
 *     is Material You wallpaper extraction, which does not exist on the desktop.
 *     The fork keeps ALBUM and FIXED.
 *  2. The reduced-motion plumbing (LocalReducedMotion / rememberReducedMotion /
 *     AppSettings.MOTION_SYSTEM) is gone: Motion.kt is Android-deep and unshared,
 *     and the panel always runs full motion. Backdrop.kt *is* shared verbatim —
 *     it imports nothing outside Compose.
 *
 * Everything below the fork points is copied verbatim from the original so the
 * two cannot drift in look. If :app's Theme.kt changes schemeFor, AppShapes or
 * the enums, this file must follow — that coupling is the price of one forked
 * file out of a theme directory that is otherwise shared whole.
 */

/**
 * Which page the app is painted on. Persisted as the string in [key].
 *
 * OLED is the app's identity, but this NUC's panel is LCD — [ThemeChoice.DARK]
 * is the desktop default (see the plan: DarkColors' own comment on true black
 * showing every backlight flaw).
 */
enum class ThemeChoice(val key: String, val label: String, val description: String) {
    OLED("oled", "OLED black", "True black. Album art melts into the screen."),
    DARK("dark", "Dark", "Softer charcoal. Kinder to LCD panels."),
    LIGHT("light", "Light", "A warm off-white page."),
    SYSTEM("system", "Follow system", "OLED black at night, light by day.");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Where the accent colour comes from. The DYNAMIC (wallpaper) choice is desktop-absent. */
enum class AccentChoice(val key: String, val label: String, val description: String) {
    ALBUM("album", "Album art", "The accent is drawn from whatever is playing."),
    FIXED("fixed", "Fixed colour", "One accent you pick, that never changes.");

    /**
     * The accent to actually use, given what the artwork yielded.
     *
     * [fromArt] is honoured only under [ALBUM]; [FIXED] takes the user's colour.
     */
    fun resolve(fromArt: Color, fixed: Color, schemePrimary: Color): Color = when (this) {
        ALBUM -> fromArt
        FIXED -> fixed
    }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ALBUM
    }
}

/**
 * The page colour for a theme key, without a composition.
 */
fun pageColorFor(themeKey: String, systemDark: Boolean): Color =
    when (ThemeChoice.from(themeKey)) {
        ThemeChoice.OLED -> OledColors.ink
        ThemeChoice.DARK -> DarkColors.ink
        ThemeChoice.LIGHT -> LightColors.ink
        ThemeChoice.SYSTEM -> if (systemDark) OledColors.ink else LightColors.ink
    }

/**
 * An ARGB hex string back to a colour, falling back to [DefaultAccent].
 *
 * Persisted as text because JSON has no colour type and an Int key would be read
 * as a signed value, which makes a stored accent unreadable by eye when debugging
 * the file.
 */
fun parseAccent(hex: String): Color =
    hex.removePrefix("#").takeIf { it.length == 8 }
        ?.toLongOrNull(16)
        ?.let { Color(it.toInt()) }
        ?: DefaultAccent

/** The inverse of [parseAccent]. */
fun Color.toAccentHex(): String = "%08X".format(toArgb())

/**
 * The M3 colour scheme, built from a [SendspinColors] page and a seed accent.
 * Verbatim from :app — see the original for the role-by-role rationale.
 */
private fun schemeFor(c: SendspinColors, accent: Color): ColorScheme {
    val base = if (c.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = c.ink,
        primaryContainer = accent.copy(alpha = 0.14f),
        onPrimaryContainer = accent,

        // Secondary mirrors primary so segmented controls and secondary buttons share
        // the accent identity rather than introducing an unrelated hue.
        secondary = accent,
        onSecondary = c.ink,
        secondaryContainer = c.glassStrong,
        onSecondaryContainer = c.textSecondary,

        // A teal companion, so components that reach for tertiary get something
        // distinct but harmonious rather than a stock purple.
        tertiary = FallbackPalette[2],
        onTertiary = c.ink,
        tertiaryContainer = FallbackPalette[2].copy(alpha = 0.14f),
        onTertiaryContainer = c.textSecondary,

        background = c.ink,
        onBackground = c.textPrimary,
        surface = c.ink,
        onSurface = c.textPrimary,
        surfaceVariant = c.glassStrong,
        onSurfaceVariant = c.textMuted,
        surfaceTint = accent,

        // Surface container roles — map the design's ink hierarchy to M3's
        // surface container scale so M3 components (Card, BottomSheet, Dialog,
        // NavigationBar) inherit the right surface tones automatically.
        surfaceContainerLowest = c.ink,
        surfaceContainerLow = c.ink2,
        surfaceContainer = c.ink3,
        surfaceContainerHigh = c.glass,
        surfaceContainerHighest = c.glassStrong,
        surfaceDim = c.ink,
        surfaceBright = c.ink3,

        // Inverse is what Snackbar sits on, so it flips against the page.
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.ink,

        error = ErrorRed,
        onError = c.ink,
        errorContainer = ErrorRed.copy(alpha = 0.14f),
        onErrorContainer = ErrorRed,

        outline = c.hairline,
        outlineVariant = c.hairlineSoft,
        scrim = Color.Black,
    )
}

/**
 * M3 shape scale — full 8-level M3 Expressive scale. Verbatim from :app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SendspinTheme(
    theme: ThemeChoice = ThemeChoice.DARK,
    accentChoice: AccentChoice = AccentChoice.ALBUM,
    /** The accent for [AccentChoice.FIXED], and the seed when nothing is playing. */
    seedAccent: Color = DefaultAccent,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (theme) {
        ThemeChoice.OLED -> OledColors
        ThemeChoice.DARK -> DarkColors
        ThemeChoice.LIGHT -> LightColors
        // Following the system picks the app's own softened dark, not OLED — the
        // same reasoning as :app's SYSTEM branch, with DARK as the dark half
        // because this panel is LCD.
        ThemeChoice.SYSTEM -> if (systemDark) DarkColors else LightColors
    }

    // DYNAMIC is the fork's one absence: no wallpaper on a desktop panel, so both
    // remaining choices build the same scheme from the seed accent.
    val scheme = remember(colors, seedAccent) { schemeFor(colors, seedAccent) }

    CompositionLocalProvider(
        LocalSendspinColors provides colors,
    ) {
        // MaterialExpressiveTheme wraps MaterialTheme and provides the M3
        // Expressive motion scheme — same as :app, same material3 alpha line.
        MaterialExpressiveTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            motionScheme = MotionScheme.expressive(),
        ) {
            // ProvideBackdrop is not on the desktop: Backdrop.kt reads
            // AppSettings.glassBlur, which is Android-deep (DataStore + Context).
            // Its own doc covers the absence — a screen with no wash recorded
            // simply falls back to the flat fill.
            content()
        }
    }
}
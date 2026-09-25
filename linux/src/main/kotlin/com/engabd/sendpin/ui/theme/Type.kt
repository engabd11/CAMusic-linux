package com.engabd.sendpin.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** Classpath anchor for loading bundled fonts — the file's own loader. */
private class FontResourceMarker

/**
 * The desktop fork of :app's Type.kt — identical 30-style scale and weights,
 * with the one deviation the plan's polish ladder demands: [AppFont] and
 * [MonoFont] are Roboto / JetBrains Mono **bundled as resources** and loaded
 * through the desktop `Font(name, bytes)` path, instead of Android's
 * FontFamily Default / Monospace (which resolved to Noto Sans here — not the
 * app's identity).
 *
 * The original's doc explains why :app approximates with the system sans; on
 * desktop the system sans was Noto Sans. Bundling makes the panel's type match
 * the phone exactly. If :app changes sizes/weights, this fork must follow —
 * one forked file out of a shared directory, same coupling as the Theme fork.
 */
private fun bundledFont(
    path: String,
    weight: FontWeight,
) = Font(
    path,
    { FontResourceMarker::class.java.classLoader.getResourceAsStream(path)!!.readBytes() },
    weight,
    FontStyle.Normal,
)

private val Roboto = FontFamily(
    bundledFont("fonts/Roboto-Regular.ttf", FontWeight.Normal),
    bundledFont("fonts/Roboto-Medium.ttf", FontWeight.Medium),
    bundledFont("fonts/Roboto-Bold.ttf", FontWeight.Bold),
    bundledFont("fonts/Roboto-Black.ttf", FontWeight.Black),
)

private val JetBrainsMono = FontFamily(
    bundledFont("fonts/JetBrainsMono-Regular.otf", FontWeight.Normal),
    bundledFont("fonts/JetBrainsMono-Bold.otf", FontWeight.Bold),
)

val AppFont: FontFamily = Roboto
val MonoFont: FontFamily = JetBrainsMono

/**
 * The M3 type scale — full 30-style scale (15 baseline + 15 emphasized).
 *
 * ## Baseline styles (15)
 * | M3 role           | Design usage                              |
 * |-------------------|-------------------------------------------|
 * | headlineLarge     | Screen titles ("Settings", "Library")     |
 * | titleLarge        | Card/section titles ("Connection", etc.)  |
 * | titleMedium       | Subsection labels (bold 12sp headers)     |
 * | bodyLarge         | Primary body text (track titles, artists)  |
 * | bodyMedium        | Secondary text, helper labels             |
 * | bodySmall         | Faint descriptions, fine print             |
 * | labelLarge        | Buttons, pills, segmented toggle labels    |
 * | labelMedium       | Quality pill (mono), quality badge         |
 * | labelSmall        | Section labels (uppercase, tracked)        |
 *
 * ## Emphasized styles (15 — M3 Expressive)
 * Higher weight for key UI moments: Now Playing title, primary actions,
 * selected items, headlines. Accessible via MaterialTheme.typography.headlineLargeEmphasized.
 *
 * Weight deltas (baseline -> emphasized):
 * - Display/Headline: ExtraBold -> Black
 * - Title: Bold -> ExtraBold
 * - Label: Bold -> ExtraBold
 * - Body: Normal -> Medium
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppTypography = Typography(
    // ── Baseline styles (15) ──────────────────────────────────────────────

    headlineLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    ),

    // ── Emphasized styles (15 — M3 Expressive) ────────────────────────────

    headlineLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
    ),
    headlineSmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
    ),
    titleLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
    ),
    titleMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
    ),
    titleSmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),
    bodyLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    bodyMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    bodySmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
    ),
    labelMediumEmphasized = TextStyle(
        fontFamily = MonoFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
    ),
    labelSmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
    ),
    displayLargeEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
    ),
    displayMediumEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
    ),
    displaySmallEmphasized = TextStyle(
        fontFamily = AppFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
    ),
)
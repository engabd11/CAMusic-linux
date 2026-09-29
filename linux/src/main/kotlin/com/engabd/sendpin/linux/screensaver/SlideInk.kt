package com.engabd.sendpin.linux.screensaver

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.oklabToOklch
import com.engabd.sendpin.ui.design.oklchToRgbInGamut
import com.engabd.sendpin.ui.design.rgbToOklab
import kotlin.math.sqrt

/**
 * How one record colours its slide: [ink] for the words, [bleed] for the colours
 * behind them — split so the words always pop while staying the album's own.
 *
 * The rule is CAMusic's. Its play button reads against the album's bloom because the
 * palette keeps the lead colour brighter and more vivid than the companions that
 * light the room. So the words take the album colour that stands furthest from all
 * the others (the odd one out, in hue and chroma), lifted, hue kept, until it clears
 * [MIN_CONTRAST] against the bleed behind it. The bleed is painted from the rest,
 * never from the ink, so the words never sit on their own colour.
 */
data class AlbumInk(val ink: Color, val bleed: List<Color>)

/** The near-black the bleed is painted over. */
internal val BLEED_GROUND = Color(0xFF07070A)

/**
 * Roughly how much of the bleed's colour reaches any point behind the words — the
 * blooms' alphas where they overlap, minus the vignette. An estimate, deliberately on
 * the bright side, so the contrast check errs toward the words.
 */
private const val BLEED_COVER = 0.5f

/** WCAG's threshold for body text; display text this size has room to spare. */
const val MIN_CONTRAST = 4.5f

fun albumInk(palette: AlbumPalette): AlbumInk {
    val swatches = palette.swatches.distinct().ifEmpty { listOf(palette.accent) }
    if (swatches.size == 1) {
        // One colour only (a monochrome sleeve): it lights the room and, lifted, sets the words.
        val only = swatches[0]
        return AlbumInk(readable(only, bleedBackground(swatches)), listOf(only))
    }
    val chosen = swatches.withIndex().maxBy { (i, c) ->
        val rest = swatches.filterIndexed { j, _ -> j != i }
        // the lead (index 0) wins ties: it is the colour the cover is about
        separation(c, rest) + if (i == 0) 0.01f else 0f
    }.value
    val rest = swatches.filter { it != chosen }
    return AlbumInk(readable(chosen, bleedBackground(rest)), rest)
}

/** How far [c] sits from the nearest of [others] in OKLab's hue–chroma plane. */
private fun separation(c: Color, others: List<Color>): Float {
    val a = lab(c)
    return others.minOf { o ->
        val b = lab(o)
        sqrt((a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    }
}

/** The colour the words actually sit on: the ground with the bleed's average laid over it. */
internal fun bleedBackground(bleed: List<Color>): Color {
    val r = bleed.map { it.red }.average().toFloat()
    val g = bleed.map { it.green }.average().toFloat()
    val b = bleed.map { it.blue }.average().toFloat()
    fun mix(ground: Float, over: Float) = ground + (over - ground) * BLEED_COVER
    return Color(mix(BLEED_GROUND.red, r), mix(BLEED_GROUND.green, g), mix(BLEED_GROUND.blue, b))
}

/**
 * [c] made legible on [background]: its hue kept exactly, its chroma kept as far as the
 * gamut allows, its lightness raised step by step until the contrast holds.
 */
internal fun readable(c: Color, background: Color): Color {
    val lch = oklabToOklch(lab(c))
    var l = lch[0]
    var out = c
    while (contrast(out, background) < MIN_CONTRAST && l < 0.98f) {
        l = (l + 0.02f).coerceAtMost(0.98f)
        val rgb = oklchToRgbInGamut(l, lch[1], lch[2])
        out = Color(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f))
    }
    return out
}

/** WCAG contrast ratio between two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun lab(c: Color): FloatArray = rgbToOklab(c.red, c.green, c.blue)

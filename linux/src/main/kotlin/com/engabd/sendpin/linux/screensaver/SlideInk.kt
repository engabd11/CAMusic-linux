package com.engabd.sendpin.linux.screensaver

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.rgbToOklab
import kotlin.math.sqrt

/**
 * How one record colours its slide: [ink] for the words, [bleed] for the colours
 * behind them, and [bleedLevel], how strongly the bleed may shine behind that ink.
 *
 * The rule is CAMusic's. Its play button reads against the album's bloom because the
 * palette keeps the lead colour apart from the companions that light the room. So the
 * words take the album colour that stands furthest from all the others (the odd one
 * out, in hue and chroma) — **exactly as the palette has it, never lightened**: a
 * lifted tint is close to the sleeve's colour but visibly not it. The bleed is painted
 * from the rest, never from the ink.
 *
 * Contrast is the background's job, not the words': where the bleed would crowd the
 * ink, it is dimmed until the ink clears [MIN_CONTRAST] against it.
 */
data class AlbumInk(val ink: Color, val bleed: List<Color>, val bleedLevel: Float = 1f)

/** The near-black the bleed is painted over. */
internal val BLEED_GROUND = Color(0xFF07070A)

/**
 * Roughly how much of the bleed's colour reaches any point behind the words at full
 * level — the blooms' alphas where they overlap, minus the vignette. An estimate,
 * deliberately on the bright side, so the contrast check errs toward the words.
 */
private const val BLEED_COVER = 0.5f

/** The dimmest the bleed goes: below this it stops reading as the album's colours. */
private const val MIN_BLEED_LEVEL = 0.35f

/** WCAG's threshold for body text; display text this size has room to spare. */
const val MIN_CONTRAST = 4.5f

fun albumInk(palette: AlbumPalette): AlbumInk {
    val swatches = palette.swatches.distinct().ifEmpty { listOf(palette.accent) }
    if (swatches.size == 1) {
        // One colour only (a monochrome sleeve): it lights the room and sets the words.
        val only = swatches[0]
        return AlbumInk(only, listOf(only), levelFor(only, swatches))
    }
    // Only colours that can be read at all — against the bare ground — may set the
    // words. The palette lifts its colours for a dark page, so this is nearly all of
    // them; if none can, the most legible one does.
    val legible = swatches.withIndex().filter { contrast(it.value, BLEED_GROUND) >= MIN_CONTRAST }
        .ifEmpty { listOf(swatches.withIndex().maxBy { contrast(it.value, BLEED_GROUND) }) }
    val chosen = legible.maxBy { (i, c) ->
        val rest = swatches.filterIndexed { j, _ -> j != i }
        // the lead (index 0) wins ties: it is the colour the cover is about
        separation(c, rest) + if (i == 0) 0.01f else 0f
    }.value
    val rest = swatches.filter { it != chosen }
    return AlbumInk(chosen, rest, levelFor(chosen, rest))
}

/** The strongest bleed level, from full down to [MIN_BLEED_LEVEL], at which [ink] still reads. */
private fun levelFor(ink: Color, bleed: List<Color>): Float {
    var level = 1f
    while (level > MIN_BLEED_LEVEL && contrast(ink, bleedBackground(bleed, level)) < MIN_CONTRAST) {
        level -= 0.05f
    }
    return level.coerceAtLeast(MIN_BLEED_LEVEL)
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
internal fun bleedBackground(bleed: List<Color>, level: Float = 1f): Color {
    val r = bleed.map { it.red }.average().toFloat()
    val g = bleed.map { it.green }.average().toFloat()
    val b = bleed.map { it.blue }.average().toFloat()
    val cover = BLEED_COVER * level
    fun mix(ground: Float, over: Float) = ground + (over - ground) * cover
    return Color(mix(BLEED_GROUND.red, r), mix(BLEED_GROUND.green, g), mix(BLEED_GROUND.blue, b))
}

/** WCAG contrast ratio between two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun lab(c: Color): FloatArray = rgbToOklab(c.red, c.green, c.blue)

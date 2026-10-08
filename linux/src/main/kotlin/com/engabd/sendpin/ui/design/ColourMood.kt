package com.engabd.sendpin.ui.design

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * A grade laid over every album's colours, the way a film is graded: the cover is never
 * touched, but the accent, the liquid, the blooms and the listening room all take the
 * mood. Settings → Colour & finish. Persisted as [key].
 *
 * Graded in OKLCh so lightness — what keeps the words readable over the colours — stays
 * where the palette put it, and every result is pulled back inside sRGB.
 */
enum class ColourMood(val key: String, val label: String, val description: String) {
    NATURAL("natural", "Natural", "The album's own colours, as the cover has them."),
    WARM("warm", "Warm", "Every record in tungsten amber, like a lamp-lit listening room."),
    COOL("cool", "Cool", "Moonlight blues and silvers, calm for late listening."),
    MONO("mono", "Mono", "A duotone: the whole room in the album's one leading hue."),
    NEON("neon", "Neon", "The colours turned right up, bright and saturated.");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: NATURAL
    }
}

/** The mood the whole panel is graded in; provided in `Main.kt`. */
val LocalColourMood = compositionLocalOf { ColourMood.NATURAL }

private const val WARM_HUE = 62f
private const val COOL_HUE = 235f
/** How far Warm and Cool pull a hue toward theirs (0 none, 1 all the way). */
private const val PULL = 0.6f
/** Below this chroma a colour reads as grey; Warm and Cool tint it rather than turn it. */
private const val GREY_C = 0.03f
private const val TINT_C = 0.035f
private const val NEON_GAIN = 1.45f

/** [palette] in [mood]. [ColourMood.NATURAL] hands it back untouched. */
fun AlbumPalette.graded(mood: ColourMood): AlbumPalette {
    if (mood == ColourMood.NATURAL) return this
    val lead = lch(accent)
    return AlbumPalette(
        accent = grade(accent, mood, lead, isAccent = true),
        swatches = swatches.map { grade(it, mood, lead, isAccent = false) },
    )
}

private fun lch(c: Color): FloatArray = oklabToOklch(rgbToOklab(c.red, c.green, c.blue))

private fun grade(c: Color, mood: ColourMood, lead: FloatArray, isAccent: Boolean): Color {
    val (l, ch, h) = lch(c).let { Triple(it[0], it[1], it[2]) }
    val (l2, c2, h2) = when (mood) {
        ColourMood.NATURAL -> Triple(l, ch, h)
        ColourMood.WARM -> toward(l, ch, h, WARM_HUE)
        ColourMood.COOL -> toward(l, ch, h, COOL_HUE)
        // One hue for the room: the accent's. Each swatch keeps its own lightness, so
        // the liquid still has light and shade; a grey album stays grey.
        ColourMood.MONO -> Triple(l, if (lead[1] < GREY_C) ch.coerceAtMost(GREY_C) else maxOf(ch, lead[1]) * 0.8f, lead[2])
        ColourMood.NEON -> {
            val lifted = if (isAccent) maxOf(l, 0.72f) else l
            Triple(lifted, minOf(ch * NEON_GAIN + 0.02f, maxChromaFor(lifted, h)), h)
        }
    }
    val rgb = oklchToRgbInGamut(l2.coerceIn(0f, 1f), c2.coerceAtLeast(0f), h2)
    return Color(rgb[0], rgb[1], rgb[2], c.alpha)
}

/** A hue pulled [PULL] of the way toward [target]; a grey given a little of it. */
private fun toward(l: Float, c: Float, h: Float, target: Float): Triple<Float, Float, Float> =
    if (c < GREY_C) Triple(l, TINT_C, target)
    else Triple(l, c, pullHue(h, target, PULL))

/** [h] moved [t] of the way to [target] around the short side of the wheel. */
internal fun pullHue(h: Float, target: Float, t: Float): Float {
    var d = (target - h) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return ((h + d * t) % 360f + 360f) % 360f
}

/** The short way round between two hues, in degrees (0..180). For the tests. */
internal fun hueGap(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

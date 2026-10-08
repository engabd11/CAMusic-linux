package com.engabd.sendpin.linux.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import com.engabd.sendpin.linux.LinuxSettings
import kotlinx.coroutines.delay
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import java.time.LocalTime
import kotlin.math.hypot
import kotlin.random.Random

/*
 * The glass in front of the whole panel: a vignette framing it like a print, a film
 * grain over everything (which also hides the banding an LCD shows in slow gradients),
 * and the night — the panel warmed and dimmed through the small hours, for a listening
 * station that never turns off. Settings → Colour & finish.
 *
 * Drawn above the shell and the always-on views in `Main.kt`, and nothing here takes a
 * touch: no pointer input at all, so it can never stand between a finger and a button.
 */

/** Settings' vignette key → how dark the edges go (0 for none). */
internal fun vignetteStrength(key: String): Float = when (key) {
    "soft" -> 0.38f
    "deep" -> 0.62f
    else -> 0f
}

/** Settings' grain key → the grain's contrast (0 for none). */
internal fun grainAmount(key: String): Float = when (key) {
    "fine" -> 0.035f
    "rich" -> 0.07f
    else -> 0f
}

/** How deep the night goes: how much it darkens, and how much amber it lays over. */
internal data class NightDepth(val dim: Float, val warm: Float)

internal fun nightDepth(key: String): NightDepth = when (key) {
    "deep" -> NightDepth(dim = 0.58f, warm = 0.32f)
    else -> NightDepth(dim = 0.32f, warm = 0.2f)
}

/** Minutes the night takes to come down at its start, and to lift at its end. */
internal const val NIGHT_FADE_MIN = 20

/**
 * How far into the night the panel is at [minuteOfDay] (0..1439): 0 by day, 1 in the
 * depth of the night, easing over [fadeMin] at each edge. [mode] `off` | `scheduled` |
 * `always`; a scheduled night runs from [startH] to [endH] and may wrap past midnight.
 * Pure for the tests.
 */
internal fun nightLevel(mode: String, minuteOfDay: Int, startH: Int, endH: Int, fadeMin: Int = NIGHT_FADE_MIN): Float {
    when (mode) {
        "always" -> return 1f
        "scheduled" -> Unit
        else -> return 0f
    }
    val day = 24 * 60
    val start = (startH.coerceIn(0, 23)) * 60
    val end = (endH.coerceIn(0, 23)) * 60
    val length = ((end - start) % day + day) % day
    if (length == 0) return 0f
    val into = ((minuteOfDay - start) % day + day) % day
    if (into >= length) return 0f
    val fade = minOf(fadeMin, length / 2).coerceAtLeast(1).toFloat()
    return minOf(1f, into / fade, (length - into) / fade)
}

@Composable
fun PanelFinish(settings: LinuxSettings) {
    val vignetteKey by settings.vignette.collectAsState(initial = "off")
    val grainKey by settings.filmGrain.collectAsState(initial = "off")
    val nightMode by settings.nightMode.collectAsState(initial = "off")
    val startH by settings.nightStartH.collectAsState(initial = 22)
    val endH by settings.nightEndH.collectAsState(initial = 7)
    val depthKey by settings.nightDepth.collectAsState(initial = "gentle")

    // The night follows the clock, read twice a minute. Each reading moves it a twentieth
    // at most (NIGHT_FADE_MIN), eased over a few seconds so it never steps — which also
    // lets a change in Settings show within moments.
    var minute by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            minute = LocalTime.now().let { it.hour * 60 + it.minute }
        }
    }
    val night by animateFloatAsState(
        nightLevel(nightMode, minute, startH, endH),
        tween(4_000, easing = LinearEasing),
        label = "night",
    )
    val vignette = vignetteStrength(vignetteKey)
    val grain = grainAmount(grainKey)
    val depth = nightDepth(depthKey)

    // A still grain, re-laid every minute or so: no pattern stays long enough to mark the
    // panel, and nothing moves for the eye to catch.
    val seed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(grain > 0f) {
        while (grain > 0f) {
            seed.floatValue = Random.nextFloat() * 1_000f
            delay(60_000)
        }
    }
    val grainEffect = if (grain > 0f) GrainShader.effect else null
    val grainBuilder = remember(grainEffect) { grainEffect?.let(::RuntimeShaderBuilder) }

    if (vignette <= 0f && grainBuilder == null && night <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        if (grainBuilder != null) {
            grainBuilder.uniform("size", size.width, size.height)
            grainBuilder.uniform("seed", seed.floatValue)
            grainBuilder.uniform("amount", grain)
            drawRect(ShaderBrush(grainBuilder.makeShader().asComposeShader()))
        }
        if (vignette > 0f) {
            drawRect(
                Brush.radialGradient(
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = vignette),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = hypot(size.width, size.height) / 2f,
                ),
            )
        }
        if (night > 0f) {
            // Amber laid over by multiply takes the blue out first, the way a lamp does;
            // then the whole panel comes down.
            drawRect(Color(0xFFFF9A4A).copy(alpha = depth.warm * night), blendMode = BlendMode.Multiply)
            drawRect(Color.Black.copy(alpha = depth.dim * night))
        }
    }
}

private object GrainShader {
    /** Compiled once; null (and never retried) where SkSL is unavailable — no grain then. */
    val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull() }

    /** Whether the grain compiles here. For the tests. */
    fun compiles(): Boolean = effect != null

    private const val SKSL = """
uniform float2 size;
uniform float seed;
uniform float amount;

half4 main(float2 coord) {
    // A grain about a pixel and a half across, lightening or darkening what is under it.
    float2 p = floor(coord / 1.5) + seed;
    float n = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    float g = (n - 0.5) * 2.0 * amount;
    return g > 0.0 ? half4(half3(g), half(g)) : half4(0.0, 0.0, 0.0, half(-g));
}
"""
}

/** Whether the film grain can be drawn on this machine. For the tests. */
internal fun grainCompiles(): Boolean = GrainShader.compiles()

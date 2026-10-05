package com.engabd.sendpin.linux.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.engabd.sendpin.linux.PanelModel
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Living art: a little way into each track the cover quietly comes alive.
 *
 * Nothing in the picture is understood — the trick is that real covers are mostly
 * texture (water, sky, smoke, skin, fabric, grain), and texture moved by a slow, smooth
 * flow field reads as the scene itself moving. So the cover is resampled through a
 * domain-warped noise field a few pixels deep, its highlights glint as if light were
 * playing on them, and a faint light passes across it every quarter minute. Hard edges
 * (type, lines, logos) are found and held still, so a title never wobbles — only the
 * soft parts of the picture flow.
 * Around that, the print itself breathes and leans by a degree, so it has depth.
 *
 * All of it is read inside graphicsLayer only (no recomposition per frame), runs only
 * while the art is awake and visible, and the clock ticks at ~30 fps — the motion is
 * far too slow to need more on a panel that is always on.
 */

/** Settings' `off` | `subtle` | `vivid` → how strongly the cover moves. */
internal fun livingStrength(key: String): Float = when (key) {
    "subtle" -> 1f
    "vivid" -> 2f
    else -> 0f
}

/**
 * Whether the cover should be awake: playing, and [delayMs] into the track. Paused, or
 * a fresh track before its mark, it settles. Pure for the tests.
 */
internal fun aliveTarget(posMs: Long, playing: Boolean, delayMs: Long): Float =
    if (playing && posMs >= delayMs) 1f else 0f

/** The awake level (0..1, eased) and the clock the motion runs on, as deferred reads. */
@Stable
class LivingArt internal constructor(
    val strength: Float,
    private val levelRead: () -> Float,
    private val timeRead: () -> Float,
) {
    /** 0 asleep → 1 fully awake; read only in draw/layer lambdas. */
    fun level(): Float = levelRead()

    /** Seconds since the cover last woke. */
    fun time(): Float = timeRead()

    companion object {
        /** Asleep for good — for the places that show the cover without the setting. */
        val Still = LivingArt(0f, { 0f }, { 0f })
    }
}

/** The ~30 fps tick: the flow is slow enough that a frame more buys nothing. */
private const val TICK_S = 1f / 30f

/**
 * The playing track's living-art state, from the settings and the transport. [asleep]
 * forces it down; the overlay flag in [PanelModel] stops the clock while something covers
 * the screen — unless this cover is itself on the overlay ([onOverlay]).
 */
@Composable
fun rememberLivingArt(model: PanelModel, asleep: Boolean = false, onOverlay: Boolean = false): LivingArt {
    val mode by model.settings.livingArt.collectAsState(initial = "subtle")
    val delayS by model.settings.livingArtDelayS.collectAsState(initial = 10)
    val remote by model.state.collectAsState()
    val covered by model.coverObscured.collectAsState()
    val obscured = covered && !onOverlay
    val strength = livingStrength(mode)
    val target = if (strength > 0f && !asleep) {
        aliveTarget(remote?.positionMs ?: 0L, remote?.playing == true, delayS.coerceAtLeast(0) * 1_000L)
    } else 0f

    val level = remember { Animatable(0f) }
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(target) {
        if (target > 0f) {
            // wakes slowly — it should be noticed a while after it began, not at once
            level.animateTo(1f, tween(4_000, easing = FastOutSlowInEasing))
        } else {
            level.animateTo(0f, tween(1_500, easing = FastOutSlowInEasing))
            clock.floatValue = 0f
        }
    }
    val awake by remember { derivedStateOf { level.value > 0f } }
    LaunchedEffect(awake, obscured) {
        if (!awake || obscured) return@LaunchedEffect
        val from = clock.floatValue
        var origin = -1L
        while (true) {
            withFrameNanos { now ->
                if (origin < 0) origin = now
                val t = from + (now - origin) / 1_000_000_000f
                if (t - clock.floatValue >= TICK_S) clock.floatValue = t
            }
        }
    }
    return remember(strength) { LivingArt(strength, { level.value }, { clock.floatValue }) }
}

/**
 * The print's own life: a slow breath and a lean of about a degree, on two periods that
 * never line up. Adds to whatever the layer already holds (the album-change motion).
 */
internal fun GraphicsLayerScope.livingMotion(amount: Float, t: Float) {
    if (amount <= 0f) return
    val a = min(amount, 2f)
    val breath = 1f + 0.008f * a * (0.5f - 0.5f * cos(t * 2f * PI.toFloat() / 9f))
    scaleX *= breath
    scaleY *= breath
    rotationX += 0.9f * a * sin(t * 2f * PI.toFloat() / 13f)
    rotationY += 0.9f * a * sin(t * 2f * PI.toFloat() / 17f + 1.3f)
}

/**
 * The flow-field shader for a layer of [width]×[height] at time [t] — null when the
 * renderer can't run SkSL, in which case the cover still breathes (see [livingMotion]).
 */
internal fun livingEffect(width: Float, height: Float, t: Float, amount: Float): RenderEffect? {
    if (amount <= 0.001f || width <= 0f || height <= 0f) return null
    val effect = LivingShader.effect ?: return null
    return runCatching {
        val b = RuntimeShaderBuilder(effect)
        b.uniform("size", width, height)
        b.uniform("time", t)
        b.uniform("amp", 0.006f * min(width, height) * amount)
        b.uniform("shimmer", 0.07f * amount)
        b.uniform("sweep", 0.045f * amount)
        ImageFilter.makeRuntimeShader(b, "content", null).asComposeRenderEffect()
    }.getOrNull()
}

private object LivingShader {
    /** Compiled once; null (and never retried) where SkSL is unavailable. */
    val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull() }

    private const val SKSL = """
uniform shader content;
uniform float2 size;
uniform float time;
uniform float amp;
uniform float shimmer;
uniform float sweep;

float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    return noise(p) * 0.65 + noise(p * 2.03 + float2(17.0, 9.0)) * 0.35;
}

float lumAt(float2 c) {
    return dot(float3(content.eval(c).rgb), float3(0.299, 0.587, 0.114));
}

half4 main(float2 coord) {
    float2 uv = coord / size;
    // the frame stays rigid: the field fades out toward every edge
    float2 edge = smoothstep(float2(0.0), float2(0.10), uv) * smoothstep(float2(0.0), float2(0.10), float2(1.0) - uv);
    float mask = edge.x * edge.y;

    // a slowly drifting, self-warping flow — the "water moving" part
    float2 p = uv * 3.2;
    float2 warp = float2(
        fbm(p + float2(time * 0.045, time * 0.030)),
        fbm(p + float2(5.2 - time * 0.035, 1.3 + time * 0.050)));
    float2 flow = float2(
        fbm(p + 2.0 * warp + float2(time * 0.020, 0.0)),
        fbm(p + 2.0 * warp + float2(8.3, 2.8 + time * 0.025)));
    // Crisp edges — lettering, lines, a logo — hold still; only soft texture flows.
    // Probed a little wider than the flow reaches, so nothing near a hard edge is
    // pulled across it.
    float r = max(amp * 1.6, 1.0);
    float q = r * 0.7071;
    float l0 = lumAt(coord);
    float hard = abs(lumAt(coord + float2(r, 0.0)) - l0);
    hard = max(hard, abs(lumAt(coord - float2(r, 0.0)) - l0));
    hard = max(hard, abs(lumAt(coord + float2(0.0, r)) - l0));
    hard = max(hard, abs(lumAt(coord - float2(0.0, r)) - l0));
    hard = max(hard, abs(lumAt(coord + float2(q, q)) - l0));
    hard = max(hard, abs(lumAt(coord - float2(q, q)) - l0));
    hard = max(hard, abs(lumAt(coord + float2(q, -q)) - l0));
    hard = max(hard, abs(lumAt(coord + float2(-q, q)) - l0));
    float calm = 1.0 - smoothstep(0.06, 0.22, hard);
    float2 d = (flow - 0.5) * 2.0 * amp * mask * calm;
    half4 c = content.eval(coord + d);

    // light playing on the highlights only, so shadows stay still and deep
    float3 rgb = float3(c.rgb);
    float lum = dot(rgb, float3(0.299, 0.587, 0.114));
    float glint = fbm(p * 2.6 + float2(time * 0.09, -time * 0.06)) - 0.5;
    float hi = smoothstep(0.42, 0.95, lum) * mask;

    // a faint light passing across the print, once every sixteen seconds
    float band = uv.x * 0.75 + uv.y * 0.55;
    float phase = fract(time / 16.0) * 2.4 - 0.55;
    float s = (band - phase) / 0.10;
    float pass = exp(-s * s) * sweep * (0.35 + 0.65 * lum);

    rgb = rgb + float3(glint * shimmer * hi) + float3(pass);
    rgb = clamp(rgb, 0.0, float(c.a));
    return half4(half3(rgb), c.a);
}
"""
}

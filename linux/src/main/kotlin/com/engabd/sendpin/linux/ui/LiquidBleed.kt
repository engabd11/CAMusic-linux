package com.engabd.sendpin.linux.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import com.engabd.sendpin.ui.design.AlbumPalette
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.abs

/*
 * The album's colours behind the player as a liquid: four of the record's colours
 * folded through each other the way inks mix in water, flowing slowly while the
 * music plays (see LivingArt for when) and resting, still mixed, when it doesn't.
 *
 * The flow is a few passes of a sine field, each bending the next — smooth at every
 * scale, so it reads as one body of liquid rather than grain or a grid. It replaces
 * the soft blooms (PanelBloom) when Settings' living colours are on; with them off, or
 * where the renderer can't run SkSL, the blooms come back.
 *
 * The liquid comes in several [LiquidStyle]s — marbled inks, an aurora's curtains, a
 * nebula's smoke, a lava lamp, folded silk — all drawn from the same four colours and
 * the same uniforms, so a style is only ever a different program over the same room.
 */

/** How the liquid looks at one of Settings' strengths. */
internal data class LiquidLook(
    /** Opacity at the top of the screen (it settles toward the bottom). */
    val alpha: Float,
    /** How distinct the colour bands are — higher is crisper marbling. */
    val sharp: Float,
    /** How many swirls fit the screen's height. */
    val scale: Float,
    /** How far each pass folds the field — the depth of the swirls. */
    val warp: Float,
    /** Flow time per second of awake playback. */
    val speed: Float,
)

/** Settings' `subtle` (strength 1) and `vivid` (2) as a look; null when off. */
internal fun liquidLook(strength: Float): LiquidLook? = when {
    strength >= 2f -> LiquidLook(alpha = 0.85f, sharp = 7f, scale = 3.2f, warp = 0.9f, speed = 0.14f)
    strength > 0f -> LiquidLook(alpha = 0.62f, sharp = 4f, scale = 2.5f, warp = 0.78f, speed = 0.08f)
    else -> null
}

/** The shape the living colours flow in — Settings → Now playing & motion → Liquid style. */
enum class LiquidStyle(
    val key: String,
    val label: String,
    val description: String,
    /** Flow speed against the strength's own, so each style moves at its natural pace. */
    val pace: Float,
) {
    MARBLE("marble", "Marble", "Inks folding through each other in water.", 1f),
    AURORA("aurora", "Aurora", "Curtains of the album's colours, hanging and swaying from the top.", 0.8f),
    NEBULA("nebula", "Nebula", "Soft smoke of colour, curling slowly like a far-off cloud.", 0.7f),
    LAVA("lava", "Lava", "Slow blobs of colour rising and merging, like a lava lamp.", 1.2f),
    SILK("silk", "Silk", "Fine ribbons with a sheen, as if the room were draped in silk.", 0.85f);

    /** The SkSL program that draws it. */
    internal val sksl: String
        get() = when (this) {
            MARBLE -> MARBLE_SKSL
            AURORA -> AURORA_SKSL
            NEBULA -> NEBULA_SKSL
            LAVA -> LAVA_SKSL
            SILK -> SILK_SKSL
        }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: MARBLE
    }
}

/** The liquid style every [AlbumBleed] draws; provided in `Main.kt`. */
val LocalLiquidStyle = compositionLocalOf { LiquidStyle.MARBLE }

/**
 * The album's colour bleed over the player's wash: liquid when [living] is on, the
 * drifting blooms otherwise. [level] scales it like [PanelBloom]'s level (1 behind
 * the cover, less behind the library's pages). [seed] gives each album its own
 * starting pattern. The shape is Settings' [LocalLiquidStyle].
 */
@Composable
fun BoxScope.AlbumBleed(palette: AlbumPalette, living: LivingArt, level: Float, drift: Boolean, seed: Int = 0) {
    val look = liquidLook(living.strength)
    val style = LocalLiquidStyle.current
    val effect = LiquidShader.effect(style)
    if (look == null || effect == null) {
        PanelBloom(palette, level, drift)
        return
    }
    // Eased over the album change's own length, as the blooms are.
    val c0 by animateColorAsState(palette.swatch(0), tween(1_200), label = "liquid0")
    val c1 by animateColorAsState(palette.swatch(1), tween(1_200), label = "liquid1")
    val c2 by animateColorAsState(palette.swatch(2), tween(1_200), label = "liquid2")
    val c3 by animateColorAsState(palette.accent, tween(1_200), label = "liquid3")
    val builder = remember(effect) { RuntimeShaderBuilder(effect) }
    val offset = (abs(seed) % 997) / 10f
    val l = level.coerceIn(0f, 1f)
    val speed = look.speed * style.pace
    Canvas(Modifier.matchParentSize()) {
        builder.uniform("size", size.width, size.height)
        // read while drawing only: the flow never recomposes
        builder.uniform("time", offset + living.time() * speed)
        builder.color("c0", c0)
        builder.color("c1", c1)
        builder.color("c2", c2)
        builder.color("c3", c3)
        builder.uniform("alpha", look.alpha * l)
        builder.uniform("sharp", look.sharp)
        builder.uniform("scale", look.scale)
        builder.uniform("warp", look.warp)
        drawRect(ShaderBrush(builder.makeShader().asComposeShader()))
    }
}

private fun RuntimeShaderBuilder.color(name: String, c: Color) = uniform(name, c.red, c.green, c.blue)

internal object LiquidShader {
    private val compiled = HashMap<LiquidStyle, RuntimeEffect?>()

    /** [style]'s program, compiled once (and never retried) where SkSL is unavailable. */
    private fun compile(style: LiquidStyle): RuntimeEffect? = synchronized(compiled) {
        compiled.getOrPut(style) { runCatching { RuntimeEffect.makeForShader(style.sksl) }.getOrNull() }
    }

    /**
     * The program to draw [style] with: its own, else the marble it grew from, else null —
     * and null means the soft blooms.
     */
    fun effect(style: LiquidStyle): RuntimeEffect? = compile(style) ?: compile(LiquidStyle.MARBLE)

    /** Whether [style] compiles on its own, without the fallback. For the tests. */
    fun compiles(style: LiquidStyle): Boolean = compile(style) != null
}

/** Every style's uniforms, in the order the builder sets them. */
private const val UNIFORMS = """
uniform float2 size;
uniform float time;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float alpha;
uniform float sharp;
uniform float scale;
uniform float warp;
"""

/**
 * Strongest at the top behind the cover, settling toward the controls so the words over
 * the lower half keep their contrast. Every style ends through it; [cover] is how much of
 * the room the style fills at this point.
 */
private const val SETTLE = """
half4 settle(float2 coord, float3 col, float cover) {
    float a = alpha * cover * mix(1.0, 0.4, smoothstep(0.35, 1.0, coord.y / size.y));
    return half4(half3(col * a), half(a));
}
"""

/** Value noise, for the styles that want softness rather than waves. */
private const val NOISE = """
float hash(float2 p) {
    // No sine: steady at large coordinates and on half-precision GPUs.
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    // Quintic fade: no lattice showing through the smoke.
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
"""

/** Four weights to one colour: sharpened, normalised, mixed. */
private const val MIX4 = """
float3 mix4(float4 w) {
    w = pow(w, float4(sharp));
    w /= (w.x + w.y + w.z + w.w + 0.0001);
    return c0 * w.x + c1 * w.y + c2 * w.z + c3 * w.w;
}
"""

private const val MARBLE_SKSL = UNIFORMS + SETTLE + MIX4 + """
half4 main(float2 coord) {
    // Height-based units, so the swirls keep their shape on a portrait or landscape panel.
    float2 p = coord / size.y * scale;
    float t = time;
    // The flow: each pass bends the field by the one before it, so the colours fold
    // into each other the way two inks do in water, never as a grid or a grain.
    for (int i = 1; i < 6; i++) {
        float fi = float(i);
        p.x += warp / fi * sin(fi * p.y + t + 0.7 * fi) + 0.3;
        p.y += warp / fi * cos(fi * p.x - 0.8 * t + 1.3 * fi) - 0.2;
    }
    // Four bands of colour laid through the folded field, one per album colour.
    float3 col = mix4(float4(
        0.5 + 0.5 * sin(p.x * 1.1 + 0.3),
        0.5 + 0.5 * sin(p.y * 1.3 + 2.1),
        0.5 + 0.5 * sin((p.x + p.y) * 0.9 + 4.2),
        0.5 + 0.5 * sin((p.x - p.y) * 1.2 + 1.0)));
    // Body: a slow light and shade through the mixture, so it reads as liquid with
    // depth rather than flat paint.
    col *= 0.72 + 0.28 * sin(p.x * 0.7 + p.y * 0.6 + 0.5 * t);
    return settle(coord, col, 1.0);
}
"""

private const val AURORA_SKSL = UNIFORMS + SETTLE + """
float3 tone(int i) {
    return i == 0 ? c0 : (i == 1 ? c1 : (i == 2 ? c2 : c3));
}
half4 main(float2 coord) {
    float2 uv = coord / size.y;
    float v = coord.y / size.y;
    float t = time;
    float3 col = float3(0.0);
    float total = 0.0;
    // Four curtains, one per colour, each swaying on its own and hanging to its own depth.
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float x = uv.x * scale * (0.55 + 0.22 * fi)
            + warp * 0.7 * sin(uv.y * 2.2 + t * 0.6 + fi * 1.7)
            + t * (0.12 + 0.04 * fi);
        float band = pow(0.5 + 0.5 * sin(x * 3.0 + fi * 2.1), sharp * 0.6 + 1.0);
        // the lower hem ripples, so the curtains never end on a straight line
        float hem = 0.55 + 0.12 * fi + 0.12 * sin(uv.x * 1.6 + t * 0.4 + fi * 2.3);
        float hang = 1.0 - smoothstep(hem - 0.35, hem + 0.1, v);
        // fine vertical rays inside each curtain
        float rays = 0.75 + 0.25 * sin(x * 23.0 + fi);
        float w = band * hang * rays;
        col += tone(i) * w;
        total += w;
    }
    col /= max(total, 0.0001);
    // a faint sky of the same colours under and between the curtains
    float cover = clamp(total * 0.85, 0.0, 1.0);
    return settle(coord, col, max(cover, 0.18));
}
"""

private const val NEBULA_SKSL = UNIFORMS + SETTLE + NOISE + MIX4 + """
half4 main(float2 coord) {
    float2 p = coord / size.y * scale * 0.9;
    float t = time * 0.6;
    // Smoke folded by smoke: two noise fields push a third, so it curls rather than drifts.
    float2 q = float2(fbm(p + float2(0.0, t * 0.35)), fbm(p + float2(5.2, 1.3) - t * 0.3));
    float r = fbm(p + warp * 2.4 * q + float2(t * 0.12, -t * 0.08));
    float3 col = mix4(float4(r, q.x, q.y, 1.0 - r) + 0.15);
    // brighter where the cloud is thick, thinner in the gaps between
    float density = smoothstep(0.2, 0.85, r);
    col *= 0.55 + 0.7 * density;
    return settle(coord, col, mix(0.35, 1.0, density));
}
"""

private const val LAVA_SKSL = UNIFORMS + SETTLE + """
float3 tone(float i) {
    float k = mod(i, 4.0);
    return k < 1.0 ? c0 : (k < 2.0 ? c1 : (k < 3.0 ? c2 : c3));
}
half4 main(float2 coord) {
    float2 uv = coord / size.y;
    float aspect = size.x / size.y;
    float t = time * 0.35;
    float field = 0.0;
    float3 col = float3(0.0);
    // Seven soft blobs rising at their own pace, wrapping round below the screen.
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float x = aspect * (0.5 + 0.38 * sin(t * (0.4 + 0.07 * fi) + fi * 2.4));
        float y = 1.35 - mod(t * (0.10 + 0.025 * fi) + fi * 0.29, 1.7);
        float r = (0.17 + 0.05 * sin(fi * 1.9)) * (0.7 + 0.3 * warp) * (3.0 / scale + 0.2);
        // taller than wide, as warm wax is, so neighbours reach each other and merge
        float2 d = (uv - float2(x, y)) * float2(1.0, 0.72);
        float f = r * r / max(dot(d, d), 0.00001);
        field += f;
        col += tone(fi) * f;
    }
    col /= max(field, 0.0001);
    // a soft-edged body — the wax — with a gentle glow around it
    float body = smoothstep(0.85, 0.85 + 1.2 / sharp, field);
    float glow = clamp(field * 0.35, 0.0, 0.4);
    col *= 0.8 + 0.25 * body;
    return settle(coord, col, max(body, glow));
}
"""

private const val SILK_SKSL = UNIFORMS + SETTLE + MIX4 + """
half4 main(float2 coord) {
    float2 p = coord / size.y * scale;
    float t = time;
    // A gentle fold, then fine ribbons laid along it.
    for (int i = 1; i < 5; i++) {
        float fi = float(i);
        p += warp * 0.55 / fi * float2(sin(fi * p.y * 0.8 + t * 0.6 + fi), cos(fi * p.x * 0.8 - t * 0.5 + 2.0 * fi));
    }
    float s = p.x * 2.2 + p.y * 1.4;
    float3 col = mix4(float4(
        0.5 + 0.5 * sin(s * 0.7 + 0.3),
        0.5 + 0.5 * sin(s * 0.9 + 2.1),
        0.5 + 0.5 * sin(s * 0.6 + 4.2 + p.y),
        0.5 + 0.5 * sin(s * 0.8 + 1.0 - p.x)));
    // the weave: light and shade across each ribbon, and a narrow sheen along its crest
    float ribbon = 0.5 + 0.5 * sin(s * 3.2);
    float sheen = pow(0.5 + 0.5 * sin(s * 3.2 + 1.1), 14.0);
    col = col * (0.62 + 0.38 * ribbon) + sheen * 0.22;
    return settle(coord, col, 1.0);
}
"""

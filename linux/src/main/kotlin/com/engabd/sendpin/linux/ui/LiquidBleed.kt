package com.engabd.sendpin.linux.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
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

/**
 * The album's colour bleed over the player's wash: liquid when [living] is on, the
 * drifting blooms otherwise. [level] scales it like [PanelBloom]'s level (1 behind
 * the cover, less behind the library's pages). [seed] gives each album its own
 * starting pattern.
 */
@Composable
fun BoxScope.AlbumBleed(palette: AlbumPalette, living: LivingArt, level: Float, drift: Boolean, seed: Int = 0) {
    val look = liquidLook(living.strength)
    val effect = LiquidShader.effect
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
    Canvas(Modifier.matchParentSize()) {
        builder.uniform("size", size.width, size.height)
        // read while drawing only: the flow never recomposes
        builder.uniform("time", offset + living.time() * look.speed)
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

private object LiquidShader {
    /** Compiled once; null (and never retried) where SkSL is unavailable. */
    val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull() }

    private const val SKSL = """
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
    float4 w = float4(
        0.5 + 0.5 * sin(p.x * 1.1 + 0.3),
        0.5 + 0.5 * sin(p.y * 1.3 + 2.1),
        0.5 + 0.5 * sin((p.x + p.y) * 0.9 + 4.2),
        0.5 + 0.5 * sin((p.x - p.y) * 1.2 + 1.0));
    w = pow(w, float4(sharp));
    w /= (w.x + w.y + w.z + w.w + 0.0001);
    float3 col = c0 * w.x + c1 * w.y + c2 * w.z + c3 * w.w;
    // Body: a slow light and shade through the mixture, so it reads as liquid with
    // depth rather than flat paint.
    col *= 0.72 + 0.28 * sin(p.x * 0.7 + p.y * 0.6 + 0.5 * t);
    // Strongest at the top behind the cover, settling toward the controls so the
    // words over the lower half keep their contrast.
    float a = alpha * mix(1.0, 0.4, smoothstep(0.35, 1.0, coord.y / size.y));
    return half4(half3(col * a), half(a));
}
"""
}

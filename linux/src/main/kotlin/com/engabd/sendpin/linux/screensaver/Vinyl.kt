package com.engabd.sendpin.linux.screensaver

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.linux.ui.AlbumArtStage
import com.engabd.sendpin.linux.ui.NowArt
import kotlin.math.sin

/*
 * The listening view's Vinyl layout: the record on the platter, its cover as the centre
 * label, turning at 33⅓ while the music plays. Pausing lets it run down the way a
 * turntable does; play brings it back up to speed. The light on the record stays where
 * it is while the record turns under it, which is what makes it read as a record.
 */

/** 33⅓ rpm, in degrees a second. */
internal const val RPM_33_DEG_S = 200f

/** How far the record turns in [dtS] seconds at [speed] (0 stopped → 1 full speed). */
internal fun spinStep(angle: Float, dtS: Float, speed: Float): Float =
    (angle + RPM_33_DEG_S * dtS * speed.coerceIn(0f, 1f)) % 360f

/** Spin-up is a quick catch, run-down a long coast — as on a belt-drive deck. */
internal fun spinEaseMs(playing: Boolean): Int = if (playing) 1_400 else 3_200

/** The record's angle, as a deferred read for `graphicsLayer` — never a recomposition. */
@Composable
internal fun rememberSpin(playing: Boolean): () -> Float {
    val speed = remember { Animatable(0f) }
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        speed.animateTo(
            if (playing) 1f else 0f,
            tween(spinEaseMs(playing), easing = if (playing) FastOutSlowInEasing else LinearOutSlowInEasing),
        )
    }
    LaunchedEffect(Unit) {
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0 && speed.value > 0f) {
                    angle.floatValue = spinStep(angle.floatValue, (now - last) / 1_000_000_000f, speed.value)
                }
                last = now
            }
        }
    }
    return { angle.floatValue }
}

/**
 * The record, square, filling [modifier]'s width: grooves in the album's [tint], the
 * cover as its label, the room's light fixed across it.
 */
@Composable
internal fun VinylRecord(art: NowArt, tint: Color, playing: Boolean, transition: String, modifier: Modifier) {
    val angle = rememberSpin(playing)
    val groove by animateColorAsState(tint, tween(1_200), label = "vinylTint")
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // The disc and its grooves turn; the label turns with them.
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = center
                drawCircle(
                    Brush.radialGradient(
                        0f to Color(0xFF15151A),
                        0.7f to Color(0xFF0B0B0E),
                        1f to Color(0xFF050506),
                        center = c, radius = r,
                    ),
                    radius = r,
                )
                // The grooves: fine rings, each a little brighter or darker than the next,
                // with the album's colour caught in a few of them.
                val labelR = r * 0.36f
                val step = (r / 140f).coerceAtLeast(1.5f)
                var ring = labelR + r * 0.05f
                var i = 0
                while (ring < r * 0.965f) {
                    val wobble = 0.5f + 0.5f * sin(i * 1.7f + i * i * 0.013f)
                    val inked = i % 23 == 0
                    drawCircle(
                        if (inked) groove.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.018f + 0.03f * wobble),
                        radius = ring,
                        center = c,
                        style = Stroke(width = step * 0.5f),
                    )
                    ring += step
                    i++
                }
                // A marker in the run-out groove, so the turning is visible on a plain label.
                drawCircle(Color.White.copy(alpha = 0.10f), radius = r * 0.012f, center = Offset(c.x, c.y - r * 0.39f))
            }
            AlbumArtStage(
                art = art,
                modifier = Modifier.fillMaxWidth(0.34f).aspectRatio(1f),
                transition = transition,
                glow = Color.Transparent,
                shape = CircleShape,
                glowElevation = 0.dp,
                contentDescription = null,
            )
            // The spindle.
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0xFF0A0A0C), radius = size.minDimension * 0.012f)
                drawCircle(Color.White.copy(alpha = 0.25f), radius = size.minDimension * 0.012f, style = Stroke(1f))
            }
        }
        // The room's light, still while the record turns under it: two soft sheens
        // across the grooves and a lit rim.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                Brush.sweepGradient(
                    0.00f to Color.Transparent,
                    0.10f to Color.White.copy(alpha = 0.10f),
                    0.20f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.60f to groove.copy(alpha = 0.10f),
                    0.70f to Color.Transparent,
                    1.00f to Color.Transparent,
                    center = center,
                ),
                radius = r * 0.96f,
            )
            drawCircle(Color.White.copy(alpha = 0.07f), radius = r - 1f, style = Stroke(2f))
        }
    }
}

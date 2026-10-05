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
import com.engabd.sendpin.linux.PanelModel

/*
 * Living colours: a little way into each track the album's colours behind the player
 * begin to flow, mixing like a liquid (LiquidBleed.kt draws it). This file is only the
 * *when* and *how fast*: an awake level that eases in after the mark and out on pause,
 * and a flow clock that advances by that level — so the liquid gathers speed as it wakes
 * and slows to a stop as it settles, and never jumps.
 *
 * The cover itself stays still: moving the picture's own pixels read as shimmer, not
 * motion, so the life is in the room around the record instead.
 *
 * The clock is read only while drawing (no recomposition per frame), runs only while
 * awake and visible, and ticks at ~30 fps — the flow is far too slow to need more on a
 * panel that is always on.
 */

/** Settings' `off` | `subtle` | `vivid` → how strongly the colours move. */
internal fun livingStrength(key: String): Float = when (key) {
    "subtle" -> 1f
    "vivid" -> 2f
    else -> 0f
}

/**
 * Whether the colours should be flowing: playing, and [delayMs] into the track. Paused,
 * or a fresh track before its mark, they settle. Pure for the tests.
 */
internal fun aliveTarget(posMs: Long, playing: Boolean, delayMs: Long): Float =
    if (playing && posMs >= delayMs) 1f else 0f

/** The awake level (0..1, eased) and the flow clock, as deferred reads. */
@Stable
class LivingArt internal constructor(
    val strength: Float,
    private val levelRead: () -> Float,
    private val timeRead: () -> Float,
) {
    /** 0 settled → 1 fully flowing; read only in draw lambdas. */
    fun level(): Float = levelRead()

    /** Flow time: seconds of awake playback, each weighted by how awake it was. */
    fun time(): Float = timeRead()

    companion object {
        /** No living colours at all — the soft blooms instead. */
        val Still = LivingArt(0f, { 0f }, { 0f })
    }
}

/** The ~30 fps tick: the flow is slow enough that a frame more buys nothing. */
private const val TICK_S = 1f / 30f

/**
 * The playing track's living-colours state, from the settings and the transport. [asleep]
 * forces it down; the overlay flag in [PanelModel] stops the clock while something covers
 * the screen — unless this is itself drawn on the overlay ([onOverlay]).
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
        // wakes slowly — noticed a while after it began, not at once — and settles gently
        level.animateTo(target, tween(if (target > 0f) 4_000 else 2_500, easing = FastOutSlowInEasing))
    }
    val awake by remember { derivedStateOf { level.value > 0f } }
    LaunchedEffect(awake, obscured) {
        if (!awake || obscured) return@LaunchedEffect
        var flow = clock.floatValue
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0) flow += (now - last) / 1_000_000_000f * level.value
                last = now
                if (flow - clock.floatValue >= TICK_S) clock.floatValue = flow
            }
        }
    }
    return remember(strength) { LivingArt(strength, { level.value }, { clock.floatValue }) }
}

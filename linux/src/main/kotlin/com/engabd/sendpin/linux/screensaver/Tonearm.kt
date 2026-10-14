package com.engabd.sendpin.linux.screensaver

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.station.Deck
import com.engabd.sendpin.ui.theme.MonoFont
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The tonearm: it waits at its rest while the record is still, swings out and lowers onto
 * the lead-in when play starts, rides inward as the side plays, and lifts and returns when
 * it stops. The geometry is the Deck's (station/Groove.kt); this file is the choreography
 * and the drawing, plus the optional hand on the arm that sets it down on another track.
 */

/**
 * What the tonearm and the rings need to know about the side on the platter. [starts] are the
 * tracks' beginnings along the groove (0..1); [current] is the playing track's place among
 * them; [progress] is how far along the side the music is. [onSeek] (null for a look-only
 * arm) is called with where the stylus was set down; [label] names that place while dragging.
 */
internal class ArmSpec(
    val starts: List<Float>,
    val current: Int,
    val progress: Float,
    val onSeek: ((Float) -> Unit)? = null,
    val label: (Float) -> String = { "" },
)

private val Metal = Color(0xFFD3D5DB)
private val Dark = Color(0xFF1A1B1F)

/** The tonearm over a deck square (its modifier gives the square). */
@Composable
internal fun Tonearm(arm: ArmSpec, playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val swing = remember { Animatable(0f) }
    val lift = remember { Animatable(1f) }
    LaunchedEffect(playing) {
        if (playing) {
            // Out from the rest, held up; down onto the lead-in; the record is already turning.
            lift.animateTo(1f, tween(250))
            swing.animateTo(1f, tween(1_700, easing = FastOutSlowInEasing))
            lift.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
        } else {
            // Up off the groove, back to the rest.
            lift.animateTo(1f, tween(380))
            swing.animateTo(0f, tween(1_500, easing = FastOutSlowInEasing))
        }
    }
    // Poll readings arrive once a second; glide between them rather than step.
    val glide by animateFloatAsState(arm.progress, tween(1_000, easing = LinearEasing), label = "armGlide")
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val angleNow = { armAngle(dragFraction ?: glide, swing.value) }
    val angle by rememberUpdatedState(angleNow)
    val onSeek by rememberUpdatedState(arm.onSeek)

    Box(
        modifier
            .fillMaxSize()
            .then(
                if (arm.onSeek == null) Modifier
                else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val (tx, ty) = Deck.tip(angle())
                        // Near the stylus or the headshell — anywhere else the touch is not
                        // the arm's and passes on (to wake the panel).
                        val back = Deck.tip(angle()).let { (x, y) ->
                            val dx = x - Deck.PX
                            val dy = y - Deck.PY
                            (Deck.PX + dx * 0.78f) to (Deck.PY + dy * 0.78f)
                        }
                        val hit = 60.dp.toPx()
                        val near = hypot(down.position.x - tx * w, down.position.y - ty * w) <= hit ||
                            hypot(down.position.x - back.first * w, down.position.y - back.second * w) <= hit
                        if (!near) return@awaitEachGesture
                        down.consume()
                        val completed = drag(down.id) { change ->
                            change.consume()
                            dragFraction = Deck.fractionAtPoint(change.position.x / w, change.position.y / w)
                        }
                        val f = dragFraction
                        dragFraction = null
                        if (completed && f != null) onSeek?.invoke(f)
                    }
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawArm(angle(), lift.value, tint)
        }
        dragFraction?.let { f ->
            Text(
                arm.label(f),
                color = Color.White,
                fontFamily = MonoFont,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** The arm's direction at [fraction] along the side, [swing] of the way from its rest. */
internal fun armAngle(fraction: Float, swing: Float): Float =
    Deck.PARKED + (Deck.angleAt(fraction) - Deck.PARKED) * swing.coerceIn(0f, 1f)

private fun DrawScope.drawArm(angleDeg: Float, lift: Float, tint: Color) {
    val w = size.width
    val rad = angleDeg * PI.toFloat() / 180f
    val dx = cos(rad)
    val dy = sin(rad)
    val pivot = Offset(w * Deck.PX, w * Deck.PY)
    val length = w * Deck.ARM
    val tip = Offset(pivot.x + dx * length, pivot.y + dy * length)
    // Perpendicular, for the highlight along the tube.
    val nx = -dy
    val ny = dx

    // Shadow: close and dark with the stylus down, farther and fainter lifted.
    val so = w * (0.006f + 0.026f * lift)
    drawLine(
        Color.Black.copy(alpha = 0.40f - 0.20f * lift),
        Offset(pivot.x + so, pivot.y + so * 1.4f), Offset(tip.x + so, tip.y + so * 1.4f),
        strokeWidth = w * 0.013f, cap = StrokeCap.Round,
    )

    // The base the arm turns on.
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = w * 0.066f, center = Offset(pivot.x + so * 0.6f, pivot.y + so * 0.9f))
    drawCircle(Color(0xFF0D0D10), radius = w * 0.062f, center = pivot)
    drawCircle(Color.White.copy(alpha = 0.10f), radius = w * 0.062f, center = pivot, style = Stroke(2f))
    drawCircle(Color(0xFF2B2C32), radius = w * 0.036f, center = pivot)

    withTransform({ scale(1f + 0.035f * lift, 1f + 0.035f * lift, pivot) }) {
        // Counterweight behind the pivot.
        val cw = Offset(pivot.x - dx * w * 0.105f, pivot.y - dy * w * 0.105f)
        drawLine(Metal, pivot, cw, strokeWidth = w * 0.012f, cap = StrokeCap.Round)
        drawCircle(Color(0xFF25262B), radius = w * 0.038f, center = cw)
        drawCircle(Color.White.copy(alpha = 0.14f), radius = w * 0.038f, center = cw, style = Stroke(2f))

        // The tube, with a highlight along it.
        drawLine(Metal, pivot, tip, strokeWidth = w * 0.011f, cap = StrokeCap.Round)
        drawLine(
            Color.White.copy(alpha = 0.55f),
            Offset(pivot.x + nx * w * 0.0028f, pivot.y + ny * w * 0.0028f),
            Offset(tip.x + nx * w * 0.0028f, tip.y + ny * w * 0.0028f),
            strokeWidth = w * 0.0030f, cap = StrokeCap.Round,
        )

        // The headshell, its nose in the album's colour.
        val shellBack = Offset(tip.x - dx * w * 0.078f, tip.y - dy * w * 0.078f)
        drawLine(Dark, shellBack, tip, strokeWidth = w * 0.028f, cap = StrokeCap.Round)
        drawLine(
            tint.copy(alpha = 0.95f),
            Offset(tip.x - dx * w * 0.026f, tip.y - dy * w * 0.026f), tip,
            strokeWidth = w * 0.028f, cap = StrokeCap.Round,
        )
    }

    // A point of light where the stylus meets the groove, only while it is down.
    val contact = (1f - 2f * lift).coerceIn(0f, 1f)
    if (contact > 0f) {
        drawCircle(tint.copy(alpha = 0.55f * contact), radius = w * 0.016f, center = tip)
        drawCircle(Color.White.copy(alpha = 0.85f * contact), radius = w * 0.0045f, center = tip)
    }
}

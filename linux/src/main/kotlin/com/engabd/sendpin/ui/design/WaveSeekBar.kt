package com.engabd.sendpin.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The desktop twin of :app's `WaveSeekBar` (SendspinDesign.kt) — the wavy progress
 * bar: an accent wave up to the knob, tapering to the flat rail exactly where the
 * knob sits, a muted rail after it, and a knob that swells under the finger.
 * Everything travel-related (wavelength, amplitude, period, taper, sampling) is
 * copied verbatim so the panel's bar IS the phone's bar.
 *
 * Two deliberate departures, both forced by the desktop fork:
 *  - `LocalReducedMotion` does not exist here (the theme fork dropped the
 *    reduced-motion plumbing), so the wave always travels. A panel is not a
 *    phone with an accessibility motion setting.
 *  - The magnifier bubble is not ported; this panel is ten inches from the
 *    finger, and the track times printed either side of the bar already say
 *    where a release lands.
 */
private val WaveLength = 34.dp
private val WaveAmplitude = 5.dp
private val WaveGap = 4.dp
private val WaveSampleStep = 2.dp
private const val WAVE_PERIOD_MS = 1600
private const val WAVE_AMPLITUDE_MS = 500
private val SliderTouchHeight = 44.dp

/** The committed value is held this long, or until the incoming value catches up. */
private const val SLIDER_LATCH_MS = 1_500L
private const val SLIDER_LATCH_EPSILON = 0.004f

/**
 * Drag state shared by the bar's two detectors — the latch is what stops the bar
 * snapping back to the last polled position for the round-trip of the seek.
 * Ported verbatim from :app's private SliderGesture.
 */
@Stable
private class SliderGesture {
    var dragging by mutableStateOf(false)
    var dragValue by mutableFloatStateOf(0f)

    /** The last committed value, held until the incoming value catches up. */
    var latched by mutableStateOf<Float?>(null)

    /** Bumped on every commit so the release effect restarts. */
    var latchSeq by mutableIntStateOf(0)

    fun begin(f: Float) { dragging = true; dragValue = f.coerceIn(0f, 1f) }

    fun move(f: Float) { dragValue = f.coerceIn(0f, 1f) }

    fun finish(f: Float) {
        dragging = false
        latched = f.coerceIn(0f, 1f)
        latchSeq++
    }

    /** What to paint for an incoming [value]. */
    fun display(value: Float): Float =
        (if (dragging) dragValue else latched ?: value).coerceIn(0f, 1f)
}

@Composable
private fun rememberSliderGesture(value: Float): SliderGesture {
    val gesture = remember { SliderGesture() }
    val incoming = rememberUpdatedState(value)
    LaunchedEffect(gesture.latchSeq) {
        val target = gesture.latched ?: return@LaunchedEffect
        withTimeoutOrNull(SLIDER_LATCH_MS) {
            snapshotFlow { incoming.value }.first { abs(it - target) <= SLIDER_LATCH_EPSILON }
        }
        gesture.latched = null
    }
    return gesture
}

/**
 * Tap and drag handling — ported from :app's sliderInput, including the
 * commit-on-cancel: these sliders sit inside vertically draggable surfaces, and
 * a drag with any vertical component can be taken over by the cover's collapse
 * gesture, which must still land the value the finger chose.
 */
private fun Modifier.sliderInput(
    gesture: SliderGesture,
    width: () -> Int,
    onChange: (Float) -> Unit,
    commit: (Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures { o ->
            val f = (o.x / width()).coerceIn(0f, 1f)
            onChange(f)
            gesture.finish(f)
            commit(f)
        }
    }
    .pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { o ->
                gesture.begin(o.x / width())
                onChange(gesture.dragValue)
            },
            onDragEnd = { gesture.finish(gesture.dragValue); commit(gesture.dragValue) },
            onDragCancel = { gesture.finish(gesture.dragValue); commit(gesture.dragValue) },
        ) { change, _ ->
            gesture.move(change.position.x / width())
            onChange(gesture.dragValue)
        }
    }

@Composable
fun WaveSeekBar(
    value: Float,
    onChange: (Float) -> Unit,
    playing: Boolean,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    knob: Dp = 13.dp,
    onCommit: ((Float) -> Unit)? = null,
) {
    val accent = rememberAccent()
    var widthPx by remember { mutableIntStateOf(1) }
    val gesture = rememberSliderGesture(value)
    val v = gesture.display(value)

    fun commit(f: Float) {
        val c = f.coerceIn(0f, 1f)
        if (onCommit != null) onCommit(c) else onChange(c)
    }

    // The wave travels; the amplitude rises with playback and settles flat when
    // paused — paused music has no motion to borrow.
    val infinite = rememberInfiniteTransition(label = "waveSeekBarPhase")
    val travelling by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(WAVE_PERIOD_MS, easing = LinearEasing)),
        label = "phase",
    )
    val amplitudeDp by animateFloatAsState(
        targetValue = if (playing) WaveAmplitude.value else 0f,
        animationSpec = tween(WAVE_AMPLITUDE_MS),
        label = "waveAmplitude",
    )

    // Allocated once, not once per frame — a fresh Path and Brush each frame is
    // 120 objects a second for a bar whose shape never changes (see :app's note).
    val wavePath = remember { Path() }
    val accentBrush = remember(accent) { Brush.horizontalGradient(listOf(accent.a(0.5f), accent)) }

    Box(
        modifier
            .fillMaxWidth()
            .height(SliderTouchHeight)
            .onSizeChanged { widthPx = if (it.width > 0) it.width else 1 }
            .sliderInput(gesture, { widthPx }, onChange, ::commit),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fill = v.coerceIn(0f, 1f)
        // Resolved in composition, referenced (not called) in the draw lambda:
        // TextPrimary is @Composable, a Canvas draw lambda is not.
        val trackColor = TextPrimary.copy(alpha = 0.14f)
        val knobColor = TextPrimary
        val dragging = gesture.dragging
        Canvas(Modifier.matchParentSize()) {
            val centerY = size.height / 2f
            val strokePx = trackHeight.toPx()
            val fillWidthPx = size.width * fill
            val amplitudePx = amplitudeDp.dp.toPx()
            val wavelengthPx = WaveLength.toPx()
            // The wave arrives at the rail's height by smoothstepping its amplitude
            // to nothing across the stretch the knob covers — no step, no damping.
            val taperPx = knob.toPx().coerceAtLeast(1f)
            fun waveY(x: Float): Float {
                val t = ((fillWidthPx - x) / taperPx).coerceIn(0f, 1f)
                val envelope = t * t * (3f - 2f * t)
                return centerY + envelope * amplitudePx *
                    sin((2 * PI * x / wavelengthPx) + travelling).toFloat()
            }

            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val gapPx = WaveGap.toPx()

            // What has played: the wave, in the accent, from the start to the knob.
            if (fillWidthPx > 0f) {
                val stepPx = WaveSampleStep.toPx().coerceAtLeast(1f)
                wavePath.reset()
                var prevX = 0f
                var prevY = waveY(0f)
                wavePath.moveTo(prevX, prevY)
                var x = stepPx
                while (x < fillWidthPx) {
                    val y = waveY(x)
                    wavePath.quadraticBezierTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                    prevX = x
                    prevY = y
                    x += stepPx
                }
                wavePath.quadraticBezierTo(prevX, prevY, fillWidthPx, waveY(fillWidthPx))
                drawPath(wavePath, brush = accentBrush, style = stroke)
            }

            // What has not: a straight muted rail, picking up after the gap.
            val railStart = fillWidthPx + gapPx
            if (railStart < size.width) {
                drawLine(
                    color = trackColor,
                    start = Offset(railStart, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }

            // The knob rides the centre line — the seam between wave and rail.
            val knobPx = knob.toPx()
            drawCircle(
                color = knobColor,
                radius = (if (dragging) knobPx * 1.35f else knobPx) / 2f,
                center = Offset(fillWidthPx, centerY),
            )
        }
    }
}

/**
 * A soft radial glow — the design's "bloom". Place inside a Box behind content.
 * Desktop twin of :app's Bloom (SendspinDesign.kt), verbatim geometry.
 */
@Composable
fun Bloom(color: Color, size: Dp, x: Dp, y: Dp, alpha: Float = 0.5f) {
    Box(
        Modifier
            .offset(x, y)
            .size(size)
            .background(
                Brush.radialGradient(listOf(color.a(alpha), Color.Transparent)),
                CircleShape,
            )
    )
}

/**
 * The album's colours bleeding across the player canvas — :app's ChameleonBloom.
 * Painted OVER the wash, never over the content: a sibling of the player column,
 * drawn before it, so text keeps its contrast however bright the album is.
 * A track change melts to the new palette via the eased colours.
 */
@Composable
fun BoxScope.ChameleonBloom(palette: AlbumPalette, level: Float) {
    val l = level.coerceIn(0f, 1f)
    if (l <= 0f) return

    val primary by animateColorAsState(palette.swatch(0), tween(220), label = "chameleonPrimary")
    val secondary by animateColorAsState(palette.swatch(1), tween(220), label = "chameleonSecondary")

    Bloom(primary, 540.dp, (-120).dp, (-180).dp, 0.50f * l)
    Bloom(secondary, 460.dp, (-60).dp, (-40).dp, 0.34f * l)
    Bloom(secondary, 420.dp, (-140).dp, 340.dp, 0.20f * l)
    Bloom(primary, 380.dp, 40.dp, 320.dp, 0.24f * l)
}
package com.engabd.sendpin.linux.station

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.theme.MonoFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The gestures a touch product is expected to have: swipe the cover to skip, drag the edge
 * for volume. Each is a switch in Settings (Touch gestures); with it off none of this is
 * attached and the cover and rows behave as before.
 */

/** What a horizontal swipe over the cover means. */
internal enum class SwipeAction { NONE, NEXT, PREVIOUS }

/**
 * A swipe of [dxPx] over a cover [widthPx] wide: left skips forward, right goes back — but
 * only once it is a deliberate distance (the larger of [minPx] and a fifth of the width), so
 * a tap with a little wobble never skips a track.
 */
internal fun swipeAction(dxPx: Float, widthPx: Float, minPx: Float): SwipeAction {
    val needed = maxOf(minPx, widthPx * 0.2f)
    return when {
        dxPx <= -needed -> SwipeAction.NEXT
        dxPx >= needed -> SwipeAction.PREVIOUS
        else -> SwipeAction.NONE
    }
}

/** Whether dragging a queue row [dxPx] left (of a row [widthPx] wide) takes it off the queue. */
internal fun rowSwipeRemoves(dxPx: Float, widthPx: Float): Boolean = widthPx > 0f && dxPx <= -widthPx * 0.35f

/** The new volume (0..1) after a drag of [dyPx] up (negative) or down (positive) a rail [heightPx] tall. */
internal fun volumeAfterDrag(start: Float, dyPx: Float, heightPx: Float): Float =
    if (heightPx <= 0f) start else (start - dyPx / heightPx).coerceIn(0f, 1f)

/**
 * Swiping the cover sideways skips: the cover follows the finger a little (damped), then
 * settles back as the track changes. Attach to the cover's box; [enabled] false attaches nothing.
 */
@Composable
internal fun Modifier.swipeToSkip(enabled: Boolean, onNext: () -> Unit, onPrevious: () -> Unit): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val min = with(LocalDensity.current) { 96.dp.toPx() }
    var total by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    return this
        .graphicsLayer { translationX = shift.value * 0.35f }
        .pointerInput(Unit) {
            width = size.width.toFloat()
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    when (swipeAction(total, width, min)) {
                        SwipeAction.NEXT -> next()
                        SwipeAction.PREVIOUS -> previous()
                        SwipeAction.NONE -> Unit
                    }
                    scope.launch { shift.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) }
                },
                onDragCancel = { scope.launch { shift.animateTo(0f) } },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    total += dx
                    scope.launch { shift.snapTo(total) }
                },
            )
        }
}

/**
 * A slim rail down the cover's right edge: drag it up and down for MPD's volume. Drawn only
 * when MPD has a volume to set ([volume] non-null); while a finger is on it the level shows.
 * Anything MPD cannot do — a fixed bit-perfect output — leaves it out entirely.
 */
@Composable
internal fun VolumeRail(volume: Float?, onVolume: (Float) -> Unit, modifier: Modifier = Modifier) {
    if (volume == null) return
    var dragging by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(volume) }
    var railPx by remember { mutableFloatStateOf(1f) }
    val onChange by rememberUpdatedState(onVolume)
    // What MPD says when no finger is down; while dragging the finger leads.
    LaunchedEffect(volume, dragging) { if (!dragging) level = volume }
    // Commands go out a few times a second, not once per pointer sample.
    LaunchedEffect(dragging) {
        var last = -1f
        while (dragging) {
            if (level != last) { onChange(level); last = level }
            delay(120)
        }
        onChange(level)
    }
    Box(
        modifier
            .fillMaxHeight()
            .width(56.dp)
            .pointerInput(Unit) {
                railPx = size.height.toFloat()
                var start = 0f
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { start = level; travelled = 0f; dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        travelled += dy
                        // The visible bar is the middle half of the rail: a full stroke across it is 0 to 100.
                        level = volumeAfterDrag(start, travelled, railPx * 0.5f)
                    },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // The handle: a slim line, brighter and longer while it is held.
        val fill = if (dragging) 0.9f else 0.28f
        Box(
            Modifier
                .padding(end = 10.dp)
                .width(if (dragging) 8.dp else 5.dp)
                .fillMaxHeight(0.5f)
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .background(Color.White.copy(alpha = fill), RoundedCornerShape(50)),
            )
        }
        if (dragging) {
            Text(
                "${(level * 100).roundToInt()}%",
                color = Color.White,
                fontFamily = MonoFont,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(end = 28.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** A queue row dragged by its handle: where it lands, as a queue index, after [dyPx] of travel. */
internal fun rowDropIndex(from: Int, dyPx: Float, rowPx: Float, size: Int): Int =
    if (rowPx <= 0f || size <= 0) from else (from + (dyPx / rowPx).roundToInt()).coerceIn(0, size - 1)

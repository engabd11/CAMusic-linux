package com.engabd.sendpin.linux.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Makes a `LazyListState` / `LazyGridState` / `ScrollState` respond to a finger, not just a
 * wheel.
 *
 * Root cause (verified against the actual Compose Foundation sources, not just observed
 * behaviour): AWT has no touch input path on X11 — libinput synthesises *mouse* events from
 * touch contacts, so Compose sees `PointerType.Mouse` for every finger on this panel.
 * Foundation's own `scrollable` deliberately refuses to drag-scroll from a mouse pointer —
 * that gate is what keeps click-drag text/list selection working with a real mouse — via an
 * internal predicate in `androidx.compose.foundation.gestures.AbstractScrollableNode`:
 *
 * ```kotlin
 * // TODO: provide public way to drag by mouse (especially requested for Pager)
 * internal val CanDragCalculation: (PointerType) -> Boolean = { type -> type != PointerType.Mouse }
 * ```
 *
 * That predicate is `internal`, wired into `canDrag` in `AbstractScrollableNode.kt`,
 * `Scrollable.kt` and `Scrollable2D.kt`, and upstream's own TODO says there is no public
 * escape hatch yet — no flag or CompositionLocal turns it off. So this modifier does not try
 * to persuade `scrollable` to accept the pointer; it drives the same `ScrollableState` from
 * an independent `pointerInput` gesture that never asks what `PointerType` it got.
 *
 * One side effect worth keeping in mind: since the gate is on pointer *type*, this also lets
 * a real USB mouse plugged into the NUC drag-scroll (it previously only had the wheel). That
 * is harmless here — and arguably an improvement on a panel — but it is why this modifier
 * must not be applied over anything that wants mouse-drag for its own purpose (a text field's
 * selection, the seek `Slider`).
 *
 * Gesture shape:
 * - Nothing is consumed until touch slop is exceeded, so a tap that lands on a clickable
 *   album tile or track row still reaches that row's `clickable` — only a real drag is
 *   stolen here.
 * - While dragging, each frame's delta goes straight to `state.dispatchRawDelta`. That is
 *   synchronous, so there is no per-pointer-event coroutine and no risk of deltas from two
 *   consecutive events being applied out of order.
 * - On release, the tracked velocity feeds a spline decay so a flick coasts to a stop instead
 *   of dropping dead the instant the finger lifts.
 */
@Composable
fun Modifier.touchDragScroll(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
): Modifier {
    // `pointerInput` remembers its own gesture-detection coroutine keyed on `state`/
    // `orientation`, restarting it if either changes — no outer `remember` needed here, and
    // wrapping it in one would just be a second, redundant memo of the same lambda.
    val density = LocalDensity.current
    return this.pointerInput(state, orientation) {
        // `awaitEachGesture`'s block runs with a `@RestrictsSuspension` receiver
        // (`AwaitPointerEventScope`) that can only call other pointer-scoped suspend
        // functions — `animateDecay` is not one of those, so it cannot be awaited directly
        // on release. Wrapping the gesture loop in `coroutineScope` gives it an ordinary
        // `CoroutineScope` to `launch` the post-release fling on instead — `launch` isn't
        // shadowed by the restricted receiver below since `AwaitPointerEventScope` doesn't
        // declare one, so it resolves to this outer scope.
        coroutineScope {
            // Tracks the in-flight fling across gestures, not just within one — without
            // this, "touch to stop a coasting list" (the most reflexive touch gesture
            // there is) did the opposite: the previous fling's animateDecay kept dispatching
            // deltas straight through dispatchRawDelta (which bypasses the scroll mutex)
            // right alongside the new drag's own deltas, and the two fought each other.
            var flingJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Cancel before the slop check, not after — a tap with no drag at all must
                // still stop a coasting list.
                flingJob?.cancel()
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                // Wait for slop before consuming anything — this is what keeps a tap on a
                // tile or row working: only a real drag reaches past this point.
                val slopChange = awaitFingerSlop(down, orientation)?.also { it.consume() }

                if (slopChange != null) {
                    velocityTracker.addPosition(slopChange.uptimeMillis, slopChange.position)
                    if (orientation == Orientation.Vertical) {
                        verticalDrag(slopChange.id) { change ->
                            val delta = change.positionChange().y
                            state.dispatchRawDelta(-delta)
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                    } else {
                        horizontalDrag(slopChange.id) { change ->
                            val delta = change.positionChange().x
                            state.dispatchRawDelta(-delta)
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                    }

                    // Release: let the flick coast via spline decay, dispatched the same
                    // synchronous way as the drag itself.
                    val velocity = velocityTracker.calculateVelocity()
                    val axisVelocity = if (orientation == Orientation.Vertical) velocity.y else velocity.x
                    flingJob = launch { runFling(state, -axisVelocity, density) }
                }
            }
        }
    }
}

/** How far a finger may roll during a tap before a drag takes the gesture — Android's 8 dp, plus room for a thumb. */
internal val FingerSlop = 12.dp

/**
 * Waits until [down]'s finger has travelled [FingerSlop] along [orientation] and returns that
 * change, unconsumed; null when it lifted first or another handler took it.
 *
 * Compose's own slop waits (`detectVerticalDragGestures`, `await…TouchSlopOrCancellation`)
 * start a drag from this panel's mouse-typed fingers after about 3 px, whatever
 * `ViewConfiguration.touchSlop` says (measured on the station, 1.12.1). A tap on play
 * that rolled that far went to the Now Playing page's swipe-down instead — the page dipped
 * and the button did nothing.
 */
internal suspend fun AwaitPointerEventScope.awaitFingerSlop(
    down: PointerInputChange,
    orientation: Orientation,
): PointerInputChange? {
    val slop = FingerSlop.toPx()
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed || change.isConsumed) return null
        val travel = change.position - down.position
        if (abs(if (orientation == Orientation.Vertical) travel.y else travel.x) > slop) return change
    }
}

/** `detectVerticalDragGestures`, but a drag only after [FingerSlop] — see [awaitFingerSlop]. */
internal suspend fun PointerInputScope.detectFingerVerticalDrag(
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onVerticalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val start = awaitFingerSlop(down, Orientation.Vertical) ?: return@awaitEachGesture
    onDragStart(down.position)
    onVerticalDrag(start, start.positionChange().y)
    if (verticalDrag(start.id) { onVerticalDrag(it, it.positionChange().y) }) onDragEnd() else onDragCancel()
}

/**
 * Runs the post-release decay, one frame at a time, via `dispatchRawDelta`.
 * `internal` rather than `private` so `TouchScrollFlingTest` can drive it directly against a
 * fake [ScrollableState] — the gesture detection above needs a real pointer stream and only
 * the panel has one, but this half is pure animation maths and doesn't.
 */
internal suspend fun runFling(
    state: ScrollableState,
    startVelocity: Float,
    density: Density,
) {
    var previousValue = 0f
    AnimationState(0f, startVelocity).animateDecay(splineBasedDecay(density)) {
        val delta = value - previousValue
        val consumed = state.dispatchRawDelta(delta)
        previousValue = value
        // Against the end of the list: stop, rather than run the rest of the fling
        // into a wall, still asking for frames nobody can see move.
        if (kotlin.math.abs(delta) > 0.5f && kotlin.math.abs(consumed) < kotlin.math.abs(delta) * 0.5f) cancelAnimation()
    }
}

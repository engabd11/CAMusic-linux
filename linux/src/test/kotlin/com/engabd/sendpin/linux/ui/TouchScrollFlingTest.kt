package com.engabd.sendpin.linux.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `animateDecay` (used by [runFling]) drives itself off `withFrameNanos`, which needs a
 * `MonotonicFrameClock` in the coroutine context — normally supplied by Compose's own test
 * rule or the real frame loop, neither of which exists in a plain `kotlin.test` run. This
 * fake ticks a virtual 16ms/frame clock forward on demand instead of waiting on a real
 * display's vsync, which is also what keeps these tests instant rather than animation-speed.
 */
private class FakeFrameClock : MonotonicFrameClock {
    private var timeNanos = 0L
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        timeNanos += 16_000_000L
        return onFrame(timeNanos)
    }
}

/**
 * The one part of [touchDragScroll] that doesn't need a real pointer stream: the post-release
 * decay in [runFling]. The gesture detection itself (slop gating, drag dispatch) is exercised
 * on the panel, per the plan — there is no touchscreen on this machine to drive it with.
 */
class TouchScrollFlingTest {

    /** Records every delta [runFling] dispatches, standing in for a real list/grid/scroll state. */
    private class RecordingScrollableState : ScrollableState {
        val dispatched = mutableListOf<Float>()
        override val isScrollInProgress: Boolean = false
        override fun dispatchRawDelta(delta: Float): Float {
            dispatched += delta
            return delta
        }
        override suspend fun scroll(
            scrollPriority: androidx.compose.foundation.MutatePriority,
            block: suspend androidx.compose.foundation.gestures.ScrollScope.() -> Unit,
        ) {
            val scope = object : androidx.compose.foundation.gestures.ScrollScope {
                override fun scrollBy(pixels: Float): Float {
                    dispatched += pixels
                    return pixels
                }
            }
            scope.block()
        }
    }

    @Test
    fun `a flick to the right dispatches only positive deltas`() = runTest {
        val state = RecordingScrollableState()
        withContext(FakeFrameClock()) { runFling(state, startVelocity = 2000f, density = Density(1f)) }
        assertTrue(state.dispatched.isNotEmpty(), "decay should dispatch at least one frame")
        assertTrue(state.dispatched.all { it >= 0f }, "a positive start velocity must never reverse direction")
        assertTrue(state.dispatched.sum() > 0f, "the total coasted distance should be positive")
    }

    @Test
    fun `a flick to the left dispatches only negative deltas`() = runTest {
        val state = RecordingScrollableState()
        withContext(FakeFrameClock()) { runFling(state, startVelocity = -2000f, density = Density(1f)) }
        assertTrue(state.dispatched.isNotEmpty(), "decay should dispatch at least one frame")
        assertTrue(state.dispatched.all { it <= 0f }, "a negative start velocity must never reverse direction")
        assertTrue(state.dispatched.sum() < 0f, "the total coasted distance should be negative")
    }

    @Test
    fun `a released finger with no velocity coasts nowhere`() = runTest {
        val state = RecordingScrollableState()
        withContext(FakeFrameClock()) { runFling(state, startVelocity = 0f, density = Density(1f)) }
        assertTrue(
            state.dispatched.sum() == 0f,
            "a tap-then-release with no measurable velocity shouldn't produce a phantom scroll",
        )
    }
}

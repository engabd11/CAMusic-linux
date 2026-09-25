package com.engabd.sendpin.linux.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Like [FakeFrameClock], but each `withFrameNanos` call suspends until the test explicitly
 * feeds it a frame via [advanceFrame] — needed for the cancellation test below, which has to
 * stop the decay loop *mid-flight*: a clock that resolves every frame instantly (as
 * [FakeFrameClock] does, deliberately, so the other tests run at test speed) would let
 * `animateDecay` run to completion inside a single dispatcher turn, leaving nothing to
 * cancel before it finishes on its own.
 */
private class SteppableFrameClock : MonotonicFrameClock {
    private val frames = Channel<Long>(Channel.UNLIMITED)
    suspend fun advanceFrame(timeNanos: Long) = frames.send(timeNanos)
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
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

    /**
     * Regression test for the bug the coordinator caught: a new touch used to leave the
     * previous fling's coroutine running, so its deltas kept landing on
     * `dispatchRawDelta` alongside the new drag's own — "touch to stop a coasting list"
     * did the opposite. `touchDragScroll` itself now cancels the held `Job` on the next
     * `awaitFirstDown`; this test covers the half of that fix that doesn't need a real
     * pointer stream — that cancelling the fling's `Job` actually stops `runFling` from
     * dispatching any further deltas, which is what `flingJob?.cancel()` relies on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the fling job stops further dispatch`() = runTest {
        val state = RecordingScrollableState()
        val clock = SteppableFrameClock()
        val job = launch(clock) { runFling(state, startVelocity = 3000f, density = Density(1f)) }

        // Let the decay run for a couple of frames — same "put a finger down mid-flick"
        // moment the bug happened at.
        clock.advanceFrame(16_000_000L)
        clock.advanceFrame(32_000_000L)
        runCurrent()
        val dispatchedBeforeCancel = state.dispatched.size
        assertTrue(dispatchedBeforeCancel > 0, "expected the fling to have dispatched something before cancelling")

        // The new touch: cancel, then feed more frames the way a still-subscribed
        // FakeFrameClock would have kept doing.
        job.cancel()
        clock.advanceFrame(48_000_000L)
        clock.advanceFrame(64_000_000L)
        runCurrent()

        assertEquals(
            dispatchedBeforeCancel,
            state.dispatched.size,
            "a cancelled fling must not dispatch any more deltas, or it fights the next drag",
        )
    }
}

package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.RemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transport's decisions, for the states that used to crash the panel: play once the
 * queue has played out, and Next / Previous with nothing to go to.
 */
class TransportTest {

    private fun state(playing: Boolean = false, stopped: Boolean = false, index: Int = 0) = RemoteState(
        playing = playing,
        stopped = stopped,
        index = index,
        positionMs = 0,
        durationMs = 0,
        volume = null,
    )

    @Test
    fun `play pauses what plays and resumes what is paused`() {
        assertEquals(PlayAction.PAUSE, playAction(state(playing = true, index = 2), queueSize = 10))
        assertEquals(PlayAction.RESUME, playAction(state(index = 2), queueSize = 10))
    }

    @Test
    fun `play after the queue has finished starts it again from the top`() {
        // MPD clears the current song when it plays past the last one.
        val finished = state(stopped = true, index = -1)
        assertEquals(PlayAction.START, playAction(finished, queueSize = 10))
        assertEquals(0, restartIndex(finished, queueSize = 10))
    }

    @Test
    fun `play on a player stopped mid-queue restarts the track it has selected`() {
        val stopped = state(stopped = true, index = 4)
        assertEquals(PlayAction.START, playAction(stopped, queueSize = 10))
        assertEquals(4, restartIndex(stopped, queueSize = 10))
        // a selection the queue no longer has falls back to the top
        assertEquals(0, restartIndex(state(stopped = true, index = 12), queueSize = 10))
    }

    @Test
    fun `play does nothing with nothing queued or MPD unread`() {
        assertEquals(PlayAction.NONE, playAction(state(stopped = true, index = -1), queueSize = 0))
        assertEquals(PlayAction.NONE, playAction(null, queueSize = 10))
    }

    @Test
    fun `next needs a track after the current one`() {
        assertTrue(canSkipNext(state(playing = true, index = 3), queueSize = 10))
        assertTrue(canSkipNext(state(index = 3), queueSize = 10)) // paused is fine
        assertFalse(canSkipNext(state(playing = true, index = 9), queueSize = 10)) // last track
        assertFalse(canSkipNext(state(stopped = true, index = -1), queueSize = 10)) // finished
        assertFalse(canSkipNext(state(stopped = true, index = 3), queueSize = 10)) // stopped
        assertFalse(canSkipNext(state(index = 0), queueSize = 0))
        assertFalse(canSkipNext(null, queueSize = 10))
    }

    @Test
    fun `previous needs something loaded`() {
        assertTrue(canSkipPrevious(state(playing = true, index = 0))) // MPD restarts the track
        assertFalse(canSkipPrevious(state(stopped = true, index = -1)))
        assertFalse(canSkipPrevious(state(stopped = true, index = 2)))
        assertFalse(canSkipPrevious(null))
    }

    @Test
    fun `a failed command keeps the connection summary and says what MPD said`() {
        assertEquals(
            "MPD connected — 127.0.0.1:6600  ·  Next: Not playing",
            commandErrorLine("Next", "Not playing", "MPD connected — 127.0.0.1:6600"),
        )
        assertEquals("Play: MPD didn't answer", commandErrorLine("Play", "  ", ""))
    }

    @Test
    fun `a reading is fresh only when no transport command landed mid-read`() {
        assertTrue(pollReadingFresh(epochBefore = 3, epochNow = 3))
        assertFalse(pollReadingFresh(epochBefore = 3, epochNow = 4))
    }

    @Test
    fun `a fresh reading is published, a stale one is not`() {
        val playing = state(playing = true, index = 2)
        val paused = state(playing = false, index = 2)
        // Fresh: the reading itself stands (the pause the command just made).
        assertEquals(paused, pollPublishes(previous = playing, reading = paused, fresh = true))
        // Stale: the reading predates the command's optimistic write — keep the
        // previous state rather than flip the button back under the thumb.
        assertEquals(paused, pollPublishes(previous = paused, reading = playing, fresh = false))
    }

    @Test
    fun `a dropped status keeps the last good state instead of blanking the screen`() {
        val playing = state(playing = true, index = 2)
        // MPD didn't answer this poll: null must not read as "stopped" — the UI
        // holds the last reading until the next answer (RemotePlayback.poll's contract).
        assertEquals(playing, pollPublishes(previous = playing, reading = null, fresh = true))
        // And with nothing held yet, it stays nothing.
        assertEquals(null, pollPublishes(previous = null, reading = null, fresh = true))
    }
}

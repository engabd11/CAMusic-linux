package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.RemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepTimerTest {

    private fun state(index: Int, playing: Boolean = true, stopped: Boolean = false) =
        RemoteState(playing = playing, stopped = stopped, index = index, positionMs = 0, durationMs = 0, volume = null)

    @Test
    fun `the chip steps through its choices and back to off`() {
        var c = SleepChoice.OFF
        val seen = buildList { repeat(SleepChoice.entries.size) { c = c.next(); add(c) } }
        assertEquals(listOf(SleepChoice.END_OF_ALBUM, SleepChoice.M30, SleepChoice.M60, SleepChoice.M90, SleepChoice.OFF), seen)
    }

    @Test
    fun `a timed sleep pauses when its time is up`() {
        val t = startSleep(SleepChoice.M30, nowMs = 1_000, sideLastIndex = 9)!!
        assertEquals(1_000 + 30 * 60_000L, t.deadlineMs)
        assertEquals(SleepAction.NONE, sleepAction(t, state(0), nowMs = 60_000))
        assertEquals(SleepAction.PAUSE, sleepAction(t, state(0), nowMs = 1_000 + 30 * 60_000L))
    }

    @Test
    fun `an album sleep arms MPD only when the last track begins`() {
        val t = startSleep(SleepChoice.END_OF_ALBUM, 0, sideLastIndex = 4)!!
        assertEquals(SleepAction.NONE, sleepAction(t, state(2), 0))
        assertEquals(SleepAction.ARM_STOP_AFTER_CURRENT, sleepAction(t, state(4), 0))
        assertEquals(SleepAction.NONE, sleepAction(t.copy(armed = true), state(4), 0), "asked once")
    }

    @Test
    fun `an album sleep clears itself when MPD has stopped or the listener skipped past`() {
        val t = startSleep(SleepChoice.END_OF_ALBUM, 0, sideLastIndex = 4)!!.copy(armed = true)
        assertEquals(SleepAction.CLEAR, sleepAction(t, state(4, playing = false, stopped = true), 0))
        assertEquals(SleepAction.CLEAR, sleepAction(t, state(5), 0))
    }

    @Test
    fun `no reading means nothing happens`() {
        assertEquals(SleepAction.NONE, sleepAction(SleepTimer(SleepChoice.M30, deadlineMs = 0), null, 99))
    }

    @Test
    fun `off and an unknown side start nothing`() {
        assertNull(startSleep(SleepChoice.OFF, 0, 3))
        assertNull(startSleep(SleepChoice.END_OF_ALBUM, 0, null))
    }

    @Test
    fun `the label counts minutes up and names the album sleep`() {
        val t = SleepTimer(SleepChoice.M30, deadlineMs = 10 * 60_000L)
        assertEquals("Sleep in 10 min", sleepLabel(t, 0))
        assertEquals("Sleep in 1 min", sleepLabel(t, 10 * 60_000L - 1_000))
        assertEquals("Stops after this album", sleepLabel(SleepTimer(SleepChoice.END_OF_ALBUM, sideEnd = 3), 0))
        assertEquals("Sleep", sleepLabel(null, 0))
    }
}

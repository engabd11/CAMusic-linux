package com.engabd.sendpin.linux.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The listening view's gate, and the signal line under its title. */
class ListeningTest {

    @Test
    fun `listens only when enabled, playing, and idle past the delay`() {
        val delay = 180_000L
        assertTrue(IdleGate.shouldListen(enabled = true, playing = true, idleMs = delay, delayMs = delay))
        assertFalse(IdleGate.shouldListen(enabled = true, playing = true, idleMs = delay - 1, delayMs = delay))
        assertFalse(IdleGate.shouldListen(enabled = true, playing = false, idleMs = delay * 10, delayMs = delay))
        assertFalse(IdleGate.shouldListen(enabled = false, playing = true, idleMs = delay * 10, delayMs = delay))
    }

    @Test
    fun `the listening view and the gallery never both claim the screen`() {
        for (playing in listOf(true, false)) for (idle in listOf(0L, 60_000L, 3_600_000L)) {
            val listen = IdleGate.shouldListen(true, playing, idle, 60_000L)
            val gallery = IdleGate.shouldShow(true, playing, idle, 60_000L)
            assertFalse(listen && gallery, "playing=$playing idle=$idle")
        }
    }

    @Test
    fun `signal line joins what is known`() {
        assertEquals("FLAC 24/96  →  Topping DX5 II", signalLine("FLAC 24/96", "Topping DX5 II"))
        assertEquals("FLAC 24/96", signalLine("FLAC 24/96", "  "))
        assertEquals("Topping DX5 II", signalLine(null, "Topping DX5 II"))
        assertEquals("", signalLine(null, null))
    }

    @Test
    fun `the view takes touches only once it is fully up and staying up`() {
        assertTrue(overlayCaptures(target = true, current = true, running = false))
        // fading in: the controls are still what the eye sees
        assertFalse(overlayCaptures(target = true, current = false, running = true))
        // fading out after the wake tap: the next tap is pause, and must reach it
        assertFalse(overlayCaptures(target = false, current = true, running = true))
        assertFalse(overlayCaptures(target = false, current = false, running = false))
    }

    @Test
    fun `a press while the view fades in sends it away, one before it began does not`() {
        assertTrue(pressEndsEntrance(showing = true, captures = false, pressAtMs = 2_000, shownAtMs = 1_000))
        assertFalse(pressEndsEntrance(showing = true, captures = false, pressAtMs = 500, shownAtMs = 1_000))
        // fully up: the view's own tap handles it (wake, or the gallery's hold-to-play)
        assertFalse(pressEndsEntrance(showing = true, captures = true, pressAtMs = 2_000, shownAtMs = 1_000))
        assertFalse(pressEndsEntrance(showing = false, captures = false, pressAtMs = 2_000, shownAtMs = 1_000))
    }

    @Test
    fun `a press is activity and is remembered, a plain touch is only activity`() {
        var now = 1_000L
        val gate = IdleGate { now }
        now = 5_000L
        gate.touch()
        assertEquals(0L, gate.lastPress.value)
        assertEquals(0L, gate.idleMs())
        now = 9_000L
        gate.press()
        assertEquals(9_000L, gate.lastPress.value)
        assertEquals(0L, gate.idleMs())
    }
}

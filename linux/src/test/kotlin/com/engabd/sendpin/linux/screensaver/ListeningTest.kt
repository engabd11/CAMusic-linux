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
}

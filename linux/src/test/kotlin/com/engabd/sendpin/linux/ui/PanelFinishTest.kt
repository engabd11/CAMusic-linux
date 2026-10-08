package com.engabd.sendpin.linux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The finish over the panel: the night's schedule, and the grain and vignette levels. */
class PanelFinishTest {

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun `off is never night and always is always night`() {
        for (minute in listOf(0, at(3), at(12), at(23, 59))) {
            assertEquals(0f, nightLevel("off", minute, 22, 7))
            assertEquals(1f, nightLevel("always", minute, 22, 7))
        }
    }

    @Test
    fun `a night past midnight covers the small hours and not the day`() {
        assertEquals(1f, nightLevel("scheduled", at(2), 22, 7))
        assertEquals(1f, nightLevel("scheduled", at(23, 30), 22, 7))
        assertEquals(0f, nightLevel("scheduled", at(12), 22, 7))
        assertEquals(0f, nightLevel("scheduled", at(7), 22, 7), "it has fully lifted at the end hour")
        assertEquals(0f, nightLevel("scheduled", at(21, 59), 22, 7))
    }

    @Test
    fun `the night comes down and lifts over its fade, never in a step`() {
        assertEquals(0f, nightLevel("scheduled", at(22), 22, 7))
        assertEquals(0.5f, nightLevel("scheduled", at(22, 10), 22, 7), 0.001f)
        assertEquals(1f, nightLevel("scheduled", at(22, NIGHT_FADE_MIN), 22, 7))
        assertEquals(0.5f, nightLevel("scheduled", at(6, 50), 22, 7), 0.001f)
        var last = 0f
        for (m in at(22)..at(22, NIGHT_FADE_MIN)) {
            val level = nightLevel("scheduled", m, 22, 7)
            assertTrue(level >= last && level - last <= 1f / NIGHT_FADE_MIN + 1e-4f)
            last = level
        }
    }

    @Test
    fun `a night within one day works too, and an empty one is no night`() {
        assertEquals(1f, nightLevel("scheduled", at(3), 1, 6))
        assertEquals(0f, nightLevel("scheduled", at(7), 1, 6))
        assertEquals(0f, nightLevel("scheduled", at(3), 5, 5))
    }

    @Test
    fun `finish levels grow with the setting, and the grain compiles`() {
        assertEquals(0f, vignetteStrength("off"))
        assertTrue(vignetteStrength("deep") > vignetteStrength("soft"))
        assertEquals(0f, grainAmount("off"))
        assertTrue(grainAmount("rich") > grainAmount("fine"))
        assertTrue(nightDepth("deep").dim > nightDepth("gentle").dim)
        assertTrue(grainCompiles())
    }
}

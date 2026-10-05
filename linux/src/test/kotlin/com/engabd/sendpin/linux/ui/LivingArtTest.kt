package com.engabd.sendpin.linux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the living colours flow, and how the liquid looks at each strength. */
class LivingArtTest {

    @Test
    fun `wakes at the mark while playing`() {
        assertEquals(0f, aliveTarget(posMs = 9_999, playing = true, delayMs = 10_000))
        assertEquals(1f, aliveTarget(posMs = 10_000, playing = true, delayMs = 10_000))
        assertEquals(1f, aliveTarget(posMs = 245_000, playing = true, delayMs = 10_000))
    }

    @Test
    fun `settles when paused, whatever the position`() {
        assertEquals(0f, aliveTarget(posMs = 60_000, playing = false, delayMs = 10_000))
    }

    @Test
    fun `a new track settles it again until its own mark`() {
        assertEquals(1f, aliveTarget(posMs = 200_000, playing = true, delayMs = 10_000))
        assertEquals(0f, aliveTarget(posMs = 1_000, playing = true, delayMs = 10_000))
    }

    @Test
    fun `strength per setting`() {
        assertEquals(0f, livingStrength("off"))
        assertEquals(1f, livingStrength("subtle"))
        assertEquals(2f, livingStrength("vivid"))
        assertEquals(0f, livingStrength("nonsense"))
    }

    @Test
    fun `off has no liquid, and vivid is bolder and quicker than subtle`() {
        assertNull(liquidLook(livingStrength("off")))
        val subtle = assertNotNull(liquidLook(livingStrength("subtle")))
        val vivid = assertNotNull(liquidLook(livingStrength("vivid")))
        assertTrue(vivid.alpha > subtle.alpha)
        assertTrue(vivid.sharp > subtle.sharp)
        assertTrue(vivid.speed > subtle.speed)
        // never fully opaque: the album's wash and the words keep showing through
        assertTrue(vivid.alpha < 1f)
    }
}

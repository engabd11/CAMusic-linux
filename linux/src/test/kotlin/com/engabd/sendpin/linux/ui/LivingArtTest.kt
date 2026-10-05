package com.engabd.sendpin.linux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** When the cover comes alive, and how strongly. */
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
}

package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The portrait overlay's two pure release decisions: when a cover drag commits
 * the collapse, and when a mini-bar up-drag asks for the cover back. The
 * thresholds mirror the Android overlay's (25% of travel; 24dp of up-travel).
 */
class PortraitGestureTest {

    private val screenPx = 800f

    @Test
    fun `a drag past a quarter of the travel commits the collapse`() {
        val collapsePx = screenPx // fully off the bottom edge
        assertTrue(collapseCommitted(collapsePx * 0.26f, collapsePx))
    }

    @Test
    fun `a drag at exactly the threshold does not commit`() {
        val collapsePx = screenPx
        // Strictly greater — a deliberate nudge of exactly 25% should settle back.
        assertFalse(collapseCommitted(collapsePx * 0.25f, collapsePx))
    }

    @Test
    fun `a small accidental nudge settles back`() {
        val collapsePx = screenPx
        assertFalse(collapseCommitted(collapsePx * 0.10f, collapsePx))
        assertFalse(collapseCommitted(0f, collapsePx))
    }

    @Test
    fun `the threshold scales with the screen the panel actually has`() {
        // The commit distance is a fraction of travel, not a fixed pixel count,
        // so a 1280x800 panel and a smaller one both feel the same.
        val small = collapseCommitted(150f, 500f)
        val large = collapseCommitted(150f, 1280f)
        assertTrue(small, "150px is past 25% of a 500px travel")
        assertFalse(large, "150px is under 25% of a 1280px travel")
    }

    @Test
    fun `an up-drag beyond the expand threshold requests the cover`() {
        assertTrue(expandRequested(upTravelPx = -30f, thresholdPx = 24f))
        assertTrue(expandRequested(-24.5f, 24f), "strictly beyond, even by a hair")
    }

    @Test
    fun `a bar nudge under the threshold does not expand`() {
        assertFalse(expandRequested(-23.9f, 24f))
        assertFalse(expandRequested(0f, 24f))
    }

    @Test
    fun `downward travel on the bar never expands`() {
        // coerceAtMost(0) in the gesture keeps upTravel non-positive; the guard
        // here is that a positive value (an un-consumed drag) cannot expand.
        assertFalse(expandRequested(50f, 24f))
    }

    @Test
    fun `equal magnitudes on opposite sides decide differently`() {
        val t = 24f
        assertFalse(expandRequested(t, t), "exactly at threshold stays put")
        assertTrue(expandRequested(-t - 0.1f, t), "just past it expands")
    }
}

private fun assertFalse(actual: Boolean) {
    assertEquals(false, actual)
}
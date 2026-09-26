package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.ui.formatTotal
import com.engabd.sendpin.linux.ui.placeholderHue
import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The small arithmetic behind the redesigned pages: album facts and disc grouping, the
 * queue's time-left, the lights offset nudges, secret masking and placeholder colours.
 */
class PanelFormatTest {

    private fun track(i: Int, disc: Int? = null, seconds: Int = 200) =
        MaItem("t$i", "t", "Track $i", null, "track", null, null, seconds, discNumber = disc)

    @Test
    fun `totals read as hours and minutes`() {
        assertEquals("48 min", formatTotal(48 * 60_000L))
        assertEquals("1 h 12 min", formatTotal(72 * 60_000L))
        assertEquals("2 h", formatTotal(120 * 60_000L))
        assertEquals("0 min", formatTotal(-5))
    }

    @Test
    fun `album facts drop the parts that are missing`() {
        val tracks = (1..12).map { track(it, seconds = 240) }
        assertEquals("2019  ·  12 songs  ·  48 min", albumFacts(2019, tracks))
        assertEquals("1 song  ·  4 min", albumFacts(null, listOf(track(1, seconds = 240))))
        assertEquals("2019", albumFacts(2019, emptyList()))
    }

    @Test
    fun `a one-disc album is one group with every index in play order`() {
        val groups = discGroups((0 until 5).map { track(it) })
        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1, 2, 3, 4), groups.single().second.map { it.first })
    }

    @Test
    fun `discs group in order and keep each track's index into the full list`() {
        val tracks = listOf(track(0, 1), track(1, 1), track(2, 2), track(3, 2))
        val groups = discGroups(tracks)
        assertEquals(listOf(1, 2), groups.map { it.first })
        // The index is what a tap hands to playAlbum — it must point into `tracks`, not
        // restart per disc, or disc 2's first row would play disc 1's first track.
        assertEquals(listOf(2, 3), groups[1].second.map { it.first })
    }

    @Test
    fun `time left is the rest of this track plus everything after it`() {
        val q = listOf(
            LocalTrack(id = "a", title = "a", durationMs = 100_000),
            LocalTrack(id = "b", title = "b", durationMs = 200_000),
            LocalTrack(id = "c", title = "c", durationMs = 300_000),
        )
        assertEquals(150_000 + 300_000, queueRemainingMs(q, index = 1, positionMs = 50_000, durationMs = 200_000))
        // MPD's duration wins when it has one; the track's own when it hasn't.
        assertEquals(50_000 + 300_000, queueRemainingMs(q, index = 1, positionMs = 50_000, durationMs = 100_000))
        assertEquals(0, queueRemainingMs(emptyList(), 0, 0, 0))
    }

    @Test
    fun `offset nudges step a tenth and stop at the range`() {
        assertEquals(0.4, nudgeOffset(0.3, +1))
        assertEquals(0.2, nudgeOffset(0.3, -1))
        assertEquals(2.0, nudgeOffset(2.0, +1))
        assertEquals(-2.0, nudgeOffset(-1.95, -1))
    }

    @Test
    fun `offsets read signed`() {
        assertEquals("+0.3 s", formatOffset(0.3))
        assertEquals("−0.5 s", formatOffset(-0.5))
        assertEquals("0.0 s", formatOffset(0.0))
        assertEquals("0.0 s", formatOffset(-0.01))
    }

    @Test
    fun `secrets show only their last four characters`() {
        assertEquals("••••••••2a71", maskSecret("4f1c9a0b7d2e4c55a1b8e9f03d6c2a71"))
        assertEquals("•••", maskSecret("abc"))
        assertEquals("", maskSecret(""))
    }

    @Test
    fun `placeholder hues are stable and in range`() {
        val names = listOf("", "Glass Tides", "Aurora Vale", "ÄÖÜ", "x".repeat(500))
        names.forEach { n ->
            val h = placeholderHue(n)
            assertTrue(h in 0f..360f, "hue $h for '$n'")
            assertEquals(h, placeholderHue(n))
        }
    }
}

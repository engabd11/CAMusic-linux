package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.LocalTrack
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrooveTest {

    private fun track(id: String, album: String?, ms: Long = 200_000) =
        LocalTrack(id = id, title = id, album = album, durationMs = ms)

    @Test
    fun `the side is the run of the queue sharing the playing album`() {
        val q = listOf(
            track("a", "Other"), track("b", "Blue"), track("c", "Blue"), track("d", "blue"), track("e", "Other"),
        )
        val side = assertNotNull(sideOf(q, 2, emptyMap()))
        assertEquals(1, side.firstIndex)
        assertEquals(listOf("b", "c", "d"), side.tracks.map { it.id }, "album titles match regardless of case")
        assertEquals(1, side.indexOf(2))
    }

    @Test
    fun `album ids from the library beat titles`() {
        val q = listOf(track("a", "Same"), track("b", "Same"))
        val ids = mapOf("a" to "A1", "b" to "B2")
        assertEquals(1, assertNotNull(sideOf(q, 0, ids)).size, "two albums of one name stay apart")
    }

    @Test
    fun `a track with no album is a side of one`() {
        val q = listOf(track("a", null), track("b", null))
        val side = assertNotNull(sideOf(q, 1, emptyMap()))
        assertEquals(1, side.size)
        assertEquals(1, side.firstIndex)
    }

    @Test
    fun `an empty queue has no side`() {
        assertEquals(null, sideOf(emptyList(), 0, emptyMap()))
    }

    @Test
    fun `unknown lengths take the average of the known ones`() {
        assertEquals(listOf(100L, 200L, 150L), effectiveDurations(listOf(100, 200, 0)))
        assertEquals(listOf(240_000L), effectiveDurations(listOf(0)))
    }

    @Test
    fun `progress runs from 0 to 1 across the side`() {
        val d = listOf(100_000L, 100_000L, 200_000L)
        assertEquals(0f, grooveProgress(d, 0, 0))
        assertEquals(0.125f, grooveProgress(d, 0, 50_000), 1e-6f)
        assertEquals(0.25f, grooveProgress(d, 1, 0), 1e-6f)
        assertEquals(1f, grooveProgress(d, 2, 200_000), 1e-6f)
        assertEquals(1f, grooveProgress(d, 2, 999_999), 1e-6f, "a position past the end stays on the record")
    }

    @Test
    fun `track starts mark where the rings go`() {
        val starts = trackStarts(listOf(100_000L, 100_000L, 200_000L))
        assertEquals(listOf(0f, 0.25f, 0.5f), starts)
    }

    @Test
    fun `locate is the inverse of progress`() {
        val d = listOf(100_000L, 100_000L, 200_000L)
        for (track in d.indices) for (pos in listOf(0L, 30_000L, 99_000L)) {
            val p = grooveProgress(d, track, pos)
            val at = locate(d, p)
            assertEquals(track, at.track)
            assertTrue(abs(at.offsetMs - pos) < 5, "offset within a few ms: ${at.offsetMs} vs $pos")
        }
        assertEquals(GroovePoint(2, 200_000L), locate(d, 1f))
        assertEquals(GroovePoint(0, 0L), locate(d, -3f))
    }

    @Test
    fun `the stylus sits on the groove radius for the whole side`() {
        for (i in 0..10) {
            val f = i / 10f
            val (x, y) = Deck.tip(Deck.angleAt(f))
            val radius = hypot(x - Deck.CX, y - Deck.CY) / Deck.R
            assertEquals(Deck.grooveRadius(f), radius, 1e-3f, "at $f")
        }
    }

    @Test
    fun `the arm swings inward as the side plays and parks outside the record`() {
        assertTrue(Deck.angleAt(1f) > Deck.angleAt(0f), "inward is the way the angle grows")
        assertTrue(Deck.PARKED < Deck.angleAt(0f), "parked is beyond the outer groove")
        val (px, py) = Deck.tip(Deck.PARKED)
        assertTrue(hypot(px - Deck.CX, py - Deck.CY) > Deck.R, "the parked stylus is off the platter")
        assertTrue(px in 0f..1f && py in 0f..1f, "and still inside the deck square")
    }

    @Test
    fun `a finger on the record maps back to a place in the groove`() {
        for (i in 0..10) {
            val f = i / 10f
            val (x, y) = Deck.tip(Deck.angleAt(f))
            assertEquals(f, Deck.fractionAtPoint(x, y), 2e-3f)
        }
        assertEquals(0f, Deck.fractionAtPoint(Deck.CX + Deck.R, Deck.CY), "beyond the lead-in clamps to the start")
        assertEquals(1f, Deck.fractionAtPoint(Deck.CX, Deck.CY), "the label clamps to the run-out")
    }
}

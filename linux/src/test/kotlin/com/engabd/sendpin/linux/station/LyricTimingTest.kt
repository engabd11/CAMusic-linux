package com.engabd.sendpin.linux.station

import com.engabd.sendpin.ma.LyricLine
import com.engabd.sendpin.ma.MaLyrics
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricTimingTest {

    private val lines = listOf(
        LyricLine(1_000, "one"), LyricLine(5_000, "two"), LyricLine(9_000, ""), LyricLine(12_000, "three"),
    )

    @Test
    fun `before the first line nothing is active`() {
        assertEquals(-1, activeLyricIndex(lines, 0, leadMs = 0))
        assertEquals(-1, activeLyricIndex(emptyList(), 5_000))
    }

    @Test
    fun `the line lights a moment before its stamp`() {
        assertEquals(-1, activeLyricIndex(lines, 700))
        assertEquals(0, activeLyricIndex(lines, 750), "250 ms of lead")
        assertEquals(1, activeLyricIndex(lines, 4_750))
    }

    @Test
    fun `the last line stays active to the end`() {
        assertEquals(3, activeLyricIndex(lines, 600_000))
    }

    @Test
    fun `the playhead is carried forward between polls but not without limit`() {
        assertEquals(10_400, playheadMs(10_000, readAtMs = 1_000, nowMs = 1_400, playing = true))
        assertEquals(12_500, playheadMs(10_000, readAtMs = 1_000, nowMs = 60_000, playing = true), "capped")
        assertEquals(10_000, playheadMs(10_000, readAtMs = 1_000, nowMs = 1_400, playing = false), "paused holds")
        assertEquals(10_000, playheadMs(10_000, readAtMs = 5_000, nowMs = 1_000, playing = true), "never backwards")
    }

    @Test
    fun `a plain lyric scrolls with the track`() {
        assertEquals(0.5f, plainLyricFraction(60_000, 120_000))
        assertEquals(0f, plainLyricFraction(60_000, 0))
        assertEquals(1f, plainLyricFraction(999_000, 120_000))
    }

    @Test
    fun `the sheet drops blank edges and collapses blank runs in a plain lyric`() {
        val plain = listOf("", "a", "", "", "b", "").map { LyricLine(0, it) }
        assertEquals(listOf("a", "", "b"), sheetLines(plain, synced = false).map { it.text })
        val timed = listOf(LyricLine(0, ""), LyricLine(1, "a"), LyricLine(2, ""), LyricLine(3, ""), LyricLine(4, "b"))
        assertEquals(listOf("a", "", "", "b"), sheetLines(timed, synced = true).map { it.text }, "timed rests are kept")
    }

    @Test
    fun `lrc text parses into timed lines the sheet can follow`() {
        val lrc = MaLyrics("[00:01.00]one\n[00:05.50]two\n[01:02.25]three", synced = true)
        assertEquals(listOf(1_000L, 5_500L, 62_250L), lrc.lines.map { it.atMs })
        assertEquals(1, activeLyricIndex(lrc.lines, 5_400))
    }
}

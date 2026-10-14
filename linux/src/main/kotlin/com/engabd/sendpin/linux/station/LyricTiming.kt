package com.engabd.sendpin.linux.station

import com.engabd.sendpin.ma.LyricLine

/*
 * Lyric timing, as pure functions: which line is being sung, and where the playhead is
 * between two readings of MPD's once-a-second poll.
 */

/** A line lights this long before its stamp, so the eye is on it when the voice comes in. */
internal const val LYRIC_LEAD_MS = 250L

/**
 * The line being sung at [positionMs], as an index into [lines] (which are sorted by
 * time), or -1 before the first line. Binary search; the lead is added to the position.
 */
internal fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long, leadMs: Long = LYRIC_LEAD_MS): Int {
    if (lines.isEmpty()) return -1
    val at = positionMs + leadMs
    var lo = 0
    var hi = lines.lastIndex
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].atMs <= at) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/**
 * The playhead now, from MPD's last reading: [readingMs] taken at [readAtMs], carried
 * forward by the wall clock while [playing], and never more than [capMs] past the reading
 * (a poll that has stopped answering must not let the lyrics run away).
 */
internal fun playheadMs(readingMs: Long, readAtMs: Long, nowMs: Long, playing: Boolean, capMs: Long = 2_500L): Long {
    if (!playing) return readingMs
    return readingMs + (nowMs - readAtMs).coerceIn(0L, capMs)
}

/** How far down a plain (untimed) lyric the sheet should be, from the track's progress. */
internal fun plainLyricFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/**
 * The lines worth setting on the sheet: leading and trailing blank lines dropped, runs of
 * blank lines in a plain lyric collapsed to one, blank timed lines kept (they mark the
 * instrumental gaps, and the sheet shows them as a rest).
 */
internal fun sheetLines(lines: List<LyricLine>, synced: Boolean): List<LyricLine> {
    val trimmed = lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
    if (synced) return trimmed
    val out = ArrayList<LyricLine>(trimmed.size)
    for (l in trimmed) {
        if (l.text.isBlank() && out.lastOrNull()?.text?.isBlank() == true) continue
        out += l
    }
    return out
}

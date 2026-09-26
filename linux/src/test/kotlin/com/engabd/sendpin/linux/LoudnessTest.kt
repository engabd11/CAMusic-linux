package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The loudness toggle's pure half: the three modes spelled as MPD spells them, MPD's own
 * reading winning over the saved preference, and the cover chip's Off → Track → Album
 * cycle — including what it does with MPD's fourth mode, which the panel never offers.
 */
class LoudnessTest {

    @Test
    fun `modes pass straight through to MPD's replay_gain_mode spelling`() {
        assertEquals(listOf("off", "track", "album"), LoudnessMode.entries.map { it.key })
        assertEquals(LoudnessMode.ALBUM, LoudnessMode.from("album"))
        assertEquals(LoudnessMode.TRACK, LoudnessMode.from("TRACK"))
        assertNull(LoudnessMode.from("auto"))
        assertNull(LoudnessMode.from(null))
    }

    @Test
    fun `MPD's reading wins over the saved preference once it has answered`() {
        // The Android app can set the same MPD to track while the panel saved album.
        assertEquals("track", shownLoudness(saved = "album", mpd = "track"))
        assertEquals("album", shownLoudness(saved = "album", mpd = null))
    }

    @Test
    fun `the chip cycles off, track, album and back`() {
        assertEquals("track", nextLoudness("off"))
        assertEquals("album", nextLoudness("track"))
        assertEquals("off", nextLoudness("album"))
    }

    @Test
    fun `a mode the panel does not offer steps to the start of the cycle`() {
        assertEquals("off", nextLoudness("auto"))
        assertEquals("off", nextLoudness(""))
    }

    @Test
    fun `labels name the mode, or echo MPD's own word`() {
        assertEquals("Album", loudnessLabel("album"))
        assertEquals("Off", loudnessLabel("off"))
        assertEquals("Auto", loudnessLabel("auto"))
    }
}

package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.ui.design.mpdBadgeQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quality badge's reading for an MPD queue entry: codec from the track (its
 * tags when the panel queued it, the file's extension when another client did),
 * the measurable numbers from MPD's live audio-format reading — the same
 * tagged/live split StreamQuality.live documents on the phone.
 */
class MpdBadgeQualityTest {

    private fun track(
        codec: String? = null,
        rate: Int = 0,
        depth: Int = 0,
        path: String? = null,
    ) = LocalTrack(
        id = "jf-1",
        title = "t",
        artist = "a",
        scrobbleId = path ?: "Artist/Album/01 song.flac",
        scrobbleProvider = "mpd",
        sourceQuality = codec?.let { StreamQuality(codec = it, sampleRateHz = rate, bitDepth = depth) },
    )

    @Test
    fun `no track means no badge`() {
        kotlin.test.assertEquals(null, mpdBadgeQuality(null, null))
    }

    @Test
    fun `a tagged queue entry keeps its codec and MPD's live numbers`() {
        val q = mpdBadgeQuality(
            track(codec = "FLAC", rate = 44100, depth = 16),
            outputFormat = com.engabd.sendpin.audio.RemoteAudioFormat(
                sampleRateHz = 44100, bitDepth = 16, bitrateKbps = 950,
            ),
        )!!
        kotlin.test.assertEquals("FLAC", q.codec)
        kotlin.test.assertEquals(44100, q.sampleRateHz)
        kotlin.test.assertEquals(16, q.bitDepth)
        kotlin.test.assertEquals(950, q.bitrateKbps, "live bitrate replaces the tags' guess")
        kotlin.test.assertEquals("FLAC • 44.1/16 • 950 kb/s", q.label)
    }

    @Test
    fun `an untagged queue entry names the codec from its own file`() {
        // The synthesised entries another client's `mpc add` produces carry no
        // sourceQuality — the path's extension is the only honest codec name.
        val q = mpdBadgeQuality(
            track(path = "Artist/Album/04 - how long is... cold.flac"),
            outputFormat = com.engabd.sendpin.audio.RemoteAudioFormat(sampleRateHz = 96000, bitDepth = 24),
        )!!
        kotlin.test.assertEquals("FLAC", q.codec)
        kotlin.test.assertEquals(96000, q.sampleRateHz)
        kotlin.test.assertEquals(24, q.bitDepth)
        kotlin.test.assertTrue(q.hiRes, "96/24 is better than CD — the pill lights up")
        kotlin.test.assertEquals("FLAC • 96/24", q.label)
    }

    @Test
    fun `a lossy file badge is not lossless`() {
        val q = mpdBadgeQuality(
            track(path = "Artist/Album/05 song.mp3"),
            outputFormat = null,
        )!!
        kotlin.test.assertEquals("MP3", q.codec)
        kotlin.test.assertEquals(false, q.lossless)
    }

    @Test
    fun `mpd's not-decided-yet reading leaves the tags standing`() {
        // MPD writes `f`/`*` through parseAudioFormat as zeros — a live reading
        // with no known numbers must not blank out a real tagged codec.
        val q = mpdBadgeQuality(
            track(codec = "FLAC", rate = 44100, depth = 16),
            outputFormat = com.engabd.sendpin.audio.RemoteAudioFormat(),
        )!!
        kotlin.test.assertEquals("FLAC", q.codec)
        kotlin.test.assertEquals(44100, q.sampleRateHz)
    }
}
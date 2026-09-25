package com.engabd.sendpin.linux

import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JellyfinToMpd] path-mapping tests — the plan's required cases: prefix strip,
 * trailing slashes, non-ASCII, and the refusals (no mapping, path traversal).
 */
class JellyfinToMpdTest {

    @Test
    fun `strips the configured prefix to get MPD's relative path`() {
        val mapped = JellyfinToMpd.toMpdPath(
            jfPath = "/mnt/music/_NSYNC/No Strings Attached/01 Bye.flac",
            prefix = "/mnt/music/",
        )
        assertEquals("_NSYNC/No Strings Attached/01 Bye.flac", mapped)
    }

    @Test
    fun `prefix without trailing slash still strips cleanly`() {
        val mapped = JellyfinToMpd.toMpdPath(
            jfPath = "/mnt/music/_NSYNC/x.flac",
            prefix = "/mnt/music",
        )
        assertEquals("_NSYNC/x.flac", mapped)
    }

    @Test
    fun `prefix that does not match refuses rather than guessing`() {
        val mapped = JellyfinToMpd.toMpdPath(
            jfPath = "/srv/jellyfin/media/Artist/x.flac",
            prefix = "/mnt/music/",
        )
        assertEquals(null, mapped, "unmatched prefix must yield null — never a wrong path")
    }

    @Test
    fun `empty prefix refuses - no configured mapping means no queue`() {
        assertEquals(null, JellyfinToMpd.toMpdPath("/mnt/music/x.flac", prefix = ""))
    }

    @Test
    fun `a filename that contains dots is not a path escape`() {
        // Regression: "04 - how long is... cold.flac" (Orions Belte, Villa Amorini)
        // was refused by the old whole-string contains("..") check, silently dropping
        // the track and shifting every queue index below it — tapping a row played
        // the next song. The real-world report that found this bug.
        val mapped = JellyfinToMpd.toMpdPath(
            jfPath = "/mnt/media/Orions Belte/Villa Amorini (2021)/04 - how long is... cold.flac",
            prefix = "/mnt/media/",
        )
        assertEquals(
            "Orions Belte/Villa Amorini (2021)/04 - how long is... cold.flac",
            mapped,
            "an ellipsis in a filename is legal — only a .. segment escapes",
        )
    }

    @Test
    fun `a literal parent-directory segment still refuses`() {
        assertEquals(null, JellyfinToMpd.toMpdPath("/mnt/music/../etc/passwd", prefix = "/mnt/music/"))
        assertEquals(null, JellyfinToMpd.toMpdPath("/mnt/music/a/../../b.flac", prefix = "/mnt/music/"))
        // "..flac" as a whole segment is a (weird) filename, not an escape — the
        // segment check is equality, not containment.
        assertEquals("..flac", JellyfinToMpd.toMpdPath("/mnt/music/..flac", prefix = "/mnt/music/"))
    }

    @Test
    fun `non-ascii paths survive intact`() {
        val mapped = JellyfinToMpd.toMpdPath(
            jfPath = "/mnt/music/Björk/Debut/01 human behaviour.flac",
            prefix = "/mnt/music/",
        )
        assertEquals("Björk/Debut/01 human behaviour.flac", mapped)
    }

    @Test
    fun `a mapping that would escape upward is refused`() {
        val mapped = JellyfinToMpd.toMpdPath(
            jfPath = "/mnt/music/../etc/passwd",
            prefix = "/mnt/music/",
        )
        assertEquals(null, mapped, "path traversal must not map into MPD's library")
    }

    @Test
    fun `queue tracks carry both identities`() {
        val t = JellyfinToMpd.toLocalTrack(
            item = MaItem(
                itemId = "jf-abc",
                provider = "jellyfin",
                name = "Bye Bye Bye",
                uri = null,
                mediaType = "Audio",
                subtitle = "_NSYNC",
                image = "http://jf/Items/jf-abc/Images/Primary",
                duration = 200,
                audioFormat = MaAudioFormat(codec = "FLAC", sampleRate = 44100, bitDepth = 16),
            ),
            mpdPath = "_NSYNC/No Strings Attached/01 _NSYNC - Bye Bye Bye.flac",
        )
        assertEquals("jf-abc", t.id)                        // Jellyfin id — for reporting
        assertEquals("_NSYNC/No Strings Attached/01 _NSYNC - Bye Bye Bye.flac", t.scrobbleId)
        assertEquals("mpd", t.scrobbleProvider)             // MpdRemote's guard
        assertEquals("_NSYNC", t.artist)
        assertEquals(200_000L, t.durationMs)
        assertTrue(t.sourceQuality != null, "quality badge material must ride along")
    }
}
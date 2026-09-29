package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [identify] — the check that the queue entry the reporter names is the file MPD is
 * really playing. A Jellyfin start for the wrong item makes HueGhost switch its ghost to
 * the wrong song, so anything short of an exact path match is skipped, not guessed.
 */
class ReportIdentityTest {

    private fun track(path: String) = LocalTrack(
        id = "jf-$path",
        title = path,
        scrobbleId = path,
        scrobbleProvider = "mpd",
    )

    /** How [reorderQueue] marks an entry this panel didn't queue: `id == scrobbleId`. */
    private fun synthesised(path: String) = LocalTrack(
        id = path,
        title = path,
        scrobbleId = path,
        scrobbleProvider = "mpd",
    )

    private val queue = listOf(track("a.flac"), track("b.flac"), synthesised("c.flac"))

    @Test
    fun `matching file gives the Jellyfin id`() {
        assertEquals(Identity.Known("jf-b.flac"), identify(queue, 1, "b.flac"))
    }

    @Test
    fun `a queue swapped ahead of MPD is unverified, not the new album's song`() {
        // MPD still on its old queue's entry 1; the panel's queue already holds the new album
        assertEquals(Identity.Unverified, identify(queue, 1, "old-album/02.flac"))
    }

    @Test
    fun `a synthesised entry is known to have no id`() {
        assertEquals(Identity.Known(null), identify(queue, 2, "c.flac"))
    }

    @Test
    fun `no current file or index out of range is unverified`() {
        assertEquals(Identity.Unverified, identify(queue, 0, null))
        assertEquals(Identity.Unverified, identify(queue, 7, "a.flac"))
        assertEquals(Identity.Unverified, identify(queue, -1, "a.flac"))
    }

    @Test
    fun `idle watcher reads addresses the way MpdClient does`() {
        assertEquals("nuc" to 6600, MpdIdle.hostPort("nuc"))
        assertEquals("192.168.1.5" to 6601, MpdIdle.hostPort("http://192.168.1.5:6601/"))
    }
}

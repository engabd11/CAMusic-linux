package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [reorderQueue] — the pure half of [PanelModel.reconcileQueueFromMpd], which QueuePane's
 * remove/move/shuffle/clear rely on entirely to reflect back in `model.queue` (this codebase's
 * convention of firing the command and letting the poll reconcile, rather than hand-patching
 * a second copy of the list).
 */
class QueueReconcileTest {

    private fun track(path: String, title: String) = LocalTrack(
        id = "jf-$path",
        title = title,
        scrobbleId = path,
        scrobbleProvider = "mpd",
    )

    @Test
    fun `re-orders to match MPD's reported order`() {
        val a = track("a.flac", "A")
        val b = track("b.flac", "B")
        val c = track("c.flac", "C")
        val result = reorderQueue(listOf(a, b, c), listOf("c.flac", "a.flac", "b.flac"))
        assertEquals(listOf(c, a, b), result)
    }

    @Test
    fun `a track MPD removed drops out, without touching the others`() {
        val a = track("a.flac", "A")
        val b = track("b.flac", "B")
        val c = track("c.flac", "C")
        // "remove index 1" on MPD's side means b.flac is simply absent from its next
        // playlistinfo -- this is exactly what QueuePane's remove button produces.
        val result = reorderQueue(listOf(a, b, c), listOf("a.flac", "c.flac"))
        assertEquals(listOf(a, c), result)
    }

    @Test
    fun `an MPD-reported path with no matching LocalTrack is silently dropped, not guessed at`() {
        val a = track("a.flac", "A")
        val result = reorderQueue(listOf(a), listOf("a.flac", "unknown.flac"))
        assertEquals(listOf(a), result)
    }

    @Test
    fun `an empty MPD queue reconciles to an empty list`() {
        val a = track("a.flac", "A")
        assertEquals(emptyList(), reorderQueue(listOf(a), emptyList()))
    }

    @Test
    fun `preserves the Jellyfin id LocalTrack carries -- this is why it reorders, not rebuilds`() {
        val a = track("a.flac", "A")
        val result = reorderQueue(listOf(a), listOf("a.flac"))
        assertEquals("jf-a.flac", result.single().id)
    }
}

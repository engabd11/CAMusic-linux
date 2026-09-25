package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [reorderQueue] — the pure half of [PanelModel.reconcileQueueFromMpd], which QueuePane's
 * remove/move/shuffle/clear rely on entirely to reflect back in `model.queue` (this codebase's
 * convention of firing the command and letting the poll reconcile, rather than hand-patching
 * a second copy of the list).
 *
 * The property that matters most is index alignment with MPD's own queue — `QueuePane` row
 * *i* addresses MPD's queue index *i* directly (`playAt(i)`, `removeFromQueue(i)`), so a
 * result shorter than `mpdTracks`, or reordered relative to it, desyncs every index below
 * the first gap and makes the panel play or delete the wrong track. `reorderQueue` must
 * therefore never drop an MPD entry, even one this panel never queued itself.
 */
class QueueReconcileTest {

    private fun track(path: String, title: String) = LocalTrack(
        id = "jf-$path",
        title = title,
        scrobbleId = path,
        scrobbleProvider = "mpd",
    )

    private fun mpdItem(path: String, title: String, artist: String? = null) = MaItem(
        itemId = path,
        provider = "mpd",
        name = title,
        uri = path,
        mediaType = "track",
        subtitle = artist,
        image = null,
        duration = null,
    )

    @Test
    fun `re-orders to match MPD's reported order`() {
        val a = track("a.flac", "A")
        val b = track("b.flac", "B")
        val c = track("c.flac", "C")
        val result = reorderQueue(
            listOf(a, b, c),
            listOf(mpdItem("c.flac", "C"), mpdItem("a.flac", "A"), mpdItem("b.flac", "B")),
        )
        assertEquals(listOf(c, a, b), result)
    }

    @Test
    fun `a track MPD removed drops out, without touching the others`() {
        val a = track("a.flac", "A")
        val b = track("b.flac", "B")
        val c = track("c.flac", "C")
        // "remove index 1" on MPD's side means b.flac is simply absent from its next
        // playlistinfo -- this is exactly what QueuePane's remove button produces.
        val result = reorderQueue(listOf(a, b, c), listOf(mpdItem("a.flac", "A"), mpdItem("c.flac", "C")))
        assertEquals(listOf(a, c), result)
    }

    @Test
    fun `an empty MPD queue reconciles to an empty list`() {
        val a = track("a.flac", "A")
        assertEquals(emptyList(), reorderQueue(listOf(a), emptyList()))
    }

    @Test
    fun `preserves the Jellyfin id LocalTrack carries -- this is why it reorders, not rebuilds`() {
        val a = track("a.flac", "A")
        val result = reorderQueue(listOf(a), listOf(mpdItem("a.flac", "A")))
        assertEquals("jf-a.flac", result.single().id)
    }

    @Test
    fun `an MPD entry this panel never queued is synthesised, not dropped`() {
        // The regression this whole test class exists for: a track MPD is playing that
        // this panel has no record of -- another client's mpc/ncmpcpp/moOde add, or a
        // queue MPD already had running before the panel connected.
        val result = reorderQueue(emptyList(), listOf(mpdItem("unknown.flac", "Unknown Track", "Some Artist")))
        val synthesised = result.single()
        assertEquals("Unknown Track", synthesised.title)
        assertEquals("Some Artist", synthesised.artist)
        assertEquals("unknown.flac", synthesised.scrobbleId)
    }

    @Test
    fun `a synthesised entry carries no Jellyfin id -- id equals scrobbleId as that marker`() {
        val result = reorderQueue(emptyList(), listOf(mpdItem("unknown.flac", "Unknown Track")))
        val synthesised = result.single()
        assertEquals(synthesised.scrobbleId, synthesised.id)
    }

    @Test
    fun `PanelModel's Jellyfin-id lookup returns null for a synthesised entry`() {
        // trackIdAt (private in PanelModel) is `id.takeIf { it != scrobbleId }` -- this
        // pins the actual predicate SessionReporter depends on to skip reporting a
        // synthesised track, expressed against synthesizeQueueTrack directly so it can't
        // drift from PanelModel's copy unnoticed.
        val synthesised = synthesizeQueueTrack(mpdItem("unknown.flac", "Unknown Track"))
        assertNull(synthesised.id.takeIf { it != synthesised.scrobbleId })
    }

    @Test
    fun `a real Jellyfin-sourced entry's id lookup is non-null`() {
        val real = track("a.flac", "A")
        assertEquals("jf-a.flac", real.id.takeIf { it != real.scrobbleId })
    }

    @Test
    fun `index alignment holds for a mixed matched-and-unmatched queue`() {
        // The assertion that would have caught the original bug: result.size must equal
        // mpdTracks.size exactly, and element i must correspond to mpdTracks[i], regardless
        // of how many entries were matched vs synthesised.
        val a = track("a.flac", "A")
        val c = track("c.flac", "C")
        val mpdOrder = listOf(
            mpdItem("a.flac", "A"),
            mpdItem("unknown1.flac", "Unknown 1"),
            mpdItem("c.flac", "C"),
            mpdItem("unknown2.flac", "Unknown 2"),
        )
        val result = reorderQueue(listOf(a, c), mpdOrder)

        assertEquals(mpdOrder.size, result.size, "must never drop an MPD entry")
        mpdOrder.forEachIndexed { i, mpdEntry ->
            assertEquals(
                mpdEntry.itemId,
                result[i].scrobbleId,
                "row $i must address MPD's queue index $i, or playAt/removeFromQueue hits the wrong track",
            )
        }
        // And the two real entries specifically kept their Jellyfin identity in place.
        assertEquals("jf-a.flac", result[0].id)
        assertEquals("jf-c.flac", result[2].id)
        assertTrue(result[1].id == result[1].scrobbleId && result[3].id == result[3].scrobbleId)
    }
}

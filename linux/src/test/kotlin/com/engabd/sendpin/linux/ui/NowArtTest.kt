package com.engabd.sendpin.linux.ui

import com.engabd.sendpin.audio.LocalTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The now-playing cover held per album: which album a track belongs to, and when the
 * cover on screen may change — the fix for the art flashing on every skip.
 */
class NowArtTest {

    private fun track(id: String, album: String? = "Blue", art: String? = "http://jf/Items/$id/Images/Primary") =
        LocalTrack(id = id, title = "t$id", artist = "Various", album = album, artUrl = art)

    @Test
    fun `the album id wins, then the album title, then the cover url`() {
        assertEquals("id:alb1", albumKey(track("1"), "alb1"))
        assertEquals("album:blue", albumKey(track("1"), null))
        assertEquals("album:blue", albumKey(track("1", album = "  Blue "), " "))
        assertEquals("art:http://x", albumKey(track("1", album = null, art = "http://x"), null))
        assertNull(albumKey(track("1", album = null, art = null), null))
        assertNull(albumKey(null, "alb1"))
    }

    @Test
    fun `tracks with their own embedded art still share one album key`() {
        // Jellyfin names each track's cover after the track: different URLs, same record.
        val a = track("1")
        val b = track("2")
        assertEquals(albumKey(a, "alb1"), albumKey(b, "alb1"))
        assertEquals(albumKey(a, null), albumKey(b, null))
    }

    @Test
    fun `a skip inside the album keeps the cover already showing`() {
        val held = NowArt("id:alb1", "http://jf/Items/1/Images/Primary")
        val next = nextNowArt(held, "id:alb1", "http://jf/Items/2/Images/Primary", holdAlbum = true)
        assertSame(held, next)
    }

    @Test
    fun `a new album takes its own cover`() {
        val held = NowArt("id:alb1", "http://jf/Items/1/Images/Primary")
        assertEquals(NowArt("id:alb2", "http://jf/Items/9/Images/Primary"),
            nextNowArt(held, "id:alb2", "http://jf/Items/9/Images/Primary", holdAlbum = true))
    }

    @Test
    fun `an album that had no cover takes one when it appears`() {
        val held = NowArt("id:alb1", null)
        assertEquals(NowArt("id:alb1", "http://x"), nextNowArt(held, "id:alb1", "http://x", holdAlbum = true))
    }

    @Test
    fun `with holding off every track's own cover is shown`() {
        val held = NowArt("id:alb1", "http://jf/Items/1/Images/Primary")
        assertEquals(NowArt("id:alb1", "http://jf/Items/2/Images/Primary"),
            nextNowArt(held, "id:alb1", "http://jf/Items/2/Images/Primary", holdAlbum = false))
    }

    @Test
    fun `nothing playing clears the key`() {
        assertEquals(NowArt(null, null), nextNowArt(NowArt("id:a", "u"), null, null, holdAlbum = true))
    }

    // ── The album-change transition ──

    @Test
    fun `the chosen transition plays only on a change of album`() {
        val a = NowArt("id:a", "u1")
        assertEquals("flip", ArtTransition.kindFor(a, NowArt("id:b", "u2"), "flip"))
        assertEquals("dissolve", ArtTransition.kindFor(a, NowArt("id:b", "u2"), "dissolve"))
        // the same record's cover changing (holding off): a quiet cross-fade, never a flip
        assertEquals(ArtTransition.FADE, ArtTransition.kindFor(a, NowArt("id:a", "u3"), "flip"))
        // the very first cover fades in rather than turning over from nothing
        assertEquals(ArtTransition.FADE, ArtTransition.kindFor(NowArt(null, null), a, "flip"))
    }

    @Test
    fun `off is instant and every other move takes time`() {
        assertEquals(0, ArtTransition.durationMs(ArtTransition.OFF))
        listOf(ArtTransition.FLIP, ArtTransition.DISSOLVE, ArtTransition.SLIDE, ArtTransition.FADE).forEach {
            assertTrue(ArtTransition.durationMs(it) > 0, it)
        }
    }
}

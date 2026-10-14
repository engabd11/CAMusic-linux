package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.pickLyricMatch
import com.engabd.sendpin.linux.queueRowActions
import com.engabd.sendpin.linux.screensaver.ListeningLayout
import com.engabd.sendpin.linux.screensaver.portraitTopWeight
import com.engabd.sendpin.ma.MaItem
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationLogicTest {

    private fun album(id: String, name: String, artist: String?, genre: String?, year: Int? = null) =
        MaItem(
            itemId = id, provider = "t", name = name, uri = null, mediaType = "album", subtitle = artist,
            image = null, duration = null, year = year, genres = listOfNotNull(genre),
        )

    private fun song(id: String, name: String, artist: String?) =
        MaItem(itemId = id, provider = "t", name = name, uri = null, mediaType = "track", subtitle = artist, image = null, duration = 200)

    // ── gestures ────────────────────────────────────────────────────────────

    @Test
    fun `a swipe skips only once it is a deliberate distance`() {
        assertEquals(SwipeAction.NONE, swipeAction(-40f, 700f, 96f), "a wobble is not a swipe")
        assertEquals(SwipeAction.NONE, swipeAction(-120f, 700f, 96f), "under a fifth of a wide cover")
        assertEquals(SwipeAction.NEXT, swipeAction(-150f, 700f, 96f))
        assertEquals(SwipeAction.PREVIOUS, swipeAction(150f, 700f, 96f))
        assertEquals(SwipeAction.NEXT, swipeAction(-100f, 300f, 96f), "on a narrow cover the floor decides")
    }

    @Test
    fun `a queue row goes only when dragged well to the left`() {
        assertFalse(rowSwipeRemoves(-100f, 700f))
        assertTrue(rowSwipeRemoves(-250f, 700f))
        assertFalse(rowSwipeRemoves(250f, 700f), "to the right never removes")
        assertFalse(rowSwipeRemoves(-250f, 0f))
    }

    @Test
    fun `volume follows the drag and stays between 0 and 1`() {
        assertEquals(0.75f, volumeAfterDrag(0.5f, -50f, 200f), 1e-6f, "up is louder")
        assertEquals(0.25f, volumeAfterDrag(0.5f, 50f, 200f), 1e-6f)
        assertEquals(1f, volumeAfterDrag(0.9f, -999f, 200f))
        assertEquals(0f, volumeAfterDrag(0.1f, 999f, 200f))
        assertEquals(0.4f, volumeAfterDrag(0.4f, 30f, 0f), "no rail, no change")
    }

    @Test
    fun `a dragged queue row lands the whole rows it was carried`() {
        assertEquals(5, rowDropIndex(3, 2.2f * 76f, 76f, 20))
        assertEquals(1, rowDropIndex(3, -1.6f * 76f, 76f, 20))
        assertEquals(3, rowDropIndex(3, 20f, 76f, 20), "a nudge settles back")
        assertEquals(0, rowDropIndex(3, -9999f, 76f, 20), "kept inside the queue")
        assertEquals(19, rowDropIndex(3, 9999f, 76f, 20))
    }

    // ── the deck ────────────────────────────────────────────────────────────

    @Test
    fun `the deck is the next few tracks with their queue places`() {
        val q = (0..6).map { LocalTrack(id = "t$it", title = "T$it") }
        assertEquals(listOf(3, 4, 5), deckCards(q, 2).map { it.queueIndex })
        assertEquals(listOf(6), deckCards(q, 5).map { it.queueIndex }, "the end of the queue leaves fewer")
        assertTrue(deckCards(q, 6).isEmpty())
        assertTrue(deckCards(q, -1).isEmpty(), "nothing playing, nothing on the deck")
    }

    @Test
    fun `a deck card dropped over a neighbour takes its place`() {
        assertEquals(2, deckDropSlot(0, 2.1f * 150f, 150f, 3))
        assertEquals(0, deckDropSlot(1, -0.8f * 150f, 150f, 3))
        assertEquals(1, deckDropSlot(1, 30f, 150f, 3), "a small drag stays")
        assertEquals(2, deckDropSlot(2, 900f, 150f, 3), "kept on the deck")
    }

    // ── crates ──────────────────────────────────────────────────────────────

    @Test
    fun `albums are binned by first genre, the biggest bin first`() {
        val albums = listOf(
            album("1", "A", "Zed", "jazz"), album("2", "B", "Amy", "Jazz"), album("3", "C", "Bo", "JAZZ"),
            album("4", "D", "Cy", "rock"), album("5", "E", "Di", "Rock"),
            album("6", "F", "Ed", "folk"),
            album("7", "G", "Fay", null),
        )
        val bins = crateBins(albums)
        assertEquals(listOf("Jazz", "Rock", "Other", "Unsorted"), bins.map { it.name })
        assertEquals(listOf("Amy", "Bo", "Zed"), bins[0].albums.map { it.subtitle }, "by artist inside a bin")
        assertEquals(listOf("F"), bins[2].albums.map { it.name }, "a lone genre goes in Other")
    }

    @Test
    fun `a bin holds a limited number of records and the library a limited number of bins`() {
        val many = (1..50).map { album("a$it", "N$it", "Artist", "pop") }
        assertEquals(30, crateBins(many).single().albums.size)
        val genres = (1..20).flatMap { g -> (1..2).map { album("g$g-$it", "X", "Y", "genre$g") } }
        assertEquals(14, crateBins(genres).size)
        assertTrue(crateBins(emptyList()).isEmpty())
    }

    @Test
    fun `the record in front faces out and raised, the rest turn away and sink`() {
        val front = cratePose(0f)
        assertEquals(0f, front.rotationY, 1e-6f)
        assertEquals(1f, front.lift)
        assertTrue(cratePose(-1f).rotationY > 0f && cratePose(1f).rotationY < 0f, "turned toward the middle")
        assertEquals(0f, cratePose(2f).lift)
        assertTrue(cratePose(0f).scale > cratePose(2f).scale)
        assertEquals(-52f, cratePose(9f).rotationY, "never edge-on")
    }

    // ── the banner ──────────────────────────────────────────────────────────

    @Test
    fun `the banner names what cannot be reached, MPD first`() {
        assertNull(bannerMessage(true, "127.0.0.1:6600", null))
        assertEquals("Can't reach MPD", bannerMessage(false, "127.0.0.1:6600", null)?.title)
        assertTrue(bannerMessage(false, "nuc:6600", null)!!.detail.startsWith("nuc:6600"))
        assertEquals("Can't reach Jellyfin", bannerMessage(true, "x", "timeout")?.title)
        assertEquals("Can't reach MPD or Jellyfin", bannerMessage(false, "x", "timeout")?.title)
    }

    // ── deep idle ───────────────────────────────────────────────────────────

    @Test
    fun `deep idle waits for its time and follows its mode`() {
        val hour = 60 * 60_000L
        assertEquals(DeepIdleStage.NONE, deepIdleStage("clock", hour - 1, hour))
        assertEquals(DeepIdleStage.CLOCK, deepIdleStage("clock", hour, hour))
        assertEquals(DeepIdleStage.SCREEN_OFF, deepIdleStage("screen_off", 2 * hour, hour))
        assertEquals(DeepIdleStage.NONE, deepIdleStage("off", 99 * hour, hour))
        assertEquals(DeepIdleStage.NONE, deepIdleStage("nonsense", 99 * hour, hour))
    }

    @Test
    fun `the dim clock keeps moving and stays on screen`() {
        val spots = (0L..59L).map { clockSpot(it) }
        assertTrue(spots.all { (x, y) -> x in 0.08f..0.92f && y in 0.12f..0.82f })
        assertEquals(60, spots.distinct().size, "no minute repeats an hour's other positions")
        // Consecutive minutes land well apart, so it never creeps.
        assertTrue(spots.zipWithNext().all { (a, b) -> kotlin.math.abs(a.first - b.first) + kotlin.math.abs(a.second - b.second) > 0.15f })
    }

    @Test
    fun `the screen commands are xset's power saving`() {
        assertEquals(listOf("xset", "dpms", "force", "off"), screenCommands(false).last())
        assertEquals(listOf("xset", "dpms", "force", "on"), screenCommands(true).first())
    }

    // ── postcards ───────────────────────────────────────────────────────────

    @Test
    fun `a postcard's file name is sortable and safe`() {
        val at = LocalDateTime.of(2026, 10, 14, 21, 5)
        assertEquals("camusic-2026-10-14-2105-aurora-vale-glass-tides.png", postcardFileName(at, "Aurora Vale", "Glass Tides"))
        assertEquals("camusic-2026-10-14-2105-sigur-rós-hoppípolla.png", postcardFileName(at, "Sigur Rós", "Hoppípolla"), "letters in any script stay")
        assertEquals("camusic-2026-10-14-2105.png", postcardFileName(at, null, "///"))
        assertTrue(postcardFileName(at, "x".repeat(200), "y").length < 90, "a long name is cut")
        assertFalse(postcardFileName(at, "AC/DC", "Back in Black: Live?").contains('/'))
    }

    @Test
    fun `postcards go to Pictures under CAMusic`() {
        assertEquals(Path.of("/home/abd", "Pictures", "CAMusic"), postcardDirectory("/home/abd"))
    }

    // ── lyrics lookup ───────────────────────────────────────────────────────

    @Test
    fun `a lyric lookup takes the same title by the same artist`() {
        val hits = listOf(
            song("1", "Glass Tides", "The Night Harbours"),
            song("2", "Glass Tides", "Aurora Vale"),
            song("3", "Glass Tides (Live)", "Aurora Vale"),
        )
        assertEquals("2", pickLyricMatch(hits, "glass tides", "Aurora Vale")?.itemId)
        assertEquals("1", pickLyricMatch(hits, "Glass Tides", null)?.itemId, "no artist: the first of that title")
        assertEquals("1", pickLyricMatch(hits, "Glass Tides", "Someone Else")?.itemId, "no artist match: the first of that title")
        assertNull(pickLyricMatch(hits, "Nothing Like It", "Aurora Vale"))
    }

    // ── menus and layouts ───────────────────────────────────────────────────

    @Test
    fun `a held queue row offers only what makes sense for it`() {
        fun labels(playing: Boolean, next: Boolean, last: Boolean) =
            queueRowActions(playing, next, last, {}, {}, {}, {}).map { it.label }
        assertEquals(listOf("Play now", "Play next", "Move to the end", "Remove from queue"), labels(false, false, false))
        assertEquals(listOf("Play now", "Move to the end", "Remove from queue"), labels(false, true, false), "already next")
        assertEquals(listOf("Play now", "Play next", "Remove from queue"), labels(false, false, true), "already last")
        assertEquals(listOf("Remove from queue"), labels(true, false, false), "the playing track can only be removed")
        assertTrue(queueRowActions(false, false, false, {}, {}, {}, {}).last().danger)
    }

    @Test
    fun `a held library song offers its album and artist only when it has them`() {
        fun labels(album: Boolean, artist: Boolean) =
            libraryTrackActions(album, artist, {}, {}, {}, {}).map { it.label }
        assertEquals(listOf("Play next", "Add to queue", "Go to album", "Go to artist"), labels(true, true))
        assertEquals(listOf("Play next", "Add to queue"), labels(false, false))
    }

    @Test
    fun `the lyric sheet is one more layout and the old ones are untouched`() {
        assertEquals(ListeningLayout.LYRICS, ListeningLayout.from("lyrics"))
        assertEquals(ListeningLayout.CLASSIC, ListeningLayout.from(null), "the default has not moved")
        assertEquals(listOf("classic", "vinyl", "poster", "cover", "lyrics"), ListeningLayout.entries.map { it.key })
    }

    @Test
    fun `the record keeps its place however the words are set`() {
        // Without the extras the weights are the v1.1 ones; the deck and the signal panel
        // each ask for more room for the words, never less.
        assertEquals(0.53f, portraitTopWeight(clock = true, deck = false, signalPanel = false))
        assertEquals(0.40f, portraitTopWeight(clock = false, deck = false, signalPanel = false))
        assertTrue(portraitTopWeight(true, deck = false, signalPanel = true) < portraitTopWeight(true, false, false))
        assertTrue(portraitTopWeight(true, deck = true, signalPanel = true) < portraitTopWeight(true, false, true))
    }
}

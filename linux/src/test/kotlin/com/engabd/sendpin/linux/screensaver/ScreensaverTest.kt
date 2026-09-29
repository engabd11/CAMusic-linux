package com.engabd.sendpin.linux.screensaver

import com.engabd.sendpin.ma.MaItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The screensaver's pure parts: when it shows, what order albums come in, how each is set. */
class ScreensaverTest {

    // ── IdleGate ──

    @Test
    fun `shows only when enabled, not playing, and idle past the delay`() {
        val delay = 600_000L
        assertTrue(IdleGate.shouldShow(enabled = true, playing = false, idleMs = delay, delayMs = delay))
        assertFalse(IdleGate.shouldShow(enabled = true, playing = false, idleMs = delay - 1, delayMs = delay))
        assertFalse(IdleGate.shouldShow(enabled = true, playing = true, idleMs = delay * 10, delayMs = delay))
        assertFalse(IdleGate.shouldShow(enabled = false, playing = false, idleMs = delay * 10, delayMs = delay))
    }

    @Test
    fun `prewarms half a minute before it shows`() {
        val delay = 600_000L
        assertTrue(IdleGate.shouldPrewarm(true, false, delay - IdleGate.PREWARM_MS, delay))
        assertFalse(IdleGate.shouldPrewarm(true, false, delay - IdleGate.PREWARM_MS - 1, delay))
    }

    @Test
    fun `a touch restarts the idle clock`() {
        var now = 0L
        val gate = IdleGate { now }
        now = 50_000
        assertEquals(50_000, gate.idleMs())
        gate.touch()
        now = 51_000
        assertEquals(1_000, gate.idleMs())
    }

    // ── SlideDeck ──

    private fun album(id: String, artist: String? = "Artist $id", image: String? = "http://art/$id") = MaItem(
        itemId = id,
        provider = "jellyfin",
        name = "Album $id",
        uri = id,
        mediaType = "album",
        subtitle = artist,
        image = image,
        duration = null,
    )

    @Test
    fun `no album repeats until every album has been shown`() {
        val deck = SlideDeck(Random(1))
        val library = (1..12).map { album("$it") }
        deck.add(library)
        val firstPass = List(12) { deck.next()!!.itemId }
        assertEquals(library.map { it.itemId }.toSet(), firstPass.toSet())
        assertNull(deck.next())
        deck.add(library) // the library comes round again
        assertEquals(12, deck.size)
    }

    @Test
    fun `refills skip what this pass already showed`() {
        val deck = SlideDeck(Random(2))
        deck.add((1..10).map { album("$it") })
        val shown = List(5) { deck.next()!!.itemId }.toSet()
        deck.add((1..10).map { album("$it") } + album("new"))
        val rest = generateSequence { deck.next()?.itemId }.toList()
        assertTrue(rest.none { it in shown })
        assertTrue("new" in rest)
    }

    @Test
    fun `never the same artist twice running when there is a choice`() {
        val deck = SlideDeck(Random(3))
        deck.add((1..6).map { album("a$it", artist = "Same") } + (1..6).map { album("b$it", artist = "Other $it") })
        var last: String? = null
        repeat(12) {
            val artist = deck.next()!!.subtitle
            if (artist == "Same") assertNotEquals("Same", last)
            last = artist
        }
    }

    @Test
    fun `albums without art are dropped`() {
        val deck = SlideDeck()
        deck.add(listOf(album("1", image = null), album("2", image = ""), album("3")))
        assertEquals(1, deck.size)
    }

    // ── SlideType ──

    @Test
    fun `genre picks the face`() {
        assertEquals(Face.CLASSICAL, faceFor(listOf("Classical"), 1990, "Goldberg Variations", "x"))
        assertEquals(Face.JAZZ, faceFor(listOf("Jazz"), 1959, "Kind of Blue", "x"))
        assertEquals(Face.ROCK, faceFor(listOf("Alternative Rock"), 1997, "OK Computer", "x"))
        assertEquals(Face.HIPHOP, faceFor(listOf("Hip-Hop"), 2015, "To Pimp a Butterfly", "x"))
        assertEquals(Face.ELECTRONIC, faceFor(listOf("Electronic"), 2001, "Discovery", "x"))
        assertEquals(Face.SOUL, faceFor(listOf("Soul"), 1971, "What's Going On", "x"))
        assertEquals(Face.FOLK, faceFor(listOf("Folk"), 2008, "For Emma", "x"))
        assertEquals(Face.RETRO, faceFor(listOf("Disco"), 1978, "C'est Chic", "x"))
        assertEquals(Face.POP, faceFor(listOf("Pop"), 2020, "Future Nostalgia", "x"))
    }

    @Test
    fun `the first matching genre wins in priority order`() {
        // "Synth-pop" is electronic before it is pop
        assertEquals(Face.ELECTRONIC, faceFor(listOf("Synth-pop"), 1983, "Violator", "x"))
    }

    @Test
    fun `no genre - the same album is always set the same way, in a quiet face`() {
        val a = faceFor(emptyList(), 2004, "Untitled", "album-42")
        repeat(3) { assertEquals(a, faceFor(emptyList(), 2004, "Untitled", "album-42")) }
        assertTrue(a in setOf(Face.POP, Face.SOUL, Face.CLASSICAL, Face.RETRO))
        assertTrue(faceFor(emptyList(), 1962, "Old", "album-7") in setOf(Face.SOUL, Face.RETRO))
    }

    @Test
    fun `non-Latin titles get the plain face`() {
        assertEquals(Face.PLAIN, faceFor(listOf("Pop"), 2020, "أم كلثوم", "x"))
        assertEquals(Face.PLAIN, faceFor(listOf("Rock"), 2020, "東京事変", "x"))
        assertEquals(Face.POP, faceFor(listOf("Pop"), 2020, "Café Society — 2 (Déjà vu)", "x"))
    }

    @Test
    fun `details line is year and two genres`() {
        assertEquals("1973  ·  SOUL  ·  FUNK", detailsLine(1973, listOf("Soul", "Funk", "Disco")))
        assertEquals("JAZZ", detailsLine(null, listOf("Jazz", "jazz ")))
        assertEquals("", detailsLine(null, emptyList()))
    }

    // ── SlideMeta ──

    @Test
    fun `meta parses genres, year, own logo and the artist's backdrop and logo`() {
        val body = """
            {"Items":[{"Id":"alb1","Genres":["Jazz","Cool Jazz"],"ProductionYear":1959,
              "ImageTags":{"Primary":"p1","Logo":"lg1"},"BackdropImageTags":[],
              "ParentBackdropItemId":"art1","ParentBackdropImageTags":["bd1"],
              "ParentLogoItemId":"art1","ParentLogoImageTag":"al1"},
             {"Id":"alb2","ImageTags":{"Primary":"p2"}}]}
        """.trimIndent()
        val m = SlideMetaClient.parse(body, "http://jf:8096/", "TOKEN")
        val a = m.getValue("alb1")
        assertEquals(listOf("Jazz", "Cool Jazz"), a.genres)
        assertEquals(1959, a.year)
        assertEquals("http://jf:8096/Items/alb1/Images/Logo?tag=lg1&maxWidth=900&quality=90&api_key=TOKEN", a.logoUrl)
        assertEquals("http://jf:8096/Items/art1/Images/Logo?tag=al1&maxWidth=900&quality=90&api_key=TOKEN", a.artistLogoUrl)
        assertEquals("http://jf:8096/Items/art1/Images/Backdrop/0?tag=bd1&maxWidth=1920&quality=90&api_key=TOKEN", a.backdropUrl)
        val b = m.getValue("alb2")
        assertEquals(SlideMeta(), b)
    }
}

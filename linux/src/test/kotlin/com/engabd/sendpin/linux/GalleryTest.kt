package com.engabd.sendpin.linux

import com.engabd.sendpin.jellyfin.JellyfinCue
import com.engabd.sendpin.jellyfin.jellyfinLineBody
import com.engabd.sendpin.linux.station.LocalRadio
import com.engabd.sendpin.linux.station.RadioSource
import com.engabd.sendpin.linux.station.SmoothPlayhead
import com.engabd.sendpin.linux.station.karaokeLines
import com.engabd.sendpin.linux.station.radioNeedsMore
import com.engabd.sendpin.linux.station.spreadArtists
import com.engabd.sendpin.linux.ui.SharedCovers
import com.engabd.sendpin.linux.ui.sizedArt
import com.engabd.sendpin.lyrics.Lrclib
import com.engabd.sendpin.ma.LyricTiming
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryTest {

    private fun item(id: String, artist: String? = null, type: String = "track", genres: List<String> = emptyList(), parent: String? = null) =
        MaItem(
            itemId = id, provider = "jellyfin", name = id, uri = null, mediaType = type, subtitle = artist,
            image = null, duration = 200, genres = genres, parentId = parent,
        )

    // ── Enhanced LRC ──

    @Test
    fun enhancedLrcCarriesWordTimes() {
        val lrc = MaLyrics("[00:10.00]<00:10.00>Hello <00:10.50>bright <00:11.20>world<00:12.00>\n[00:15.00]plain line", synced = true)
        val lines = lrc.lines
        assertEquals("Hello bright world", lines[0].text)
        val words = assertNotNull(lines[0].words)
        assertEquals(listOf(10_000L, 10_500L, 11_200L), words.map { it.atMs })
        assertEquals(12_000L, words.last().endMs)
        assertNull(lines[1].words)
        assertTrue(lrc.hasWordTiming)
    }

    @Test
    fun plainLrcStillParsesLineByLine() {
        val lines = MaLyrics("[00:01.00]one\n[00:05.50]two", synced = true).lines
        assertEquals(listOf(1_000L, 5_500L), lines.map { it.atMs })
        assertTrue(lines.all { it.words == null })
    }

    @Test
    fun sungCharsFillsWordByWord() {
        val line = MaLyrics("[00:10.00]<00:10.00>ab <00:11.00>cd<00:12.00>", synced = true).lines.single()
        assertEquals(0f, LyricTiming.sungChars(line, 9_000))
        assertEquals(1f, LyricTiming.sungChars(line, 10_500), 0.01f) // half of "ab"
        assertEquals(2f, LyricTiming.sungChars(line, 10_999), 0.05f)
        assertEquals(5f, LyricTiming.sungChars(line, 13_000), 0.01f) // "ab cd" sung through
    }

    @Test
    fun karaokeEstimatesWordsOnlyWhenAsked() {
        val lrc = MaLyrics("[00:01.00]one two three\n[00:05.00]four", synced = true)
        assertTrue(karaokeLines(lrc, karaoke = true).first().words!!.all { it.estimated })
        assertTrue(karaokeLines(lrc, karaoke = false).all { it.words == null })
    }

    // ── Jellyfin word cues ──

    @Test
    fun jellyfinCuesBecomeEnhancedLrc() {
        val body = jellyfinLineBody(
            "Hello world",
            listOf(JellyfinCue(0, 5, 10_000, 10_400), JellyfinCue(6, 11, 10_500, 11_000)),
        )
        assertEquals("<00:10.00>Hello <00:10.50>world<00:11.00>", body)
        val line = MaLyrics("[00:10.00]$body", synced = true).lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words?.size)
    }

    @Test
    fun badCuesAreIgnored() {
        assertEquals("Hi", jellyfinLineBody("Hi", listOf(JellyfinCue(4, 9, 0, null))))
        assertEquals("Hi", jellyfinLineBody("Hi", emptyList()))
    }

    // ── LRCLIB ──

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun lrclibPrefersSyncedLyrics() {
        val l = assertNotNull(Lrclib.lyricsOf(obj("""{"syncedLyrics":"[00:01.00]hi","plainLyrics":"hi"}""")))
        assertTrue(l.synced)
        assertEquals("LRCLIB", l.source)
        assertNull(Lrclib.lyricsOf(obj("""{"instrumental":true,"plainLyrics":"x"}""")))
        assertEquals(false, Lrclib.lyricsOf(obj("""{"plainLyrics":"just words"}"""))?.synced)
    }

    @Test
    fun lrclibSearchMatchesTheLength() {
        val hits = listOf(obj("""{"id":1,"duration":300.0}"""), obj("""{"id":2,"duration":241.0}"""), obj("""{"id":3,"duration":239.5}"""))
        assertEquals("3", Lrclib.pickSearchResult(hits, 240)?.get("id").toString())
        assertNull(Lrclib.pickSearchResult(hits, 100))
        assertEquals("1", Lrclib.pickSearchResult(hits, null)?.get("id").toString())
    }

    // ── The playhead ──

    @Test
    fun playheadRunsOnAndNeverTwitchesBack() {
        val p = SmoothPlayhead()
        p.onReading(10_000, playing = true, atMs = 0)
        assertEquals(10_500, p.valueAt(500))
        // A reading 100 ms behind where the clock had carried it is held, not stepped back to.
        assertEquals(10_999, p.valueAt(999))
        p.onReading(10_900, playing = true, atMs = 1_000)
        assertEquals(10_999, p.valueAt(1_000))
        assertTrue(p.valueAt(1_200) >= 11_000)
        // A seek back is taken at once.
        p.onReading(2_000, playing = true, atMs = 2_000)
        assertEquals(2_000, p.valueAt(2_000))
        // Paused, it stands still.
        p.onReading(3_000, playing = false, atMs = 3_000)
        assertEquals(3_000, p.valueAt(9_000))
    }

    @Test
    fun playheadStopsWhenThePollDoes() {
        val p = SmoothPlayhead(capMs = 2_500)
        p.onReading(0, playing = true, atMs = 0)
        assertEquals(2_500, p.valueAt(60_000))
    }

    // ── Cover Flow ──

    @Test
    fun flowPoseIsSymmetricAndFacesOutInTheMiddle() {
        val mid = flowPose(0f)
        assertEquals(0f, mid.x)
        assertEquals(0f, abs(mid.rotationY))
        assertEquals(1f, mid.scale)
        val l = flowPose(-2f)
        val r = flowPose(2f)
        assertEquals(-l.x, r.x, 1e-4f)
        assertEquals(-l.rotationY, r.rotationY, 1e-4f)
        assertTrue(r.rotationY < 0f && r.scale < 1f)
        assertEquals(0f, flowPose(9f).alpha)
    }

    @Test
    fun flowSettlesForwardOnAFlingAndStaysInRange() {
        assertEquals(3, flowTarget(3.2f, 0f, 10))
        assertTrue(flowTarget(3.2f, 20f, 10) > 3)
        assertEquals(9, flowTarget(8f, 500f, 10))
        assertEquals(0, flowTarget(1f, -500f, 10))
    }

    // ── Home carousel ──

    @Test
    fun heroSlidesHaveNoAlbumTwice() {
        val a = item("a", type = "album").copy(image = "http://x/a")
        val b = item("b", type = "album").copy(image = "http://x/b")
        val c = item("c", type = "album").copy(image = "http://x/c")
        val bare = item("bare", type = "album")
        val slides = heroSlides(HomeShelves(recentlyPlayed = listOf(a), recentlyAdded = listOf(a, b, bare), rediscover = listOf(b, c)))
        assertEquals(listOf("a", "b", "c"), slides.map { it.item.itemId })
        assertEquals("Jump back in", slides.first().why)
    }

    // ── Album and artist pages ──

    @Test
    fun albumFactsReadLikeASleeve() {
        val tracks = listOf(item("1").copy(duration = 120, discNumber = 1), item("2").copy(duration = 180, discNumber = 2))
        val facts = albumFactRows(2014, "2014-03-21", "Ninja Tune", listOf("Electronic"), tracks, 7.25).toMap()
        assertEquals("21 March 2014", facts["Released"])
        assertEquals("Ninja Tune", facts["Label"])
        assertEquals("2", facts["Tracks"])
        assertEquals("2", facts["Discs"])
        assertEquals("7.3 / 10", facts["Rating"])
        assertEquals("2014", albumFactRows(2014, null, null, emptyList(), emptyList(), null).toMap()["Year"])
    }

    @Test
    fun artistFactsAndLatest() {
        val albums = listOf(
            item("x", type = "album").copy(year = 1999, genres = listOf("Jazz")),
            item("y", type = "album").copy(year = 2021, genres = listOf("Jazz")),
        )
        assertEquals("2 albums  ·  1999–2021  ·  Jazz", artistFacts(albums, emptyList()))
        assertEquals("y", latestOf(albums)?.itemId)
    }

    @Test
    fun overviewsLoseTheirHtml() {
        assertEquals("One & two\nthree", com.engabd.sendpin.jellyfin.plainText("<p>One &amp; two<br/>three</p>"))
    }

    // ── Shared covers and art sizes ──

    @Test
    fun albumPageBorrowsTheTappedTilesKey() {
        val covers = SharedCovers()
        assertEquals("page/x", covers.keyFor("x"))
        covers.lastTapped = "shelf:Recently added/x"
        assertEquals("shelf:Recently added/x", covers.keyFor("x"))
        assertEquals("page/y", covers.keyFor("y"))
    }

    @Test
    fun sizedArtRewritesOnlyTheWidth() {
        assertEquals("http://j/Items/1/Images/Primary?maxWidth=420&api_key=k", sizedArt("http://j/Items/1/Images/Primary?maxWidth=1000&api_key=k", 420))
        assertEquals("http://other/cover.jpg", sizedArt("http://other/cover.jpg", 420))
        assertNull(sizedArt(null, 420))
    }

    // ── Radio ──

    private class FakeRadio(
        val similar: List<MaItem> = emptyList(),
        val top: List<MaItem> = emptyList(),
        val genre: List<MaItem> = emptyList(),
        val any: List<MaItem> = emptyList(),
    ) : RadioSource {
        override suspend fun similarTo(id: String, count: Int) = similar
        override suspend fun topSongs(artistName: String, count: Int) = top
        override suspend fun byGenre(genre: String, count: Int) = this.genre
        override suspend fun random(count: Int) = any
    }

    @Test
    fun radioTakesTheFirstRungThatAnswers() = runBlocking {
        val seed = item("s", artist = "A", genres = listOf("Jazz"))
        val radio = LocalRadio()
        val fromTop = radio.next(FakeRadio(top = listOf(item("t1"), item("t2"))), seed, count = 5)
        assertEquals(listOf("t1", "t2"), fromTop.map { it.itemId })
        // Already served, so the same rung has nothing new and the ladder falls through.
        val next = radio.next(FakeRadio(top = listOf(item("t1")), genre = listOf(item("g1"))), seed, count = 5)
        assertEquals(listOf("g1"), next.map { it.itemId })
    }

    @Test
    fun radioSkipsWhatIsQueuedAndWhatIsNotASong() = runBlocking {
        val radio = LocalRadio()
        val got = radio.next(
            FakeRadio(any = listOf(item("q"), item("al", type = "album"), item("ok"))),
            seed = null, count = 5, exclude = setOf("q"),
        )
        assertEquals(listOf("ok"), got.map { it.itemId })
    }

    @Test
    fun radioSpreadsArtistsAndRefillsNearTheEnd() {
        val spread = spreadArtists(listOf(item("1", "A"), item("2", "A"), item("3", "B"), item("4", "B")))
        assertEquals(listOf("1", "3", "2", "4"), spread.map { it.itemId })
        assertTrue(radioNeedsMore(index = 8, queueSize = 10))
        assertTrue(!radioNeedsMore(index = 2, queueSize = 10))
        assertTrue(!radioNeedsMore(index = -1, queueSize = 0))
    }
}

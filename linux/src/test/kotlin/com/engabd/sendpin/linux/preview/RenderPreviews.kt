package com.engabd.sendpin.linux.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.RemoteState
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.ServerKind
import com.engabd.sendpin.library.SourceError
import com.engabd.sendpin.linux.HueGhostClient
import com.engabd.sendpin.linux.LayoutMode
import com.engabd.sendpin.linux.LibraryCategory
import com.engabd.sendpin.linux.LibraryRoute
import com.engabd.sendpin.linux.LibraryState
import com.engabd.sendpin.linux.LinuxSettings
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.PanelShell
import com.engabd.sendpin.linux.PanelTab
import com.engabd.sendpin.linux.PortraitShell
import com.engabd.sendpin.linux.screensaver.IdleGate
import com.engabd.sendpin.linux.screensaver.ListeningHost
import com.engabd.sendpin.linux.screensaver.ScreensaverHost
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.ui.theme.AccentChoice
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.ThemeChoice
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/*
 * The preview renderer: every pane of the panel, drawn offscreen with a fake library
 * and written to PNG — so the UI can be looked at from a machine that is not the NUC.
 *
 * Nothing here talks to a real server. A tiny local HTTP server hands out generated
 * cover art (Coil and the palette extractor both fetch over HTTP, exactly as they do
 * from Jellyfin) and a canned HueGhost `/api/status`; [FakeLibrary] answers the
 * library's questions from a made-up catalogue of fictional artists.
 *
 *   ./gradlew :linux:renderPreviews   → one PNG per screen in linux/build/previews
 */

fun main(args: Array<String>) {
    // Any failure must end the process: the preview server's thread is not a daemon,
    // so an exception escaping main would leave the JVM (and a CI job) running forever.
    try {
        renderAll(args)
    } catch (t: Throwable) {
        t.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private fun renderAll(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/previews").apply { mkdirs() }
    val server = PreviewServer.start()
    val lib = FakeLibrary(server.base)

    fun freshModel(): PanelModel {
        val path = Files.createTempFile("camusic-preview", ".json").also { Files.delete(it) }
        val settings = LinuxSettings(path)
        runBlocking {
            settings.setMpdAddress("127.0.0.1:6600")
            settings.setJfUrl("http://jellyfin.lan:8096")
            settings.setJfToken("4f1c9a0b7d2e4c55a1b8e9f03d6c2a71")
            settings.setJellyfinPathPrefix("/mnt/music/")
            settings.setHueGhostUrl("http://192.168.1.20:8765")
            settings.setHueGhostToken("hg_live_29c1f0")
            settings.setReplayGainMode("album")
        }
        return PanelModel(settings).apply {
            _source.value = lib
            _hueGhost.value = HueGhostClient(server.base, "preview")
            val album = lib.albums.first()
            val tracks = lib.tracksOf(album)
            queue.value = tracks.mapIndexed { i, t ->
                LocalTrack(
                    id = t.itemId,
                    title = t.name,
                    artist = t.subtitle,
                    album = album.name,
                    durationMs = (t.duration ?: 0) * 1000L,
                    artUrl = t.image,
                    scrobbleId = "${album.subtitle}/${album.name}/%02d.flac".format(i + 1),
                    scrobbleProvider = "mpd",
                    sourceQuality = StreamQuality(codec = "FLAC", sampleRateHz = 96_000, bitDepth = 24),
                )
            }
            state.value = RemoteState(
                playing = true,
                stopped = false,
                index = 3,
                positionMs = 83_000,
                durationMs = (tracks[3].duration ?: 240) * 1000L,
                volume = null,
                outputFormat = RemoteAudioFormat(96_000, 24, 2, 4_608),
                outputDeviceName = "Topping D90SE",
            )
            replayGain.value = "album"
            statusLine.value = "MPD connected — 127.0.0.1:6600  ·  Jellyfin connected"
        }
    }

    fun portrait(name: String, tab: PanelTab, expanded: Boolean = false, stack: List<LibraryRoute>? = null, query: String = "", height: Int = 1280) {
        val model = freshModel()
        val state = LibraryState().also { s ->
            // Bind first so the pane's own bind is a no-op and the stack below survives.
            s.bind(lib)
            stack?.let { s.stack = it }
            s.query = query
        }
        render(File(out, "$name.png"), 800, height) {
            Themed { PortraitShell(model, initialTab = tab, initiallyExpanded = expanded, libraryState = remember { state }) }
        }
    }

    val album = lib.albums[4]
    val artist = lib.artists[0]
    portrait("01-now-playing", PanelTab.LIBRARY, expanded = true)
    portrait("02-library-home", PanelTab.LIBRARY)
    portrait("03-album", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Album(album)))
    portrait("04-artist", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Artist(artist)))
    portrait("05-favourites", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Browse(LibraryCategory.FAVORITES)))
    portrait("06-albums", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Browse(LibraryCategory.ALBUMS)))
    portrait("07-artists", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Browse(LibraryCategory.ARTISTS)))
    portrait("08-genres", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Browse(LibraryCategory.GENRES)))
    portrait("09-playlist", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Playlist(lib.playlists[0])))
    portrait("10-search", PanelTab.LIBRARY, stack = listOf(LibraryRoute.Home, LibraryRoute.Search), query = "au")
    portrait("11-queue", PanelTab.QUEUE)
    portrait("12-lights", PanelTab.LIGHTS)
    portrait("13-settings", PanelTab.SETTINGS)
    // Tall renders: a whole scrolling page at once, for review.
    portrait("15-lights-full", PanelTab.LIGHTS, height = 1700)
    portrait("16-settings-full", PanelTab.SETTINGS, height = 3900)
    portrait("17-home-full", PanelTab.LIBRARY, height = 2600)

    val model = freshModel()
    render(File(out, "14-landscape.png"), 1280, 800) {
        Themed { PanelShell(model, LayoutMode.LANDSCAPE) }
    }

    // The screensaver, forced on ("Preview now") with nothing playing. Long renders: the
    // feed loads over real HTTP, then the first slide dissolves in and its words enter.
    fun screensaver(
        name: String, style: String, width: Int, height: Int,
        clock: Boolean = true, albumText: Boolean = false,
        textSize: String = "regular", clockAt: String = "right",
    ) {
        val m = freshModel()
        runBlocking {
            m.settings.setScreensaverStyle(style)
            m.settings.setScreensaverClock(clock)
            m.settings.setScreensaverAlbumText(albumText)
            m.settings.setScreensaverTextSize(textSize)
            m.settings.setScreensaverClockPosition(clockAt)
        }
        m.state.value = m.state.value?.copy(playing = false)
        m.screensaverNow.value = true
        render(File(out, "$name.png"), width, height, frames = 320) {
            Themed { ScreensaverHost(m, remember { IdleGate() }) }
        }
    }
    screensaver("18-screensaver-gallery-landscape", "gallery", 1280, 800)
    screensaver("19-screensaver-gallery-portrait", "gallery", 800, 1280)
    screensaver("20-screensaver-fullbleed-landscape", "kenburns", 1280, 800)
    screensaver("21-screensaver-fullbleed-portrait", "kenburns", 800, 1280, clock = false)
    screensaver("22-screensaver-colours-landscape", "bloom", 1280, 800, albumText = true)
    screensaver("23-screensaver-colours-portrait", "bloom", 800, 1280, albumText = true, textSize = "large", clockAt = "centre")
    screensaver("24-screensaver-gallery-albumtext", "gallery", 800, 1280, albumText = true, textSize = "small", clockAt = "left")
    screensaver("25-screensaver-large-landscape", "gallery", 1280, 800, textSize = "large", clockAt = "left")

    // The listening view, forced on ("Preview now") while the fake album plays — long
    // enough for the cover to arrive and, past the 10 s mark, begin to come alive.
    fun listening(name: String, width: Int, height: Int, clock: Boolean = true) {
        val m = freshModel()
        runBlocking {
            m.settings.setListeningClock(clock)
            // A still can't show the cover moving, and the shader is costly on a CPU.
            m.settings.setLivingArt("off")
        }
        m.listeningNow.value = true
        render(File(out, "$name.png"), width, height, frames = 320) {
            Themed { ListeningHost(m, remember { IdleGate() }) }
        }
    }
    listening("26-listening-portrait", 800, 1280)
    listening("27-listening-landscape", 1280, 800)
    listening("28-listening-portrait-noclock", 800, 1280, clock = false)

    server.stop()
    println("Previews written to ${out.absolutePath}")
    kotlin.system.exitProcess(0)
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    SendspinTheme(theme = ThemeChoice.DARK, accentChoice = AccentChoice.ALBUM, glassBlur = true, content = content)
}

/** The wall-clock ceiling for one preview. */
private const val RENDER_BUDGET_NS = 90_000_000_000L

/**
 * Draw [content] for a couple of seconds of frames — long enough for the fake library's
 * loads, Coil's image fetches, the palette extraction and the cover's rise-in to settle
 * — then write the last frame.
 */
private fun render(file: File, width: Int, height: Int, frames: Int = 90, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width, height, Density(1f), content = content)
    val started = System.nanoTime()
    var t = 0L
    var drawn = 0
    while (drawn < frames) {
        scene.render(t)
        t += 16_000_000L
        drawn++
        // A software-rendered shader (CI has no GPU) can make every frame expensive;
        // a preview that runs long is written as it stands rather than holding up the rest.
        if (System.nanoTime() - started > RENDER_BUDGET_NS) break
        Thread.sleep(25)
    }
    val image = scene.render(t)
    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    val secs = (System.nanoTime() - started) / 1e9
    if (drawn < frames) print("  (cut at frame $drawn of $frames)")
    println("  ${file.name}  ${"%.1f".format(secs)} s")
}

// ─── Fake server ──────────────────────────────────────────────────────────────

private class PreviewServer(private val http: HttpServer) {
    val base = "http://127.0.0.1:${http.address.port}"
    fun stop() = http.stop(0)

    companion object {
        private val cache = ConcurrentHashMap<String, ByteArray>()

        fun start(): PreviewServer {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/art/") { ex ->
                val name = ex.requestURI.path.removePrefix("/art/").removeSuffix(".png")
                val bytes = cache.getOrPut(name) { artPng(name) }
                ex.responseHeaders.add("Content-Type", "image/png")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            http.createContext("/api/status") { ex ->
                val body = """{"state":"syncing","mode":"music","intensity":"high","offset_s":0.3,""" +
                    """"follow":{"item":"Aurora Vale — Glass Tides"},"version":"2.8.1"}"""
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            http.start()
            return PreviewServer(http)
        }

        private fun hsv(h: Float, s: Float, v: Float, a: Int = 255): Int {
            val rgb = java.awt.Color.HSBtoRGB(((h % 360f) + 360f) % 360f / 360f, s, v)
            return (a shl 24) or (rgb and 0xFFFFFF)
        }

        /** A made-up cover: a vivid gradient with shapes over it, seeded by [name]. */
        private fun artPng(name: String): ByteArray {
            val rnd = Random(name.hashCode())
            val size = 480
            val surface = Surface.makeRasterN32Premul(size, size)
            val c = surface.canvas
            val h0 = rnd.nextFloat() * 360f
            val s = size.toFloat()
            // A diagonal-ish two-stop gradient, drawn as bands (plain paint, no shader API).
            val top = hsv(h0, 0.75f, 0.85f)
            val bottom = hsv(h0 + 70f, 0.85f, 0.25f)
            for (y in 0 until size) {
                val f = y / s
                fun mix(sh: Int) = (((top shr sh) and 0xFF) * (1 - f) + ((bottom shr sh) and 0xFF) * f).toInt()
                val col = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                c.drawRect(Rect.makeXYWH(0f, y.toFloat(), s, 1f), Paint().apply { color = col })
            }
            if (name.startsWith("artist")) {
                // A silhouette-ish portrait.
                c.drawCircle(s / 2, s * 0.40f, s * 0.18f, Paint().apply { color = hsv(h0 + 180f, 0.3f, 0.95f, 230) })
                c.drawCircle(s / 2, s * 1.05f, s * 0.42f, Paint().apply { color = hsv(h0 + 180f, 0.3f, 0.95f, 230) })
            } else {
                when (rnd.nextInt(3)) {
                    0 -> repeat(4) { i ->
                        c.drawCircle(s * rnd.nextFloat(), s * rnd.nextFloat(), s * (0.12f + 0.2f * rnd.nextFloat()),
                            Paint().apply { color = hsv(h0 + 40f * (i + 1), 0.7f, 0.95f, 200) })
                    }
                    1 -> repeat(9) { i ->
                        c.drawRect(Rect.makeXYWH(0f, s * i / 9f, s, s / 18f),
                            Paint().apply { color = hsv(h0 + 150f + i * 8f, 0.65f, 0.95f, 150) })
                    }
                    else -> repeat(5) { i ->
                        c.drawCircle(s / 2, s / 2, s * (0.45f - i * 0.08f),
                            Paint().apply { color = hsv(h0 + 25f * i + 120f, 0.8f, 0.4f + 0.12f * i, 255) })
                    }
                }
            }
            val img = surface.makeImageSnapshot()
            return img.encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
    }
}

// ─── Fake library ─────────────────────────────────────────────────────────────

private val ARTISTS = listOf(
    "Aurora Vale", "The Night Harbours", "Kofi Mensah Trio", "Lumen Drift", "Saffron & Sea",
    "Marlowe", "Hollow Pines", "Neon Cathedral", "Yuki Tanabe", "Glasshouse Orchestra",
    "Desert Radio", "Iris Okafor",
)
private val ALBUM_WORDS = listOf(
    "Glass Tides", "Low Light", "Northern Static", "Paper Moons", "The Long Summer", "Afterglow",
    "Salt & Ember", "Blue Hour", "Weather Systems", "Quiet Machines", "Golden Record", "Night Swim",
    "Cartography", "Velvet Signal", "Open Water", "Satellite Hearts", "Slow Bloom", "Echo Park",
    "Midnight Harbour", "Fieldnotes", "Parallel Lines", "Soft Focus", "Lanterns", "The Tall Grass",
    "Coastlines", "Ultraviolet", "Standing Waves", "Harvest Moon", "Kaleidoscope", "First Light",
    "Tin Roof Rain", "Evergreen", "Solstice", "Heat Haze", "Morning Songs", "Constellations",
)
private val TRACK_WORDS = listOf(
    "Intro", "Glass Tides", "Held Breath", "Undertow", "Lighthouse", "Paper Boats", "Signal Fire",
    "Slow Dance", "Northbound", "Afterimage", "Tidal", "Reprise", "Weightless", "Harbour Lights",
    "Night Bus", "Fever Dream", "Outro",
)
private val GENRES = listOf(
    "Ambient", "Blues", "Classical", "Electronic", "Folk", "Hip-Hop", "Indie", "Jazz", "Metal",
    "Pop", "R&B", "Rock", "Soul", "Soundtrack", "World",
)

private class FakeLibrary(base: String) : MusicSource {
    override val kind = ServerKind.JELLYFIN
    override val providerId = "preview"
    override val capabilities = setOf(Capability.SEARCH, Capability.GENRES, Capability.FAVORITES, Capability.PLAYLIST_READ, Capability.TRACKS)
    override val serverUrl = base

    val artists: List<MaItem> = ARTISTS.mapIndexed { i, n ->
        MaItem("ar$i", "preview", n, null, "artist", null, "$base/art/artist-$i.png", null, favorite = i % 3 == 0)
    }
    val albums: List<MaItem> = ALBUM_WORDS.mapIndexed { i, n ->
        MaItem(
            "al$i", "preview", n, null, "album", ARTISTS[i % ARTISTS.size],
            // A few albums without art, to show the placeholder.
            if (i % 11 == 7) null else "$base/art/album-$i.png",
            null, favorite = i % 4 == 1, year = 1998 + (i * 7) % 27,
            genres = listOf(GENRES[(i * 5) % GENRES.size]),
        )
    }
    val playlists: List<MaItem> = listOf("Sunday Morning", "Late Night Drive", "Kitchen Dancing", "Deep Focus", "Dinner Party").mapIndexed { i, n ->
        MaItem("pl$i", "preview", n, null, "playlist", "${12 + i * 5} songs", null, null)
    }

    fun tracksOf(album: MaItem): List<MaItem> {
        val i = album.itemId.removePrefix("al").toInt()
        val n = 9 + i % 5
        return (0 until n).map { k ->
            MaItem(
                "${album.itemId}-t$k", "preview", TRACK_WORDS[(i + k * 3) % TRACK_WORDS.size], null, "track",
                album.subtitle, album.image, 150 + (i * 37 + k * 53) % 240,
                trackNumber = k + 1, discNumber = if (i == 4 && k >= n / 2) 2 else 1, album = album.name,
                parentId = album.itemId,
            )
        }
    }

    private val allTracks by lazy { albums.flatMap { tracksOf(it) } }

    override suspend fun probe(): SourceError? = null
    override suspend fun artists() = artists
    override suspend fun albums(offset: Int, limit: Int) = albums.drop(offset).take(limit)
    override suspend fun playlists() = playlists
    override suspend fun artistDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val a = artists.first { it.itemId == id }
        return a to albums.filter { it.subtitle == a.name }
    }
    override suspend fun albumDetail(id: String): Pair<MaItem?, List<MaItem>> {
        val a = albums.first { it.itemId == id }
        return a to tracksOf(a)
    }
    override suspend fun playlistTracks(id: String) = allTracks.shuffled(Random(id.hashCode())).take(14)
    override suspend fun children(item: MaItem) = emptyList<MaItem>()
    override suspend fun tracksUnder(item: MaItem) = allTracks.filter { it.subtitle == item.name }
    override suspend fun song(id: String) = allTracks.firstOrNull { it.itemId == id }
    override suspend fun recentlyAdded(limit: Int) = albums.reversed().take(limit)
    override suspend fun recentlyPlayed(limit: Int) = albums.drop(4).take(limit)
    override suspend fun mostPlayed(limit: Int) = albums.drop(10).take(limit)
    override suspend fun continueListening(limit: Int) = allTracks.drop(40).take(6)
    override suspend fun randomAlbums(limit: Int) = albums.shuffled(Random(7)).take(limit)
    override suspend fun tracks(offset: Int, limit: Int) = allTracks.drop(offset).take(limit)
    override suspend fun genres() = GENRES.map { MaItem(it, "preview", it, null, "genre", null, null, null) }
    override suspend fun topSongs(artistName: String, count: Int) = allTracks.filter { it.subtitle == artistName }.take(count)
    override suspend fun favorites() = MaSearchResults(
        artists = artists.filter { it.favorite },
        albums = albums.filter { it.favorite },
        tracks = allTracks.filterIndexed { i, _ -> i % 23 == 0 }.take(6),
        playlists = playlists.take(3),
    )
    override suspend fun search(query: String, limit: Int): MaSearchResults {
        val q = query.lowercase()
        return MaSearchResults(
            artists = artists.filter { q in it.name.lowercase() },
            albums = albums.filter { q in it.name.lowercase() || q in (it.subtitle ?: "").lowercase() },
            tracks = allTracks.filter { q in it.name.lowercase() }.take(6),
            playlists = emptyList(),
        )
    }
    override fun downloadUrl(id: String) = ""
    override fun streamUrl(id: String, format: String) = ""
    override fun coverUrl(id: String?, size: Int): String? = null
    override var streamFormat: String = "raw"
}

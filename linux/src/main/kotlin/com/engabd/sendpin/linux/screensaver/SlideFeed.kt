package com.engabd.sendpin.linux.screensaver

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.linux.JellyfinPaths
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.paletteOfImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * What the album list doesn't carry: genres (for the lettering), and the art Moonfin
 * builds its slides from — a logo to set the title with, and a wide backdrop for the
 * full-bleed style. Albums rarely have either of their own, so the artist's are used:
 * Jellyfin puts them on every album as `ParentLogoItemId` / `ParentBackdropItemId`.
 */
data class SlideMeta(
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    /** The album's own logo — replaces the set title. */
    val logoUrl: String? = null,
    /** The artist's logo — replaces the artist line. */
    val artistLogoUrl: String? = null,
    /** The album's or artist's backdrop — the full-bleed style's picture. */
    val backdropUrl: String? = null,
)

/** The linux-owned `/Items` call for [SlideMeta] — same shape and header as [JellyfinPaths]. */
object SlideMetaClient {

    fun itemsUrl(baseUrl: String, ids: List<String>): String {
        val base = baseUrl.trim().trimEnd('/')
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$base/Items?Ids=${enc(ids.joinToString(","))}&Fields=${enc("Genres,ProductionYear")}" +
            "&EnableImageTypes=${enc("Primary,Logo,Backdrop")}"
    }

    /** id → [SlideMeta] from an `/Items` body. Pure; image URLs carry the token like `coverUrl` does. */
    fun parse(body: String, baseUrl: String, token: String): Map<String, SlideMeta> {
        val base = baseUrl.trim().trimEnd('/')
        fun image(id: String?, type: String, tag: String?, width: Int): String? {
            if (id.isNullOrBlank() || tag.isNullOrBlank()) return null
            val index = if (type == "Backdrop") "/0" else ""
            return "$base/Items/$id/Images/$type$index?tag=$tag&maxWidth=$width&quality=90&api_key=$token"
        }
        val items = Json.parseToJsonElement(body).jsonObject["Items"]?.jsonArray ?: return emptyMap()
        return items.mapNotNull { el ->
            val o = el.jsonObject
            val id = o.str("Id") ?: return@mapNotNull null
            val ownBackdrop = o["BackdropImageTags"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            val parentBackdrop = o["ParentBackdropImageTags"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            id to SlideMeta(
                genres = o["Genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                year = o["ProductionYear"]?.jsonPrimitive?.intOrNull,
                logoUrl = image(id, "Logo", o["ImageTags"]?.jsonObject?.str("Logo"), LOGO_PX),
                artistLogoUrl = image(o.str("ParentLogoItemId"), "Logo", o.str("ParentLogoImageTag"), LOGO_PX),
                backdropUrl = image(id, "Backdrop", ownBackdrop, BACKDROP_PX)
                    ?: image(o.str("ParentBackdropItemId"), "Backdrop", parentBackdrop, BACKDROP_PX),
            )
        }.toMap()
    }

    suspend fun fetch(
        http: OkHttpClient,
        baseUrl: String,
        token: String,
        deviceId: String,
        ids: List<String>,
    ): Map<String, SlideMeta> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || ids.isEmpty()) return@withContext emptyMap()
        runCatching {
            ids.chunked(100).flatMap { chunk ->
                val req = Request.Builder().url(itemsUrl(baseUrl, chunk))
                    .header("Authorization", JellyfinPaths.authHeader(deviceId, token))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else parse(resp.body!!.string(), baseUrl, token).toList()
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private const val LOGO_PX = 900
    private const val BACKDROP_PX = 1920
}

/** One album, fully loaded: nothing on it is fetched or decoded while it is on screen. */
class Slide(
    val item: MaItem,
    val face: Face,
    val genres: List<String>,
    val year: Int?,
    val cover: ImageBitmap,
    /** A small, pre-blurred copy of the cover — the gallery's wash, scaled up for free. */
    val wash: ImageBitmap,
    val backdrop: ImageBitmap?,
    val logo: ImageBitmap?,
    val artistLogo: ImageBitmap?,
    val palette: AlbumPalette,
) {
    /** The words' colour and the bleed's, split so the words pop — see [albumInk]. */
    val look: AlbumInk = albumInk(palette)
}

/**
 * The preloader. Runs ahead of the screen: pulls albums from [SlideDeck], fetches their
 * [SlideMeta] a batch at a time, and fully prepares the next slides — downloaded at the
 * size the style needs, decoded, palette extracted, wash blurred — before the screen
 * asks. [next] only ever hands out a ready slide; one that fails to load is skipped.
 *
 * At most [AHEAD] slides wait ready (the channel's capacity), so memory stays bounded
 * to those, the one loading and the two on screen during a crossfade.
 */
class SlideFeed(
    private val source: MusicSource,
    /** `all` | `favourites` | `recent`. */
    private val albums: String,
    /** `gallery` | `kenburns` — decides which pictures are worth fetching. */
    private val style: String,
    private val meta: suspend (List<String>) -> Map<String, SlideMeta>,
    private val http: OkHttpClient = defaultHttp,
) {
    private val deck = SlideDeck()
    private val metaCache = HashMap<String, SlideMeta>()
    private val ready = Channel<Slide>(AHEAD)

    /** Loads until cancelled — launched while the gallery is due or showing. */
    suspend fun run() {
        var failures = 0
        while (true) {
            if (deck.needsMore()) refill()
            val item = deck.next()
            if (item == null) {
                // an empty library (or none reachable): look again later, gently
                delay(30_000)
                continue
            }
            val slide = runCatching { load(item) }.getOrNull()
            if (slide == null) {
                if (++failures >= 20) { delay(10_000); failures = 0 }
                continue
            }
            failures = 0
            ready.send(slide)
        }
    }

    suspend fun next(): Slide = ready.receive()

    private suspend fun refill() {
        val items = runCatching {
            when (albums) {
                "favourites" -> source.favorites().albums
                "recent" -> source.recentlyAdded(200)
                else -> source.randomAlbums(60)
            }
        }.getOrDefault(emptyList())
            // no favourites yet, say: the whole library rather than a blank wall
            .ifEmpty { runCatching { source.randomAlbums(60) }.getOrDefault(emptyList()) }
        deck.add(items)
        val missing = items.map { it.itemId }.filter { it !in metaCache }
        if (missing.isNotEmpty()) metaCache += meta(missing)
    }

    private suspend fun load(item: MaItem): Slide? = withContext(Dispatchers.IO) {
        val m = metaCache[item.itemId]
        val coverPx = if (style == "kenburns") 1600 else 1000
        val coverUrl = source.coverUrl(item.itemId, coverPx) ?: item.image ?: return@withContext null
        val coverImg = fetchImage(coverUrl) ?: return@withContext null
        try {
            val genres = m?.genres?.ifEmpty { null } ?: item.genres
            val year = m?.year ?: item.year
            val text = item.name + " " + (item.subtitle ?: "")
            Slide(
                item = item,
                face = faceFor(genres, year, text, item.itemId),
                genres = genres,
                year = year,
                cover = coverImg.toComposeImageBitmap(),
                wash = blurred(coverImg),
                backdrop = if (style == "kenburns") m?.backdropUrl?.let(::fetchBitmap) else null,
                logo = m?.logoUrl?.let(::fetchBitmap),
                artistLogo = m?.artistLogoUrl?.let(::fetchBitmap),
                palette = paletteOfImage(coverImg) ?: AlbumPalette(),
            )
        } finally {
            coverImg.close()
        }
    }

    private fun fetchImage(url: String): Image? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }?.let { Image.makeFromEncoded(it) }?.takeIf { it.width > 0 && it.height > 0 }
    }.getOrNull()

    private fun fetchBitmap(url: String): ImageBitmap? =
        fetchImage(url)?.let { img -> try { img.toComposeImageBitmap() } finally { img.close() } }

    companion object {
        /** Slides kept ready ahead of the screen. */
        const val AHEAD = 2
        private const val WASH_PX = 96

        private val defaultHttp by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /**
         * The cover drawn down to [WASH_PX] square through a Gaussian blur, once, on the
         * loader thread. Scaled up behind the print it reads as a soft wash of the
         * album's own light — and costs the frame nothing, unlike a live blur.
         */
        internal fun blurred(img: Image): ImageBitmap =
            Surface.makeRasterN32Premul(WASH_PX, WASH_PX).use { surface ->
                Paint().use { paint ->
                    paint.imageFilter = ImageFilter.makeBlur(7f, 7f, FilterTileMode.CLAMP)
                    surface.canvas.drawImageRect(
                        img,
                        Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                        Rect.makeWH(WASH_PX.toFloat(), WASH_PX.toFloat()),
                        paint,
                    )
                }
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
    }
}

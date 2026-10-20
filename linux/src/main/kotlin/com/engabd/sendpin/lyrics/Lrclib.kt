package com.engabd.sendpin.lyrics

import com.engabd.sendpin.data.Http
import com.engabd.sendpin.ma.MaLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

/**
 * [LRCLIB](https://lrclib.net): an open, keyless database of synced lyrics, ported from
 * the Android app.
 *
 * Asked only when Jellyfin has no timed lyrics for a track, and only while Settings →
 * Lyrics → "Find lyrics online" is on: the query is the artist, title, album and length
 * of the song, sent to a third party.
 *
 * `get` wants an exact match on all four; when it has none, a `search` by title and
 * artist is taken if one result is within [DURATION_SLACK_S] of the track's length, the
 * check that stops a live version's lyrics being timed against the studio cut.
 */
class Lrclib(
    private val http: OkHttpClient = Http.base,
    private val base: HttpUrl = "https://lrclib.net/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(title: String, artist: String?, album: String?, durationS: Int?): MaLyrics? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            exact(title, artist, album, durationS) ?: search(title, artist, durationS)
        }

    private fun exact(title: String, artist: String?, album: String?, durationS: Int?): MaLyrics? {
        if (artist.isNullOrBlank()) return null
        val url = base.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationS != null && durationS > 0) addQueryParameter("duration", durationS.toString()) }
            .build()
        val body = call(url) ?: return null
        return runCatching { lyricsOf(json.parseToJsonElement(body) as JsonObject) }.getOrNull()
    }

    private fun search(title: String, artist: String?, durationS: Int?): MaLyrics? {
        val url = base.newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", title)
            .apply { if (!artist.isNullOrBlank()) addQueryParameter("artist_name", artist) }
            .build()
        val body = call(url) ?: return null
        val results = runCatching { json.parseToJsonElement(body) as JsonArray }.getOrNull() ?: return null
        return pickSearchResult(results.mapNotNull { it as? JsonObject }, durationS)?.let(::lyricsOf)
    }

    private fun call(url: HttpUrl): String? {
        val req = Request.Builder().url(url).build()
        return runCatching {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    companion object {
        const val DURATION_SLACK_S = 3

        /** Synced first, plain second; an instrumental answers with none. */
        internal fun lyricsOf(o: JsonObject): MaLyrics? {
            if (o["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return null
            o.str("syncedLyrics")?.let { return MaLyrics(it.trim(), synced = true, source = "LRCLIB") }
            return o.str("plainLyrics")?.let { MaLyrics(it.trim(), synced = false, source = "LRCLIB") }
        }

        /** The search hit closest in length to the track, within the slack; the first one when the length is unknown. */
        internal fun pickSearchResult(candidates: List<JsonObject>, durationS: Int?): JsonObject? =
            if (durationS != null && durationS > 0) {
                candidates
                    .filter { o -> o.num("duration")?.let { abs(it - durationS) <= DURATION_SLACK_S } == true }
                    .minByOrNull { o -> abs((o.num("duration") ?: 0.0) - durationS) }
            } else {
                candidates.firstOrNull()
            }

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
    }
}

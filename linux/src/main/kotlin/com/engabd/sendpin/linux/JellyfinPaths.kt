package com.engabd.sendpin.linux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `GET /Items?Ids=<csv>&Fields=Path,MediaSources` → id → path.
 *
 * Needed because the shared [com.engabd.sendpin.jellyfin.JellyfinClient] returns
 * [com.engabd.sendpin.ma.MaItem], which carries no file path, and its `get`/`post`
 * are private — so this is the one small client the Linux panel owns. The auth
 * header is Jellyfin's `Authorization` (not Bearer — see JellyfinClient's note:
 * a plain Bearer is the most common way to get a 401 from a working server).
 */
object JellyfinPaths {

    data class Resolved(val id: String, val path: String?)

    /**
     * The Authorization header value. Exposed for tests: it must read exactly like
     * the shared client's, because the server treats them as the same device.
     */
    fun authHeader(deviceId: String, token: String): String =
        buildString {
            append("MediaBrowser Client=\"CAMusic\", Device=\"Linux\", DeviceId=\"$deviceId\", Version=\"1.0\"")
            if (token.isNotBlank()) append(", Token=\"$token\"")
        }

    fun itemsUrl(baseUrl: String, idsCsv: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val enc = java.net.URLEncoder.encode(idsCsv, "UTF-8")
        val fields = java.net.URLEncoder.encode("Path,MediaSources", "UTF-8")
        return "$base/Items?Ids=$enc&Fields=$fields"
    }

    /** Parse an /Items response body into id → Path. Pure; testable without a server. */
    fun parseItems(body: String): Map<String, String> {
        val json = Json { ignoreUnknownKeys = true }
        val items = json.parseToJsonElement(body).jsonObject["Items"]?.jsonArray ?: return emptyMap()
        return items
            .mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["Id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val path = obj["Path"]?.jsonPrimitive?.content
                    ?: obj["MediaSources"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("Path")?.jsonPrimitive?.content
                id to path
            }
            .filter { it.second != null }
            .toMap() as Map<String, String>
    }

    /**
     * Fetch paths for [ids] in one round-trip (chunked — a queue of hundreds of
     * ids still fits a GET comfortably, but the URL is bounded anyway).
     */
    suspend fun paths(
        baseUrl: String,
        token: String,
        deviceId: String,
        ids: List<String>,
        http: OkHttpClient = OkHttpClient(),
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val header = authHeader(deviceId, token)
        val out = mutableMapOf<String, String>()
        ids.chunked(100).forEach { chunk ->
            val req = Request.Builder()
                .url(itemsUrl(baseUrl, idsCsv = chunk.joinToString(",")))
                .header("Authorization", header)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext out
                val body = resp.body?.string() ?: return@withContext out
                out += parseItems(body)
            }
        }
        out
    }
}
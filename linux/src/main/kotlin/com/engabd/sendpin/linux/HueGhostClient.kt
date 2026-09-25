package com.engabd.sendpin.linux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HueGhost's REST client — the panel's small control surface over the daemon.
 *
 * Routes verified against hueghost/webapi.py (v2.6.0): `/api/on`, `/api/off`,
 * `/api/set`, `/api/status`. There is **no `/api/toggle` route** — the toggle here
 * is client-side: read `/api/status`, then `/api/on` or `/api/off` accordingly.
 * Auth is `Authorization: Bearer <token>` (the control token). The base URL is
 * configurable because HueGhost binds 127.0.0.1 on its host — reaching it from
 * the NUC needs a tunnel or proxy, whichever way the user chooses.
 */
class HueGhostClient(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient(),
) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Normalised base URL: trailing slash gone. Empty means unconfigured. */
    private val base: String get() = baseUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean = base.isNotBlank()

    private fun url(path: String) = "$base/api$path"

    private fun requestBuilder(): Request.Builder {
        val b = Request.Builder()
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    /** Turn sync on. Returns true on HTTP success. */
    suspend fun on(): Boolean = postEmpty("on")

    /** Turn sync off. Returns true on HTTP success. */
    suspend fun off(): Boolean = postEmpty("off")

    /** Client-side toggle (no server route): read state, then act. */
    suspend fun toggle(): Boolean = when (status()?.running) {
        true -> off()
        else -> on()
    }

    /** The current status, or null when unreachable/unauthorised. */
    suspend fun status(): Status? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        runCatching {
            val resp = http.newCall(
                requestBuilder().url(url("/status")).get().build(),
            ).execute()
            resp.use { if (!it.isSuccessful) return@runCatching null; parseStatus(it.body?.string() ?: "") }
        }.getOrNull()
    }

    /**
     * POST /api/set — any subset of mode / intensity / offset_s / enabled
     * (v2.8 keys; see [setBody]). Null leaves the setting alone.
     */
    suspend fun set(
        mode: String? = null,
        intensity: String? = null,
        offsetS: Double? = null,
        enabled: Boolean? = null,
    ): Boolean = post(url("/set"), setBody(mode, intensity, offsetS, enabled))

    private suspend fun postEmpty(path: String): Boolean = post(url(path), "{}")

    private suspend fun post(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        runCatching {
            val resp = http.newCall(
                requestBuilder().url(url).post(body.toRequestBody(jsonType)).build(),
            ).execute()
            resp.use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** What the Lights pane shows — HueGhost v2.8's top-level status shape. */
    data class Status(
        val running: Boolean,
        val mode: String?,
        val intensity: String?,
        /** The follow target's item, when a session is being tracked. */
        val followItem: String?,
        /** HueGhost's music-sync offset, in seconds. */
        val offsetS: Double?,
        val version: String?,
    )

    companion object {
        /**
         * The exact /set body for a subset of fields — pure, so the wire shape is
         * testable. v2.8 keys: mode, intensity, offset_s. (`brightness` does not
         * exist — official Hue Sync exposes none; `offset_delta` was v2.6's name,
         * now `offset_s`.)
         */
        internal fun setBody(
            mode: String? = null,
            intensity: String? = null,
            offsetS: Double? = null,
            enabled: Boolean? = null,
        ): String = buildString {
            append("{")
            var first = true
            fun field(json: String) {
                if (!first) append(",")
                first = false
                append(json)
            }
            mode?.let { field("\"mode\":\"$it\"") }
            intensity?.let { field("\"intensity\":\"$it\"") }
            offsetS?.let { field("\"offset_s\":$it") }
            enabled?.let { field("\"enabled\":$it") }
            append("}")
        }

        /**
         * Parse /api/status — HueGhost v2.8's top-level shape: `state`, live
         * `mode`/`intensity` (engine-reported, falling back to config defaults),
         * `offset_s`, `follow.item`. Pure so the Lights pane's read model is
         * testable without a daemon; unknown keys are ignored.
         */
        fun parseStatus(body: String): Status? {
            if (body.isBlank()) return null
            return runCatching {
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body).jsonObject
                fun s(key: String): String? =
                    root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                Status(
                    running = s("state") == "syncing",
                    mode = s("mode"),
                    intensity = s("intensity"),
                    followItem = root["follow"]?.jsonObject?.get("item")
                        ?.jsonPrimitive?.contentOrNull,
                    offsetS = root["offset_s"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    version = s("version"),
                )
            }.getOrNull()
        }
    }
}
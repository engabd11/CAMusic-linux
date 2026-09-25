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
     * POST /api/set — any subset of mode / intensity / brightness / offset_delta /
     * binding. [mode], [intensity] null = leave alone.
     */
    suspend fun set(
        mode: String? = null,
        intensity: String? = null,
        brightness: Int? = null,
        offsetDelta: Double? = null,
        bindingEnabled: Pair<String, Boolean>? = null,
    ): Boolean = post(url("/set"), setBody(mode, intensity, brightness, offsetDelta, bindingEnabled))

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

    /** What the Lights pane shows. `running` reads HueGhost's engine state. */
    data class Status(
        val running: Boolean,
        val mode: String?,
        val intensity: String?,
        val brightness: Int?,
        val version: String?,
    )

    companion object {
        /** The exact /set body for a subset of fields — pure, so the wire shape is testable. */
        internal fun setBody(
            mode: String? = null,
            intensity: String? = null,
            brightness: Int? = null,
            offsetDelta: Double? = null,
            bindingEnabled: Pair<String, Boolean>? = null,
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
            brightness?.let { field("\"brightness\":$it") }
            offsetDelta?.let { field("\"offset_delta\":$it") }
            bindingEnabled?.let { (key, enabled) ->
                field("\"binding\":{\"key\":\"$key\",\"enabled\":$enabled}")
            }
            append("}")
        }

        /**
         * Parse /api/status. Pure so the Lights pane's read model is testable
         * without a daemon. HueGhost's `engine.state().as_dict()` is nested under
         * `engine`; missing keys fall back rather than throw.
         */
        fun parseStatus(body: String): Status? {
            if (body.isBlank()) return null
            return runCatching {
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body).jsonObject
                val engine = root["engine"]?.jsonObject ?: root
                fun s(key: String): String? =
                    engine[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                Status(
                    running = engine["state"]?.jsonPrimitive?.contentOrNull == "syncing",
                    mode = s("mode"),
                    intensity = s("intensity"),
                    brightness = engine["bri"]?.jsonPrimitive?.intOrNull,
                    version = root["version"]?.jsonPrimitive?.contentOrNull,
                )
            }.getOrNull()
        }
    }
}
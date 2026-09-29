package com.engabd.sendpin.linux.tools

import com.engabd.sendpin.linux.LinuxSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import kotlin.math.abs

/**
 * How good are the panel's reports, seen the way HueGhost sees them?
 *
 * Reads `/Sessions` every 100 ms, follows this panel's session (its DeviceId, from the
 * panel's own settings file), and for each new report while playing takes
 * `position − LastPlaybackCheckIn`: the timeline's intercept. Were every report exact,
 * that would never move. Its spread is the noise HueGhost measures — under 30 ms
 * (`SHARP_NOISE_S`) it steers by the reports in full; above, only through a slow median.
 *
 *   ./gradlew :linux:runSyncProbe       (on the NUC, while music plays; Ctrl-C to stop)
 *
 * JF_URL / JF_TOKEN / JF_DEVICE_ID override the settings file.
 */
fun main() = runBlocking {
    val settings = LinuxSettings(LinuxSettings.defaultPath())
    val url = (System.getenv("JF_URL") ?: settings.jfUrl.first()).trimEnd('/')
    val token = System.getenv("JF_TOKEN") ?: settings.jfToken.first()
    val deviceId = System.getenv("JF_DEVICE_ID") ?: settings.deviceId.first()
    if (url.isBlank() || token.isBlank()) {
        println("No Jellyfin URL/token — configure the panel or set JF_URL and JF_TOKEN.")
        return@runBlocking
    }
    println("Following DeviceId $deviceId on $url — play something on the panel.")

    val http = OkHttpClient()
    val json = Json { ignoreUnknownKeys = true }
    var lastKey: Pair<Long, String>? = null
    var lastItem: String? = null
    val intercepts = ArrayDeque<Double>()

    while (true) {
        val sessions = runCatching {
            http.newCall(
                Request.Builder().url("$url/Sessions")
                    .header("Authorization", "MediaBrowser Token=\"$token\"")
                    .build(),
            ).execute().use { json.parseToJsonElement(it.body!!.string()).jsonArray }
        }.getOrElse {
            println("  /Sessions failed: ${it.message}")
            delay(1_000)
            continue
        }
        val s = sessions.map { it.jsonObject }.firstOrNull { it.str("DeviceId") == deviceId }
        val item = s?.get("NowPlayingItem")?.jsonObject
        val play = s?.get("PlayState")?.jsonObject
        val ticks = play?.get("PositionTicks")?.jsonPrimitive?.longOrNull
        val checkIn = s?.str("LastPlaybackCheckIn")
        if (item != null && ticks != null && checkIn != null) {
            val key = ticks to checkIn
            val itemId = item.str("Id")
            val paused = play["IsPaused"]?.jsonPrimitive?.booleanOrNull == true
            if (itemId != lastItem) {
                println("▶ ${item.str("Name")}")
                intercepts.clear()
                lastItem = itemId
            }
            if (key != lastKey) {
                lastKey = key
                val posS = ticks / 10_000_000.0
                val atS = Instant.parse(checkIn).toEpochMilli() / 1000.0
                if (paused) {
                    println(String.format("  pos %8.3f s  paused", posS))
                    intercepts.clear()
                } else {
                    val intercept = posS - atS
                    val prev = intercepts.lastOrNull()
                    // A jump of more than a second is a seek: start a new timeline.
                    if (prev != null && abs(intercept - prev) > 1.0) intercepts.clear()
                    intercepts.addLast(intercept)
                    while (intercepts.size > 30) intercepts.removeFirst()
                    val med = intercepts.sorted()[intercepts.size / 2]
                    val dev = (intercept - med) * 1000
                    val mad = intercepts.map { abs(it - med) }.sorted()[intercepts.size / 2] * 1000
                    println(String.format("  pos %8.3f s  residual %+6.1f ms  noise(MAD) %5.1f ms  n=%d", posS, dev, mad, intercepts.size))
                }
            }
        }
        delay(100)
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

package com.engabd.sendpin.linux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The desktop settings store: `~/.config/camusic/settings.json`, same shape as
 * :app's `AppSettings` — one [Flow] per key plus a `suspend fun setX` per key —
 * but ~30 keys, not 2527 lines, and no DataStore.
 *
 * The one contract :app's `pref {}` helper enforces and this store must keep:
 * a flow emits only when *its own* key changes (a `MutableStateFlow` mapped with
 * `distinctUntilChanged`), because collectors here do real work on emission —
 * rebuilding the Jellyfin client, reconnecting MPD, re-pointing HueGhost. A
 * re-emitted unchanged value would be as harmful as it was on Android.
 *
 * Writes update memory first and then rewrite the whole file atomically
 * (temp file + move), so a crash mid-write leaves the previous config intact.
 */
class LinuxSettings(private val path: Path) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val state = MutableStateFlow(load())

    private fun load(): JsonObject = runCatching {
        json.parseToJsonElement(Files.readString(path)).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    /** Read a string key with [default]; absent or wrong-typed falls back. */
    private fun str(key: String, default: String): Flow<String> =
        state.map { it[key]?.jsonPrimitive?.contentOrNull ?: default }.distinctUntilChanged()

    private fun bool(key: String, default: Boolean): Flow<Boolean> =
        state.map { it[key]?.jsonPrimitive?.booleanOrNull ?: default }.distinctUntilChanged()

    private fun int(key: String, default: Int): Flow<Int> =
        state.map { it[key]?.jsonPrimitive?.intOrNull ?: default }.distinctUntilChanged()

    private fun dbl(key: String, default: Double): Flow<Double> =
        state.map { it[key]?.jsonPrimitive?.doubleOrNull ?: default }.distinctUntilChanged()

    /** Update memory, then atomically rewrite the file. */
    private suspend fun set(key: String, value: JsonPrimitive) {
        val next = buildJsonObject {
            state.value.forEach { (k, v) -> put(k, v) }
            put(key, value)
        }
        state.value = next
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(JsonObject.serializer(), next))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // ── MPD (the transport) ───────────────────────────────────────────────

    /** `host:port` of MPD. Default is localhost — MPD lives on this NUC. */
    val mpdAddress: Flow<String> = str("mpd_address", "127.0.0.1:6600")
    val mpdPassword: Flow<String> = str("mpd_password", "")
    /** `track` | `album` | `off`, handed to MPD's `replay_gain_mode`. */
    val replayGainMode: Flow<String> = str("replay_gain_mode", "album")

    suspend fun setMpdAddress(value: String) = set("mpd_address", JsonPrimitive(value))
    suspend fun setMpdPassword(value: String) = set("mpd_password", JsonPrimitive(value))
    suspend fun setReplayGainMode(value: String) = set("replay_gain_mode", JsonPrimitive(value))

    // ── Jellyfin (the library) ────────────────────────────────────────────

    val jfUrl: Flow<String> = str("jf_url", "")
    val jfToken: Flow<String> = str("jf_token", "")
    val jfUserId: Flow<String> = str("jf_user_id", "")
    val jfLibraryId: Flow<String> = str("jf_library_id", "")
    /** Reported to Jellyfin as the session device — what HueGhost pins a source on. */
    val deviceId: Flow<String> = str("device_id", "camusic-linux")

    suspend fun setJfUrl(value: String) = set("jf_url", JsonPrimitive(value))
    suspend fun setJfToken(value: String) = set("jf_token", JsonPrimitive(value))
    suspend fun setJfUserId(value: String) = set("jf_user_id", JsonPrimitive(value))
    suspend fun setJfLibraryId(value: String) = set("jf_library_id", JsonPrimitive(value))
    suspend fun setDeviceId(value: String) = set("device_id", JsonPrimitive(value))

    // ── Jellyfin path → MPD path mapping ──────────────────────────────────

    /**
     * The leading prefix to strip from a Jellyfin item's absolute file path to get
     * MPD's relative path — e.g. Jellyfin sees `/mnt/music/Artist/Album/x.flac` and
     * MPD's library root is the same directory, so the prefix is `/mnt/music/`.
     */
    val jellyfinPathPrefix: Flow<String> = str("jellyfin_path_prefix", "")
    suspend fun setJellyfinPathPrefix(value: String) = set("jellyfin_path_prefix", JsonPrimitive(value))

    // ── HueGhost (the lights) ─────────────────────────────────────────────

    val hueGhostUrl: Flow<String> = str("hueghost_url", "")
    val hueGhostToken: Flow<String> = str("hueghost_token", "")
    /** Per-source enable — POST /api/set {"binding": {"key": ..., "enabled": ...}}. */
    val hueGhostSourceEnabled: Flow<Boolean> = bool("hueghost_source_enabled", true)
    /** `video` | `music` | `games` | `scenes`. */
    val hueGhostMode: Flow<String> = str("hueghost_mode", "music")
    /** `subtle` | `moderate` | `high` | `extreme`. */
    val hueGhostIntensity: Flow<String> = str("hueghost_intensity", "high")
    /** 0..100 session brightness. */
    val hueGhostBrightness: Flow<Int> = int("hueghost_brightness", 50)
    /** Sync offset delta in seconds (HueGhost's `offset_delta`). */
    val hueGhostOffsetDelta: Flow<Double> = dbl("hueghost_offset_delta", 0.0)

    suspend fun setHueGhostUrl(value: String) = set("hueghost_url", JsonPrimitive(value))
    suspend fun setHueGhostToken(value: String) = set("hueghost_token", JsonPrimitive(value))
    suspend fun setHueGhostSourceEnabled(value: Boolean) = set("hueghost_source_enabled", JsonPrimitive(value))
    suspend fun setHueGhostMode(value: String) = set("hueghost_mode", JsonPrimitive(value))
    suspend fun setHueGhostIntensity(value: String) = set("hueghost_intensity", JsonPrimitive(value))
    suspend fun setHueGhostBrightness(value: Int) = set("hueghost_brightness", JsonPrimitive(value))
    suspend fun setHueGhostOffsetDelta(value: Double) = set("hueghost_offset_delta", JsonPrimitive(value))

    // ── Panel (this screen) ───────────────────────────────────────────────

    /** `landscape` | `portrait` — chosen manually in Settings, never auto-detected. */
    val layoutMode: Flow<String> = str("layout_mode", "landscape")
    /** `album` | `fixed` — the forked theme drops DYNAMIC (no wallpaper on desktop). */
    val accentChoice: Flow<String> = str("accent_choice", "album")
    /** The fixed accent, as ARGB int, when accentChoice is `fixed`. */
    val accentFixed: Flow<Int> = int("accent_fixed", 0xFF8AB4F8.toInt())
    /** Whether the quality badge shows on the player pane. */
    val showQualityBadge: Flow<Boolean> = bool("show_quality_badge", true)

    suspend fun setLayoutMode(value: String) = set("layout_mode", JsonPrimitive(value))
    suspend fun setAccentChoice(value: String) = set("accent_choice", JsonPrimitive(value))
    suspend fun setAccentFixed(value: Int) = set("accent_fixed", JsonPrimitive(value))
    suspend fun setShowQualityBadge(value: Boolean) = set("show_quality_badge", JsonPrimitive(value))

    // ── Session reporting (the HueGhost hook) ─────────────────────────────

    /** Master switch for reporting to Jellyfin while MPD plays. */
    val reportToJellyfin: Flow<Boolean> = bool("report_to_jellyfin", true)
    suspend fun setReportToJellyfin(value: Boolean) = set("report_to_jellyfin", JsonPrimitive(value))

    companion object {
        /** The default settings path on this machine. */
        fun defaultPath(): Path {
            val home = System.getProperty("user.home")
            val xdg = System.getenv("XDG_CONFIG_HOME")
            return Path.of(xdg ?: "$home/.config", "camusic", "settings.json")
        }
    }
}
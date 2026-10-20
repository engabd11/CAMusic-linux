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
    /** `oled` | `dark` | `light` | `system` — the forked ThemeChoice key. */
    val themeChoice: Flow<String> = str("theme_choice", "dark")
    /** Whether glass surfaces blur the backdrop (Backdrop.kt's blur flag). */
    val glassBlur: Flow<Boolean> = bool("glass_blur", true)

    suspend fun setThemeChoice(value: String) = set("theme_choice", JsonPrimitive(value))
    suspend fun setGlassBlur(value: Boolean) = set("glass_blur", JsonPrimitive(value))

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

    // ── Screensaver (the album-art gallery when idle and not playing) ────

    val screensaverEnabled: Flow<Boolean> = bool("screensaver_enabled", true)
    /** Minutes untouched, with nothing playing, before the gallery begins. */
    val screensaverDelayMin: Flow<Int> = int("screensaver_delay_min", 10)
    /** Typed seconds that replace [screensaverDelayMin] while above 0 (Settings → Custom). */
    val screensaverDelayS: Flow<Int> = int("screensaver_delay_s", 0)
    /**
     * `gallery` (the cover as a lit print on its blurred wash) | `bloom` (the same print on
     * the album's colours bleeding across the screen) | `kenburns` (full-bleed, slow pan and zoom).
     */
    val screensaverStyle: Flow<String> = str("screensaver_style", "gallery")
    /** Seconds each album holds the screen. */
    val screensaverSlideS: Flow<Int> = int("screensaver_slide_s", 20)
    /** `all` | `favourites` | `recent` — which albums rotate. */
    val screensaverAlbums: Flow<String> = str("screensaver_albums", "all")
    val screensaverClock: Flow<Boolean> = bool("screensaver_clock", true)
    /** The year · genre line under the artist. */
    val screensaverDetails: Flow<Boolean> = bool("screensaver_details", true)
    /** Hold a slide to play that album (a tap only ever wakes the panel). */
    val screensaverHoldToPlay: Flow<Boolean> = bool("screensaver_hold_to_play", true)
    /** Drift the text and clock a few pixels over time — the panel never turns off. */
    val screensaverDrift: Flow<Boolean> = bool("screensaver_drift", true)
    /** Set the words (and clock) in the showing record's colours, as the player does. */
    val screensaverAlbumText: Flow<Boolean> = bool("screensaver_album_text", false)

    suspend fun setScreensaverEnabled(value: Boolean) = set("screensaver_enabled", JsonPrimitive(value))
    suspend fun setScreensaverDelayMin(value: Int) = set("screensaver_delay_min", JsonPrimitive(value))
    suspend fun setScreensaverDelayS(value: Int) = set("screensaver_delay_s", JsonPrimitive(value))
    suspend fun setScreensaverStyle(value: String) = set("screensaver_style", JsonPrimitive(value))
    suspend fun setScreensaverSlideS(value: Int) = set("screensaver_slide_s", JsonPrimitive(value))
    suspend fun setScreensaverAlbums(value: String) = set("screensaver_albums", JsonPrimitive(value))
    suspend fun setScreensaverClock(value: Boolean) = set("screensaver_clock", JsonPrimitive(value))
    suspend fun setScreensaverDetails(value: Boolean) = set("screensaver_details", JsonPrimitive(value))
    suspend fun setScreensaverHoldToPlay(value: Boolean) = set("screensaver_hold_to_play", JsonPrimitive(value))
    suspend fun setScreensaverDrift(value: Boolean) = set("screensaver_drift", JsonPrimitive(value))
    /** `small` | `regular` | `large` — the words and clock, scaled together. */
    val screensaverTextSize: Flow<String> = str("screensaver_text_size", "regular")
    /** `left` | `centre` | `right` — where along the top the clock sits. */
    val screensaverClockPosition: Flow<String> = str("screensaver_clock_position", "right")
    suspend fun setScreensaverTextSize(value: String) = set("screensaver_text_size", JsonPrimitive(value))
    suspend fun setScreensaverClockPosition(value: String) = set("screensaver_clock_position", JsonPrimitive(value))
    suspend fun setScreensaverAlbumText(value: Boolean) = set("screensaver_album_text", JsonPrimitive(value))

    // ── Now playing & motion (looks only — playback never reads these) ────

    /** Hold one cover for the whole album, so a skip inside it never reloads the art. */
    val artHoldAlbum: Flow<Boolean> = bool("art_hold_album", true)
    /** `flip` | `dissolve` | `slide` | `off` — how the cover changes when a new album starts. */
    val albumTransition: Flow<String> = str("album_transition", "flip")
    /** `off` | `subtle` | `vivid` — the album's colours flowing like a liquid behind the player, a little into each track. */
    val livingArt: Flow<String> = str("living_art", "subtle")
    /** Seconds into a track before the colours start to flow. */
    val livingArtDelayS: Flow<Int> = int("living_art_delay_s", 10)
    /** Title and artist glide over on a track change instead of jumping. */
    val trackTextMotion: Flow<Boolean> = bool("track_text_motion", true)
    /** The album's colour blooms drift slowly behind the player. */
    val ambientMotion: Flow<Boolean> = bool("ambient_motion", true)
    /** The cover settles back and dims a little while paused. */
    val coverRestsOnPause: Flow<Boolean> = bool("cover_rests_on_pause", true)

    suspend fun setArtHoldAlbum(value: Boolean) = set("art_hold_album", JsonPrimitive(value))
    suspend fun setAlbumTransition(value: String) = set("album_transition", JsonPrimitive(value))
    suspend fun setLivingArt(value: String) = set("living_art", JsonPrimitive(value))
    suspend fun setLivingArtDelayS(value: Int) = set("living_art_delay_s", JsonPrimitive(value))
    suspend fun setTrackTextMotion(value: Boolean) = set("track_text_motion", JsonPrimitive(value))
    suspend fun setAmbientMotion(value: Boolean) = set("ambient_motion", JsonPrimitive(value))
    suspend fun setCoverRestsOnPause(value: Boolean) = set("cover_rests_on_pause", JsonPrimitive(value))

    /**
     * `marble` | `aurora` | `nebula` | `lava` | `silk` — the shape the living colours
     * flow in (LiquidBleed.kt). Only drawn while [livingArt] is on.
     */
    val livingStyle: Flow<String> = str("living_style", "marble")
    suspend fun setLivingStyle(value: String) = set("living_style", JsonPrimitive(value))

    // ── Colour & finish (the grade over every album, and the glass in front) ──

    /** `natural` | `warm` | `cool` | `mono` | `neon` — see ui/design/ColourMood.kt. */
    val colourMood: Flow<String> = str("colour_mood", "natural")
    /** `off` | `soft` | `deep` — the screen's edges darkened, as a print is framed. */
    val vignette: Flow<String> = str("vignette", "off")
    /** `off` | `fine` | `rich` — a film grain over everything; it also hides LCD banding. */
    val filmGrain: Flow<String> = str("film_grain", "off")
    /** `off` | `scheduled` | `always` — the panel warmed and dimmed for the night. */
    val nightMode: Flow<String> = str("night_mode", "off")
    /** Hours (0..23) the scheduled night begins and ends. */
    val nightStartH: Flow<Int> = int("night_start_h", 22)
    val nightEndH: Flow<Int> = int("night_end_h", 7)
    /** `gentle` | `deep` — how far the night dims. */
    val nightDepth: Flow<String> = str("night_depth", "gentle")

    suspend fun setColourMood(value: String) = set("colour_mood", JsonPrimitive(value))
    suspend fun setVignette(value: String) = set("vignette", JsonPrimitive(value))
    suspend fun setFilmGrain(value: String) = set("film_grain", JsonPrimitive(value))
    suspend fun setNightMode(value: String) = set("night_mode", JsonPrimitive(value))
    suspend fun setNightStartH(value: Int) = set("night_start_h", JsonPrimitive(value))
    suspend fun setNightEndH(value: Int) = set("night_end_h", JsonPrimitive(value))
    suspend fun setNightDepth(value: String) = set("night_depth", JsonPrimitive(value))

    // ── Listening mode (the ambient view while music plays and nobody touches) ──

    val listeningMode: Flow<Boolean> = bool("listening_mode", true)
    /** Minutes untouched, while playing, before the listening view takes the screen. */
    val listeningDelayMin: Flow<Int> = int("listening_delay_min", 3)
    /** Typed seconds that replace [listeningDelayMin] while above 0 (Settings → Custom). */
    val listeningDelayS: Flow<Int> = int("listening_delay_s", 0)
    val listeningClock: Flow<Boolean> = bool("listening_clock", true)
    /** `classic` | `vinyl` | `poster` | `cover` — how the record is laid out. */
    val listeningLayout: Flow<String> = str("listening_layout", "classic")
    suspend fun setListeningLayout(value: String) = set("listening_layout", JsonPrimitive(value))

    suspend fun setListeningMode(value: Boolean) = set("listening_mode", JsonPrimitive(value))
    suspend fun setListeningDelayMin(value: Int) = set("listening_delay_min", JsonPrimitive(value))
    suspend fun setListeningDelayS(value: Int) = set("listening_delay_s", JsonPrimitive(value))
    suspend fun setListeningClock(value: Boolean) = set("listening_clock", JsonPrimitive(value))

    // ── The hi-fi station (v1.2) ──────────────────────────────────────────
    //
    // Everything here is an addition, and every addition is off until it is switched on:
    // an upgrade changes nothing about how the panel looks or behaves by itself. The
    // defaults below are the v1.1 behaviour.

    /** A tonearm on the Vinyl layout: drops at play, lifts at pause, follows the groove. */
    val tonearm: Flow<Boolean> = bool("tonearm", false)
    /** The tonearm can be taken by the finger and set down on another track. */
    val tonearmSeek: Flow<Boolean> = bool("tonearm_seek", false)
    /** The next records lean against the platter in the Vinyl layout; drag to reorder. */
    val queueDeck: Flow<Boolean> = bool("queue_deck", false)
    /** Hold the cover in the listening view to save a postcard to ~/Pictures. */
    val postcards: Flow<Boolean> = bool("postcards", false)
    /** Hold the cover in the player to turn it over to the back of the sleeve. */
    val sleeveFlip: Flow<Boolean> = bool("sleeve_flip", false)
    /** Swipe the cover to skip, swipe a queue row away, drag a queue row to reorder it. */
    val touchGestures: Flow<Boolean> = bool("touch_gestures", false)
    /** Hold a song or a queue row for Play next, Add to queue and the like. */
    val rowMenus: Flow<Boolean> = bool("row_menus", false)
    /** Crates: the library as records in bins, beside the usual grid. */
    val crateMode: Flow<Boolean> = bool("crate_mode", false)
    /** The signal path as stages — source, decoder, loudness, output — with a bit-perfect lamp. */
    val signalPanel: Flow<Boolean> = bool("signal_panel", false)
    /** A sleep timer chip by the loudness chip: end of album, or after a set time. */
    val sleepTimer: Flow<Boolean> = bool("sleep_timer", false)
    /** Larger times and clock, for reading from across the room. */
    val bigReadouts: Flow<Boolean> = bool("big_readouts", false)
    /** A calm banner with a Retry when MPD or Jellyfin cannot be reached. */
    val statusBanner: Flow<Boolean> = bool("status_banner", false)
    /**
     * `off` | `clock` | `screen_off` — after [deepIdleMin] of nobody touching, the panel goes
     * to a dim clock alone, or switches the screen off (a touch wakes it).
     */
    val deepIdle: Flow<String> = str("deep_idle", "off")
    val deepIdleMin: Flow<Int> = int("deep_idle_min", 60)

    suspend fun setTonearm(value: Boolean) = set("tonearm", JsonPrimitive(value))
    suspend fun setTonearmSeek(value: Boolean) = set("tonearm_seek", JsonPrimitive(value))
    suspend fun setQueueDeck(value: Boolean) = set("queue_deck", JsonPrimitive(value))
    suspend fun setPostcards(value: Boolean) = set("postcards", JsonPrimitive(value))
    suspend fun setSleeveFlip(value: Boolean) = set("sleeve_flip", JsonPrimitive(value))
    suspend fun setTouchGestures(value: Boolean) = set("touch_gestures", JsonPrimitive(value))
    suspend fun setRowMenus(value: Boolean) = set("row_menus", JsonPrimitive(value))
    suspend fun setCrateMode(value: Boolean) = set("crate_mode", JsonPrimitive(value))
    suspend fun setSignalPanel(value: Boolean) = set("signal_panel", JsonPrimitive(value))
    suspend fun setSleepTimer(value: Boolean) = set("sleep_timer", JsonPrimitive(value))
    suspend fun setBigReadouts(value: Boolean) = set("big_readouts", JsonPrimitive(value))
    suspend fun setStatusBanner(value: Boolean) = set("status_banner", JsonPrimitive(value))
    suspend fun setDeepIdle(value: String) = set("deep_idle", JsonPrimitive(value))
    suspend fun setDeepIdleMin(value: Int) = set("deep_idle_min", JsonPrimitive(value))

    // ── Library navigation ────────────────────────────────────────────────

    /** Tapping Library while already on it goes back to the library's home page. */
    val libraryRetapHome: Flow<Boolean> = bool("library_retap_home", true)
    /** Library pages slide and tabs cross-fade instead of cutting. */
    val pageTransitions: Flow<Boolean> = bool("page_transitions", true)

    suspend fun setLibraryRetapHome(value: Boolean) = set("library_retap_home", JsonPrimitive(value))
    suspend fun setPageTransitions(value: Boolean) = set("page_transitions", JsonPrimitive(value))

    // ── The gallery (v1.3) ────────────────────────────────────────────────
    //
    // The look and motion of a station that is mostly watched: on by default, each one
    // still a switch in Settings → Display & motion.

    /** The line being sung fills word by word, like karaoke. */
    val karaokeFill: Flow<Boolean> = bool("karaoke_fill", true)
    /** Ask LRCLIB (lrclib.net) for lyrics when Jellyfin has no timed ones. */
    val lyricsOnline: Flow<Boolean> = bool("lyrics_online", true)
    /** The words of the song under the record in every listening layout, not only the lyric sheet. */
    val listeningLyrics: Flow<Boolean> = bool("listening_lyrics", true)
    /** A full-width carousel of covers at the top of the library home. */
    val homeHero: Flow<Boolean> = bool("home_hero", true)
    /** `grid` | `flow` — how the Albums page lays its covers out. */
    val albumsView: Flow<String> = str("albums_view", "grid")
    /** The cover a finger taps grows into the album page instead of the page sliding in. */
    val sharedCoverMotion: Flow<Boolean> = bool("shared_cover_motion", true)

    suspend fun setKaraokeFill(value: Boolean) = set("karaoke_fill", JsonPrimitive(value))
    suspend fun setLyricsOnline(value: Boolean) = set("lyrics_online", JsonPrimitive(value))
    suspend fun setListeningLyrics(value: Boolean) = set("listening_lyrics", JsonPrimitive(value))
    suspend fun setHomeHero(value: Boolean) = set("home_hero", JsonPrimitive(value))
    suspend fun setAlbumsView(value: String) = set("albums_view", JsonPrimitive(value))
    suspend fun setSharedCoverMotion(value: Boolean) = set("shared_cover_motion", JsonPrimitive(value))

    companion object {
        /** The default settings path on this machine. */
        fun defaultPath(): Path {
            val home = System.getProperty("user.home")
            val xdg = System.getenv("XDG_CONFIG_HOME")
            return Path.of(xdg ?: "$home/.config", "camusic", "settings.json")
        }
    }
}
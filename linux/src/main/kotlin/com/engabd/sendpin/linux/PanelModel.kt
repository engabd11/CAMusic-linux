package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteState
import com.engabd.sendpin.jellyfin.JellyfinClient
import com.engabd.sendpin.library.JellyfinSource
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.mpd.MpdClient
import com.engabd.sendpin.mpd.MpdRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The panel's one model: settings → clients, the MPD queue model, the 1 Hz poll
 * loop and the [SessionReporter] wiring. Everything the panes render reads from
 * here; everything they do goes through here.
 *
 * MPD is the transport (RemotePlayback shape): the queue held here mirrors MPD's,
 * built by [queueAlbum] from Jellyfin items with their MPD paths stamped as
 * `scrobbleId` — the identity [com.engabd.sendpin.mpd.MpdRemote] addresses tracks
 * by, while [SessionReporter] reports against the Jellyfin `id`.
 */
class PanelModel(val settings: LinuxSettings) {

    val mpdAddress = MutableStateFlow("")
    val jfUrl = MutableStateFlow("")
    val hueGhostUrl = MutableStateFlow("")
    val hueGhostToken = MutableStateFlow("")

    /** The MPD queue as this panel built it — index-aligned with MPD's own. */
    val queue = MutableStateFlow<List<LocalTrack>>(emptyList())
    /**
     * Jellyfin track id → its album id, for the tracks this panel queued. Only the look
     * reads it: the now-playing cover is held per album (`ui/NowArt.kt`), and a track's own
     * [LocalTrack] has no album id to key on. Kept across queue swaps — an id maps to the
     * same album whatever queue it is in.
     */
    val albumIds = MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * True while something covers the whole panel (the gallery or the listening view), so
     * the now-playing cover underneath can stop animating what nobody can see.
     */
    val coverObscured = MutableStateFlow(false)
    /** Latest transport reading, polled at 1 Hz while playing. */
    val state = MutableStateFlow<RemoteState?>(null)
    /** Status line for the panes: what is connected and what is not. */
    val statusLine = MutableStateFlow("")

    // Clients as StateFlows so panes recompose the moment they come up — a plain
    // var set after first composition is invisible to Compose (the Library tab
    // sat on "Configure Jellyfin" with a healthy client because of exactly that).
    //
    // Typed as the MusicSource interface rather than JellyfinSource: the panes only ever
    // browse through the interface, and `internal` lets the preview renderer
    // (src/test/.../preview) hand them a fake library without a Jellyfin server.
    internal val _source = MutableStateFlow<MusicSource?>(null)
    val source: StateFlow<MusicSource?> = _source
    internal val _hueGhost = MutableStateFlow<HueGhostClient?>(null)
    val hueGhost: StateFlow<HueGhostClient?> = _hueGhost

    /**
     * MPD's own `replay_gain_mode`, read back from `replay_gain_status` — not the saved
     * preference. MPD is a shared daemon: the Android app's MPD session sets the same
     * mode through its own `MpdRemote.setReplayGain`, so what the panel last asked for
     * and what MPD is applying can differ. Null until MPD has answered.
     */
    val replayGain = MutableStateFlow<String?>(null)

    private var mpd: MpdClient? = null
    private var remote: MpdRemote? = null
    private var jellyfin: JellyfinClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: kotlinx.coroutines.Job? = null
    private var idleJob: kotlinx.coroutines.Job? = null

    /**
     * Held across "read MPD, reconcile [queue]" in the poll and across "swap [queue], send
     * MPD the new queue" in [playAlbum]. Without it a poll landing between [playAlbum]'s two
     * steps reconciled the new album against MPD's *old* queue, which synthesised every
     * entry — and the album then played with no Jellyfin ids, so nothing was reported.
     */
    private val queueLock = Mutex()

    /** Wakes the poll at once: MPD's idle push, or the panel's own transport commands. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * What the poll hands the reporter. Conflated: if Jellyfin is slow, the reporter
     * skips to the newest reading (it diffs against its own last state, so a track
     * change is still seen) and the poll never waits on HTTP.
     */
    private val readings = Channel<Reading>(Channel.CONFLATED)
    private class Reading(val state: RemoteState?, val id: String?, val readAtMs: Long)

    /** Serialises the reporter between its own coroutine and [close]. */
    private val reporterLock = Mutex()

    private val reporter = SessionReporter(
        jellyfin = object : SessionReporter.Reporter {
            private val jf: JellyfinClient? get() = jellyfin
            override suspend fun start(id: String, positionMs: Long) {
                jf?.reportPlayback(id, completed = false, positionMs = positionMs)
            }

            override suspend fun progress(id: String, positionMs: Long, paused: Boolean) {
                jf?.reportProgress(id, positionMs, paused)
            }

            override suspend fun stop(id: String, positionMs: Long) {
                jf?.reportPlayback(id, completed = true, positionMs = positionMs)
            }
        },
    )

    val reportingToJellyfin: Boolean get() = reporter.reporting

    init {
        // Declared below everything it touches — a field read in `init` before its own
        // declaration is null at runtime, and only a release build shows it.
        scope.launch {
            for (r in readings) reporterLock.withLock {
                runCatching {
                    // Switched off mid-song: end the session rather than leave HueGhost
                    // following a report that has stopped coming.
                    if (settings.reportToJellyfin.first()) reporter.onState(r.state, r.id, r.readAtMs)
                    else reporter.end()
                }
            }
        }
    }

    /** Build clients from current settings and start the poll loop. */
    fun start() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val mpdAddr = settings.mpdAddress.first()
            val rg = settings.replayGainMode.first()
            val jfUrlV = settings.jfUrl.first()
            val jfTokenV = settings.jfToken.first()
            val jfUserV = settings.jfUserId.first()
            val jfLibV = settings.jfLibraryId.first()
            val hgUrlV = settings.hueGhostUrl.first()
            val hgTokV = settings.hueGhostToken.first()

            mpdAddress.value = mpdAddr
            jfUrl.value = jfUrlV
            hueGhostUrl.value = hgUrlOr(hgUrlV)
            hueGhostToken.value = hgTokV

            val client = MpdClient(mpdAddr)
            val err = client.pingResult()
            mpd = client
            remote = MpdRemote(client)
            runCatching { client.setReplayGainMode(rg) }
            replayGain.value = client.replayGainMode()
            statusLine.value = if (err == null) "MPD connected — $mpdAddr" else "MPD: ${err.message}"

            if (jfUrlV.isNotBlank() && jfTokenV.isNotBlank()) {
                jellyfin = JellyfinClient(
                    baseUrl = jfUrlV,
                    token = jfTokenV,
                    userId = jfUserV,
                    libraryId = jfLibV,
                    deviceId = settings.deviceId.first(),
                    // the same name JellyfinPaths' header gives it: the server keeps
                    // whichever came last, so the two must agree
                    deviceName = "Linux",
                )
                // The browse pane reads through this — without it the Library tab
                // sits on "Configure Jellyfin" even with a healthy client.
                _source.value = JellyfinSource(jellyfin!!)
                val jfErr = jellyfin?.pingError()
                statusLine.value = (statusLine.value + "  ·  " +
                    if (jfErr == null) "Jellyfin connected" else "Jellyfin: $jfErr")
            }

            if (hgUrlV.isNotBlank()) {
                _hueGhost.value = HueGhostClient(hgUrlV, hgTokV)
            }

            idleJob?.cancel()
            idleJob = scope.launch { MpdIdle(mpdAddr) { wake.trySend(Unit) }.run() }

            // The poll: transport truth, the queue, then a reading for the reporter.
            // Once a second, and at once whenever MPD's idle push (or a button here)
            // says something changed — the reporter's 1 s tick and HueGhost's 15 s
            // music timeout both depend on it never stalling, which is why the
            // reporter runs on its own coroutine rather than in here.
            var lastGainReadAt = 0L
            while (isActive) {
                queueLock.withLock {
                    val s = remote?.poll()
                    // Taken straight after the answer: the reporter extrapolates from it.
                    val readAt = System.currentTimeMillis()
                    state.value = s
                    // Read after the status, so a track change between the two shows up
                    // as a mismatch below rather than as the wrong song.
                    val playingFile = if (s != null && !s.stopped) {
                        runCatching { mpd?.currentSong()?.itemId }.getOrNull()
                    } else {
                        null
                    }
                    reconcileQueueFromMpd()
                    val reading = when {
                        s == null || s.stopped -> Reading(s, null, readAt)
                        else -> when (val who = identify(queue.value, s.index, playingFile)) {
                            is Identity.Known -> Reading(s, who.jellyfinId, readAt)
                            Identity.Unverified -> null // next poll, once the two agree
                        }
                    }
                    reading?.let { readings.trySend(it) }
                }
                // Another client can change MPD's loudness under us (see [replayGain]);
                // one extra command every 10 s keeps the readback honest without
                // doubling the poll's traffic.
                val now = System.currentTimeMillis()
                if (now - lastGainReadAt >= 10_000) {
                    lastGainReadAt = now
                    mpd?.replayGainMode()?.let { replayGain.value = it }
                }
                withTimeoutOrNull(1_000) { wake.receive() }
            }
        }
    }

    /**
     * Quit: close the Jellyfin session so HueGhost lets go now rather than 15 s from now.
     * Called from a JVM shutdown hook, so it covers the window closing, the Quit row's
     * `exitProcess` and systemd's SIGTERM alike. Bounded — a dead server must not hold
     * the panel open.
     */
    fun close() {
        pollJob?.cancel()
        idleJob?.cancel()
        runBlocking {
            withTimeoutOrNull(1_500) { reporterLock.withLock { runCatching { reporter.end() } } }
        }
        scope.cancel()
    }

    private fun hgUrlOr(v: String) = v

    /**
     * Re-sync `queue` to MPD's own queue, index-for-index, every poll tick — this is what
     * lets QueuePane fire a remove/move/shuffle/clear and never touch `queue.value` itself,
     * per the app's established convention of correcting the UI from the source of truth
     * rather than maintaining a second copy of it.
     *
     * Must never drop an MPD entry: `QueuePane` row *i* calls `playAt(i)`/`removeFromQueue(i)`,
     * and MPD addresses that same index into its *own* full queue (`docs/providers.md`:
     * "every command addresses a track by index, so a queue edit is sent as the same edit on
     * both sides"). A filtered/shorter list here would desync every index below the first
     * gap and silently play or delete the wrong track — the bug this replaced. See
     * [reorderQueue] for the fix (index-aligned, synthesising rather than dropping).
     */
    private suspend fun reconcileQueueFromMpd() {
        val client = mpd ?: return
        val mpdTracks = try {
            client.queueTracks()
        } catch (e: Exception) {
            return
        }
        queue.value = reorderQueue(queue.value, mpdTracks)
    }

    /** Rebuild everything from the (just-saved) settings — Settings pane's Apply. */
    fun reconnect() = start()

    /** Settings' "Preview now": show the screensaver at once; it clears this when dismissed. */
    val screensaverNow = MutableStateFlow(false)

    /** Settings' listening-view "Preview now": show it at once; it clears this when dismissed. */
    val listeningNow = MutableStateFlow(false)

    /** The screensaver's hold-to-play: the album from the start, through [playAlbum]. */
    suspend fun playAlbumById(id: String) {
        val src = source.value ?: return
        val (album, tracks) = runCatching { src.albumDetail(id) }.getOrNull() ?: return
        if (album == null || tracks.isEmpty()) return
        playAlbum(album, tracks, 0)
    }

    // ── Transport (the panes call these) ──────────────────────────────────

    // Each wakes the poll once MPD has taken it, so the screen and the Jellyfin
    // report follow at once even if the idle push is down.
    suspend fun playAt(index: Int) = transport { playAt(index) }
    suspend fun togglePause() {
        val s = state.value ?: return
        transport { if (s.playing) pause() else resume() }
    }

    suspend fun next() = transport { next() }
    suspend fun previous() = transport { previous() }
    suspend fun seekTo(ms: Long) = transport { seekTo(ms) }

    private suspend fun transport(command: suspend MpdRemote.() -> Unit) {
        remote?.command()
        wake.trySend(Unit)
    }

    /**
     * Level loudness: persist the choice, hand it to MPD (which applies the files'
     * ReplayGain tags as it decodes), then read back what MPD actually took. `off`,
     * `track` or `album` — the Android app's three modes, spelled as MPD spells them.
     */
    suspend fun setReplayGain(mode: String) {
        settings.setReplayGainMode(mode)
        val client = mpd ?: return
        runCatching { client.setReplayGainMode(mode) }
        replayGain.value = client.replayGainMode() ?: replayGain.value
    }

    // ── Queue editing (QueuePane) ──────────────────────────────────────────
    // Same fire-and-let-the-poll-reconcile shape as the transport calls above:
    // send MPD the edit and don't touch `queue.value` here at all.
    // reconcileQueueFromMpd(), called every poll tick, is what actually
    // resyncs it — see that function's doc comment for why it re-orders the
    // existing list instead of rebuilding it from MPD's answer directly.
    suspend fun removeFromQueue(index: Int) = remote?.removeAt(index) ?: Unit
    suspend fun moveInQueue(from: Int, to: Int) = remote?.move(from, to) ?: Unit
    suspend fun shuffleQueue() = remote?.shuffleQueue() ?: Unit
    suspend fun clearQueue() = remote?.clear() ?: Unit

    /**
     * Queue a Jellyfin album through MPD: resolve every item's file path, map to
     * MPD's relative path, push the queue. Items whose path will not map are
     * skipped — a queue that silently plays the wrong thing is worse than a
     * shorter one.
     */
    suspend fun playAlbum(album: MaItem, tracks: List<MaItem>, startIndex: Int = 0) {
        val jf = jellyfin ?: return
        val client = mpd ?: return
        val paths = JellyfinPaths.paths(
            baseUrl = jfUrl.value,
            token = settings.jfToken.first(),
            deviceId = settings.deviceId.first(),
            ids = tracks.map { it.itemId },
        )
        val prefix = settings.jellyfinPathPrefix.first()
        val queueTracks = tracks.mapNotNull { item ->
            val jfPath = paths[item.itemId] ?: return@mapNotNull null
            val mpdPath = JellyfinToMpd.toMpdPath(jfPath, prefix) ?: return@mapNotNull null
            JellyfinToMpd.toLocalTrack(item, mpdPath)
        }
        if (queueTracks.isEmpty()) {
            statusLine.value = "No track mapped to MPD — check the path prefix in Settings"
            return
        }
        // The tapped track's MPD position decides where playback starts, NOT the
        // tapped row's index. A track that fails to map is dropped from the queue
        // (mapNotNull above), and since MPD's queue then has no entry for it, every
        // index below the drop would name a different song than the row it came from
        // — the "tap a song, hear the next one" bug. Locating by identity keeps the
        // row → song contract even when some other track in the album fails to map.
        // The tapped track itself failing to map is a hard refusal: playing its
        // neighbour would be exactly the bug again.
        val tapped = tracks.getOrNull(startIndex)
        val tappedPath = tapped?.let { paths[it.itemId] }
            ?.let { JellyfinToMpd.toMpdPath(it, settings.jellyfinPathPrefix.first()) }
        if (tapped == null || tappedPath == null) {
            statusLine.value = "That track has no MPD-mappable path — check Settings"
            return
        }
        val at = queueTracks.indexOfFirst { it.scrobbleId == tappedPath }
        if (at < 0) {
            statusLine.value = "That track will not queue — check Settings"
            return
        }
        // Each track's album, for the cover held per album. A track names its own album
        // (Jellyfin's AlbumId, as parentId); an album header stands in where it doesn't.
        val headerAlbum = album.itemId.takeIf { album.mediaType == "album" }
        val ids = tracks.mapNotNull { t -> (t.parentId?.takeIf { it.isNotBlank() } ?: headerAlbum)?.let { t.itemId to it } }
        if (ids.isNotEmpty()) albumIds.value = albumIds.value + ids
        // Both halves under the poll's lock: see [queueLock].
        queueLock.withLock {
            queue.value = queueTracks
            remote!!.setQueue(queueTracks, at)
        }
        wake.trySend(Unit)
    }
}

/**
 * The pure half of [PanelModel.reconcileQueueFromMpd]: given the panel's own queue and
 * MPD's own queue (as [MaItem]s — [MpdClient.queueTracks]'s `itemId` is the MPD file path,
 * built fresh from MPD's own metadata every call), produce a list that is always exactly
 * `mpdTracks.size` long and index-aligned with it. `internal` so `kotlin.test` can cover it
 * without a running MPD.
 *
 * Index alignment is non-negotiable (see [PanelModel.reconcileQueueFromMpd]'s doc comment),
 * so an MPD entry with no match in `current` is never dropped — a track MPD is playing that
 * this panel didn't queue itself (another client's `mpc add`, ncmpcpp, moOde's web UI, or a
 * queue MPD already had running before this panel connected — `docs/providers.md`: "MPD
 * does not stop because a phone was closed") gets a [LocalTrack] synthesised from MPD's own
 * metadata instead. A synthesised entry's `id` is deliberately set equal to its
 * `scrobbleId` (the MPD path) rather than left as a separate identity — that equality is
 * the exact thing [PanelModel.trackIdAt] checks to know the entry has no real Jellyfin id
 * and must not be reported.
 */
internal fun reorderQueue(current: List<LocalTrack>, mpdTracks: List<MaItem>): List<LocalTrack> {
    val byMpdPath = current.associateBy { it.scrobbleId }
    return mpdTracks.map { m -> byMpdPath[m.itemId] ?: synthesizeQueueTrack(m) }
}

/**
 * A [LocalTrack] for an MPD queue entry this panel never queued, so has no Jellyfin
 * identity for — see [reorderQueue]'s doc comment for why `id == scrobbleId` here is
 * deliberate, not an oversight.
 */
internal fun synthesizeQueueTrack(m: MaItem): LocalTrack = LocalTrack(
    id = m.itemId,
    title = m.name,
    artist = m.subtitle,
    // Kept for the look only: the now-playing cover is held per album, and a foreign
    // queue's tracks would otherwise each count as an album of their own.
    album = m.album?.takeIf { it.isNotBlank() },
    durationMs = (m.duration ?: 0) * 1000L,
    artUrl = m.image,
    scrobbleId = m.itemId,
    scrobbleProvider = "mpd",
)
/** What the poll can say about the Jellyfin identity of the entry MPD is playing. */
internal sealed interface Identity {
    /** The queue entry is the file MPD is playing; [jellyfinId] is null when it has none. */
    data class Known(val jellyfinId: String?) : Identity

    /** The queue and MPD disagree this instant (mid-edit, mid-track-change): skip, don't guess. */
    object Unverified : Identity
}

/**
 * The Jellyfin id to report for MPD's entry [index], checked against [playingFile] — the
 * file MPD itself says it is playing. Index alone was trusted before, and an index read
 * against a queue that had just been swapped named a song from the wrong album.
 *
 * A synthesised entry (see [reorderQueue]: `id == scrobbleId`, queued by `mpc` or another
 * client) is [Identity.Known] with no id — there is no Jellyfin session to report for it.
 */
internal fun identify(queue: List<LocalTrack>, index: Int, playingFile: String?): Identity {
    val entry = queue.getOrNull(index) ?: return Identity.Unverified
    if (playingFile == null || entry.scrobbleId != playingFile) return Identity.Unverified
    return Identity.Known(entry.id.takeIf { it != entry.scrobbleId })
}

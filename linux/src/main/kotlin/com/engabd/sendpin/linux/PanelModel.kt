package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteState
import com.engabd.sendpin.jellyfin.JellyfinClient
import com.engabd.sendpin.library.JellyfinSource
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.mpd.MpdClient
import com.engabd.sendpin.mpd.MpdRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    /** Latest transport reading, polled at 1 Hz while playing. */
    val state = MutableStateFlow<RemoteState?>(null)
    /** Status line for the panes: what is connected and what is not. */
    val statusLine = MutableStateFlow("")

    // Clients as StateFlows so panes recompose the moment they come up — a plain
    // var set after first composition is invisible to Compose (the Library tab
    // sat on "Configure Jellyfin" with a healthy client because of exactly that).
    private val _source = MutableStateFlow<JellyfinSource?>(null)
    val source: StateFlow<JellyfinSource?> = _source
    private val _hueGhost = MutableStateFlow<HueGhostClient?>(null)
    val hueGhost: StateFlow<HueGhostClient?> = _hueGhost

    private var mpd: MpdClient? = null
    private var remote: MpdRemote? = null
    private var jellyfin: JellyfinClient? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: kotlinx.coroutines.Job? = null

    /**
     * The Jellyfin id for an MPD queue index, or null when that entry has none —
     * [SessionReporter]'s lookup, and the reason it skips reporting rather than reports a
     * garbage id.
     *
     * `LocalTrack.id` can't just be read directly: since [reorderQueue] synthesises an
     * entry (id = the MPD path, same as [LocalTrack.scrobbleId]) for anything in MPD's
     * queue this panel didn't queue itself — `mpc add`, another client, a queue MPD already
     * had running before this panel connected — comparing `id` against `scrobbleId` is what
     * tells a real Jellyfin id (always a different string, a guid vs a file path) from a
     * synthesised one (deliberately set equal to `scrobbleId` as that marker).
     */
    private fun trackIdAt(index: Int): String? =
        queue.value.getOrNull(index)?.let { t -> t.id.takeIf { it != t.scrobbleId } }

    private val reporter = SessionReporter(
        jellyfin = object : SessionReporter.Reporter {
            private val jf: JellyfinClient? get() = jellyfin
            override suspend fun start(id: String) {
                jf?.reportPlayback(id, completed = false)
            }

            override suspend fun progress(id: String, positionMs: Long, paused: Boolean) {
                jf?.reportProgress(id, positionMs, paused)
            }

            override suspend fun stop(id: String, positionMs: Long) {
                jf?.reportPlayback(id, completed = true, positionMs = positionMs)
            }
        },
        trackIdAt = ::trackIdAt,
    )

    val reportingToJellyfin: Boolean get() = reporter.reporting

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
            statusLine.value = if (err == null) "MPD connected — $mpdAddr" else "MPD: ${err.message}"

            if (jfUrlV.isNotBlank() && jfTokenV.isNotBlank()) {
                jellyfin = JellyfinClient(
                    baseUrl = jfUrlV,
                    token = jfTokenV,
                    userId = jfUserV,
                    libraryId = jfLibV,
                    deviceId = settings.deviceId.first(),
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

            // The 1 Hz poll: transport truth, then the reporter, then the queue
            // itself. This is the loop whose ~3 s tick HueGhost's 15 s music
            // timeout depends on.
            while (scope.isActive) {
                val s = remote?.poll()
                state.value = s
                if (settings.reportToJellyfin.first()) {
                    runCatching { reporter.onState(s, System.currentTimeMillis()) }
                }
                reconcileQueueFromMpd()
                delay(1_000)
            }
        }
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

    // ── Transport (the panes call these) ──────────────────────────────────

    suspend fun playAt(index: Int) = remote?.playAt(index) ?: Unit
    suspend fun togglePause() {
        val s = state.value ?: return
        if (s.playing) remote?.pause() else remote?.resume()
    }

    suspend fun next() = remote?.next() ?: Unit
    suspend fun previous() = remote?.previous() ?: Unit
    suspend fun seekTo(ms: Long) = remote?.seekTo(ms) ?: Unit

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
        queue.value = queueTracks
        remote!!.setQueue(queueTracks, at)
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
    durationMs = (m.duration ?: 0) * 1000L,
    artUrl = m.image,
    scrobbleId = m.itemId,
    scrobbleProvider = "mpd",
)
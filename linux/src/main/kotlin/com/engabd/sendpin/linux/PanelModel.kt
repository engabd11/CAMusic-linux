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

    /** The Jellyfin id for an MPD queue index — [SessionReporter]'s lookup. */
    private fun trackIdAt(index: Int): String? = queue.value.getOrNull(index)?.id

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
        trackIdAt = { index -> queue.value.getOrNull(index)?.id },
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
     * Re-sync `queue` to MPD's own queue order/membership every poll tick — this is what
     * lets QueuePane fire a remove/move/shuffle/clear and never touch `queue.value`
     * itself, per the app's established convention of correcting the UI from the source
     * of truth rather than maintaining a second copy of it.
     *
     * [MpdClient.queueTracks] rebuilds each entry from MPD's own metadata, whose `itemId`
     * is the MPD file path, not the Jellyfin id [LocalTrack.id] needs to be for session
     * reporting ([trackIdAt]/[SessionReporter]) to keep naming the right item. So this
     * does not rebuild [LocalTrack]s from MPD's answer — it re-orders and filters the
     * panel's *existing* list (built with the real Jellyfin identity by [playAlbum]) to
     * match what MPD reports, keyed on [LocalTrack.scrobbleId] (the MPD path both sides
     * agree on). A track MPD no longer has is dropped; nothing is guessed back in.
     */
    private suspend fun reconcileQueueFromMpd() {
        val client = mpd ?: return
        val mpdOrder = try {
            client.queueTracks().map { it.itemId }
        } catch (e: Exception) {
            return
        }
        queue.value = reorderQueue(queue.value, mpdOrder)
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
        queue.value = queueTracks
        remote!!.setQueue(queueTracks, startIndex)
    }
}

/**
 * The pure half of [PanelModel.reconcileQueueFromMpd]: given the panel's own queue (which
 * carries the real Jellyfin identity — see that function's doc comment for why this
 * re-orders rather than rebuilds) and the file-path order MPD reports, re-order and filter
 * to match. `internal` so `kotlin.test` can cover it without a running MPD.
 */
internal fun reorderQueue(current: List<LocalTrack>, mpdOrder: List<String>): List<LocalTrack> {
    val byMpdPath = current.associateBy { it.scrobbleId }
    return mpdOrder.mapNotNull { byMpdPath[it] }
}
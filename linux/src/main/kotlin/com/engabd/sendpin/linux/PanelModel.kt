package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteState
import com.engabd.sendpin.jellyfin.JellyfinClient
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.JellyfinSource
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.lyrics.Lrclib
import com.engabd.sendpin.linux.station.LibraryRadioSource
import com.engabd.sendpin.linux.station.LocalRadio
import com.engabd.sendpin.linux.station.RadioSession
import com.engabd.sendpin.linux.station.SleepAction
import com.engabd.sendpin.linux.station.radioNeedsMore
import com.engabd.sendpin.linux.station.SleepChoice
import com.engabd.sendpin.linux.station.SleepTimer
import com.engabd.sendpin.linux.station.sideOf
import com.engabd.sendpin.linux.station.sleepAction
import com.engabd.sendpin.linux.station.startSleep
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.mpd.MpdClient
import com.engabd.sendpin.mpd.MpdRemote
import kotlinx.coroutines.CancellationException
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

    /**
     * Whether MPD is answering. Goes false only after a few polls in a row get nothing, so a
     * dropped packet never reads as an outage; true again at the first answer.
     */
    val mpdUp = MutableStateFlow(true)
    /** Why Jellyfin could not be reached when the clients were last built, or null if it could. */
    val jellyfinProblem = MutableStateFlow<String?>(null)
    private var mpdMisses = 0

    /** The sleep timer, if one is running. */
    val sleep = MutableStateFlow<SleepTimer?>(null)

    /** The radio, while one is keeping the queue topped up. */
    val radio = MutableStateFlow<RadioSession?>(null)
    private val localRadio = LocalRadio()
    /** The newest track the radio queued: the seed of its next batch. */
    @Volatile private var radioSeed: MaItem? = null
    private val radioFilling = java.util.concurrent.atomic.AtomicBoolean(false)

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
            connectionLine = statusLine.value
            mpdUp.value = err == null
            mpdMisses = 0
            jellyfinProblem.value = null

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
                jellyfinProblem.value = jfErr
                statusLine.value = (statusLine.value + "  ·  " +
                    if (jfErr == null) "Jellyfin connected" else "Jellyfin: $jfErr")
                connectionLine = statusLine.value
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
                val pollAt = System.currentTimeMillis()
                queueLock.withLock {
                    val epochBefore = commandEpoch.get()
                    val s = remote?.poll()
                    // Taken straight after the answer: the reporter extrapolates from it.
                    val readAt = System.currentTimeMillis()
                    // A transport command that landed while this reading was in flight
                    // makes the reading older than the command's own optimistic state —
                    // publishing it would flip the button back under the thumb (and
                    // hand HueGhost a stale "playing" after a pause). The wake the
                    // command sent brings a fresh reading within milliseconds, so the
                    // stale one is skipped. A dropped status (s == null) keeps the last
                    // good reading instead of blanking the screen — the contract
                    // RemotePlayback.poll documents.
                    val fresh = pollReadingFresh(epochBefore, commandEpoch.get())
                    state.value = pollPublishes(state.value, s, fresh)
                    if (s != null) {
                        mpdMisses = 0
                        mpdUp.value = true
                    } else if (++mpdMisses >= MPD_MISSES_FOR_OUTAGE) {
                        mpdUp.value = false
                    }
                    // Read after the status, so a track change between the two shows up
                    // as a mismatch below rather than as the wrong song.
                    val playingFile = if (s != null && !s.stopped) {
                        runCatching { mpd?.currentSong()?.itemId }.getOrNull()
                    } else {
                        null
                    }
                    reconcileQueueFromMpd()
                    val reading = when {
                        !fresh -> null // stale: the command's wake brings a fresh reading
                        s == null || s.stopped -> Reading(s, null, readAt)
                        else -> when (val who = identify(queue.value, s.index, playingFile)) {
                            is Identity.Known -> Reading(s, who.jellyfinId, readAt)
                            Identity.Unverified -> null // next poll, once the two agree
                        }
                    }
                    reading?.let { readings.trySend(it) }
                }
                sleepTick(state.value)
                radioTick(state.value)
                // Another client can change MPD's loudness under us (see [replayGain]);
                // one extra command every 10 s keeps the readback honest without
                // doubling the poll's traffic.
                val now = System.currentTimeMillis()
                if (now - lastGainReadAt >= 10_000) {
                    lastGainReadAt = now
                    mpd?.replayGainMode()?.let { replayGain.value = it }
                }
                // On a fixed POLL_PERIOD_MS from the start of this poll, not "1 s after
                // the work": see POLL_PERIOD_MS for why the period is not a whole second.
                val wait = (POLL_PERIOD_MS - (System.currentTimeMillis() - pollAt)).coerceAtLeast(50L)
                withTimeoutOrNull(wait) { wake.receive() }
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
    // report follow at once even if the idle push is down. None of them throws: see
    // [guarded] — a refused command used to take the whole panel down. They run one at a
    // time ([transportLock]), so a quick double tap is two commands in order, each
    // deciding from what the one before it left.
    suspend fun playAt(index: Int) = transportLock.withLock { send("Play") { playAt(index) } }

    /**
     * Play / pause. A stopped player — the queue played to its end, or nothing was
     * started — is *started* again: MPD's `pause 0` does nothing once stopped, so the
     * play button used to do nothing there either. See [playAction].
     *
     * Decided from MPD's own answer, read now, not from the last poll: a reading up to a
     * second old (or none at all, after one dropped `status`) could make a tap on pause do
     * nothing. The answer is then shown at once, so the button turns over under the thumb
     * and a second tap reads the new state; the poll the command wakes confirms it.
     */
    suspend fun togglePause() {
        transportLock.withLock {
            val s = remote?.poll() ?: state.value
            val size = queue.value.size
            when (playAction(s, size)) {
                PlayAction.PAUSE -> if (send("Pause") { pause() }) state.value = s?.copy(playing = false)
                PlayAction.RESUME -> if (send("Play") { resume() }) state.value = s?.copy(playing = true)
                PlayAction.START -> send("Play") { playAt(restartIndex(s, size)) }
                PlayAction.NONE -> Unit
            }
        }
    }

    /**
     * Next track — nothing to do (rather than an error from MPD) when there is no next.
     *
     * Decided from MPD's own answer, read now, not from the last poll — a reading up
     * to a second old (or none at all, after one dropped `status`) made a tap on Next
     * do nothing: [canSkipNext] refused on the stale state and the tap was silently
     * dropped, which is the "have to click it twice" skip. The poll the command wakes
     * confirms the move.
     */
    suspend fun next() = transportLock.withLock {
        val s = remote?.poll() ?: state.value
        if (canSkipNext(s, queue.value.size)) send("Next") { next() }
    }

    /** Previous track (or the start of this one, as MPD does) — only while something is loaded. */
    suspend fun previous() = transportLock.withLock {
        val s = remote?.poll() ?: state.value
        if (canSkipPrevious(s)) send("Previous") { previous() }
    }

    suspend fun seekTo(ms: Long) = transportLock.withLock {
        val s = remote?.poll() ?: state.value
        if (s?.stopped == false) send("Seek") { seekTo(ms) }
    }

    /** One transport command at a time — see the note above [playAt]. */
    private val transportLock = Mutex()

    /**
     * Bumped by [send] the moment a transport command is issued. The poll captures it
     * before its read and compares after: a command that landed mid-read makes the
     * reading older than the command's own optimistic state, so the poll skips
     * publishing it — the wake the command sent brings a fresh reading at once. See
     * [pollReadingFresh].
     */
    private val commandEpoch = java.util.concurrent.atomic.AtomicLong()

    /** Sends [command] through [guarded] and wakes the poll; true when MPD took it. */
    private suspend fun send(what: String, command: suspend MpdRemote.() -> Unit): Boolean {
        val r = remote ?: return false
        // The moment a command is issued, any poll reading in flight is suspect: it may
        // predate the command, and publishing it would clobber the command's optimistic
        // state. The poll compares this epoch before and after its read and skips a
        // reading a command landed in the middle of.
        commandEpoch.incrementAndGet()
        val ok = guarded(what) { r.command() }
        wake.trySend(Unit)
        return ok
    }

    /** The connection summary [start] built — what [statusLine] returns to after an error clears. */
    @Volatile
    private var connectionLine = ""

    /** The error line [guarded] last showed, so a success clears that and nothing else. */
    @Volatile
    private var shownError: String? = null

    /**
     * Runs a command a pane asked for, and never lets it throw. MPD refuses some commands
     * outright — `next` or `previous` with nothing playing ("Not playing"), a play on an
     * empty queue — and a dropped connection fails any of them. Each failure used to escape
     * into the button's coroutine and crash the app; now the status line says what MPD said
     * and the panel carries on. The next command that succeeds clears it again.
     */
    private suspend fun guarded(what: String, block: suspend () -> Unit): Boolean {
        return try {
            block()
            // Clear only our own error line — a hint the command itself wrote
            // ("check the path prefix…") must stay up.
            if (shownError != null && statusLine.value == shownError) statusLine.value = connectionLine
            shownError = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val line = commandErrorLine(what, e.message, connectionLine)
            shownError = line
            statusLine.value = line
            false
        }
    }

    /**
     * Level loudness: persist the choice, hand it to MPD (which applies the files'
     * ReplayGain tags as it decodes), then read back what MPD actually took. `off`,
     * `track` or `album` — the Android app's three modes, spelled as MPD spells them.
     */
    suspend fun setReplayGain(mode: String) = guarded("Loudness") {
        settings.setReplayGainMode(mode)
        val client = mpd ?: return@guarded
        runCatching { client.setReplayGainMode(mode) }
        replayGain.value = client.replayGainMode() ?: replayGain.value
    }

    // ── Queue editing (QueuePane) ──────────────────────────────────────────
    // Same fire-and-let-the-poll-reconcile shape as the transport calls above:
    // send MPD the edit and don't touch `queue.value` here at all.
    // reconcileQueueFromMpd(), called every poll tick, is what actually
    // resyncs it — see that function's doc comment for why it re-orders the
    // existing list instead of rebuilding it from MPD's answer directly.
    suspend fun removeFromQueue(index: Int) = guarded("Remove") { remote?.removeAt(index) }.also { wake.trySend(Unit) }
    suspend fun moveInQueue(from: Int, to: Int) = guarded("Move") { remote?.move(from, to) }.also { wake.trySend(Unit) }
    suspend fun shuffleQueue() = guarded("Shuffle") { remote?.shuffleQueue() }
    suspend fun clearQueue() = guarded("Clear") { stopRadio(); remote?.clear() }

    /** MPD's volume, 0..1, when it has a mixer to set (a fixed output answers nothing). */
    suspend fun setVolume(volume: Float) = guarded("Volume") { remote?.setVolume(volume) }

    // ── Adding to the queue (the row menus) ────────────────────────────────

    /**
     * Put [tracks] in the queue, right after the playing one when [next], else at the end,
     * without touching what plays. The same path mapping as [playAlbum]; tracks that will
     * not map are skipped, and the status line says so when none did.
     */
    suspend fun enqueue(tracks: List<MaItem>, next: Boolean) =
        guarded(if (next) "Play next" else "Add to queue") {
            val r = remote ?: return@guarded
            val queueTracks = mpdTracksFor(tracks)
            if (queueTracks.isEmpty()) {
                statusLine.value = "No track mapped to MPD — check the path prefix in Settings"
                return@guarded
            }
            val ids = tracks.mapNotNull { t -> t.parentId?.takeIf { it.isNotBlank() }?.let { t.itemId to it } }
            if (ids.isNotEmpty()) albumIds.value = albumIds.value + ids
            queueLock.withLock {
                val playing = state.value?.index?.takeIf { it >= 0 }
                if (next) {
                    val after = playing ?: (queue.value.size - 1)
                    r.playNext(queueTracks, after)
                    val at = (after + 1).coerceIn(0, queue.value.size)
                    queue.value = queue.value.take(at) + queueTracks + queue.value.drop(at)
                } else {
                    r.addToQueue(queueTracks)
                    queue.value = queue.value + queueTracks
                }
            }
            wake.trySend(Unit)
        }

    /** A queue row's "Play next": move the entry at [from] to just after the playing one. */
    suspend fun playQueueEntryNext(from: Int) {
        val playing = state.value?.index?.takeIf { it >= 0 } ?: return
        val to = if (from > playing) playing + 1 else playing
        if (from != to) moveInQueue(from, to)
    }

    // ── Lyrics (the lyric sheet) ───────────────────────────────────────────

    private val lyricCache = object : LinkedHashMap<String, MaLyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MaLyrics?>?) = size > 48
    }

    /**
     * The lyrics for [track], or null when the library has none. A track this panel queued
     * carries its Jellyfin id; one queued by another MPD client is found by name. Remembered
     * (including "none"), so a track is asked about once; a failed request is not remembered.
     */
    suspend fun lyricsFor(track: LocalTrack): MaLyrics? {
        val key = track.scrobbleId ?: track.id
        synchronized(lyricCache) { if (lyricCache.containsKey(key)) return lyricCache[key] }
        val src = source.value
        val library = try {
            if (src == null || !src.has(Capability.LYRICS)) null else {
                val id = track.id.takeIf { it != track.scrobbleId }
                    ?: pickLyricMatch(
                        src.search(listOfNotNull(track.artist, track.title).joinToString(" "), 12).tracks,
                        track.title, track.artist,
                    )?.itemId
                id?.let { src.lyrics(it) }?.takeIf { it.text.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        // Jellyfin's own timed lyrics win. Without them, LRCLIB is asked (when allowed),
        // and its answer is taken if it is timed, or if the library had nothing at all.
        val online = if (library?.synced != true && settings.lyricsOnline.first()) {
            runCatching {
                lrclib.find(track.title, track.artist, track.album, (track.durationMs / 1000).toInt().takeIf { it > 0 })
            }.getOrNull()?.takeIf { it.text.isNotBlank() && (it.synced || library == null) }
        } else null
        val found = online ?: library
        synchronized(lyricCache) { lyricCache[key] = found }
        return found
    }

    private val lrclib = Lrclib()

    // ── The sleep timer ─────────────────────────────────────────────────────

    /** Start (or, with [SleepChoice.OFF], cancel) the sleep timer. */
    fun setSleep(choice: SleepChoice) {
        val running = sleep.value
        val s = state.value
        val side = s?.index?.takeIf { it >= 0 }?.let { sideOf(queue.value, it, albumIds.value) }
        sleep.value = startSleep(choice, System.currentTimeMillis(), side?.let { it.firstIndex + it.size - 1 })
        // Cancelling an album sleep MPD was already asked to honour takes the request back.
        if (running?.armed == true) scope.launch { guarded("Sleep") { remote?.stopAfterCurrent(false) } }
    }

    private suspend fun sleepTick(s: RemoteState?) {
        val t = sleep.value ?: return
        when (sleepAction(t, s, System.currentTimeMillis())) {
            SleepAction.NONE -> Unit
            SleepAction.PAUSE -> {
                guarded("Sleep") { remote?.pause() }
                sleep.value = null
                wake.trySend(Unit)
            }
            SleepAction.ARM_STOP_AFTER_CURRENT -> {
                if (guarded("Sleep") { remote?.stopAfterCurrent(true) }) {
                    sleep.value = t.copy(armed = true)
                } else {
                    // An MPD too old for `single oneshot`: pause as the last track runs out instead.
                    val left = ((s?.durationMs ?: 0L) - (s?.positionMs ?: 0L)).coerceAtLeast(0L)
                    sleep.value = t.copy(deadlineMs = System.currentTimeMillis() + left + 300L, sideEnd = null)
                }
            }
            SleepAction.CLEAR -> sleep.value = null
        }
    }

    /**
     * Queue a Jellyfin album through MPD: resolve every item's file path, map to
     * MPD's relative path, push the queue. Items whose path will not map are
     * skipped — a queue that silently plays the wrong thing is worse than a
     * shorter one.
     */
    suspend fun playAlbum(album: MaItem, tracks: List<MaItem>, startIndex: Int = 0) =
        guarded("Play") { stopRadio(); queueAndPlay(album, tracks, startIndex) }

    // ── Radio ───────────────────────────────────────────────────────────────

    /**
     * Start a radio from [seed] — a song, an album or an artist. A song plays first and
     * the music like it follows; an album or an artist starts straight into its mix. The
     * queue is topped up a few tracks before it runs out, for as long as the radio is on.
     */
    suspend fun startRadio(seed: MaItem) = guarded("Radio") {
        val src = source.value ?: return@guarded
        localRadio.forget()
        val isTrack = seed.mediaType == "track"
        if (isTrack) localRadio.remember(listOf(seed.itemId))
        val batch = localRadio.next(LibraryRadioSource(src), seed, count = 16, exclude = setOf(seed.itemId))
        val tracks = if (isTrack) listOf(seed) + batch else batch
        if (tracks.isEmpty()) {
            statusLine.value = "Radio: the library had nothing to play"
            return@guarded
        }
        queueAndPlay(seed, tracks, 0)
        radioSeed = tracks.last()
        radio.value = RadioSession(seed.name, seed.mediaType)
    }

    fun stopRadio() {
        radio.value = null
        radioSeed = null
    }

    private fun radioTick(s: com.engabd.sendpin.audio.RemoteState?) {
        if (radio.value == null || s == null || s.stopped) return
        if (!radioNeedsMore(s.index, queue.value.size)) return
        if (!radioFilling.compareAndSet(false, true)) return
        scope.launch {
            try {
                val src = source.value ?: return@launch
                val queued = queue.value.map { it.id }.toSet()
                val batch = localRadio.next(LibraryRadioSource(src), radioSeed, count = 12, exclude = queued)
                if (batch.isNotEmpty() && radio.value != null) {
                    enqueue(batch, next = false)
                    radioSeed = batch.last()
                } else {
                    // Nothing found (or nothing new): ask again in a while, not every poll.
                    kotlinx.coroutines.delay(30_000)
                }
            } finally {
                radioFilling.set(false)
            }
        }
    }

    /** [tracks] as MPD queue entries: path mapped, the ones that will not map dropped. */
    private suspend fun mpdTracksFor(tracks: List<MaItem>): List<LocalTrack> {
        if (jellyfin == null || mpd == null) return emptyList()
        val paths = JellyfinPaths.paths(
            baseUrl = jfUrl.value,
            token = settings.jfToken.first(),
            deviceId = settings.deviceId.first(),
            ids = tracks.map { it.itemId },
        )
        val prefix = settings.jellyfinPathPrefix.first()
        return tracks.mapNotNull { item ->
            val jfPath = paths[item.itemId] ?: return@mapNotNull null
            val mpdPath = JellyfinToMpd.toMpdPath(jfPath, prefix) ?: return@mapNotNull null
            JellyfinToMpd.toLocalTrack(item, mpdPath)
        }
    }

    private suspend fun queueAndPlay(album: MaItem, tracks: List<MaItem>, startIndex: Int) {
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
            (remote ?: return).setQueue(queueTracks, at)
        }
        wake.trySend(Unit)
    }
}

/** What the play button does, for [playAction]. */
internal enum class PlayAction { PAUSE, RESUME, START, NONE }

/**
 * The play button's meaning in state [s] with [queueSize] tracks queued: pause what plays,
 * resume what is paused, *start* a stopped player (MPD ignores a resume once stopped —
 * the queue ran out, or it was never started), and nothing at all with nothing queued or
 * MPD unread. Pure so every case is testable without a server.
 */
internal fun playAction(s: RemoteState?, queueSize: Int): PlayAction = when {
    s == null || queueSize <= 0 -> PlayAction.NONE
    s.playing -> PlayAction.PAUSE
    s.stopped -> PlayAction.START
    else -> PlayAction.RESUME
}

/**
 * Where a stopped player starts again: the track MPD still has selected when it has one
 * (stopped mid-queue), the top of the queue when it has none (it played to the end).
 */
internal fun restartIndex(s: RemoteState?, queueSize: Int): Int =
    s?.index?.takeIf { it in 0 until queueSize } ?: 0

/**
 * Whether Next has somewhere to go: something loaded, and a track after it. MPD answers
 * `next` with an error once stopped, and on the last track it would end the music
 * rather than skip — both are a tap that should simply do nothing.
 */
internal fun canSkipNext(s: RemoteState?, queueSize: Int): Boolean =
    s != null && !s.stopped && s.index in 0 until queueSize - 1

/** Whether Previous can act: something loaded (MPD refuses it once stopped). */
internal fun canSkipPrevious(s: RemoteState?): Boolean =
    s != null && !s.stopped && s.index >= 0

/**
 * The status line while a command has failed: what was asked and MPD's own words, after the
 * connection summary so that stays visible. Pure for the tests.
 */
internal fun commandErrorLine(what: String, message: String?, connection: String): String {
    val reason = message?.trim()?.takeIf { it.isNotEmpty() } ?: "MPD didn't answer"
    val line = "$what: $reason"
    return if (connection.isBlank()) line else "$connection  ·  $line"
}

/**
 * Whether a poll reading taken at command epoch [epochBefore] is still current now that
 * the epoch reads [epochNow]: only when no transport command landed while the reading
 * was in flight. A command that did land makes the reading older than the command's own
 * optimistic state, so the poll must not publish it — see [PanelModel.send]. Pure for
 * the tests.
 */
internal fun pollReadingFresh(epochBefore: Long, epochNow: Long): Boolean = epochBefore == epochNow

/**
 * The state the poll publishes from one reading: the reading itself when it is fresh and
 * present, otherwise the previous state — a dropped `status` must not blank the screen
 * (the contract [com.engabd.sendpin.audio.RemotePlayback.poll] documents), and a stale
 * reading must not clobber the command's optimistic write. Pure for the tests.
 */
internal fun pollPublishes(previous: RemoteState?, reading: RemoteState?, fresh: Boolean): RemoteState? =
    if (fresh && reading != null) reading else previous

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

/** Polls in a row that must get no answer from MPD before the panel calls it an outage. */
internal const val MPD_MISSES_FOR_OUTAGE = 3

/**
 * The poll period, deliberately not a whole second.
 *
 * MPD moves `elapsed` in steps of its output chunk: 0.25 s here, bit-perfect 96 kHz
 * into the Topping (measured 2026-10-19). A reading is stale by however far the poll
 * landed into the current step. Polling "1 s after the work" made the period
 * 1.0024 s, so each reading landed 2.4 ms later in the step than the last: the
 * staleness crept from 0 to 0.25 s and wrapped, roughly every 100 s, and every
 * reading in [SessionReporter]'s window shared it. Jellyfin saw a sawtooth -
 * -2.44 ms/s, then +0.22 s - and HueGhost followed it with the lights.
 *
 * A fixed 1037 ms moves each reading 37 ms along the step instead, so any 8 in a row
 * cover a whole 0.25 s step. The window's max ([SessionReporter]) then sits within
 * ~37 ms of the audio all the time. Prime, so it never locks onto MPD's grid.
 */
internal const val POLL_PERIOD_MS = 1_037L

/**
 * The library track that [title] by [artist] most likely is, among search [hits]: the same
 * title (case aside) by the same artist when both are known, else the first of that title.
 * Pure for the tests.
 */
internal fun pickLyricMatch(hits: List<MaItem>, title: String, artist: String?): MaItem? {
    val sameTitle = hits.filter { it.name.trim().equals(title.trim(), ignoreCase = true) }
    if (artist.isNullOrBlank()) return sameTitle.firstOrNull()
    val byArtist = sameTitle.firstOrNull { hit ->
        val by = hit.subtitle?.trim().orEmpty()
        by.isNotEmpty() && (by.contains(artist.trim(), ignoreCase = true) || artist.contains(by, ignoreCase = true))
    }
    return byArtist ?: sameTitle.firstOrNull()
}

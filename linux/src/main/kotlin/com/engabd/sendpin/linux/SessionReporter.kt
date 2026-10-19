package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.RemoteState

/**
 * The Jellyfin session reporter — the load-bearing piece between MPD's transport
 * and HueGhost's source list.
 *
 * HueGhost stops music sync **15 seconds** after a Jellyfin session goes quiet,
 * and it steers its ghost copy of the track by the reports it reads back from
 * `/Sessions`: each one is anchored at the report's `LastPlaybackCheckIn`, which
 * Jellyfin stamps with its own clock the moment the POST arrives. So a report is
 * only as good as *how exactly its position matches the moment it lands*.
 * HueGhost steers hard by reports whose error is under 30 ms and only slowly
 * (median filter) by noisier ones.
 *
 * Two things make that possible here:
 *  - **Positions are computed at send time**, not at poll time. MPD's `elapsed`
 *    is read once per poll; by the time the POST goes out, a track change's stop
 *    and start have already cost a round-trip each. Reporting the polled value
 *    as-is is ~0.1 s of variable error — exactly the noise that kept HueGhost off
 *    its precise path. Instead every report asks [clock] for "now" and
 *    extrapolates from the playback model.
 *  - **The model is a running maximum of `elapsed − readAt`.** Both errors in a
 *    reading point the same way: MPD advances `elapsed` in chunk-sized steps, so
 *    it only ever lags the true position, and `readAt` is taken after the
 *    response, so it only ever runs late. Each sample is therefore a lower bound
 *    of the true intercept, and the max over recent samples converges on it —
 *    the same estimator HueGhost uses for its own clock offset. The window is
 *    short, so a DAC clock drifting against the system clock is followed.
 *
 * Every transition (track change, pause, resume, seek, stop) fires exactly once,
 * and a progress tick keeps the session alive: every second while playing (the
 * rate Moonfin reports at, and what HueGhost's 0.5 s poll can use), every three
 * while paused.
 *
 * A pure state machine: readings enter with their read time, "now" comes from
 * the injected [clock], so the whole contract is unit-testable without a server.
 * The poll loop that feeds it lives in [PanelModel].
 */
class SessionReporter(
    private val jellyfin: Reporter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val playingPeriodMs: Long = 1_000,
    private val pausedPeriodMs: Long = 3_000,
) {

    /** The three Jellyfin reports, named for what they mean rather than what they POST. */
    interface Reporter {
        suspend fun start(id: String, positionMs: Long)
        suspend fun progress(id: String, positionMs: Long, paused: Boolean)
        suspend fun stop(id: String, positionMs: Long)
    }

    private var currentId: String? = null
    private var paused: Boolean = false
    /** Where playback stands while [paused]. */
    private var pausedPositionMs: Long = 0
    /** Recent `elapsed − readAt` samples while playing; their max is the model's intercept. */
    private val samples = ArrayDeque<Long>()
    /** Consecutive samples well below the intercept — see [LAG_RESET_MS]. */
    private var lagging = 0
    /** Readings folded in since the last [anchor]. */
    private var sinceAnchor = 0
    private var lastReadAtMs: Long = 0
    private var lastProgressAtMs: Long = 0

    /** True while a Jellyfin session is open (drives the Lights pane's status line). */
    val reporting: Boolean get() = currentId != null

    /**
     * Fold in one MPD reading.
     *
     * @param state the transport, or null when MPD could not be reached.
     * @param id the Jellyfin item id of the entry MPD is playing, or null when that
     *   entry has none (queued by another client). The caller resolves it only once it
     *   has checked the entry really is the file MPD is playing — a reading it cannot
     *   vouch for is not passed in at all.
     * @param readAtMs when the reading was taken, on the same clock as [clock].
     */
    suspend fun onState(state: RemoteState?, id: String?, readAtMs: Long) {
        // MPD unreachable: hold the session open and report nothing. A Wi-Fi blip
        // must not end the session HueGhost is following — the next good state
        // reconciles position as usual.
        if (state == null) return

        if (state.stopped || id == null) {
            // Stopped, or now playing something with no Jellyfin identity: either way
            // the session HueGhost follows is over. Leaving it open would keep the
            // ghost playing the previous song until HueGhost's 15 s timeout.
            end()
            return
        }

        val nowPaused = !state.playing

        // Track change: finish the old item, open the new one where it really is.
        // A queue MPD was already playing, or a panel restarted mid-track, does not
        // start at 0 — reported as 0, HueGhost jumps to the start and back.
        if (id != currentId) {
            end()
            currentId = id
            anchor(state, nowPaused, readAtMs)
            jellyfin.start(id, positionAt(clock()))
            // Jellyfin's start report always says "playing". Found paused (MPD paused
            // before the panel came up), say so at once, or HueGhost lights up for a
            // song nobody is playing.
            if (nowPaused) jellyfin.progress(id, positionAt(clock()), true)
            lastProgressAtMs = clock()
            return
        }

        val transition = when {
            nowPaused != paused -> {
                anchor(state, nowPaused, readAtMs)
                true
            }
            nowPaused -> {
                val moved = kotlin.math.abs(state.positionMs - pausedPositionMs) > SEEK_TOLERANCE_MS
                if (moved) anchor(state, true, readAtMs)
                moved
            }
            else -> {
                val sample = state.positionMs - readAtMs
                if (kotlin.math.abs(sample - intercept()) > SEEK_TOLERANCE_MS) {
                    anchor(state, false, readAtMs)
                    true
                } else {
                    addSample(sample)
                    lastReadAtMs = readAtMs
                    false
                }
            }
        }

        val period = if (paused) pausedPeriodMs else playingPeriodMs
        // The slack keeps a 1 Hz poll from missing every other 1 s tick by a few ms.
        val tickDue = clock() - lastProgressAtMs >= period - TICK_SLACK_MS
        if (transition || tickDue) {
            jellyfin.progress(id, positionAt(clock()), paused)
            lastProgressAtMs = clock()
        }
    }

    /**
     * Close the open session, if any — on MPD stopping, and when the panel quits or
     * reporting is switched off, so HueGhost lets go at once rather than after its
     * 15 s timeout.
     */
    suspend fun end() {
        val id = currentId ?: return
        currentId = null
        // Where the last reading had it: "now" may be well past the end of a track
        // that finished while the panel waited on MPD.
        jellyfin.stop(id, positionAt(lastReadAtMs))
    }

    /** Restart the model from one reading: a new track, a pause/resume or a seek. */
    private fun anchor(state: RemoteState, nowPaused: Boolean, readAtMs: Long) {
        paused = nowPaused
        pausedPositionMs = state.positionMs
        samples.clear()
        lagging = 0
        sinceAnchor = 0
        samples.addLast(state.positionMs - readAtMs)
        lastReadAtMs = readAtMs
    }

    private fun addSample(sample: Long) {
        // Samples far below the intercept, three running, mean the intercept itself
        // was too high — the audio started a moment after MPD first reported it
        // playing (the output opening after a start or resume). Rebuild from the
        // lagging samples rather than hold the early one for the whole window.
        // Only just after an anchor: that is when an output opens late. Later on,
        // samples sit anywhere up to one MPD step (0.25 s) under the max by design
        // (see POLL_PERIOD_MS), and three of them in a row are normal.
        sinceAnchor++
        if (sinceAnchor <= LAG_RESET_READINGS && intercept() - sample > LAG_RESET_MS) {
            if (++lagging >= LAG_RESET_COUNT) {
                val lagged = samples.takeLast(LAG_RESET_COUNT - 1)
                samples.clear()
                samples.addAll(lagged)
                lagging = 0
            }
        } else {
            lagging = 0
        }
        samples.addLast(sample)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    private fun intercept(): Long = samples.maxOrNull() ?: 0

    private fun positionAt(nowMs: Long): Long =
        (if (paused) pausedPositionMs else intercept() + nowMs).coerceAtLeast(0)

    companion object {
        /**
         * How far a reading may land from the model before it reads as a seek rather
         * than playback. Readings are time-stamped, so ordinary jitter is MPD's chunk
         * step plus a local round-trip — tens of ms. Half a second is well clear of
         * that and still catches a short scrub.
         */
        private const val SEEK_TOLERANCE_MS = 500L

        /**
         * Samples in the intercept window — about sixteen seconds at 1 Hz. The poll's
         * phase walks 37 ms along MPD's step per reading ([POLL_PERIOD_MS]), so this
         * covers a step of up to ~0.6 s, and the max is a reading taken just as
         * `elapsed` moved: the audio's own position.
         */
        private const val WINDOW = 16

        /**
         * How far under the intercept a sample must sit to count as lagging, and how
         * many in a row it takes to rebuild the window: well above one MPD chunk at CD
         * rate (~23 ms) plus a slow round-trip, so ordinary jitter never trips it.
         */
        private const val LAG_RESET_MS = 120L
        private const val LAG_RESET_COUNT = 3
        /** ... and only within this many readings of a start, resume or seek. */
        private const val LAG_RESET_READINGS = 6

        private const val TICK_SLACK_MS = 250L
    }
}

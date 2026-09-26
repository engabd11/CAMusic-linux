package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.RemoteState

/**
 * The Jellyfin session reporter — the load-bearing piece between MPD's transport
 * and HueGhost's source list.
 *
 * HueGhost stops music sync **15 seconds** after a Jellyfin session goes quiet,
 * so the ~3 s progress tick must never lapse while playing; and every transition
 * (track change, pause, resume, seek, stop) must fire exactly once. This class is
 * a pure state machine over [RemoteState] readings — time enters as a parameter
 * (`nowMs`), so the whole contract is unit-testable without a server or a clock.
 *
 * The 1 Hz poll loop that feeds it lives in the shell (Main), not here.
 */
class SessionReporter(
    private val jellyfin: Reporter,
    /** The Jellyfin item id for an MPD queue index — from the panel's queue model. */
    private val trackIdAt: (Int) -> String?,
    private val progressPeriodMs: Long = 3_000,
) {

    /** The three Jellyfin reports, named for what they mean rather than what they POST. */
    interface Reporter {
        suspend fun start(id: String, positionMs: Long)
        suspend fun progress(id: String, positionMs: Long, paused: Boolean)
        suspend fun stop(id: String, positionMs: Long)
    }

    private var currentId: String? = null
    private var lastPositionMs: Long = 0
    private var lastStateAtMs: Long = 0
    private var lastReportedPaused: Boolean = false
    private var lastProgressAtMs: Long = 0

    /** True while a Jellyfin session is open (drives the Lights pane's status line). */
    val reporting: Boolean get() = currentId != null

    suspend fun onState(state: RemoteState?, nowMs: Long) {
        // MPD unreachable: hold the session open and report nothing. A Wi-Fi blip
        // must not end the session HueGhost is following — the next good state
        // reconciles position as usual.
        if (state == null) return

        if (state.stopped) {
            currentId?.let { jellyfin.stop(it, lastPositionMs) }
            currentId = null
            return
        }

        val id = trackIdAt(state.index) ?: return
        val paused = !state.playing

        // Track change: finish the old item, open the new one, reset the tick clock.
        if (id != currentId) {
            currentId?.let { jellyfin.stop(it, lastPositionMs) }
            currentId = id
            lastPositionMs = state.positionMs
            lastStateAtMs = nowMs
            lastProgressAtMs = nowMs
            lastReportedPaused = paused
            // Where it really is: a queue MPD was already playing, or a panel that
            // restarted mid-track, does not start at 0 — reported as 0, a follower
            // (Hue Ghost) jumps to the start and back on the next tick.
            jellyfin.start(id, state.positionMs)
            // Jellyfin's start report always says "playing". Found paused (MPD paused
            // before the panel came up), say so at once, or Hue Ghost lights up for a
            // song nobody is playing until the next tick.
            if (paused) jellyfin.progress(id, state.positionMs, true)
            return
        }

        val deltaPos = state.positionMs - lastPositionMs
        val deltaWall = nowMs - lastStateAtMs
        val seek = !paused && kotlin.math.abs(deltaPos - deltaWall) > SEEK_TOLERANCE_MS

        val pauseOrResume = paused != lastReportedPaused
        val tickDue = nowMs - lastProgressAtMs >= progressPeriodMs

        if (seek || pauseOrResume || tickDue) {
            jellyfin.progress(id, state.positionMs, paused)
            lastProgressAtMs = nowMs
        }
        lastReportedPaused = paused
        lastPositionMs = state.positionMs
        lastStateAtMs = nowMs
    }

    companion object {
        /**
         * How far the position may deviate from elapsed wall time before it reads
         * as a seek rather than playback. A 1 Hz poll under a slow machine still
         * lands within a few hundred ms; two seconds is generous and cannot be
         * reached by drift.
         */
        private const val SEEK_TOLERANCE_MS = 2_000L
    }
}
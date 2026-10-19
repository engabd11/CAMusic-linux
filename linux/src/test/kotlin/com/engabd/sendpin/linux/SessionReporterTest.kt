package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.RemoteState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SessionReporter]'s state machine, fed synthetic [RemoteState]s on a virtual clock —
 * the contract being that HueGhost stops music sync 15 s after a session goes quiet
 * (so the tick must never lapse), every transition fires the right report exactly
 * once, and every reported position is where playback stands *when the report is
 * sent*, to within MPD's own step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReporterTest {

    private class Rig {
        var now = 0L
        /** How long each fake POST takes — advances [now], like a real round-trip. */
        var postMs = 0L
        val starts = mutableListOf<Pair<String, Long>>()
        val progress = mutableListOf<Triple<String, Long, Boolean>>()
        val stops = mutableListOf<Pair<String, Long>>()
        /** The clock at each progress report, for checking the extrapolation. */
        val progressAt = mutableListOf<Long>()

        val rep = SessionReporter(
            object : SessionReporter.Reporter {
                override suspend fun start(id: String, positionMs: Long) {
                    starts.add(id to positionMs); now += postMs
                }

                override suspend fun progress(id: String, positionMs: Long, paused: Boolean) {
                    progress.add(Triple(id, positionMs, paused)); progressAt.add(now); now += postMs
                }

                override suspend fun stop(id: String, positionMs: Long) {
                    stops.add(id to positionMs); now += postMs
                }
            },
            clock = { now },
        )

        /** A reading taken at [t], of entry [index] with Jellyfin id `jf-index` unless given. */
        suspend fun at(t: Long, s: RemoteState?, id: String? = s?.let { "jf-${it.index}" }) {
            now = t
            rep.onState(s, id, t)
        }
    }

    private fun state(
        playing: Boolean = true,
        stopped: Boolean = false,
        index: Int = 0,
        pos: Long = 0,
    ) = RemoteState(
        playing = playing,
        stopped = stopped,
        index = index,
        positionMs = pos,
        durationMs = 200_000,
        volume = null,
        outputFormat = RemoteAudioFormat(44100, 16, 2, 660),
        outputDeviceName = "Topping DX5 II (bit-perfect)",
    )

    @Test
    fun `first playing state reports start, not progress`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 5_000))
        assertEquals(listOf("jf-0" to 5_000L), r.starts)
        assertEquals(0, r.progress.size)
        assertEquals(0, r.stops.size)
    }

    @Test
    fun `progress ticks every second while playing, through poll jitter`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0)) // → start
        r.at(980, state(pos = 980))
        r.at(2_010, state(pos = 2_010))
        r.at(2_990, state(pos = 2_990))
        assertEquals(3, r.progress.size, "a poll landing a few ms early must not skip a tick")
    }

    @Test
    fun `progress ticks every three seconds while paused`() = runTest {
        val r = Rig()
        r.at(0, state(playing = false, pos = 7_000)) // start + paused
        r.at(1_000, state(playing = false, pos = 7_000))
        r.at(2_000, state(playing = false, pos = 7_000))
        r.at(3_000, state(playing = false, pos = 7_000))
        assertEquals(2, r.progress.size)
        assertTrue(r.progress.all { it.third && it.second == 7_000L })
    }

    @Test
    fun `pause reports immediately and only once`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0))
        r.at(500, state(playing = false, pos = 7_000))
        r.at(1_000, state(playing = false, pos = 7_000))
        assertEquals(listOf(Triple("jf-0", 7_000L, true)), r.progress)
    }

    @Test
    fun `resume reports immediately`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0))
        r.at(1_000, state(playing = false, pos = 7_000))
        r.at(1_500, state(pos = 7_000))
        assertEquals(
            listOf(Triple("jf-0", 7_000L, true), Triple("jf-0", 7_000L, false)),
            r.progress,
        )
    }

    @Test
    fun `seek reports immediately`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0))
        r.at(500, state(pos = 61_000))
        assertEquals(listOf(Triple("jf-0", 61_000L, false)), r.progress)
    }

    @Test
    fun `a short scrub still reads as a seek`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0))
        r.at(300, state(pos = 900)) // 600 ms ahead of the timeline
        assertEquals(listOf(Triple("jf-0", 900L, false)), r.progress)
    }

    @Test
    fun `read jitter is neither a seek nor reported - positions stay on the timeline`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0))
        // True position == wall clock. Each reading lags by MPD's chunk step and a
        // late read time, up to 150 ms — a lower bound, never ahead.
        val lags = listOf(40L, 150L, 0L, 90L, 120L, 10L, 60L, 150L)
        lags.forEachIndexed { i, lag ->
            val t = (i + 1) * 1_000L
            r.at(t, state(pos = t - lag))
        }
        assertEquals(lags.size, r.progress.size, "one tick per second, no extra seek reports")
        r.progress.zip(r.progressAt).forEach { (p, sentAt) ->
            assertEquals(sentAt, p.second, "reported where playback is at send time")
        }
    }

    @Test
    fun `positions are extrapolated to when each report is sent`() = runTest {
        val r = Rig()
        r.postMs = 80 // a slow Jellyfin
        r.at(0, state(index = 0, pos = 150_000))
        r.at(1_000, state(index = 1, pos = 200))
        // stop(jf-0) took 80 ms before start(jf-1) went out
        assertEquals("jf-1" to 280L, r.starts.last())
    }

    @Test
    fun `audio starting late pulls the timeline back within three readings`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0)) // MPD says playing, the output opens 300 ms later
        r.at(1_000, state(pos = 700))
        r.at(2_000, state(pos = 1_700))
        r.at(3_000, state(pos = 2_700))
        assertEquals(2_700L, r.progress.last().second)
    }

    /**
     * MPD moves `elapsed` in 0.25 s steps (bit-perfect 96 kHz, measured on the panel).
     * Polled every [period] ms for five minutes: the worst gap, after the window has
     * filled, between a reported position and the audio when the report went out.
     */
    private suspend fun worstReportError(period: Long): Long {
        val r = Rig()
        val step = 250L
        var t = 0L
        while (t < 300_000) {
            r.at(t, state(pos = (t / step) * step))
            t += period
        }
        return r.progress.zip(r.progressAt).drop(16).maxOf { (p, sentAt) -> kotlin.math.abs(p.second - sentAt) }
    }

    @Test
    fun `polled on POLL_PERIOD_MS, a stepped elapsed is reported smooth`() = runTest {
        val worst = worstReportError(POLL_PERIOD_MS)
        assertTrue(worst <= 40, "each report within ~one phase stride of the audio, was $worst ms")
    }

    @Test
    fun `polled a hair over a second, the same elapsed is a sawtooth`() = runTest {
        // what the panel did: 1 s after the work, ~2.4 ms of it - every reading
        // landed at nearly the same point of MPD's step as the 15 before it
        val worst = worstReportError(1_002)
        assertTrue(worst >= 150, "the 2026-10-19 recording: reports up to 0.22 s behind, was $worst ms")
    }

    @Test
    fun `track change stops the old id and starts the new`() = runTest {
        val r = Rig()
        r.at(0, state(index = 0, pos = 150_000))
        r.at(1_000, state(index = 1, pos = 0))
        assertEquals(listOf("jf-0" to 150_000L), r.stops)
        assertEquals(listOf("jf-0", "jf-1"), r.starts.map { it.first })
    }

    @Test
    fun `stop reports once and nothing repeats`() = runTest {
        val r = Rig()
        r.at(0, state(index = 0, pos = 42_000))
        r.at(1_000, state(stopped = true, playing = false, pos = 0))
        r.at(2_000, state(stopped = true, playing = false))
        assertEquals(listOf("jf-0" to 42_000L), r.stops)
        assertEquals(0, r.progress.size)
    }

    @Test
    fun `a track with no Jellyfin id ends the session`() = runTest {
        val r = Rig()
        r.at(0, state(index = 0, pos = 10_000))
        r.at(1_000, state(index = 1, pos = 0), id = null) // queued by mpc
        assertEquals(listOf("jf-0" to 10_000L), r.stops)
        assertFalse(r.rep.reporting)
    }

    @Test
    fun `end closes the open session once`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 10_000))
        r.now = 5_000
        r.rep.end()
        r.rep.end()
        assertEquals(listOf("jf-0" to 10_000L), r.stops)
    }

    @Test
    fun `null state - MPD unreachable - holds the session, never reports`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 0))
        r.at(1_000, null)
        r.at(30_000, null)
        assertEquals(0, r.stops.size, "a Wi-Fi blip must not end the Jellyfin session")
        r.at(31_000, state(pos = 2_000))
        assertEquals(listOf(Triple("jf-0", 2_000L, false)), r.progress)
    }

    @Test
    fun `start carries the real position, not zero`() = runTest {
        val r = Rig()
        r.at(0, state(pos = 95_000)) // joined a track MPD was already playing
        assertEquals(listOf("jf-0" to 95_000L), r.starts)
    }

    @Test
    fun `a track found paused is reported paused at once`() = runTest {
        val r = Rig()
        r.at(0, state(playing = false, pos = 40_000))
        assertEquals(listOf("jf-0"), r.starts.map { it.first })
        assertEquals(listOf(Triple("jf-0", 40_000L, true)), r.progress)
    }
}

package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.RemoteState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SessionReporter]'s state machine, fed synthetic [RemoteState]s — the load-bearing
 * contract being that HueGhost stops music sync 15 s after a session goes quiet, so
 * the ~3 s progress tick must never lapse while playing, and every transition
 * (start, pause, resume, seek, track change, stop) must fire the right report
 * exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReporterTest {

    private class FakeJellyfin : SessionReporter.Reporter {
        val starts = mutableListOf<String>()
        val startPositions = mutableListOf<Long>()
        val progress = mutableListOf<Triple<String, Long, Boolean>>()
        val stops = mutableListOf<Pair<String, Long>>()

        override suspend fun start(id: String, positionMs: Long) {
            starts.add(id)
            startPositions.add(positionMs)
        }

        override suspend fun progress(id: String, positionMs: Long, paused: Boolean) {
            progress.add(Triple(id, positionMs, paused))
        }

        override suspend fun stop(id: String, positionMs: Long) {
            stops.add(id to positionMs)
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
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 5_000), nowMs = 0)
        assertEquals(listOf("jf-0"), jf.starts)
        assertEquals(0, jf.progress.size)
        assertEquals(0, jf.stops.size)
    }

    @Test
    fun `progress ticks about every 3 s while playing`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 0), nowMs = 0) // → start
        rep.onState(state(pos = 1_000), nowMs = 1_000) // too soon — skip
        rep.onState(state(pos = 2_000), nowMs = 2_000) // still under period — skip
        rep.onState(state(pos = 3_000), nowMs = 3_000) // due — fire
        assertEquals(1, jf.progress.size)
        assertEquals(Triple("jf-0", 3_000L, false), jf.progress[0])
    }

    @Test
    fun `pause reports immediately and only once`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 0), nowMs = 0) // → start
        rep.onState(state(playing = false, pos = 7_000), nowMs = 1_000) // paused → report now
        rep.onState(state(playing = false, pos = 7_000), nowMs = 2_000) // still paused, no tick due
        assertEquals(listOf(Triple("jf-0", 7_000L, true)), jf.progress)
    }

    @Test
    fun `resume reports immediately`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 0), nowMs = 0)
        rep.onState(state(playing = false, pos = 7_000), nowMs = 1_000)
        rep.onState(state(pos = 7_000), nowMs = 2_000) // resumed → report unpaused
        assertEquals(
            listOf(Triple("jf-0", 7_000L, true), Triple("jf-0", 7_000L, false)),
            jf.progress,
        )
    }

    @Test
    fun `seek reports immediately`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 0), nowMs = 0)
        // Position jumped 60 s in 1 s of wall clock — a seek, not playback.
        rep.onState(state(pos = 61_000), nowMs = 1_000)
        assertEquals(listOf(Triple("jf-0", 61_000L, false)), jf.progress)
    }

    @Test
    fun `track change stops the old id and starts the new`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(index = 0, pos = 150_000), nowMs = 0)
        rep.onState(state(index = 1, pos = 0), nowMs = 1_000)
        assertEquals(listOf("jf-0" to 150_000L), jf.stops)
        assertEquals(listOf("jf-0", "jf-1"), jf.starts)
    }

    @Test
    fun `stop reports once and nothing repeats`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(index = 0, pos = 42_000), nowMs = 0)
        rep.onState(state(stopped = true, playing = false, pos = 0), nowMs = 1_000)
        rep.onState(state(stopped = true, playing = false), nowMs = 2_000)
        assertEquals(listOf("jf-0" to 42_000L), jf.stops)
        assertEquals(0, jf.progress.size)
    }

    @Test
    fun `null state - MPD unreachable - holds the session, never reports`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 0), nowMs = 0)
        rep.onState(null, nowMs = 1_000)
        rep.onState(null, nowMs = 30_000)
        assertEquals(0, jf.stops.size, "a Wi-Fi blip must not end the Jellyfin session")
        rep.onState(state(pos = 2_000), nowMs = 31_000)
        assertEquals(listOf(Triple("jf-0", 2_000L, false)), jf.progress)
    }

    @Test
    fun `start carries the real position, not zero`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(pos = 95_000), nowMs = 0) // joined a track MPD was already playing
        assertEquals(listOf(95_000L), jf.startPositions)
    }

    @Test
    fun `a track found paused is reported paused at once`() = runTest {
        val jf = FakeJellyfin()
        val rep = SessionReporter(jf, trackIdAt = { "jf-$it" })
        rep.onState(state(playing = false, pos = 40_000), nowMs = 0)
        assertEquals(listOf("jf-0"), jf.starts)
        assertEquals(listOf(Triple("jf-0", 40_000L, true)), jf.progress)
    }
}
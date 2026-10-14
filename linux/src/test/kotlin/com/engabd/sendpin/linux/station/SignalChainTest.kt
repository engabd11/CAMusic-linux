package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalChainTest {

    private val flac9624 = StreamQuality(codec = "FLAC", sampleRateHz = 96_000, bitDepth = 24)
    private val out9624 = RemoteAudioFormat(96_000, 24, 2, 4_000)

    @Test
    fun `a lossless file played untouched to a fixed output is bit-perfect`() {
        val c = signalChain(flac9624, out9624, "Topping D90SE", gainMode = "off", volume = null)
        assertTrue(c.bitPerfect)
        assertEquals(null, c.why)
        assertEquals(listOf("Source", "Decode", "Level", "Volume", "Output"), c.stages.map { it.name })
        assertTrue(c.stages.all { it.state == StageState.LIT })
    }

    @Test
    fun `loudness gain breaks it and is named`() {
        val c = signalChain(flac9624, out9624, "DAC", gainMode = "album", volume = null)
        assertFalse(c.bitPerfect)
        assertEquals("Loudness gain on", c.why)
        assertEquals(StageState.ALTERED, c.stages.first { it.name == "Level" }.state)
    }

    @Test
    fun `a software volume below full breaks it, full volume does not`() {
        assertFalse(signalChain(flac9624, out9624, "DAC", "off", volume = 0.55f).bitPerfect)
        assertEquals("55%", signalChain(flac9624, out9624, "DAC", "off", volume = 0.55f).stages[3].value)
        assertTrue(signalChain(flac9624, out9624, "DAC", "off", volume = 1f).bitPerfect)
    }

    @Test
    fun `a different decode rate means the audio was resampled`() {
        val c = signalChain(flac9624, RemoteAudioFormat(48_000, 24, 2, 0), "DAC", "off", null)
        assertFalse(c.bitPerfect)
        assertEquals("Resampled", c.why)
        assertEquals(StageState.ALTERED, c.stages[1].state)
    }

    @Test
    fun `a lossy source is never bit-perfect`() {
        val mp3 = StreamQuality(codec = "MP3", sampleRateHz = 44_100, bitrateKbps = 320)
        val c = signalChain(mp3, RemoteAudioFormat(44_100, 16, 2, 320), "DAC", "off", null)
        assertFalse(c.bitPerfect)
        assertEquals("Lossy source", c.why)
    }

    @Test
    fun `unknown stages never light the lamp`() {
        assertFalse(signalChain(null, null, null, null, null).bitPerfect)
        assertFalse(signalChain(flac9624, out9624, null, "off", null).bitPerfect, "no output name")
        assertFalse(signalChain(flac9624, null, "DAC", "off", null).bitPerfect, "no decode reading")
        assertFalse(signalChain(flac9624, out9624, "DAC", null, null).bitPerfect, "gain not read")
    }

    @Test
    fun `a float pipeline that reports no depth is judged on rate alone`() {
        val c = signalChain(flac9624, RemoteAudioFormat(96_000, 0, 2, 0), "DAC", "off", null)
        assertTrue(c.bitPerfect)
        assertEquals("96 kHz", c.stages[1].value)
    }
}

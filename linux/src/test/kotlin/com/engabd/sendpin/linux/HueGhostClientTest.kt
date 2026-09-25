package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HueGhostClient]'s pure half: the /set wire body and status parsing. Network
 * behaviour (on/off/toggle) is a thin OkHttp round-trip, exercised live against
 * the real daemon in the end-to-end check.
 */
class HueGhostClientTest {

    @Test
    fun `set body carries only what was asked`() {
        assertEquals("""{"mode":"music"}""", HueGhostClient.setBody(mode = "music"))
        assertEquals("""{"brightness":62}""", HueGhostClient.setBody(brightness = 62))
        assertEquals("""{"offset_delta":0.25}""", HueGhostClient.setBody(offsetDelta = 0.25))
        assertEquals(
            """{"mode":"video","intensity":"moderate"}""",
            HueGhostClient.setBody(mode = "video", intensity = "moderate"),
        )
        assertEquals(
            """{"binding":{"key":"camusic-linux","enabled":false}}""",
            HueGhostClient.setBody(bindingEnabled = "camusic-linux" to false),
        )
        assertEquals("{}", HueGhostClient.setBody())
    }

    @Test
    fun `parse reads engine state`() {
        val s = HueGhostClient.parseStatus(
            """{"version":"2.6.0","engine":{"state":"syncing","mode":"music","intensity":"high","bri":56}}""",
        )
        assertTrue(s!!.running)
        assertEquals("music", s.mode)
        assertEquals("high", s.intensity)
        assertEquals(56, s.brightness)
        assertEquals("2.6.0", s.version)
    }

    @Test
    fun `parse survives idle and missing engine`() {
        val s1 = HueGhostClient.parseStatus("""{"engine":{"state":"idle","mode":"video"}}""")
        assertFalse(s1!!.running)
        assertEquals("video", s1.mode)
        val s2 = HueGhostClient.parseStatus("{}")
        assertFalse(s2!!.running)
        assertEquals(null, s2.version)
        assertEquals(null, HueGhostClient.parseStatus(""))
        assertEquals(null, HueGhostClient.parseStatus("not json"))
    }
}
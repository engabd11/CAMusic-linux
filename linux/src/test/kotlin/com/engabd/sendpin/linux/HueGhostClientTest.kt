package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HueGhostClient]'s pure half: the /set wire body (v2.8 keys) and status
 * parsing (v2.8 top-level shape). Network behaviour (on/off/toggle/set) is a
 * thin OkHttp round-trip, exercised live against the real daemon separately.
 */
class HueGhostClientTest {

    @Test
    fun `set body carries only what was asked`() {
        assertEquals("""{"mode":"music"}""", HueGhostClient.setBody(mode = "music"))
        assertEquals("""{"offset_s":0.25}""", HueGhostClient.setBody(offsetS = 0.25))
        assertEquals("""{"enabled":true}""", HueGhostClient.setBody(enabled = true))
        assertEquals(
            """{"mode":"video","intensity":"moderate"}""",
            HueGhostClient.setBody(mode = "video", intensity = "moderate"),
        )
        assertEquals("{}", HueGhostClient.setBody())
    }

    @Test
    fun `parse reads v2_8 top-level status`() {
        val s = HueGhostClient.parseStatus(
            """
            {"version":"2.8.1","enabled":true,"state":"syncing",
             "offset_s":0.05,
             "follow":{"device":"Apple TV","seen":true,"playing":true,"item":"Mirage"},
             "intensity":"high","mode":"music"}
            """.trimIndent(),
        )
        assertTrue(s!!.running)
        assertEquals("music", s.mode)
        assertEquals("high", s.intensity)
        assertEquals("Mirage", s.followItem)
        assertEquals(0.05, s.offsetS!!)
        assertEquals("2.8.1", s.version)
    }

    @Test
    fun `parse survives idle, missing follow and junk`() {
        val s1 = HueGhostClient.parseStatus(
            """{"version":"2.8.1","enabled":true,"state":"idle","offset_s":0.0}""",
        )
        assertFalse(s1!!.running)
        assertEquals(null, s1.followItem)
        assertEquals(0.0, s1.offsetS!!)
        val s2 = HueGhostClient.parseStatus("{}")
        assertFalse(s2!!.running)
        assertEquals(null, HueGhostClient.parseStatus(""))
        assertEquals(null, HueGhostClient.parseStatus("not json"))
    }

    @Test
    fun startStopFollowsEnabledNotTheMomentaryState() {
        val idleButOn = HueGhostClient.parseStatus("""{"enabled": true, "state": "idle"}""")!!
        kotlin.test.assertEquals(true, idleButOn.on)
        kotlin.test.assertEquals(false, idleButOn.running)
        val off = HueGhostClient.parseStatus("""{"enabled": false, "state": "idle"}""")!!
        kotlin.test.assertEquals(false, off.on)
        // An older HueGhost with no `enabled` falls back to whether it is syncing.
        kotlin.test.assertEquals(true, HueGhostClient.parseStatus("""{"state": "syncing"}""")!!.on)
    }

    @Test
    fun startAndStopHitTheRealRoutes() {
        val c = HueGhostClient("http://hg.lan:8787/", "t")
        kotlin.test.assertEquals("http://hg.lan:8787/api/off", c.url("/off"))
        kotlin.test.assertEquals("http://hg.lan:8787/api/on", c.url("/on"))
    }

    @Test
    fun musicTimingSetsTheMusicOffset() {
        val st = HueGhostClient.parseStatus("""{"enabled": true, "state": "syncing", "mode": "music", "offset_s": 0.1, "music": {"offset_s": 0.1}}""")!!
        kotlin.test.assertEquals(true, st.musicTiming)
        kotlin.test.assertEquals("{\"music_offset_s\":0.25}", HueGhostClient.setBody(musicOffsetS = 0.25))
        kotlin.test.assertEquals(false, HueGhostClient.parseStatus("""{"mode": "video", "music": {}}""")!!.musicTiming)
    }
}

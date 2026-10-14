package com.engabd.sendpin.linux.station

import com.engabd.sendpin.linux.LinuxSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the v1.2 station follows: every addition is an option, and every option is off
 * until it is switched on — an upgrade changes nothing by itself.
 */
class StationSettingsTest {

    private fun settings() = LinuxSettings(Files.createTempDirectory("camusic-station").resolve("settings.json"))

    @Test
    fun `every new feature defaults to off`() = runTest {
        val s = settings()
        assertFalse(s.tonearm.first())
        assertFalse(s.tonearmSeek.first())
        assertFalse(s.queueDeck.first())
        assertFalse(s.postcards.first())
        assertFalse(s.sleeveFlip.first())
        assertFalse(s.touchGestures.first())
        assertFalse(s.rowMenus.first())
        assertFalse(s.crateMode.first())
        assertFalse(s.signalPanel.first())
        assertFalse(s.sleepTimer.first())
        assertFalse(s.bigReadouts.first())
        assertFalse(s.statusBanner.first())
        assertEquals("off", s.deepIdle.first())
        assertEquals("classic", s.listeningLayout.first(), "the listening layout is still the v1.1 default")
    }

    @Test
    fun `the options round-trip through the file`() = runTest {
        val dir = Files.createTempDirectory("camusic-station")
        val path = dir.resolve("settings.json")
        val s = LinuxSettings(path)
        s.setTonearm(true); s.setQueueDeck(true); s.setRowMenus(true); s.setDeepIdle("screen_off"); s.setDeepIdleMin(30)
        val again = LinuxSettings(path)
        assertTrue(again.tonearm.first())
        assertTrue(again.queueDeck.first())
        assertTrue(again.rowMenus.first())
        assertFalse(again.postcards.first(), "the rest stay off")
        assertEquals("screen_off", again.deepIdle.first())
        assertEquals(30, again.deepIdleMin.first())
    }
}

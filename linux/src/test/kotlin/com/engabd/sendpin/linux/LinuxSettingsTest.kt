package com.engabd.sendpin.linux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip and default tests for [LinuxSettings] — the desktop settings store.
 * Runs against a temp directory; nothing touches the user's real ~/.config.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxSettingsTest {

    private fun tempSettings(): Pair<LinuxSettings, Path> {
        val dir = Files.createTempDirectory("camusic-test")
        val path = dir.resolve("settings.json")
        return LinuxSettings(path) to path
    }

    @Test
    fun `defaults emit before anything is written`() = runTest {
        val (settings, _) = tempSettings()
        assertEquals("127.0.0.1:6600", settings.mpdAddress.first())
        assertEquals("", settings.jfUrl.first())
        assertEquals("landscape", settings.layoutMode.first())
        assertEquals("camusic-linux", settings.deviceId.first())
    }

    @Test
    fun `set persists to disk and a fresh instance reads it back`() = runTest {
        val (settings, path) = tempSettings()
        settings.setMpdAddress("192.168.0.202:6600")
        settings.setJfUrl("http://192.168.0.138:8096")
        settings.setLayoutMode("portrait")

        assertTrue(Files.exists(path), "settings.json must exist after a set")
        val reloaded = LinuxSettings(path)
        assertEquals("192.168.0.202:6600", reloaded.mpdAddress.first())
        assertEquals("http://192.168.0.138:8096", reloaded.jfUrl.first())
        assertEquals("portrait", reloaded.layoutMode.first())
    }

    @Test
    fun `flow emits only on change of that key`() = runTest {
        val (settings, _) = tempSettings()
        val seen = mutableListOf<String>()
        val collector = launch { settings.mpdAddress.collect { seen.add(it) } }
        runCurrent() // let the collector subscribe and take the initial value
        settings.setJfUrl("http://x") // unrelated write must not re-emit mpdAddress
        runCurrent()
        settings.setMpdAddress("a:1")
        runCurrent()
        settings.setMpdAddress("a:1") // same value again — distinctUntilChanged drops it
        runCurrent()
        collector.cancel()
        assertEquals(listOf("127.0.0.1:6600", "a:1"), seen)
    }
}
package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinPathsTest {

    @Test
    fun `auth header matches the shared client's shape`() {
        val h = JellyfinPaths.authHeader("camusic-linux", "tok123")
        assertEquals(
            "MediaBrowser Client=\"CAMusic\", Device=\"Linux\", DeviceId=\"camusic-linux\", Version=\"1.0\", Token=\"tok123\"",
            h,
        )
    }

    @Test
    fun `auth header omits token when empty`() {
        val h = JellyfinPaths.authHeader("d", "")
        assertTrue(!h.contains("Token"), "a blank token must not emit a Token field")
    }

    @Test
    fun `items url carries Ids and Fields`() {
        val url = JellyfinPaths.itemsUrl("http://192.168.0.138:8096", "a,b")
        assertTrue(url.contains("/Items?Ids=a%2Cb"), "csv must be encoded: $url")
        assertTrue(url.contains("Fields=Path%2CMediaSources"), "fields request must be encoded: $url")
    }

    @Test
    fun `parse reads Path and falls back to MediaSources`() {
        val body = """
            {"Items":[
              {"Id":"a","Path":"/mnt/music/x.flac"},
              {"Id":"b","MediaSources":[{"Path":"/mnt/music/y.flac"}]},
              {"Id":"c"}
            ]}
        """.trimIndent()
        val map = JellyfinPaths.parseItems(body)
        assertEquals("/mnt/music/x.flac", map["a"])
        assertEquals("/mnt/music/y.flac", map["b"])
        assertTrue(!map.containsKey("c"), "an item with no path must not appear")
    }
}
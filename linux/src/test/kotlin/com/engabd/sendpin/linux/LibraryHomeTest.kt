package com.engabd.sendpin.linux

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Library tab tapped again: back to the library's home page, from however deep. */
class LibraryHomeTest {

    private fun item(id: String) = MaItem(id, "t", id, null, "album", null, null, null)

    @Test
    fun `home resets a deep stack to the home page`() {
        val s = LibraryState()
        s.push(LibraryRoute.Browse(LibraryCategory.ARTISTS))
        s.push(LibraryRoute.Artist(item("ar1")))
        s.push(LibraryRoute.Album(item("al1")))
        val before = s.homeSignal
        s.home()
        assertEquals(listOf<LibraryRoute>(LibraryRoute.Home), s.stack)
        assertEquals(before + 1, s.homeSignal)
        // coming back from deeper: Home jumps to its top rather than scrolling there
        assertFalse(s.homeSmooth)
    }

    @Test
    fun `home on the home page scrolls it back up smoothly`() {
        val s = LibraryState()
        s.home()
        assertEquals(listOf<LibraryRoute>(LibraryRoute.Home), s.stack)
        assertTrue(s.homeSmooth)
    }
}

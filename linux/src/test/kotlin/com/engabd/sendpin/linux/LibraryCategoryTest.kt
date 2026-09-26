package com.engabd.sendpin.linux

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The library's navigation and paging decisions: the category cards, the back stack
 * that replaced the old one-level-deep detail flags, the paged grid's load trigger, and
 * which album Home features.
 */
class LibraryCategoryTest {

    private fun item(id: String) = MaItem(id, "t", id, null, "album", null, null, null)

    @Test
    fun `every category has a distinct label`() {
        val labels = LibraryCategory.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate category labels: $labels")
    }

    @Test
    fun `Home is the landing page and the root of the stack`() {
        val state = LibraryState()
        assertEquals(listOf<LibraryRoute>(LibraryRoute.Home), state.stack)
    }

    @Test
    fun `routes stack and pop in order, so Back from an album returns to its artist`() {
        val artist = LibraryRoute.Artist(item("ar"))
        val album = LibraryRoute.Album(item("al"))
        val stack = listOf<LibraryRoute>(LibraryRoute.Home).pushed(artist).pushed(album)
        assertEquals(listOf(LibraryRoute.Home, artist, album), stack)
        assertEquals(listOf(LibraryRoute.Home, artist), stack.popped())
    }

    @Test
    fun `the root never pops`() {
        val root = listOf<LibraryRoute>(LibraryRoute.Home)
        assertEquals(root, root.popped())
    }

    @Test
    fun `a double tap does not stack the same page twice`() {
        val album = LibraryRoute.Album(item("al"))
        val once = listOf<LibraryRoute>(LibraryRoute.Home).pushed(album)
        assertSame(once, once.pushed(LibraryRoute.Album(item("al"))))
    }

    @Test
    fun `route keys are distinct per item, for the saved scroll positions`() {
        assertFalse(LibraryRoute.Album(item("a")).key == LibraryRoute.Album(item("b")).key)
        assertFalse(LibraryRoute.Album(item("a")).key == LibraryRoute.Artist(item("a")).key)
    }

    @Test
    fun `the next page loads near the end, and never once the server ran out`() {
        assertFalse(shouldLoadMore(lastVisible = 10, loaded = 120, exhausted = false))
        assertTrue(shouldLoadMore(lastVisible = 110, loaded = 120, exhausted = false))
        assertFalse(shouldLoadMore(lastVisible = 119, loaded = 120, exhausted = true))
        assertFalse(shouldLoadMore(lastVisible = 0, loaded = 0, exhausted = false))
    }

    @Test
    fun `Home features the last album played, else the newest`() {
        val played = item("played")
        val added = item("added")
        assertEquals(played, HomeShelves(recentlyPlayed = listOf(played), recentlyAdded = listOf(added)).featured)
        assertEquals(added, HomeShelves(recentlyAdded = listOf(added)).featured)
        assertNull(HomeShelves().featured)
    }

    @Test
    fun `the greeting follows the clock`() {
        assertEquals("Good morning", greetingFor(8))
        assertEquals("Good afternoon", greetingFor(14))
        assertEquals("Good evening", greetingFor(20))
        assertEquals("Good evening", greetingFor(2))
    }
}

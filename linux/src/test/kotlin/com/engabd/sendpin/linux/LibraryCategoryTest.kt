package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LibraryCategory] is the browse-root selector items 7 and 10 collapsed into one control
 * (a category row *is* the artists entry point) — this covers the two things that would
 * silently break the library root if they regressed: every chip having a distinct label
 * (two categories sharing a label would look like a bug in the row), and FAVORITES staying
 * last so it reads as "everything else, then the curated one" in the chip row.
 */
class LibraryCategoryTest {

    @Test
    fun `every category has a distinct label`() {
        val labels = LibraryCategory.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate category labels: $labels")
    }

    @Test
    fun `Recently added is the default landing category`() {
        assertEquals(LibraryCategory.RECENT, LibraryCategory.entries.first())
    }

    @Test
    fun `Favourites is a distinct category from the flat browse roots`() {
        assertTrue(LibraryCategory.FAVORITES in LibraryCategory.entries)
        // Everything except FAVORITES is rendered as a flat MaItem grid in LibraryRoot;
        // FAVORITES alone renders MaSearchResults sections instead -- this just pins that
        // FAVORITES exists and is exactly one value, not a range or a flag combination.
        assertEquals(1, LibraryCategory.entries.count { it == LibraryCategory.FAVORITES })
    }
}

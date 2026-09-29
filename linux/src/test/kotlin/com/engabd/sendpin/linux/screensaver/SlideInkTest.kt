package com.engabd.sendpin.linux.screensaver

import androidx.compose.ui.graphics.Color
import com.engabd.sendpin.ui.design.AlbumPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [albumInk]: the words take the album colour that stands apart from the others,
 * exactly as extracted — and legibility comes from dimming the bleed of the rest,
 * never from changing the words' colour.
 */
class SlideInkTest {

    private val blue1 = Color(0xFF5070E0)
    private val blue2 = Color(0xFF4868C8)
    private val blue3 = Color(0xFF6080F0)
    private val orange = Color(0xFFE08030)

    private fun readsOn(look: AlbumInk) = contrast(look.ink, bleedBackground(look.bleed, look.bleedLevel))

    @Test
    fun `the odd colour out sets the words, exactly as extracted`() {
        val look = albumInk(AlbumPalette(accent = blue1, swatches = listOf(blue1, blue2, orange, blue3)))
        assertEquals(orange, look.ink)
        assertFalse(orange in look.bleed)
        assertEquals(3, look.bleed.size)
    }

    @Test
    fun `the ink is always one of the palette's own colours`() {
        val palettes = listOf(
            listOf(blue1, blue2, orange, blue3),
            listOf(Color(0xFFE05060), Color(0xFFB04050), Color(0xFF5070D0)),
            listOf(Color(0xFFE0D040), Color(0xFFF0E060), Color(0xFFD0C030)),
        )
        for (sw in palettes) {
            val look = albumInk(AlbumPalette(accent = sw[0], swatches = sw))
            assertTrue(look.ink in sw, "ink ${look.ink} not in $sw")
        }
    }

    @Test
    fun `a bright bleed is dimmed until the exact ink reads`() {
        // pale yellows behind a mid-tone yellow: the bleed must give way, not the words
        val sw = listOf(Color(0xFFB0A020), Color(0xFFF8F0A0), Color(0xFFF0E890))
        val look = albumInk(AlbumPalette(accent = sw[0], swatches = sw))
        assertTrue(look.ink in sw)
        assertTrue(look.bleedLevel < 1f, "bleed dimmed")
        assertTrue(readsOn(look) >= MIN_CONTRAST || look.bleedLevel <= 0.35f)
    }

    @Test
    fun `a dark bleed stays at full strength`() {
        val sw = listOf(Color(0xFFF0C040), Color(0xFF402060), Color(0xFF203050))
        val look = albumInk(AlbumPalette(accent = sw[0], swatches = sw))
        assertEquals(1f, look.bleedLevel)
        assertTrue(readsOn(look) >= MIN_CONTRAST)
    }

    @Test
    fun `a colour too dark to read never sets the words when another can`() {
        val navy = Color(0xFF101850)
        val look = albumInk(AlbumPalette(accent = navy, swatches = listOf(navy, Color(0xFFE0A030))))
        assertEquals(Color(0xFFE0A030), look.ink)
    }

    @Test
    fun `the lead wins a tie`() {
        val a = Color(0xFFE05050)
        val b = Color(0xFF50E050)
        val look = albumInk(AlbumPalette(accent = a, swatches = listOf(a, b)))
        assertEquals(a, look.ink)
    }
}

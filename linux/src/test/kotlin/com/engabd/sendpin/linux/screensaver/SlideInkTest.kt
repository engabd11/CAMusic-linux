package com.engabd.sendpin.linux.screensaver

import androidx.compose.ui.graphics.Color
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.oklabToOklch
import com.engabd.sendpin.ui.design.rgbToOklab
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [albumInk]: the words take the album colour that stands apart from the others, made
 * legible against the bleed of the rest — and the bleed never contains it.
 */
class SlideInkTest {

    private fun hue(c: Color) = oklabToOklch(rgbToOklab(c.red, c.green, c.blue))[2]
    private fun hueGap(a: Float, b: Float) = abs(a - b).let { minOf(it, 360f - it) }

    private val blue1 = Color(0xFF3050C0)
    private val blue2 = Color(0xFF2848A0)
    private val blue3 = Color(0xFF4060D8)
    private val orange = Color(0xFFD07020)

    @Test
    fun `the odd colour out sets the words`() {
        val look = albumInk(AlbumPalette(accent = blue1, swatches = listOf(blue1, blue2, orange, blue3)))
        assertTrue(hueGap(hue(look.ink), hue(orange)) < 3f, "hue kept from the orange swatch")
        assertFalse(orange in look.bleed)
        assertEquals(3, look.bleed.size)
    }

    @Test
    fun `words clear the contrast bar against the bleed behind them`() {
        val palettes = listOf(
            listOf(blue1, blue2, orange, blue3),
            listOf(Color(0xFF8A1020), Color(0xFF601018), Color(0xFF203080)), // dark reds, a navy
            listOf(Color(0xFFE0D040), Color(0xFFF0E060), Color(0xFFD0C030)), // bright yellows
        )
        for (sw in palettes) {
            val look = albumInk(AlbumPalette(accent = sw[0], swatches = sw))
            val c = contrast(look.ink, bleedBackground(look.bleed))
            assertTrue(c >= MIN_CONTRAST, "contrast $c for $sw")
        }
    }

    @Test
    fun `a dark ink is lifted, not swapped - the hue stays`() {
        val navy = Color(0xFF101850)
        val look = albumInk(AlbumPalette(accent = navy, swatches = listOf(navy)))
        assertTrue(hueGap(hue(look.ink), hue(navy)) < 3f)
        assertTrue(contrast(look.ink, bleedBackground(look.bleed)) >= MIN_CONTRAST)
        assertEquals(listOf(navy), look.bleed)
    }

    @Test
    fun `the lead wins a tie`() {
        val a = Color(0xFFC03030)
        val look = albumInk(AlbumPalette(accent = a, swatches = listOf(a, Color(0xFF30C030))))
        assertTrue(hueGap(hue(look.ink), hue(a)) < 3f)
    }
}

package com.engabd.sendpin.ui.design

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The colour moods: what each grade does to a palette, and that it stays in sRGB. */
class ColourMoodTest {

    private val palette = AlbumPalette(
        accent = Color(0.85f, 0.25f, 0.55f), // magenta-ish
        swatches = listOf(
            Color(0.20f, 0.55f, 0.85f), // blue
            Color(0.30f, 0.75f, 0.35f), // green
            Color(0.5f, 0.5f, 0.5f),    // grey
        ),
    )

    private fun lchOf(c: Color) = oklabToOklch(rgbToOklab(c.red, c.green, c.blue))
    private fun all(p: AlbumPalette) = listOf(p.accent) + p.swatches

    @Test
    fun `natural hands the palette back untouched`() {
        assertSame(palette, palette.graded(ColourMood.NATURAL))
    }

    @Test
    fun `warm and cool pull every colour toward their hue`() {
        for ((mood, hue) in listOf(ColourMood.WARM to 62f, ColourMood.COOL to 235f)) {
            val graded = palette.graded(mood)
            for ((before, after) in all(palette).zip(all(graded))) {
                val b = lchOf(before)
                val a = lchOf(after)
                if (b[1] < 0.03f) {
                    // a grey is tinted, not left grey
                    assertTrue(a[1] > b[1], "$mood tints grey")
                } else {
                    assertTrue(hueGap(a[2], hue) < hueGap(b[2], hue), "$mood moves ${b[2]} toward $hue, got ${a[2]}")
                }
            }
        }
    }

    @Test
    fun `mono sets the room in the accent's one hue`() {
        val graded = palette.graded(ColourMood.MONO)
        val lead = lchOf(graded.accent)[2]
        for (c in graded.swatches) assertTrue(hueGap(lchOf(c)[2], lead) < 4f, "swatch hue ${lchOf(c)[2]} vs $lead")
    }

    @Test
    fun `neon raises chroma and keeps every colour in gamut`() {
        val graded = palette.graded(ColourMood.NEON)
        for ((before, after) in all(palette).zip(all(graded))) {
            assertTrue(lchOf(after)[1] >= lchOf(before)[1] - 1e-3f)
        }
        for (mood in ColourMood.entries) for (c in all(palette.graded(mood))) {
            for (ch in listOf(c.red, c.green, c.blue)) assertTrue(ch in 0f..1f, "$mood channel $ch")
        }
    }

    @Test
    fun `hues pull around the short side of the wheel`() {
        assertEquals(10f, pullHue(350f, 30f, 0.5f), 0.01f)
        assertEquals(340f, pullHue(20f, 300f, 0.5f), 0.01f)
        assertEquals(62f, pullHue(62f, 62f, 0.6f), 0.01f)
    }
}

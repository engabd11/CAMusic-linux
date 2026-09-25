package com.engabd.sendpin.linux

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shell's layout choice — Settings-driven, never auto-detected (the plan). */
class LayoutModeTest {

    @Test
    fun `parses both keys and defaults to landscape`() {
        assertEquals(LayoutMode.LANDSCAPE, LayoutMode.from("landscape"))
        assertEquals(LayoutMode.LANDSCAPE, LayoutMode.from("LANDSCAPE"))
        assertEquals(LayoutMode.PORTRAIT, LayoutMode.from("portrait"))
        assertEquals(LayoutMode.PORTRAIT, LayoutMode.from("PORTRAIT"))
        assertEquals(LayoutMode.LANDSCAPE, LayoutMode.from(null))
        assertEquals(LayoutMode.LANDSCAPE, LayoutMode.from("bogus"))
    }
}
package com.engabd.sendpin.linux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure half of [OnScreenKeyboard]: what each key does to the query string. The layout
 * itself needs a pointer stream to exercise for real (unverified here, per the plan's own
 * limits on this machine) — this covers the string transitions it drives.
 */
class OnScreenKeyboardTest {

    @Test
    fun `a letter key appends to the query`() {
        assertEquals("a", applyKey("", OnScreenKeyboardKey.Char('a')))
        assertEquals("ab", applyKey("a", OnScreenKeyboardKey.Char('b')))
    }

    @Test
    fun `space appends like any other key -- it matters for artist names`() {
        assertEquals("the ", applyKey("the", OnScreenKeyboardKey.Char(' ')))
        assertEquals("the b", applyKey("the ", OnScreenKeyboardKey.Char('b')))
    }

    @Test
    fun `backspace drops the last character`() {
        assertEquals("ab", applyKey("abc", OnScreenKeyboardKey.Backspace))
        assertEquals("", applyKey("a", OnScreenKeyboardKey.Backspace))
    }

    @Test
    fun `backspace on an empty query is a no-op, not a crash`() {
        assertEquals("", applyKey("", OnScreenKeyboardKey.Backspace))
    }

    @Test
    fun `clear empties the query regardless of its contents`() {
        assertEquals("", applyKey("pink floyd", OnScreenKeyboardKey.Clear))
        assertEquals("", applyKey("", OnScreenKeyboardKey.Clear))
    }
}

package com.engabd.sendpin.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * An in-app QWERTY keyboard, drawn inside our own window instead of shelling out to a
 * system on-screen keyboard.
 *
 * Two independent reasons the external-process version (`ProcessBuilder("squeekboard")`,
 * the previous shape of this feature) could never work on this panel, both fatal:
 *
 * 1. `squeekboard` is Wayland-only — it drives `zwp_input_method_v2`, a Wayland input-method
 *    protocol, and this panel runs a bare X11 server (see `Main.kt`'s own comment on the
 *    window setup). It cannot function here no matter what's on PATH.
 * 2. There is no window manager. Even X11/GTK's `onboard` fails twice over: nothing exists
 *    to stack its window above our `undecorated`, fullscreen-at-(0,0) one, and with no WM
 *    the X input focus model defaults to `PointerRoot` — focus follows the pointer — so
 *    XTEST-synthesised keystrokes land in whatever window the pointer is over, which while
 *    tapping the keyboard is the keyboard itself, never our `TextField`.
 *
 * Drawing the keys ourselves sidesteps both: correct stacking by construction (it's a
 * Composable in the same window), no XTEST, no input-method protocol, and it can be themed
 * with the same tokens as everything else. No `FocusRequester` or soft-input API is needed
 * either — [query] in `LibraryPane` is already hoisted as a plain `String`, so each key
 * drives it directly via [onKey]/[onBackspace]/[onClear] rather than fighting the
 * `TextField` for focus.
 *
 * Lowercase only, no shift key — this drives a library search box, not general text entry,
 * and a shift row is complexity this keyboard doesn't need.
 */
@Composable
fun OnScreenKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(16.dp), Glass, border = Hairline)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyRow(KEYBOARD_DIGIT_ROW.map { it.toString() }) { onKey(it[0]) }
        KEYBOARD_LETTER_ROWS.forEach { row ->
            KeyRow(row.map { it.toString() }) { onKey(it[0]) }
        }
        // Bottom row: space gets most of the width (it's what artist/album names need
        // most), the rest are fixed-weight utility keys.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Key("clear", weight = 1.5f, onClick = onClear)
            Key("space", weight = 4f, onClick = { onKey(' ') })
            Key("⌫", weight = 1.5f, onClick = onBackspace)
            Key("done", weight = 1.5f, onClick = onDone)
        }
    }
}

@Composable
private fun KeyRow(keys: List<String>, onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { k -> Key(k, weight = 1f, onClick = { onKey(k) }) }
    }
}

/** One key — 48dp minimum height, matching the touch-target floor set elsewhere on the panel. */
@Composable
private fun RowScope.Key(label: String, weight: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(weight)
            .heightIn(min = 48.dp)
            .glassSurface(RoundedCornerShape(10.dp), Glass, border = Hairline)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = TextPrimary,
            fontFamily = AppFont,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private const val KEYBOARD_DIGIT_ROW = "1234567890"
private val KEYBOARD_LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/**
 * The pure half of the keyboard: what a key does to the current query string. Kept separate
 * from the Composables above so `kotlin.test` can cover it directly — the layout needs a
 * pointer stream to exercise for real, this doesn't.
 */
sealed interface OnScreenKeyboardKey {
    data class Char(val c: kotlin.Char) : OnScreenKeyboardKey
    object Backspace : OnScreenKeyboardKey
    object Clear : OnScreenKeyboardKey
}

/**
 * Applies one [OnScreenKeyboardKey] to [current], returning the new query string.
 * Backspace on an empty string is a no-op (not an exception, not a negative-length crash) —
 * this is the only "invalid" input the keyboard can produce, since every other key either
 * appends or clears.
 */
internal fun applyKey(current: String, key: OnScreenKeyboardKey): String = when (key) {
    is OnScreenKeyboardKey.Char -> current + key.c
    is OnScreenKeyboardKey.Backspace -> if (current.isEmpty()) current else current.dropLast(1)
    is OnScreenKeyboardKey.Clear -> ""
}

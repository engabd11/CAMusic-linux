package com.engabd.sendpin.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.inkOn

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
 * Search needs only lowercase letters and digits. [symbols] adds what the Settings
 * fields need on top — a shift key (tokens can be mixed-case) and a row of the
 * punctuation URLs and paths are made of — so a server address can be typed on the
 * panel itself rather than over SSH into the settings file.
 */
@Composable
fun OnScreenKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    symbols: Boolean = false,
) {
    var shift by remember { mutableStateOf(false) }
    val type: (String) -> Unit = { k -> onKey(if (shift) k[0].uppercaseChar() else k[0]) }
    Column(
        modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(24.dp), inkOn(0.06f), border = Hairline)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyRow(KEYBOARD_DIGIT_ROW.map { it.toString() }) { onKey(it[0]) }
        if (symbols) KeyRow(KEYBOARD_SYMBOL_ROW.map { it.toString() }) { onKey(it[0]) }
        KEYBOARD_LETTER_ROWS.forEach { row ->
            KeyRow(row.map { if (shift) it.uppercase() else it.toString() }) { type(it) }
        }
        // Bottom row: space gets most of the width (it's what artist/album names need
        // most), the rest are fixed-weight utility keys; done is the accent key.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (symbols) Key("⇧", weight = 1.2f, lit = shift, onClick = { shift = !shift })
            Key("clear", weight = 1.5f, onClick = onClear)
            Key("space", weight = 4f, onClick = { onKey(' ') })
            Key("⌫", weight = 1.5f, onClick = onBackspace)
            Key("done", weight = 1.7f, primary = true, onClick = onDone)
        }
    }
}

@Composable
private fun KeyRow(keys: List<String>, onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { k -> Key(k, weight = 1f, onClick = { onKey(k) }) }
    }
}

/**
 * One key — 58dp tall, above the panel's touch floor: a keyboard is typed with a
 * fingertip at arm's length, and a missed key costs a backspace.
 */
@Composable
private fun RowScope.Key(
    label: String,
    weight: Float,
    primary: Boolean = false,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = rememberAccent()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .weight(weight)
            .height(58.dp)
            .then(
                if (primary || lit) Modifier.clip(shape).background(if (primary) accent else accent.copy(alpha = 0.3f))
                else Modifier.clip(shape).background(inkOn(0.09f))
            )
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (primary) Ink else TextPrimary,
            fontFamily = AppFont,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (label.length == 1) 22.sp else 17.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private const val KEYBOARD_DIGIT_ROW = "1234567890"
private const val KEYBOARD_SYMBOL_ROW = ".:/-_@?=&~"
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

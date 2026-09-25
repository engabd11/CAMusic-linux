package com.engabd.sendpin.linux

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.engabd.sendpin.ui.theme.AccentChoice
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.ThemeChoice
import java.awt.GraphicsEnvironment
import org.jetbrains.skia.Image

/**
 * The panel's real pixel size, read from the X server's own idea of the display mode.
 * `1280x800` stays only as the fallback — for a headless environment where
 * `GraphicsEnvironment` throws (`HeadlessException`, which this Windows dev box hits every
 * time), or for the rare case the API can't tell and returns a non-positive width/height
 * instead of throwing. Previously this was hardcoded 1280x800 — fine for the one panel it
 * was built on, wrong and unfixable-from-the-screen for any other, since `undecorated =
 * true` at (0,0) means a wrong size just clips content or leaves a void, with no window
 * chrome to resize it from.
 */
private fun panelSize(): Pair<Dp, Dp> {
    return try {
        val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        if (mode.width > 0 && mode.height > 0) mode.width.dp to mode.height.dp else 1280.dp to 800.dp
    } catch (_: Exception) {
        1280.dp to 800.dp
    }
}

fun main() = application {
    val settings = remember { LinuxSettings(LinuxSettings.defaultPath()) }
    val model = remember { PanelModel(settings) }
    // Clients built from settings; the 1 Hz poll loop starts here.
    LaunchedEffect(Unit) { model.start() }

    // The CAMusic mark (the original repo's docs/app-icon.png) on the window itself —
    // undecorated on a bare X server has no taskbar, but anything listing windows
    // (KWin's panel, xdotool) otherwise shows Compose's default coffee cup.
    val icon = remember {
        javaClass.getResourceAsStream("/icon.png")?.readBytes()
    }

    val layout by settings.layoutMode.collectAsState(initial = "landscape")
    val accent by settings.accentChoice.collectAsState(initial = "album")
    val fixed by settings.accentFixed.collectAsState(initial = 0xFF8AB4F8.toInt())
    val theme by settings.themeChoice.collectAsState(initial = "dark")
    val glassBlur by settings.glassBlur.collectAsState(initial = true)

    val (panelWidth, panelHeight) = remember { panelSize() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "CAMusic for Linux",
        // The panel runs a bare X server with no window manager, so the window
        // must place itself: undecorated at (0,0), exactly the panel's size —
        // the plan's "undecorated/fullscreen for the panel". No voids, no bar.
        state = rememberWindowState(
            width = panelWidth,
            height = panelHeight,
            position = WindowPosition(0.dp, 0.dp),
        ),
        icon = icon?.let { bytes -> BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap()) },
        undecorated = true,
        resizable = false,
    ) {
        SendspinTheme(
            theme = ThemeChoice.from(theme),
            accentChoice = AccentChoice.from(accent),
            seedAccent = Color(fixed),
            glassBlur = glassBlur,
        ) {
            PanelShell(model = model, layoutMode = LayoutMode.from(layout))
        }
    }
}
package com.engabd.sendpin.linux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.engabd.sendpin.linux.screensaver.IdleGate
import com.engabd.sendpin.linux.ui.LiquidStyle
import com.engabd.sendpin.linux.ui.LocalLiquidStyle
import com.engabd.sendpin.linux.ui.PanelFinish
import com.engabd.sendpin.linux.screensaver.ListeningHost
import com.engabd.sendpin.linux.screensaver.ScreensaverHost
import com.engabd.sendpin.linux.station.ActionMenuHost
import com.engabd.sendpin.linux.station.ActionMenuState
import com.engabd.sendpin.linux.station.DeepIdleHost
import com.engabd.sendpin.linux.station.LocalActionMenu
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
import com.engabd.sendpin.ui.design.ColourMood
import com.engabd.sendpin.ui.design.LocalColourMood
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
    val model = remember {
        PanelModel(settings).also { m ->
            // Every way out — the window closing, the Quit row's exitProcess, systemd's
            // SIGTERM — ends the Jellyfin session, so HueGhost lets go at once.
            Runtime.getRuntime().addShutdownHook(Thread { m.close() })
        }
    }
    // Clients built from settings; the poll loop starts here.
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
    val mood by settings.colourMood.collectAsState(initial = "natural")
    val liquid by settings.livingStyle.collectAsState(initial = "marble")

    // The screensaver's idle clock, fed by every touch and key below.
    val idle = remember { IdleGate() }
    // The one menu a held row opens, drawn for every pane.
    val menus = remember { ActionMenuState() }

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
        onPreviewKeyEvent = { idle.touch(); false },
    ) {
        SendspinTheme(
            theme = ThemeChoice.from(theme),
            accentChoice = AccentChoice.from(accent),
            seedAccent = Color(fixed),
            glassBlur = glassBlur,
        ) {
            // Every album palette below is graded in this mood (rememberAlbumPalette), and
            // every living-colour room flows in this style (AlbumBleed).
            CompositionLocalProvider(
                LocalColourMood provides ColourMood.from(mood),
                LocalLiquidStyle provides LiquidStyle.from(liquid),
                LocalActionMenu provides menus,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // Every touch, anywhere, restarts the screensaver's clock. The Initial
                        // pass sees it before any pane does, and nothing is consumed. A finger
                        // going down is also a press, which a view still fading in steps
                        // aside for (IdleGate.lastPress).
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.any { it.pressed && !it.previousPressed }) idle.press()
                                    else idle.touch()
                                }
                            }
                        },
                ) {
                    PanelShell(model = model, layoutMode = LayoutMode.from(layout))
                    // A held row's menu, above the shell and below the always-on views.
                    ActionMenuHost(menus)
                    // While music plays and nobody touches: the record takes the screen.
                    ListeningHost(model, idle)
                    // While nothing plays: the album gallery.
                    ScreensaverHost(model, idle)
                    // After a long while untouched: a dim clock, or the screen off.
                    DeepIdleHost(model, idle)
                    // In front of it all, taking no touches: vignette, grain and the night.
                    PanelFinish(settings)
                }
            }
        }
    }
}
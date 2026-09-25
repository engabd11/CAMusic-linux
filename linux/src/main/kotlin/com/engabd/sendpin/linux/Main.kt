package com.engabd.sendpin.linux

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.engabd.sendpin.ui.theme.AccentChoice
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.ThemeChoice

fun main() = application {
    val settings = remember { LinuxSettings(LinuxSettings.defaultPath()) }
    val model = remember { PanelModel(settings) }
    // Clients built from settings; the 1 Hz poll loop starts here.
    LaunchedEffect(Unit) { model.start() }

    val layout by settings.layoutMode.collectAsState(initial = "landscape")
    val accent by settings.accentChoice.collectAsState(initial = "album")
    val fixed by settings.accentFixed.collectAsState(initial = 0xFF8AB4F8.toInt())
    val theme by settings.themeChoice.collectAsState(initial = "dark")
    val glassBlur by settings.glassBlur.collectAsState(initial = true)

    Window(
        onCloseRequest = ::exitApplication,
        title = "CAMusic for Linux",
        // The panel runs a bare X server with no window manager, so the window
        // must place itself: undecorated at (0,0), exactly the panel's size —
        // the plan's "undecorated/fullscreen for the panel". No voids, no bar.
        state = rememberWindowState(
            width = 1280.dp,
            height = 800.dp,
            position = WindowPosition(0.dp, 0.dp),
        ),
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
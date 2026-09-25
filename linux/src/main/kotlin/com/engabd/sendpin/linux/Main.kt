package com.engabd.sendpin.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

// Hello-world proving the :linux module wiring: Kotlin JVM plugin next to AGP 9's
// built-in Kotlin, Compose Desktop resolving, material3 present. DarkColors' #101114
// — the NUC panel is LCD, per the shared Color.kt note.
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "CAMusic for Linux",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        MaterialTheme {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF101114)),
                contentAlignment = Alignment.Center,
            ) {
                Text("CAMusic for Linux — hello world", color = Color(0xFFE8EAF0))
            }
        }
    }
}
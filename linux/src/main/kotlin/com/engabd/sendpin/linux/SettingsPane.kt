package com.engabd.sendpin.linux

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.engabd.sendpin.linux.ui.touchDragScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * The settings pane: MPD, Jellyfin, path mapping, HueGhost, layout mode, theme.
 * Fields are local edits; Apply persists through [LinuxSettings]' setters and
 * asks [PanelModel.reconnect] to rebuild the clients — no restart needed.
 */
@Composable
fun SettingsPane(modifier: Modifier, model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()

    val mpdAddress by settings.mpdAddress.collectAsState(initial = "127.0.0.1:6600")
    val jfUrl by settings.jfUrl.collectAsState(initial = "")
    val jfToken by settings.jfToken.collectAsState(initial = "")
    val prefix by settings.jellyfinPathPrefix.collectAsState(initial = "")
    val hgUrl by settings.hueGhostUrl.collectAsState(initial = "")
    val hgToken by settings.hueGhostToken.collectAsState(initial = "")
    val layout by settings.layoutMode.collectAsState(initial = "landscape")
    val accent by settings.accentChoice.collectAsState(initial = "album")
    val report by settings.reportToJellyfin.collectAsState(initial = true)
    val theme by settings.themeChoice.collectAsState(initial = "dark")
    val glassBlur by settings.glassBlur.collectAsState(initial = true)

    // Local edit buffers, re-keyed on settings changes so Apply/external edits refresh.
    var mpdField by remember(mpdAddress) { mutableStateOf(mpdAddress) }
    var jfField by remember(jfUrl) { mutableStateOf(jfUrl) }
    var tokenField by remember(jfToken) { mutableStateOf(jfToken) }
    var prefixField by remember(prefix) { mutableStateOf(prefix) }
    var hgField by remember(hgUrl) { mutableStateOf(hgUrl) }
    var hgTokenField by remember(hgToken) { mutableStateOf(hgToken) }

    val scrollState = rememberScrollState()
    Box(modifier) {
    Column(
        Modifier.fillMaxSize().padding(24.dp)
            .verticalScroll(scrollState)
            .touchDragScroll(scrollState, Orientation.Vertical),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Settings",
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )

        Section("MPD")
        Field("Address (host:port)", mpdField) { mpdField = it }
        Hint("MPD decodes; this panel is its remote. Default localhost — MPD runs on this NUC.")

        HorizontalDivider(color = Ink2)

        Section("Jellyfin")
        Field("URL", jfField) { jfField = it }
        Field("API token", tokenField) { tokenField = it }
        Field("Music path prefix", prefixField) { prefixField = it }
        Hint(
            "Jellyfin's absolute library path, stripped to get MPD's relative one — " +
                "e.g. /mnt/music/ turns /mnt/music/Artist/Album/x.flac into Artist/Album/x.flac.",
        )

        HorizontalDivider(color = Ink2)

        Section("HueGhost")
        Field("Base URL", hgField) { hgField = it }
        Field("Control token", hgTokenField) { hgTokenField = it }
        Hint(
            "The daemon binds 127.0.0.1 on its host — front it with a tunnel or proxy " +
                "reachable from this NUC.",
        )

        HorizontalDivider(color = Ink2)

        Section("Panel")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Report to Jellyfin while MPD plays",
                color = TextPrimary,
                fontFamily = AppFont,
                fontSize = 15.sp,
            )
            Switch(
                checked = report,
                onCheckedChange = { v -> scope.launch { settings.setReportToJellyfin(v) } },
            )
        }
        ChipRow("Layout mode", current = layout, options = listOf("landscape", "portrait")) { mode ->
            scope.launch { settings.setLayoutMode(mode) }
        }
        ChipRow(
            label = "Accent",
            current = accent,
            options = listOf("album", "fixed"),
        ) { choice ->
            scope.launch { settings.setAccentChoice(choice) }
        }
        ChipRow(
            label = "Theme",
            current = theme,
            options = listOf("oled", "dark", "light", "system"),
        ) { t ->
            scope.launch { settings.setThemeChoice(t) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Blur behind glass surfaces",
                color = TextPrimary,
                fontFamily = AppFont,
                fontSize = 15.sp,
            )
            Switch(
                checked = glassBlur,
                onCheckedChange = { v -> scope.launch { settings.setGlassBlur(v) } },
            )
        }

        HorizontalDivider(color = Ink2)

        Button(onClick = {
            scope.launch {
                settings.setMpdAddress(mpdField)
                settings.setJfUrl(jfField)
                settings.setJfToken(tokenField)
                settings.setJellyfinPathPrefix(prefixField)
                settings.setHueGhostUrl(hgField)
                settings.setHueGhostToken(hgTokenField)
                model.reconnect()
            }
        }) {
            Text("Apply", fontFamily = AppFont)
        }
        Hint("Applies without a restart — the clients are rebuilt from these values.")

        HorizontalDivider(color = Ink2)

        Section("Exit")
        // `undecorated = true` on a bare X server with no window manager means there is
        // no title bar, no close button and `onCloseRequest` has nothing to invoke it —
        // the only way off this screen otherwise is SSH in and kill the process.
        Button(
            onClick = { kotlin.system.exitProcess(0) },
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFB3261E)),
        ) {
            Text("Quit", fontFamily = AppFont)
        }
    }
        VerticalScrollbar(
            rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = TextPrimary,
        fontFamily = AppFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = TextMuted, fontFamily = AppFont, fontSize = 11.sp)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChipRow(label: String, current: String, options: List<String>, onPick: (String) -> Unit) {
    Column {
        Text(label, color = TextPrimary, fontFamily = AppFont, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val active = current.equals(opt, ignoreCase = true)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (active) Glass else Color.Transparent)
                        .clickable { onPick(opt) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        opt,
                        color = if (active) TextPrimary else TextMuted,
                        fontFamily = AppFont,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
package com.engabd.sendpin.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lights pane: a small control panel over HueGhost's REST API — on/off via
 * client-side toggle, mode, intensity, brightness. Every read reflects the
 * daemon (`/api/status` parsed by [HueGhostClient.parseStatus]); every control
 * fires `/api/set` or `/api/on|off`. HueGhost v2.6.0 reconciles live.
 *
 * Reaching HueGhost from this NUC needs the daemon reachable (tunnel or proxy —
 * it binds 127.0.0.1 on its host); the base URL setting fronts any of those.
 */
@Composable
fun LightsPane(modifier: Modifier, model: PanelModel) {
    val hgC = model.hueGhost.collectAsState().value
    val scope = rememberCoroutineScope()

    if (hgC == null || !hgC.isConfigured()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Configure HueGhost in Settings — the daemon must be reachable over the LAN.",
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 14.sp,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    val hg = hgC!!

    var status by remember { mutableStateOf<HueGhostClient.Status?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Reflect the daemon: poll status every 2 s while the pane composes.
    LaunchedEffect(hg) {
        while (true) {
            status = hg.status()
            delay(2000)
        }
    }

    suspend fun refresh() {
        status = hg.status()
        busy = false
    }

    Column(
        modifier.padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Hue Ghost",
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Sync", color = TextPrimary, fontFamily = AppFont, fontSize = 16.sp)
                Text(
                    status?.let { s ->
                        if (s.running) "syncing · ${s.mode ?: "?"} · ${s.intensity ?: "?"}" else "not syncing"
                    } ?: "unreachable",
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 12.sp,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp))
            } else {
                Button(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { hg.toggle() }
                        refresh()
                    }
                }) {
                    Text(if (status?.running == true) "Stop" else "Start", fontFamily = AppFont)
                }
            }
        }

        HorizontalDivider(color = Ink2)

        ModeRow(
            label = "Mode",
            current = status?.mode,
            options = listOf("video", "music", "games"),
        ) { m ->
            busy = true
            scope.launch {
                runCatching { hg.set(mode = m) }
                refresh()
            }
        }

        ModeRow(
            label = "Intensity",
            current = status?.intensity,
            options = listOf("subtle", "moderate", "high", "extreme"),
        ) { i ->
            busy = true
            scope.launch {
                runCatching { hg.set(intensity = i) }
                refresh()
            }
        }

        HorizontalDivider(color = Ink2)

        // Offset — HueGhost's music-sync timing trim, in seconds, committed on
        // release. (`brightness` is not an official Hue Sync concept; the lights
        // follow the room as the Sync Box drives them.)
        val off = status?.offsetS ?: 0.0
        var offScrub by remember { mutableStateOf<Double?>(null) }
        Column {
            Text("Offset (s)", color = TextPrimary, fontFamily = AppFont, fontSize = 15.sp)
            Text(
                "Trim the sync timing — negative pulls the lights earlier.",
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 11.sp,
            )
            Slider(
                value = (offScrub ?: off).toFloat(),
                onValueChange = { offScrub = it.toDouble() },
                onValueChangeFinished = {
                    offScrub?.let { v ->
                        scope.launch {
                            runCatching { hg.set(offsetS = (v * 10).toInt() / 10.0) }
                            refresh()
                        }
                    }
                    offScrub = null
                },
                valueRange = -2f..2f,
            )
        }

        // What the follow source is tracking, straight from the daemon.
        status?.followItem?.let { item ->
            Text(
                "Following: $item",
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 12.sp,
            )
        }
    }
}

/** One selectable row of options — the active one (what the daemon reports) fills. */
@Composable
private fun ModeRow(label: String, current: String?, options: List<String>, onPick: (String) -> Unit) {
    Column {
        Text(label, color = TextPrimary, fontFamily = AppFont, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val active = current.equals(opt, ignoreCase = true)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (active) Glass else androidx.compose.ui.graphics.Color.Transparent)
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
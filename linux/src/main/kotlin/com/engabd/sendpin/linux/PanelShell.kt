package com.engabd.sendpin.linux

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.engabd.sendpin.linux.ui.TabSwitch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.station.LocalBigReadouts
import com.engabd.sendpin.linux.station.StatusBanner
import com.engabd.sendpin.linux.ui.LocalNowArt
import com.engabd.sendpin.linux.ui.PaneAmbience
import com.engabd.sendpin.linux.ui.rememberLivingArt
import com.engabd.sendpin.linux.ui.rememberNowArt
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary

/**
 * Which way the panel is cut in two — chosen manually in Settings, never
 * auto-detected (the plan's explicit decision; the panel knows its own shape).
 * Pure so the shell's layout choice is unit-testable.
 */
enum class LayoutMode { LANDSCAPE, PORTRAIT;

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.name.equals(key, true) } ?: LANDSCAPE
    }
}

/** The four panes the right side (or bottom half) can hold. */
enum class PanelTab(val label: String) {
    LIBRARY("Library"), QUEUE("Queue"), LIGHTS("Lights"), SETTINGS("Settings")
}

/**
 * The player pane's share, landscape — the library gets the rest. CarShell's ratio.
 */
private const val PLAYER_WEIGHT_WIDE = 0.42f

/**
 * The panel's shell — two panes, no navigation stack, modelled on CarShell
 * (structure copied, car specifics not): a player pane that never leaves the
 * screen, and one panel at a time beside or below it. The cut is [layoutMode],
 * read from Settings — no orientation detection, per the plan.
 *
 * Landscape: album art + transport left, tab strip + panel content right.
 * Portrait: the overlay shell (PortraitShell) — full-screen cover over tabs.
 */
@Composable
fun PanelShell(
    model: PanelModel,
    layoutMode: LayoutMode,
    // Hoisted above both layouts and the tab switch: the library keeps its page and
    // its loaded shelves while the Queue or Lights tab is showing.
    libraryState: LibraryState = remember { LibraryState() },
) {
    var tab by remember { mutableStateOf(PanelTab.LIBRARY) }

    val bigReadouts by model.settings.bigReadouts.collectAsState(initial = false)
    CompositionLocalProvider(LocalBigReadouts provides bigReadouts) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                when (layoutMode) {
                    LayoutMode.LANDSCAPE -> LandscapeShell(model, tab, { t -> tab = t }, libraryState)
                    // Portrait is the overlay shell: the now-playing cover IS the screen,
                    // swiped down to reveal tabs + mini bar + bottom nav (PortraitShell
                    // owns its tab state — the shared `tab` above is the landscape strip's).
                    LayoutMode.PORTRAIT -> PortraitShell(model, libraryState = libraryState)
                }
            }
            // Over everything in the shell, and only there while something cannot be reached.
            StatusBanner(model, Modifier.align(Alignment.TopCenter))
        }
    }
}

/**
 * Landscape: the player left, tab strip + panel right, one album ambience behind both
 * — the same palette the portrait shell provides, so both layouts take the record's
 * colours.
 */
@Composable
private fun LandscapeShell(
    model: PanelModel,
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    libraryState: LibraryState,
) {
    // The cover held per album (ui/NowArt.kt) — the same single source the portrait
    // shell uses, so a skip inside one record moves nothing on either layout.
    val nowArt = rememberNowArt(model)
    val art = nowArt.url
    val palette = rememberAlbumPalette(art)
    val ambientMotion by model.settings.ambientMotion.collectAsState(initial = true)
    val pageTransitions by model.settings.pageTransitions.collectAsState(initial = true)
    val retapHome by model.settings.libraryRetapHome.collectAsState(initial = true)
    val obscured by model.coverObscured.collectAsState()
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides palette.accent,
        LocalNowArt provides nowArt,
    ) {
        Box(Modifier.fillMaxSize()) {
            PaneAmbience(
                art, palette,
                drift = ambientMotion && !obscured,
                living = rememberLivingArt(model),
                seed = nowArt.key.hashCode(),
            )
            Row(Modifier.fillMaxSize()) {
                PlayerPane(Modifier.weight(PLAYER_WEIGHT_WIDE).fillMaxHeight(), model)
                Box(Modifier.fillMaxHeight().width(1.dp).background(Hairline))
                Column(Modifier.weight(1f - PLAYER_WEIGHT_WIDE).fillMaxHeight()) {
                    TabStrip(
                        Modifier.fillMaxWidth(),
                        model = model,
                        selected = tab,
                        on = { t ->
                            // Library tapped while already showing: back to its home page.
                            if (retapHome && t == PanelTab.LIBRARY && tab == PanelTab.LIBRARY) libraryState.home()
                            onTab(t)
                        },
                    )
                    Box(Modifier.fillMaxSize()) {
                        val tabPane: @Composable (PanelTab) -> Unit = { t ->
                            when (t) {
                                PanelTab.LIBRARY -> LibraryPane(Modifier.fillMaxSize(), model, libraryState)
                                PanelTab.QUEUE -> QueuePane(Modifier.fillMaxSize(), model)
                                PanelTab.LIGHTS -> LightsPane(Modifier.fillMaxSize(), model)
                                PanelTab.SETTINGS -> SettingsPane(Modifier.fillMaxSize(), model)
                            }
                        }
                        if (pageTransitions) {
                            TabSwitch(tab, Modifier.fillMaxSize()) { tabPane(it) }
                        } else {
                            tabPane(tab)
                        }
                    }
                }
            }
        }
    }
}

/** Tab strip: three tabs plus the connection status at the far edge. */
@Composable
private fun TabStrip(
    modifier: Modifier,
    model: PanelModel,
    selected: PanelTab,
    on: (PanelTab) -> Unit,
) {
    val status by model.statusLine.collectAsState()
    val accent = rememberAccent()
    Row(
        modifier.padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelTab.entries.forEach { t ->
            val active = t == selected
            Row(
                Modifier
                    // The panel's chip floor (PanelMetrics) — 48dp, like the library
                    // category chips; the old 9dp padding only reached ~36dp.
                    .heightIn(min = PanelMetrics.ChipMinHeight)
                    .clip(RoundedCornerShape(100.dp))
                    .background(if (active) accent.a(0.18f) else Color.Transparent)
                    .clickable { on(t) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    when (t) {
                        PanelTab.LIBRARY -> Icons.Default.LibraryMusic
                        PanelTab.QUEUE -> Icons.AutoMirrored.Filled.QueueMusic
                        PanelTab.LIGHTS -> Icons.Default.Lightbulb
                        PanelTab.SETTINGS -> Icons.Default.Settings
                    },
                    contentDescription = t.label,
                    tint = if (active) accent else TextMuted,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    t.label,
                    color = if (active) TextPrimary else TextMuted,
                    fontFamily = AppFont,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = PanelMetrics.ChipLabel,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            status,
            color = TextMuted,
            fontFamily = AppFont,
            fontSize = PanelMetrics.NavStatus,
            maxLines = 1,
        )
    }
}
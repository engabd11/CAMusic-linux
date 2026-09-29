package com.engabd.sendpin.linux

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.ui.GlassCard
import com.engabd.sendpin.linux.ui.OnScreenKeyboard
import com.engabd.sendpin.linux.ui.OnScreenKeyboardKey
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.PageHeader
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.linux.ui.SegmentedControl
import com.engabd.sendpin.linux.ui.applyKey
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.ErrorRed
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.ThemeChoice
import com.engabd.sendpin.ui.theme.inkOn
import kotlinx.coroutines.launch

/** A secret shown at rest: dots, and the last four characters so two tokens can be told apart. */
internal fun maskSecret(value: String): String = when {
    value.isEmpty() -> ""
    value.length <= 4 -> "•".repeat(value.length)
    else -> "•".repeat(8) + value.takeLast(4)
}

/** The connection fields, in the order they appear — also the edit buffers' keys. */
private enum class Field(val label: String, val secret: Boolean = false) {
    MPD("MPD address"),
    JF_URL("Jellyfin URL"),
    JF_TOKEN("Jellyfin API key", secret = true),
    PREFIX("Music path prefix"),
    HG_URL("HueGhost URL"),
    HG_TOKEN("HueGhost token", secret = true),
}

/**
 * The settings pane, as cards: Sound (loudness), Appearance, Connections, Session,
 * System. Toggles apply at once; the connection fields are edits held until Apply,
 * which persists them and rebuilds the clients — no restart.
 *
 * The fields are typed on the panel's own keyboard (the one search uses, with a shift
 * key and a punctuation row): tap a field and the keyboard docks under the page, the
 * field scrolled into view above it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsPane(modifier: Modifier, model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()

    val saved = mapOf(
        Field.MPD to settings.mpdAddress.collectAsState(initial = "127.0.0.1:6600").value,
        Field.JF_URL to settings.jfUrl.collectAsState(initial = "").value,
        Field.JF_TOKEN to settings.jfToken.collectAsState(initial = "").value,
        Field.PREFIX to settings.jellyfinPathPrefix.collectAsState(initial = "").value,
        Field.HG_URL to settings.hueGhostUrl.collectAsState(initial = "").value,
        Field.HG_TOKEN to settings.hueGhostToken.collectAsState(initial = "").value,
    )
    val layout by settings.layoutMode.collectAsState(initial = "landscape")
    val accentChoice by settings.accentChoice.collectAsState(initial = "album")
    val report by settings.reportToJellyfin.collectAsState(initial = true)
    val theme by settings.themeChoice.collectAsState(initial = "dark")
    val glassBlur by settings.glassBlur.collectAsState(initial = true)
    val savedLoudness by settings.replayGainMode.collectAsState(initial = "album")
    val mpdLoudness by model.replayGain.collectAsState()

    // Edits in progress; a field with no entry shows its saved value.
    val edits = remember { mutableStateMapOf<Field, String>() }
    fun valueOf(f: Field) = edits[f] ?: saved.getValue(f)
    var editing by remember { mutableStateOf<Field?>(null) }
    val requesters = remember { Field.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(editing) { editing?.let { requesters.getValue(it).bringIntoView() } }

    val scrollState = rememberScrollState()
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scrollState)
                    .touchDragScroll(scrollState, Orientation.Vertical)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PageHeader(title = "Settings")

                // ── Sound ──
                val shown = shownLoudness(savedLoudness, mpdLoudness)
                val mode = LoudnessMode.from(shown)
                SettingsCard(Icons.AutoMirrored.Filled.VolumeUp, "Loudness",
                    "A 1985 master and a 2015 one can land 10 dB apart. ReplayGain uses the " +
                        "level tags in your files to even that out.") {
                    SegmentedControl(
                        options = LoudnessMode.entries,
                        selected = mode,
                        label = { it.label },
                        onSelect = { m -> scope.launch { model.setReplayGain(m.key) } },
                        height = 60.dp,
                    )
                    Text(
                        mode?.blurb ?: "MPD is set to “$shown”, a mode this panel doesn't offer — pick one above to take it over.",
                        color = TextSecondary, fontFamily = AppFont, fontSize = 16.sp,
                    )
                    Text(
                        if (mpdLoudness != null) "Applied by MPD as it decodes · MPD reports “$mpdLoudness”."
                        else "Applied by MPD as it decodes · MPD hasn't answered yet.",
                        color = TextFaint, fontFamily = AppFont, fontSize = 14.sp,
                    )
                }

                // ── Appearance ──
                SettingsCard(Icons.Default.Palette, "Appearance", null) {
                    Label("Layout")
                    SegmentedControl(
                        options = LayoutMode.entries,
                        selected = LayoutMode.from(layout),
                        label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                        onSelect = { m -> scope.launch { settings.setLayoutMode(m.name.lowercase()) } },
                    )
                    Label("Theme")
                    SegmentedControl(
                        options = ThemeChoice.entries,
                        selected = ThemeChoice.from(theme),
                        label = { if (it == ThemeChoice.SYSTEM) "System" else it.label.substringBefore(' ') },
                        onSelect = { t -> scope.launch { settings.setThemeChoice(t.key) } },
                    )
                    Label("Accent colour")
                    SegmentedControl(
                        options = listOf("album", "fixed"),
                        selected = accentChoice,
                        label = { if (it == "album") "From the album" else "Fixed" },
                        onSelect = { c -> scope.launch { settings.setAccentChoice(c) } },
                    )
                    ToggleRow("Blur behind glass", "Frosted cards over the album's colours. Off draws them flat.", glassBlur) { v ->
                        scope.launch { settings.setGlassBlur(v) }
                    }
                }

                // ── Screensaver ──
                ScreensaverCard(model)

                // ── Connections ──
                SettingsCard(Icons.Default.Cable, "Connections",
                    "MPD plays; Jellyfin is the library; HueGhost drives the lights.") {
                    Field.entries.forEach { f ->
                        FieldRow(
                            field = f,
                            value = valueOf(f),
                            editing = editing == f,
                            modifier = Modifier.bringIntoViewRequester(requesters.getValue(f)),
                            onClick = { editing = if (editing == f) null else f },
                        )
                        if (f == Field.PREFIX) {
                            Hint("Jellyfin's absolute library path, stripped to get MPD's relative one — " +
                                "/mnt/music/ turns /mnt/music/Artist/Album/x.flac into Artist/Album/x.flac.")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PillButton("Apply", icon = Icons.Default.Check, onClick = {
                            editing = null
                            scope.launch {
                                settings.setMpdAddress(valueOf(Field.MPD))
                                settings.setJfUrl(valueOf(Field.JF_URL))
                                settings.setJfToken(valueOf(Field.JF_TOKEN))
                                settings.setJellyfinPathPrefix(valueOf(Field.PREFIX))
                                settings.setHueGhostUrl(valueOf(Field.HG_URL))
                                settings.setHueGhostToken(valueOf(Field.HG_TOKEN))
                                edits.clear()
                                model.reconnect()
                            }
                        })
                        Text(
                            if (edits.isEmpty()) "Reconnects without a restart." else "Unsaved changes.",
                            color = if (edits.isEmpty()) TextMuted else rememberAccent(),
                            fontFamily = AppFont, fontSize = 15.sp,
                        )
                    }
                }

                // ── Session ──
                SettingsCard(Icons.Default.Sensors, "Session", null) {
                    ToggleRow(
                        "Report playback to Jellyfin",
                        "HueGhost follows this Jellyfin session — without it the lights stop syncing after 15 s.",
                        report,
                    ) { v -> scope.launch { settings.setReportToJellyfin(v) } }
                }

                // ── System ──
                // `undecorated = true` on a bare X server with no window manager means there
                // is no title bar and no close button — this is the only way off the panel
                // short of SSH.
                SettingsCard(Icons.Default.PowerSettingsNew, "System", null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Close CAMusic", color = TextPrimary, fontFamily = AppFont, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        PillButton("Quit", color = ErrorRed, onClick = { kotlin.system.exitProcess(0) })
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }

        editing?.let { f ->
            fun edit(key: OnScreenKeyboardKey) { edits[f] = applyKey(valueOf(f), key) }
            OnScreenKeyboard(
                onKey = { c -> edit(OnScreenKeyboardKey.Char(c)) },
                onBackspace = { edit(OnScreenKeyboardKey.Backspace) },
                onClear = { edit(OnScreenKeyboardKey.Clear) },
                onDone = { editing = null },
                symbols = true,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * The gallery that takes the screen when the panel is idle and nothing plays — see
 * `screensaver/Screensaver.kt`. Everything it shows besides the art is a switch here.
 */
@Composable
private fun ScreensaverCard(model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()
    val enabled by settings.screensaverEnabled.collectAsState(initial = true)
    val delayMin by settings.screensaverDelayMin.collectAsState(initial = 10)
    val style by settings.screensaverStyle.collectAsState(initial = "gallery")
    val slideS by settings.screensaverSlideS.collectAsState(initial = 20)
    val albums by settings.screensaverAlbums.collectAsState(initial = "all")
    val clock by settings.screensaverClock.collectAsState(initial = true)
    val details by settings.screensaverDetails.collectAsState(initial = true)
    val hold by settings.screensaverHoldToPlay.collectAsState(initial = true)
    val drift by settings.screensaverDrift.collectAsState(initial = true)
    val albumText by settings.screensaverAlbumText.collectAsState(initial = false)
    val textSize by settings.screensaverTextSize.collectAsState(initial = "regular")
    val clockPosition by settings.screensaverClockPosition.collectAsState(initial = "right")

    SettingsCard(Icons.Default.Slideshow, "Screensaver",
        "When nothing is playing and the panel is left alone, your album covers take the screen.") {
        ToggleRow("Album gallery", "A tap wakes the panel; music starting anywhere ends it.", enabled) { v ->
            scope.launch { settings.setScreensaverEnabled(v) }
        }
        if (enabled) {
            Label("Begins after")
            SegmentedControl(
                options = listOf(1, 5, 10, 20, 30, 60),
                selected = delayMin,
                label = { if (it == 60) "1 h" else "$it min" },
                onSelect = { m -> scope.launch { settings.setScreensaverDelayMin(m) } },
            )
            Label("Style")
            SegmentedControl(
                options = listOf("gallery", "bloom", "kenburns"),
                selected = style,
                label = { when (it) { "bloom" -> "Album colours"; "kenburns" -> "Full bleed"; else -> "Gallery wall" } },
                onSelect = { s -> scope.launch { settings.setScreensaverStyle(s) } },
            )
            Hint(
                when (style) {
                    "bloom" -> "The cover as a lit print, on the album's colours bleeding across the screen."
                    "kenburns" -> "Artist backdrop (or the cover) edge to edge, with a slow pan and zoom."
                    else -> "The cover as a lit print on a wash of its own colours."
                },
            )
            Label("Each album for")
            SegmentedControl(
                options = listOf(10, 20, 30, 60),
                selected = slideS,
                label = { if (it == 60) "1 min" else "$it s" },
                onSelect = { s -> scope.launch { settings.setScreensaverSlideS(s) } },
            )
            Label("Albums")
            SegmentedControl(
                options = listOf("all", "favourites", "recent"),
                selected = albums,
                label = { when (it) { "favourites" -> "Favourites"; "recent" -> "Recently added"; else -> "Whole library" } },
                onSelect = { a -> scope.launch { settings.setScreensaverAlbums(a) } },
            )
            Label("Text size")
            SegmentedControl(
                options = listOf("small", "regular", "large"),
                selected = textSize,
                label = { it.replaceFirstChar { c -> c.uppercase() } },
                onSelect = { v -> scope.launch { settings.setScreensaverTextSize(v) } },
            )
            ToggleRow("Album-coloured text", "Sets the words and clock in each record's own colour, like the player.", albumText) { v ->
                scope.launch { settings.setScreensaverAlbumText(v) }
            }
            ToggleRow("Clock", "The time and date in a corner.", clock) { v ->
                scope.launch { settings.setScreensaverClock(v) }
            }
            if (clock) {
                Label("Clock position")
                SegmentedControl(
                    options = listOf("left", "centre", "right"),
                    selected = clockPosition,
                    label = { it.replaceFirstChar { c -> c.uppercase() } },
                    onSelect = { v -> scope.launch { settings.setScreensaverClockPosition(v) } },
                )
            }
            ToggleRow("Year and genre", "A quiet line under the title.", details) { v ->
                scope.launch { settings.setScreensaverDetails(v) }
            }
            ToggleRow("Hold to play", "Press and hold a cover to play that album.", hold) { v ->
                scope.launch { settings.setScreensaverHoldToPlay(v) }
            }
            ToggleRow("Burn-in drift", "Moves the words and clock a few pixels over time, since the screen never sleeps.", drift) { v ->
                scope.launch { settings.setScreensaverDrift(v) }
            }
            PillButton("Preview now", icon = Icons.Default.PlayArrow, onClick = { model.screensaverNow.value = true })
        }
    }
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, lead: String?, content: @Composable () -> Unit) {
    val accent = rememberAccent()
    GlassCard(Modifier.padding(horizontal = PageGutter)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.a(0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp)) }
            Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
        if (lead != null) Text(lead, color = TextMuted, fontFamily = AppFont, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextSecondary, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = TextFaint, fontFamily = AppFont, fontSize = 14.sp)
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = rememberAccent()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            Text(hint, color = TextMuted, fontFamily = AppFont, fontSize = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
            ),
        )
    }
}

/** A connection field: label over value, tapped to edit on the drawn keyboard. */
@Composable
private fun FieldRow(field: Field, value: String, editing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = rememberAccent()
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (editing) accent.a(0.10f) else inkOn(0.05f))
            .border(if (editing) 2.dp else 1.dp, if (editing) accent else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(field.label, color = if (editing) accent else TextMuted, fontFamily = AppFont, fontSize = 14.sp)
        val shown = when {
            editing -> "$value▏"
            field.secret -> maskSecret(value)
            else -> value
        }
        Text(
            shown.ifEmpty { "Not set" },
            color = if (value.isEmpty() && !editing) TextFaint else TextPrimary,
            fontFamily = if (field.secret || field == Field.MPD) MonoFont else AppFont,
            fontSize = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
    }
}


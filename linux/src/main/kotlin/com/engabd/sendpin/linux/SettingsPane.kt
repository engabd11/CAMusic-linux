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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Headphones
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
import com.engabd.sendpin.linux.ui.LiquidStyle
import com.engabd.sendpin.linux.screensaver.ListeningLayout
import com.engabd.sendpin.ui.design.ColourMood
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

                // ── Colour & finish ──
                ColourCard(model)

                // ── Now playing & motion ──
                MotionCard(model)

                // ── Listening mode ──
                ListeningCard(model)

                // ── The hi-fi station ──
                StationCard(model)

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

/**
 * The panel as a picture: the grade every album's colours are set in, the finish over
 * the glass, and the night for a station that never turns off. Looks only.
 */
@Composable
private fun ColourCard(model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()
    val mood by settings.colourMood.collectAsState(initial = "natural")
    val vignette by settings.vignette.collectAsState(initial = "off")
    val grain by settings.filmGrain.collectAsState(initial = "off")
    val night by settings.nightMode.collectAsState(initial = "off")
    val startH by settings.nightStartH.collectAsState(initial = 22)
    val endH by settings.nightEndH.collectAsState(initial = 7)
    val depth by settings.nightDepth.collectAsState(initial = "gentle")

    SettingsCard(Icons.Default.Brush, "Colour & finish",
        "How every record lights the room, and the glass the panel is seen through.") {
        Label("Colour mood")
        SegmentedControl(
            options = ColourMood.entries,
            selected = ColourMood.from(mood),
            label = { it.label },
            onSelect = { v -> scope.launch { settings.setColourMood(v.key) } },
        )
        Hint(ColourMood.from(mood).description + " The covers themselves are never changed.")
        Label("Vignette")
        SegmentedControl(
            options = listOf("off", "soft", "deep"),
            selected = vignette,
            label = { it.replaceFirstChar { c -> c.uppercase() } },
            onSelect = { v -> scope.launch { settings.setVignette(v) } },
        )
        Label("Film grain")
        SegmentedControl(
            options = listOf("off", "fine", "rich"),
            selected = grain,
            label = { it.replaceFirstChar { c -> c.uppercase() } },
            onSelect = { v -> scope.launch { settings.setFilmGrain(v) } },
        )
        Hint("Vignette darkens the edges like a framed print. Grain lays a still film texture over everything, and smooths the banding an LCD shows in soft gradients.")
        Label("Night")
        SegmentedControl(
            options = listOf("off", "scheduled", "always"),
            selected = night,
            label = { when (it) { "scheduled" -> "On a schedule"; "always" -> "Always"; else -> "Off" } },
            onSelect = { v -> scope.launch { settings.setNightMode(v) } },
        )
        Hint("Warms the whole panel toward lamplight and dims it, easing in and out over twenty minutes.")
        if (night == "scheduled") {
            Label("Night begins")
            SegmentedControl(
                options = listOf(20, 21, 22, 23, 0),
                selected = startH,
                label = { hourLabel(it) },
                onSelect = { v -> scope.launch { settings.setNightStartH(v) } },
            )
            Label("Morning comes")
            SegmentedControl(
                options = listOf(5, 6, 7, 8, 9),
                selected = endH,
                label = { hourLabel(it) },
                onSelect = { v -> scope.launch { settings.setNightEndH(v) } },
            )
        }
        if (night != "off") {
            Label("Depth")
            SegmentedControl(
                options = listOf("gentle", "deep"),
                selected = depth,
                label = { it.replaceFirstChar { c -> c.uppercase() } },
                onSelect = { v -> scope.launch { settings.setNightDepth(v) } },
            )
        }
    }
}

/** An hour of the day as the panel shows it: `22:00`. */
internal fun hourLabel(h: Int): String = "%02d:00".format(h)

/**
 * How the player moves: the cover held per album and turned over on a new one, the cover
 * coming alive into each track, the words and colours easing rather than cutting, and
 * the library's navigation. Looks only — none of it touches playback.
 */
@Composable
private fun MotionCard(model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()
    val hold by settings.artHoldAlbum.collectAsState(initial = true)
    val transition by settings.albumTransition.collectAsState(initial = "flip")
    val living by settings.livingArt.collectAsState(initial = "subtle")
    val livingStyle by settings.livingStyle.collectAsState(initial = "marble")
    val livingDelay by settings.livingArtDelayS.collectAsState(initial = 10)
    val textMotion by settings.trackTextMotion.collectAsState(initial = true)
    val ambient by settings.ambientMotion.collectAsState(initial = true)
    val rests by settings.coverRestsOnPause.collectAsState(initial = true)
    val retap by settings.libraryRetapHome.collectAsState(initial = true)
    val pages by settings.pageTransitions.collectAsState(initial = true)

    SettingsCard(Icons.Default.Animation, "Now playing & motion",
        "How the cover and the room move with the music. Looks only — playback is untouched.") {
        ToggleRow("Hold the cover through an album", "Skipping inside one record leaves the art, its colours and the backdrop perfectly still.", hold) { v ->
            scope.launch { settings.setArtHoldAlbum(v) }
        }
        Label("When a new album starts")
        SegmentedControl(
            options = listOf("flip", "dissolve", "slide", "off"),
            selected = transition,
            label = { when (it) { "flip" -> "Flip"; "dissolve" -> "Dissolve"; "slide" -> "Slide"; else -> "Off" } },
            onSelect = { v -> scope.launch { settings.setAlbumTransition(v) } },
        )
        Hint(
            when (transition) {
                "flip" -> "The cover turns over like a sleeve in the hand, and the room's colours turn with it."
                "dissolve" -> "The old cover sinks back into a blur as the new one rises into focus."
                "slide" -> "The old cover slides away and the new one arrives, like flicking through a crate."
                else -> "The new cover simply replaces the old one."
            },
        )
        Label("Living colours")
        SegmentedControl(
            options = listOf("off", "subtle", "vivid"),
            selected = living,
            label = { it.replaceFirstChar { c -> c.uppercase() } },
            onSelect = { v -> scope.launch { settings.setLivingArt(v) } },
        )
        Hint("A little way into each track the album's colours behind the player start to flow, mixing like a liquid of inks; Vivid is bolder and quicker. The cover itself stays still, and pausing lets the colours settle. Off brings back the soft colour blooms.")
        if (living != "off") {
            Label("Liquid style")
            SegmentedControl(
                options = LiquidStyle.entries,
                selected = LiquidStyle.from(livingStyle),
                label = { it.label },
                onSelect = { v -> scope.launch { settings.setLivingStyle(v.key) } },
            )
            Hint(LiquidStyle.from(livingStyle).description)
            Label("Starts flowing after")
            SegmentedControl(
                options = listOf(5, 10, 20, 30),
                selected = livingDelay,
                label = { "$it s" },
                onSelect = { v -> scope.launch { settings.setLivingArtDelayS(v) } },
            )
        }
        ToggleRow("Gliding titles", "The title and artist glide over on a track change instead of jumping.", textMotion) { v ->
            scope.launch { settings.setTrackTextMotion(v) }
        }
        ToggleRow("Drifting colours", "The album's colour blooms wander slowly behind the player and the pages.", ambient) { v ->
            scope.launch { settings.setAmbientMotion(v) }
        }
        ToggleRow("Cover rests when paused", "Paused, the cover settles back a little and dims; play brings it forward again.", rests) { v ->
            scope.launch { settings.setCoverRestsOnPause(v) }
        }
        ToggleRow("Library tab goes home", "Tapping Library while browsing an album or artist goes back to the library's home page.", retap) { v ->
            scope.launch { settings.setLibraryRetapHome(v) }
        }
        ToggleRow("Page transitions", "Library pages slide in and out; tabs cross-fade instead of cutting.", pages) { v ->
            scope.launch { settings.setPageTransitions(v) }
        }
    }
}

/**
 * The listening view — `screensaver/ListeningMode.kt`: the record taking the screen while
 * music plays and the panel is left alone. The gallery card below covers when nothing plays.
 */
@Composable
private fun ListeningCard(model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()
    val enabled by settings.listeningMode.collectAsState(initial = true)
    val delayMin by settings.listeningDelayMin.collectAsState(initial = 3)
    val clock by settings.listeningClock.collectAsState(initial = true)
    val layout by settings.listeningLayout.collectAsState(initial = "classic")

    SettingsCard(Icons.Default.Headphones, "Listening mode",
        "While music plays and the panel is left alone, the controls step aside and the record takes the screen.") {
        ToggleRow("Listening view", "A tap brings the controls back; the music stopping ends it.", enabled) { v ->
            scope.launch { settings.setListeningMode(v) }
        }
        if (enabled) {
            Label("Begins after")
            SegmentedControl(
                options = listOf(1, 3, 5, 10, 20),
                selected = delayMin,
                label = { "$it min" },
                onSelect = { m -> scope.launch { settings.setListeningDelayMin(m) } },
            )
            Label("Layout")
            SegmentedControl(
                options = ListeningLayout.entries,
                selected = ListeningLayout.from(layout),
                label = { it.label },
                onSelect = { v -> scope.launch { settings.setListeningLayout(v.key) } },
            )
            Hint(ListeningLayout.from(layout).description)
            ToggleRow("Clock", "The time and date above the record.", clock) { v ->
                scope.launch { settings.setListeningClock(v) }
            }
            Hint("Burn-in drift (in the screensaver settings below) moves this view too.")
            PillButton("Preview now", icon = Icons.Default.PlayArrow, onClick = { model.listeningNow.value = true })
        }
    }
}

/**
 * The hi-fi station's additions — each one a switch, each off until it is switched on, and
 * none of them replaces anything the panel already does.
 */
@Composable
private fun StationCard(model: PanelModel) {
    val settings = model.settings
    val scope = rememberCoroutineScope()
    val tonearm by settings.tonearm.collectAsState(initial = false)
    val tonearmSeek by settings.tonearmSeek.collectAsState(initial = false)
    val deck by settings.queueDeck.collectAsState(initial = false)
    val postcards by settings.postcards.collectAsState(initial = false)
    val sleeve by settings.sleeveFlip.collectAsState(initial = false)
    val gestures by settings.touchGestures.collectAsState(initial = false)
    val menus by settings.rowMenus.collectAsState(initial = false)
    val crates by settings.crateMode.collectAsState(initial = false)
    val signal by settings.signalPanel.collectAsState(initial = false)
    val sleep by settings.sleepTimer.collectAsState(initial = false)
    val big by settings.bigReadouts.collectAsState(initial = false)
    val banner by settings.statusBanner.collectAsState(initial = false)
    val deepIdle by settings.deepIdle.collectAsState(initial = "off")
    val deepMin by settings.deepIdleMin.collectAsState(initial = 60)

    SettingsCard(Icons.Default.Album, "Hi-fi station",
        "Extras for a panel that plays all day. Each is off until you switch it on, and nothing here changes what the panel already does.") {
        Label("The turntable")
        ToggleRow("Tonearm", "On the Vinyl layout: the arm swings onto the record when it plays, rides in as the side goes, lifts and parks when it stops. Rings in the grooves mark the tracks.", tonearm) { v ->
            scope.launch { settings.setTonearm(v) }
        }
        if (tonearm) {
            ToggleRow("Touch the tonearm", "Take the arm in a finger and set it down on another track or part of one.", tonearmSeek) { v ->
                scope.launch { settings.setTonearmSeek(v) }
            }
        }
        ToggleRow("Queue deck", "On the Vinyl layout: the next records lean beside the platter. Tap one to play it, drag one to change the order.", deck) { v ->
            scope.launch { settings.setQueueDeck(v) }
        }
        Hint("Both need the Vinyl layout (Listening mode above). The lyric sheet is a layout of its own, in the same list.")

        Label("Keepsakes")
        ToggleRow("Postcards", "Hold the cover in the listening view to save a postcard — cover, title, the signal path and the date — to Pictures/CAMusic.", postcards) { v ->
            scope.launch { settings.setPostcards(v) }
        }
        ToggleRow("Back of the sleeve", "Hold the cover in the player to turn it over: the album's tracks, the playing one lit. Touch one to play it.", sleeve) { v ->
            scope.launch { settings.setSleeveFlip(v) }
        }

        Label("Touch")
        ToggleRow("Touch gestures", "Swipe the cover to skip. Swipe a queue row away, or drag it by its handle to reorder. Drag the cover's edge for volume, where MPD has one.", gestures) { v ->
            scope.launch { settings.setTouchGestures(v) }
        }
        ToggleRow("Row menus", "Hold a song or a queue row for Play next, Add to queue, Go to album and the like.", menus) { v ->
            scope.launch { settings.setRowMenus(v) }
        }
        ToggleRow("Crates", "A way to browse the library as records standing in bins, beside the usual grid.", crates) { v ->
            scope.launch { settings.setCrateMode(v) }
        }

        Label("The signal")
        ToggleRow("Signal path", "Source, decoder, loudness, volume and output as lit stages, and a BIT-PERFECT lamp when nothing MPD reports changes the bits. It reads what MPD and the library say; it cannot see inside a DAC driver.", signal) { v ->
            scope.launch { settings.setSignalPanel(v) }
        }
        ToggleRow("Sleep timer", "A chip by the loudness chip: stop at the end of the album, or after 30, 60 or 90 minutes.", sleep) { v ->
            scope.launch { settings.setSleepTimer(v) }
        }
        ToggleRow("Big readouts", "Times and the clock set large, for reading from across the room.", big) { v ->
            scope.launch { settings.setBigReadouts(v) }
        }
        ToggleRow("Connection banner", "A calm banner with a Retry when MPD or Jellyfin cannot be reached.", banner) { v ->
            scope.launch { settings.setStatusBanner(v) }
        }

        Label("Deep idle")
        SegmentedControl(
            options = listOf("off", "clock", "screen_off"),
            selected = deepIdle,
            label = { when (it) { "clock" -> "Dim clock"; "screen_off" -> "Screen off"; else -> "Off" } },
            onSelect = { v -> scope.launch { settings.setDeepIdle(v) } },
        )
        Hint(
            when (deepIdle) {
                "clock" -> "After a long time untouched the panel goes to a dim clock, in a new place each minute. A touch wakes it."
                "screen_off" -> "After a long time untouched the screen switches off (X power saving). A touch wakes it."
                else -> "Past the listening view and the gallery, for a panel that stays on for days."
            },
        )
        if (deepIdle != "off") {
            Label("After")
            SegmentedControl(
                options = listOf(15, 30, 60, 120),
                selected = deepMin,
                label = { if (it >= 60) "${it / 60} h" else "$it min" },
                onSelect = { v -> scope.launch { settings.setDeepIdleMin(v) } },
            )
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


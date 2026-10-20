package com.engabd.sendpin.linux

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.engabd.sendpin.linux.station.FlippableCover
import com.engabd.sendpin.linux.station.LocalBigReadouts
import com.engabd.sendpin.linux.station.SignalSheet
import com.engabd.sendpin.linux.station.SleepChip
import com.engabd.sendpin.linux.station.LyricsChip
import com.engabd.sendpin.linux.station.PlayerLyrics
import com.engabd.sendpin.linux.station.RadioChip
import com.engabd.sendpin.linux.ui.Motion
import com.engabd.sendpin.linux.ui.TabSwitch
import com.engabd.sendpin.linux.station.SleeveBack
import com.engabd.sendpin.linux.station.VolumeRail
import com.engabd.sendpin.linux.station.swipeToSkip
import com.engabd.sendpin.linux.ui.AlbumArtStage
import com.engabd.sendpin.linux.ui.AlbumBleed
import com.engabd.sendpin.linux.ui.ArtTransition
import com.engabd.sendpin.linux.ui.LivingArt
import com.engabd.sendpin.linux.ui.LocalNowArt
import com.engabd.sendpin.linux.ui.rememberLivingArt
import com.engabd.sendpin.linux.ui.rememberNowArt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.PaneAmbience
import com.engabd.sendpin.linux.ui.PanelBloom
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.MeltBackdrop
import com.engabd.sendpin.ui.design.MpdQualityBadge
import com.engabd.sendpin.ui.design.WaveSeekBar
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.inkOn
import kotlin.math.roundToInt

/**
 * How much of the slide-to-collapse distance a downward drag must cover before
 * release commits it — the Android overlay's own threshold (`dragPx >
 * collapseOffset * 0.25f`). Lower would collapse on accidental nudges; higher
 * makes the gesture feel heavy on a panel that is mostly swiped, not clicked.
 */
private const val COLLAPSE_COMMIT_FRACTION = 0.25f

/**
 * Upward travel on the cover, as a fraction of screen height, that means
 * "open the Queue" on release — the Android overlay's queue-reveal threshold.
 */
private const val QUEUE_REVEAL_FRACTION = 0.06f

/**
 * Whether a cover release at [dragPx] commits the collapse. Pure so the
 * gesture's one decision is unit-testable without a screen.
 */
internal fun collapseCommitted(dragPx: Float, collapsePx: Float): Boolean =
    dragPx > collapsePx * COLLAPSE_COMMIT_FRACTION

/**
 * Whether the mini bar's accumulated upward travel [upTravelPx] passes the
 * [thresholdPx] expand gate. Same shape as the Android bar's
 * `upTravel < -expandThresholdPx`: strictly beyond the threshold, downward
 * travel never counts.
 */
internal fun expandRequested(upTravelPx: Float, thresholdPx: Float): Boolean =
    upTravelPx < -thresholdPx

/**
 * The portrait shell, rebuilt around the Android app's overlay player: the
 * now-playing cover IS the screen, and swiping it down (past
 * [COLLAPSE_COMMIT_FRACTION] of the travel) slides it off the bottom edge to
 * reveal the tabs — content pane, mini player bar, bottom nav. Swiping up on
 * the bar (or tapping it) raises the cover again; swiping *up* on the cover
 * itself hands the screen to the Queue tab.
 *
 * The gesture mechanics are ported from `ui/screens/NowPlayingOverlay.kt`:
 * one plain-state drag offset moved synchronously in the gesture callback,
 * an `Animatable` only for the release settle (the offset-as-Animatable
 * version dropped deltas under a fast flick — see that file's note), and
 * `settleTo` awaited *before* `onCollapse()` fires so the composable is never
 * dropped mid-slide.
 *
 * The whole screen lives inside the album's palette (CompositionLocalProvider):
 * the cover's wash and blooms, the tabs' ambience behind them, the wave bar,
 * the transport, the mini bar and the nav all take their colour from the
 * record — one colour source, so the room adopts the album. The base layer
 * stays composed underneath at all times, so the collapse is a reveal, not a
 * load, and tab selection survives every expand/collapse.
 *
 * [initialTab] and [initiallyExpanded] exist for the preview renderer, which
 * draws each tab without a finger to swipe the cover away.
 */
@Composable
fun PortraitShell(
    model: PanelModel,
    initialTab: PanelTab = PanelTab.LIBRARY,
    initiallyExpanded: Boolean = true,
    libraryState: LibraryState = remember { LibraryState() },
) {
    // The cover held per album (NowArt.kt): a skip inside one record changes nothing
    // here, so the palette, the wash and the blooms stay exactly where they are.
    val nowArt = rememberNowArt(model)
    // Extracted once per cover change, off the UI thread; the previous palette
    // is held while a new one is pending (see rememberAlbumPalette).
    val palette = rememberAlbumPalette(nowArt.url)

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides palette.accent,
        LocalNowArt provides nowArt,
    ) {
        PortraitShellBody(model, initialTab, initiallyExpanded, libraryState)
    }
}

@Composable
private fun PortraitShellBody(
    model: PanelModel,
    initialTab: PanelTab,
    initiallyExpanded: Boolean,
    libraryState: LibraryState,
) {
    var tab by remember { mutableStateOf(initialTab) }
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    // True while the cover sits fully over the tabs — the tabs' ambience (a second
    // full-screen blur) is then skipped, so the panel pays for one backdrop, not two.
    var coverOpaque by remember { mutableStateOf(false) }

    val art = LocalNowArt.current.url
    val ambientMotion by model.settings.ambientMotion.collectAsState(initial = true)
    val pageTransitions by model.settings.pageTransitions.collectAsState(initial = true)
    val retapHome by model.settings.libraryRetapHome.collectAsState(initial = true)
    val obscured by model.coverObscured.collectAsState()
    // One flow clock for the whole screen, so the cover's room and the pages behind it
    // are the same liquid at the same moment.
    val living = rememberLivingArt(model)
    val seed = LocalNowArt.current.key.hashCode()

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val screenHpx = with(LocalDensity.current) { maxHeight.toPx() }
        // Collapse carries the cover fully off the bottom edge, so the handover
        // to the tabs is seamless — Android could stop at 0.82 of the screen
        // because its mini bar covered the remainder; this shell has nothing
        // drawn over the cover's path.
        val collapsePx = screenHpx
        val queueRevealPx = screenHpx * QUEUE_REVEAL_FRACTION

        Box(Modifier.fillMaxSize()) {
            if (!(expanded && coverOpaque)) {
                PaneAmbience(art, LocalPalette.current, drift = ambientMotion && !obscured, living = living, seed = seed)
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
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
                MiniPlayerBar(model = model, onExpand = { expanded = true })
                PortraitNavBar(
                    model = model,
                    selected = tab,
                    on = { t ->
                        // Library tapped while already showing: back to its home page,
                        // wherever in an album or artist the browse had got to.
                        if (retapHome && t == PanelTab.LIBRARY && tab == PanelTab.LIBRARY) libraryState.home()
                        tab = t
                    },
                )
            }
        }

        if (expanded) {
            NowPlayingCover(
                model = model,
                living = living,
                collapsePx = collapsePx,
                queueRevealPx = queueRevealPx,
                onOpaque = { coverOpaque = it },
                onCollapse = { expanded = false; coverOpaque = false },
                onSwipeUp = {
                    tab = PanelTab.QUEUE
                    expanded = false
                    coverOpaque = false
                },
            )
        }
    }
}

/**
 * The full-screen cover. Slides up into place on first composition (the mirror
 * of the collapse settle, so expand animates exactly as collapse does — the
 * asymmetry the Android overlay was built to fix), tracks the finger while
 * held, and settles on release.
 */
@Composable
private fun NowPlayingCover(
    model: PanelModel,
    living: LivingArt,
    collapsePx: Float,
    queueRevealPx: Float,
    onOpaque: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = rememberAccent()

    val index = state?.index?.takeIf { it >= 0 } ?: 0
    val track = queue.getOrNull(index)
    val posMs = state?.positionMs ?: 0L
    val durMs = state?.durationMs ?: track?.durationMs ?: 0L
    val playing = state?.playing ?: false

    val nowArt = LocalNowArt.current
    val transition by model.settings.albumTransition.collectAsState(initial = ArtTransition.FLIP)
    val ambientMotion by model.settings.ambientMotion.collectAsState(initial = true)
    val restsOnPause by model.settings.coverRestsOnPause.collectAsState(initial = true)
    val textMotion by model.settings.trackTextMotion.collectAsState(initial = true)
    val obscured by model.coverObscured.collectAsState()
    // The station's additions — each a switch in Settings, off until switched on.
    val touch by model.settings.touchGestures.collectAsState(initial = false)
    val sleeve by model.settings.sleeveFlip.collectAsState(initial = false)
    val signalPanel by model.settings.signalPanel.collectAsState(initial = false)
    val sleepChip by model.settings.sleepTimer.collectAsState(initial = false)
    val big = LocalBigReadouts.current
    var signalOpen by remember { mutableStateOf(false) }
    // The cover turned into the song's words (the Lyrics chip).
    var lyricsOpen by remember { mutableStateOf(false) }
    val karaoke by model.settings.karaokeFill.collectAsState(initial = true)
    // Paused, the record settles back a little and dims; playing, it springs forward.
    val rest = remember { Animatable(0f) }
    LaunchedEffect(playing, restsOnPause, track != null) {
        rest.animateTo(
            if (restsOnPause && !playing && track != null) 1f else 0f,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow),
        )
    }

    // The live drag offset is plain state mutated synchronously per pointer
    // sample; the Animatable only owns the release settle — one animation at a
    // time, exactly as the Android overlay settled its jitter.
    var dragPx by remember { mutableFloatStateOf(collapsePx) }
    var upTravel by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val settle = remember { Animatable(collapsePx) }
    val offsetPx = if (settling) settle.value else dragPx

    val opaque = !settling && !dragging && dragPx == 0f
    LaunchedEffect(opaque) { onOpaque(opaque) }

    suspend fun settleTo(target: Float) {
        settle.snapTo(dragPx)
        settling = true
        // No bounce on either settle: this is a panel, not a phone — and the
        // collapse has a handover waiting on it, which an overshoot would tease.
        settle.animateTo(
            target,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = if (target != 0f) Spring.StiffnessMedium else Spring.StiffnessMediumLow,
            ),
        )
        dragPx = target
        settling = false
    }

    // Rise into place — the expand side of the same motion the swipe-down runs.
    LaunchedEffect(Unit) { settleTo(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetPx.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragPx = 0f; upTravel = 0f; dragging = true },
                    onDragEnd = {
                        dragging = false
                        scope.launch {
                            when {
                                collapseCommitted(dragPx, collapsePx) -> {
                                    settleTo(collapsePx)
                                    onCollapse()
                                }
                                upTravel < -queueRevealPx -> onSwipeUp()
                                else -> settleTo(0f)
                            }
                        }
                    },
                    onDragCancel = { dragging = false; scope.launch { settleTo(0f) } },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        settling = false
                        // Up moves nothing — the cover staying put while the travel
                        // accumulates is the Android overlay's behaviour too; the
                        // release threshold alone decides what an up-swipe meant.
                        upTravel = (upTravel + dragAmount).coerceAtMost(0f)
                        dragPx = (dragPx + dragAmount).coerceAtLeast(0f)
                    },
                )
            }
            .background(Ink),
    ) {
        // The album wash behind everything — the backdrop the glass surfaces sample.
        MeltBackdrop(url = nowArt.url)
        // The bloom over it: the album's colours spread over the whole panel, drawn
        // after the wash and before the content (Box children paint in declaration
        // order), so text keeps its contrast however bright the album is.
        // With living colours on, the bleed is a slowly flowing liquid of the album's
        // colours (LiquidBleed.kt); off, the soft blooms.
        AlbumBleed(LocalPalette.current, living, 1f, drift = ambientMotion && !obscured, seed = nowArt.key.hashCode())

        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Drag handle — decor, but it says "this surface moves".
            Box(
                Modifier.size(width = 44.dp, height = 5.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(inkOn(0.3f))
            )

            // Top gap — generous headroom above the cover: the art floats well clear of
            // the drag handle rather than hugging it.
            Spacer(Modifier.fillMaxWidth().height(36.dp))

            // The album, as large as the screen allows — an explicit square, as wide as
            // the screen, packing directly under the handle. The band does NOT take
            // the column's leftover height (weighting it letterboxed the art and
            // left half a cover-height of air beneath it); the square's height IS
            // its width. Rounded and lifted on a glow in the album's own accent, so
            // the record sits *on* its colour rather than cut out of it. The stage holds
            // the cover still through a whole album and turns it over on a new one; the
            // living colours move the room around it, never the picture.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Swipe to skip, hold to turn the sleeve over, the volume along the edge —
                // each only with its switch on; with them all off this is the cover alone.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .swipeToSkip(touch, { scope.launch { model.next() } }, { scope.launch { model.previous() } }),
                ) {
                    AnimatedContent(
                        targetState = lyricsOpen,
                        transitionSpec = {
                            (fadeIn(tween(Motion.PAGE_MS, easing = Motion.Standard)) +
                                scaleIn(tween(Motion.PAGE_MS, easing = Motion.Emphasized), initialScale = 0.96f)) togetherWith
                                (fadeOut(tween(Motion.FADE_MS)) + scaleOut(tween(Motion.PAGE_MS), targetScale = 0.96f))
                        },
                        label = "coverLyrics",
                        modifier = Modifier.fillMaxSize(),
                    ) { words ->
                        if (words) {
                            PlayerLyrics(model, track, accent, karaoke, onClose = { lyricsOpen = false }, modifier = Modifier.fillMaxSize())
                        } else {
                            FlippableCover(sleeve, Modifier.fillMaxSize(), back = { SleeveBack(model) }) {
                                AlbumArtStage(
                                    art = nowArt,
                                    modifier = Modifier.fillMaxSize(),
                                    transition = transition,
                                    glow = accent,
                                    shape = RoundedCornerShape(28.dp),
                                    rest = { rest.value },
                                    contentDescription = track?.album ?: track?.title,
                                )
                            }
                        }
                    }
                    if (touch) {
                        VolumeRail(state?.volume, { v -> scope.launch { model.setVolume(v) } }, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }

            // Half the letterbox gap the old weighted band left: the art → badge space is
            // an explicit 20dp here on top of the column's 14dp spacing.
            Spacer(Modifier.fillMaxWidth().height(20.dp))

            // The signal chain in one line: what MPD is decoding (the quality badge, in
            // the album's accent) and how it is being levelled (the loudness chip —
            // one tap steps Off → Track → Album, the same three modes as Settings).
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // With the signal panel on, the badge opens the chain behind it.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .then(if (signalPanel) Modifier.clickable { signalOpen = true } else Modifier),
                ) { MpdQualityBadge(track = track, outputFormat = state?.outputFormat) }
                LoudnessChip(model)
                LyricsChip(lyricsOpen) { lyricsOpen = !lyricsOpen }
                if (sleepChip) SleepChip(model)
            }
            RadioChip(model)

            // Title block — centred, the overlay's own arrangement. Title and artist
            // are sized to *fill* the space under the cover, not just sit in it. On a
            // track change the old words lift away and the new ones rise into place.
            TrackTitles(
                id = track?.id,
                title = track?.title ?: "Nothing playing",
                subtitle = listOfNotNull(track?.artist, track?.album).joinToString("  ·  ")
                    .ifEmpty { "Queue an album from the library" },
                motion = textMotion,
            )

            // Seek — the wavy bar, in the album's accent: a travelling wave up to
            // the knob while playing, a flat rail when paused. Scrub commits on
            // release; the poll reconciles (the bar's latch holds the target until
            // the polled position catches up).
            WaveSeekBar(
                value = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                onChange = { },
                playing = playing,
                onCommit = { f -> scope.launch { model.seekTo((f * durMs).toLong()) } },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(posMs), color = TextMuted, fontSize = if (big) 26.sp else 14.sp, fontFamily = MonoFont)
                Text("−" + formatMs((durMs - posMs).coerceAtLeast(0)), color = TextMuted, fontSize = if (big) 26.sp else 14.sp, fontFamily = MonoFont)
            }
            // Transport — the accent carries the play circle, as on the phone. Skips are
            // pushed well out from the play circle so a hurried thumb cannot hit
            // the wrong one (≈42dp between visible edges); the row's horizontal
            // padding is halved to cancel the column's spacing so the spread is
            // exactly what is chosen here.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverButton(Icons.Default.SkipPrevious, "Previous", 64.dp, 32.dp) {
                    scope.launch { model.previous() }
                }
                PlayCircle(
                    playing = playing,
                    accent = accent,
                    modifier = Modifier.padding(horizontal = 28.dp).size(92.dp),
                    iconSize = 46.dp,
                    glow = 28.dp,
                ) { scope.launch { model.togglePause() } }
                CoverButton(Icons.Default.SkipNext, "Next", 64.dp, 32.dp) {
                    scope.launch { model.next() }
                }
            }
            // The column's leftover height pools here, under the transport —
            // invisible on the OLED black, and it keeps the packed upper stack
            // from over-flowing the screen on a small cover band.
            Spacer(Modifier.fillMaxWidth().weight(1f))
        }
        if (signalPanel) SignalSheet(model, signalOpen) { signalOpen = false }
    }
}

/**
 * The cover's loudness toggle — the phone's player-options ReplayGain block, folded
 * into one chip beside the quality badge. Shows the mode MPD reports (falling back to
 * the saved one); a tap steps to the next and hands it to MPD.
 */
@Composable
internal fun LoudnessChip(model: PanelModel) {
    val saved by model.settings.replayGainMode.collectAsState(initial = "album")
    val mpd by model.replayGain.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = rememberAccent()
    val mode = shownLoudness(saved, mpd)
    val on = mode != LoudnessMode.OFF.key
    val shape = RoundedCornerShape(11.dp)
    val tint by animateColorAsState(if (on) accent else TextMuted, tween(220), label = "loudTint")
    Row(
        Modifier
            .clip(shape)
            .background(if (on) tint.a(0.12f) else inkOn(0.06f))
            .border(1.dp, tint.a(if (on) 0.35f else 0.25f), shape)
            .clickable { scope.launch { model.setReplayGain(nextLoudness(mode)) } }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Loudness", tint = tint, modifier = Modifier.size(18.dp))
        Text(
            if (on) "${loudnessLabel(mode)} gain" else "Gain off",
            color = if (on) inkOn(0.92f) else TextMuted,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** A glass transport target at least 48dp — a panel is touched, not clicked. */
@Composable
private fun CoverButton(
    icon: ImageVector,
    label: String,
    boxSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(horizontal = 14.dp)
            .pressScale(press)
            .size(boxSize)
            .glassSurface(RoundedCornerShape(100.dp), inkOn(0.08f), border = Hairline)
            .clickable(interactionSource = press, indication = LocalIndication.current) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(iconSize))
    }
}

/**
 * The play / pause circle: the accent disc on a glow that brightens while the music
 * plays and settles when it stops; the glyph turns over between the two states, and the
 * whole disc gives a little under the thumb.
 */
@Composable
internal fun PlayCircle(
    playing: Boolean,
    accent: Color,
    modifier: Modifier,
    iconSize: Dp,
    glow: Dp,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val lift by animateDpAsState(if (playing) glow else glow * 0.45f, tween(600), label = "playGlow")
    Box(
        modifier
            .pressScale(press)
            .shadow(lift, CircleShape, ambientColor = accent, spotColor = accent)
            .clip(CircleShape)
            .background(accent)
            .clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (fadeIn(tween(180, delayMillis = 60)) + scaleIn(tween(240, delayMillis = 60), initialScale = 0.55f)) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.55f))
            },
            label = "playGlyph",
        ) { on ->
            Icon(
                if (on) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (on) "Pause" else "Play",
                tint = Ink,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** A touch target that gives a little while held, and springs back on release. */
internal fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.92f): Modifier = composed {
    val down by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (down) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/**
 * The cover's title and artist · album, changing with the track: the old pair lifts and
 * fades, the new title rises into its place and the line under it follows a beat later.
 * [motion] off cuts straight to the new words.
 */
@Composable
private fun TrackTitles(id: String?, title: String, subtitle: String, motion: Boolean) {
    AnimatedContent(
        targetState = Triple(id, title, subtitle),
        contentKey = { it.first },
        transitionSpec = {
            // The new words come in on their own (animateEnterExit below), staggered;
            // the container itself only carries the old pair away.
            val out = if (!motion) ExitTransition.None
            else fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it / 6 }
            (EnterTransition.None togetherWith out) using SizeTransform(clip = false)
        },
        modifier = Modifier.fillMaxWidth(),
        label = "trackTitles",
    ) { (_, t, sub) ->
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                t,
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = if (motion) Modifier.animateEnterExit(
                    enter = fadeIn(tween(420, delayMillis = 140)) +
                        slideInVertically(tween(560, delayMillis = 140, easing = FastOutSlowInEasing)) { it / 3 },
                    exit = ExitTransition.None,
                ) else Modifier,
            )
            Text(
                sub,
                color = TextSecondary,
                fontFamily = AppFont,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = if (motion) Modifier.animateEnterExit(
                    enter = fadeIn(tween(420, delayMillis = 240)) +
                        slideInVertically(tween(560, delayMillis = 240, easing = FastOutSlowInEasing)) { it / 2 },
                    exit = ExitTransition.None,
                ) else Modifier,
            )
        }
    }
}

/**
 * The collapsed player: a glass bar washed from the left in the album's accent, with
 * the cover, title / artist / playing-on line, inline transport, and the track's
 * progress as a hairline along its foot. Tap or swipe up expands the cover, the
 * inverse of the swipe that got you here.
 */
@Composable
private fun MiniPlayerBar(model: PanelModel, onExpand: () -> Unit) {
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = rememberAccent()

    val index = state?.index?.takeIf { it >= 0 } ?: 0
    val track = queue.getOrNull(index)
    val playing = state?.playing ?: false
    val durMs = state?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L
    val progress = if (durMs > 0) ((state?.positionMs ?: 0L).toFloat() / durMs).coerceIn(0f, 1f) else 0f

    // Enough upward travel to mean it, in pixels — the Android bar's threshold.
    val expandPx = with(LocalDensity.current) { 24.dp.toPx() }
    var upTravel by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(22.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .height(PanelMetrics.MiniBarHeight + 12.dp)
            .glassSurface(shape, inkOn(0.07f), border = Hairline)
            .clickable { onExpand() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { upTravel = 0f },
                    onDragEnd = { if (expandRequested(upTravel, expandPx)) onExpand() },
                    onDragCancel = { upTravel = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    upTravel = (upTravel + dragAmount).coerceAtMost(0f)
                }
            },
    ) {
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(accent.a(0.22f), Color.Transparent))))
        // Progress along the foot of the bar.
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp)
                .background(accent)
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtImage(
                url = LocalNowArt.current.url,
                name = track?.album ?: track?.title ?: "",
                shape = PanelShapes.Thumb,
                modifier = Modifier.size(60.dp),
            )

            // Title + artist + where it is playing.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    track?.title ?: "Nothing playing",
                    color = if (track == null) TextSecondary else TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = PanelMetrics.RowTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artist ?: "Queue an album from the library",
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = PanelMetrics.RowSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val device = state?.outputDeviceName
                if (!device.isNullOrBlank()) {
                    Text(
                        "on $device",
                        color = accent.a(0.9f),
                        fontFamily = AppFont,
                        fontSize = PanelMetrics.Meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Transport — nothing to drive when nothing is queued; the bar then stays
            // a plain tap-to-open target instead of offering dead buttons.
            if (track != null) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable {
                        scope.launch { model.previous() }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = TextSecondary, modifier = Modifier.size(32.dp))
                }
                PlayCircle(
                    playing = playing,
                    accent = accent,
                    modifier = Modifier.size(60.dp),
                    iconSize = 30.dp,
                    glow = 0.dp,
                ) { scope.launch { model.togglePause() } }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).clickable {
                        scope.launch { model.next() }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.SkipNext, "Next", tint = TextSecondary, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

/**
 * The bottom nav: a floating glass dock, the four tabs as icon-over-label targets
 * with the active one lifted onto an accent pill, and the connection status line
 * beneath — the landscape tab strip carries the status at its far edge; portrait's
 * strip is gone, so the line lives here instead.
 */
@Composable
private fun PortraitNavBar(model: PanelModel, selected: PanelTab, on: (PanelTab) -> Unit) {
    val status by model.statusLine.collectAsState()
    val accent = rememberAccent()
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PanelMetrics.NavHeight + 8.dp)
                .glassSurface(RoundedCornerShape(26.dp), inkOn(0.07f), border = Hairline)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelTab.entries.forEach { t ->
                val active = t == selected
                val bg by animateColorAsState(if (active) accent.a(0.18f) else Color.Transparent, tween(220), label = "navBg")
                val tint = if (active) accent else TextMuted
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .clickable { on(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(iconFor(t), t.label, tint = tint, modifier = Modifier.size(28.dp))
                    Text(
                        t.label,
                        color = if (active) TextPrimary else TextMuted,
                        fontFamily = AppFont,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                color = TextFaint,
                fontFamily = AppFont,
                fontSize = PanelMetrics.NavStatus,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            )
        }
    }
}

private fun iconFor(tab: PanelTab): ImageVector = when (tab) {
    PanelTab.LIBRARY -> Icons.Default.LibraryMusic
    PanelTab.QUEUE -> Icons.AutoMirrored.Filled.QueueMusic
    PanelTab.LIGHTS -> Icons.Default.Lightbulb
    PanelTab.SETTINGS -> Icons.Default.Settings
}

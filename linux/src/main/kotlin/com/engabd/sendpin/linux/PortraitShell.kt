package com.engabd.sendpin.linux

import androidx.compose.animation.animateColorAsState
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
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val index = state?.index?.takeIf { it >= 0 } ?: 0
    // Extracted once per cover change, off the UI thread; the previous palette
    // is held while a new one is pending (see rememberAlbumPalette).
    val palette = rememberAlbumPalette(queue.getOrNull(index)?.artUrl)

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccent provides palette.accent,
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

    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val art = queue.getOrNull(state?.index?.takeIf { it >= 0 } ?: 0)?.artUrl

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val screenHpx = with(LocalDensity.current) { maxHeight.toPx() }
        // Collapse carries the cover fully off the bottom edge, so the handover
        // to the tabs is seamless — Android could stop at 0.82 of the screen
        // because its mini bar covered the remainder; this shell has nothing
        // drawn over the cover's path.
        val collapsePx = screenHpx
        val queueRevealPx = screenHpx * QUEUE_REVEAL_FRACTION

        Box(Modifier.fillMaxSize()) {
            if (!(expanded && coverOpaque)) PaneAmbience(art, LocalPalette.current)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        PanelTab.LIBRARY -> LibraryPane(Modifier.fillMaxSize(), model, libraryState)
                        PanelTab.QUEUE -> QueuePane(Modifier.fillMaxSize(), model)
                        PanelTab.LIGHTS -> LightsPane(Modifier.fillMaxSize(), model)
                        PanelTab.SETTINGS -> SettingsPane(Modifier.fillMaxSize(), model)
                    }
                }
                MiniPlayerBar(model = model, onExpand = { expanded = true })
                PortraitNavBar(model = model, selected = tab, on = { tab = it })
            }
        }

        if (expanded) {
            NowPlayingCover(
                model = model,
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
        MeltBackdrop(url = track?.artUrl)
        // The bloom over it: the album's colours spread over the whole panel, drawn
        // after the wash and before the content (Box children paint in declaration
        // order), so text keeps its contrast however bright the album is.
        PanelBloom(LocalPalette.current, 1f)

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
            // the record sits *on* its colour rather than cut out of it.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(28.dp)
                ArtImage(
                    url = track?.artUrl,
                    name = track?.title ?: "",
                    shape = shape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .shadow(48.dp, shape, ambientColor = accent, spotColor = accent),
                )
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
                MpdQualityBadge(track = track, outputFormat = state?.outputFormat)
                LoudnessChip(model)
            }

            // Title block — centred, the overlay's own arrangement. Title and artist
            // are sized to *fill* the space under the cover, not just sit in it.
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    track?.title ?: "Nothing playing",
                    color = TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    listOfNotNull(track?.artist, track?.album).joinToString("  ·  ")
                        .ifEmpty { "Queue an album from the library" },
                    color = TextSecondary,
                    fontFamily = AppFont,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

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
                Text(formatMs(posMs), color = TextMuted, fontSize = 14.sp, fontFamily = MonoFont)
                Text("−" + formatMs((durMs - posMs).coerceAtLeast(0)), color = TextMuted, fontSize = 14.sp, fontFamily = MonoFont)
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
                Box(
                    Modifier
                        .padding(horizontal = 28.dp)
                        .size(92.dp)
                        .shadow(28.dp, CircleShape, ambientColor = accent, spotColor = accent)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable { scope.launch { model.togglePause() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        tint = Ink,
                        modifier = Modifier.size(46.dp),
                    )
                }
                CoverButton(Icons.Default.SkipNext, "Next", 64.dp, 32.dp) {
                    scope.launch { model.next() }
                }
            }
            // The column's leftover height pools here, under the transport —
            // invisible on the OLED black, and it keeps the packed upper stack
            // from over-flowing the screen on a small cover band.
            Spacer(Modifier.fillMaxWidth().weight(1f))
        }
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
    Box(
        Modifier
            .padding(horizontal = 14.dp)
            .size(boxSize)
            .glassSurface(RoundedCornerShape(100.dp), inkOn(0.08f), border = Hairline)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(iconSize))
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
                url = track?.artUrl,
                name = track?.title ?: "",
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
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable { scope.launch { model.togglePause() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        tint = Ink,
                        modifier = Modifier.size(30.dp),
                    )
                }
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

package com.engabd.sendpin.linux

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.engabd.sendpin.ui.design.ChameleonBloom
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.MeltBackdrop
import com.engabd.sendpin.ui.design.MpdQualityBadge
import com.engabd.sendpin.ui.design.WaveSeekBar
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.HairlineSoft
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextFaint
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
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
 * the cover's wash and blooms, the wave bar, the transport, the mini bar and
 * the nav all take their accent from the record — one colour source, so the
 * room adopts the album. The base layer stays composed underneath at all
 * times, so the collapse is a reveal, not a load, and tab selection survives
 * every expand/collapse.
 */
@Composable
fun PortraitShell(model: PanelModel) {
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
        PortraitShellBody(model)
    }
}

@Composable
private fun PortraitShellBody(model: PanelModel) {
    var tab by remember { mutableStateOf(PanelTab.LIBRARY) }
    var expanded by remember { mutableStateOf(true) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val screenHpx = with(LocalDensity.current) { maxHeight.toPx() }
        // Collapse carries the cover fully off the bottom edge, so the handover
        // to the tabs is seamless — Android could stop at 0.82 of the screen
        // because its mini bar covered the remainder; this shell has nothing
        // drawn over the cover's path.
        val collapsePx = screenHpx
        val queueRevealPx = screenHpx * QUEUE_REVEAL_FRACTION

        Column(Modifier.fillMaxSize().background(Ink)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    PanelTab.LIBRARY -> LibraryPane(Modifier.fillMaxSize(), model)
                    PanelTab.QUEUE -> QueuePane(Modifier.fillMaxSize(), model)
                    PanelTab.LIGHTS -> LightsPane(Modifier.fillMaxSize(), model)
                    PanelTab.SETTINGS -> SettingsPane(Modifier.fillMaxSize(), model)
                }
            }
            MiniPlayerBar(model = model, onExpand = { expanded = true })
            PortraitNavBar(model = model, selected = tab, on = { tab = it })
        }

        if (expanded) {
            NowPlayingCover(
                model = model,
                collapsePx = collapsePx,
                queueRevealPx = queueRevealPx,
                onCollapse = { expanded = false },
                onSwipeUp = {
                    tab = PanelTab.QUEUE
                    expanded = false
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
    val settle = remember { Animatable(collapsePx) }
    val offsetPx = if (settling) settle.value else dragPx

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
                    onDragStart = { dragPx = 0f; upTravel = 0f },
                    onDragEnd = {
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
                    onDragCancel = { scope.launch { settleTo(0f) } },
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
        // The bloom over it: the album's colours bleeding across the canvas, drawn
        // after the wash and before the content (Box children paint in declaration
        // order), so text keeps its contrast however bright the album is.
        CoverBloom()

        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Drag handle — decor, but it says "this surface moves".
            Box(
                Modifier.size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Hairline)
            )

            // The album, as large as the screen allows. Fit, never Crop — a square
            // cover inside a taller box letterboxes rather than losing artwork.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val art = track?.artUrl
                if (art != null) {
                    coil3.compose.AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "♪",
                        color = TextMuted,
                        fontSize = 64.sp,
                        fontFamily = AppFont,
                    )
                }
            }

            // The quality badge — what MPD is actually decoding, in the album's accent:
            // codec + rate/depth (+ live bitrate), lit up on hi-res. Sits between
            // the cover and the title, exactly where the phone's overlay puts it.
            MpdQualityBadge(track = track, outputFormat = state?.outputFormat)

            // Title block — centred now, the overlay's own arrangement.
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    track?.title ?: "Nothing playing",
                    color = TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    track?.artist ?: "Queue an album from the library",
                    color = TextSecondary,
                    fontFamily = AppFont,
                    fontSize = 15.sp,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(posMs), color = TextMuted, fontSize = 11.sp, fontFamily = AppFont)
                Text(formatMs(durMs), color = TextMuted, fontSize = 11.sp, fontFamily = AppFont)
            }

            // Transport — the accent carries the play circle, as on the phone.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverButton(Icons.Default.SkipPrevious, "Previous", 52.dp, 26.dp) {
                    scope.launch { model.previous() }
                }
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable { scope.launch { model.togglePause() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        tint = Ink,
                        modifier = Modifier.size(36.dp),
                    )
                }
                CoverButton(Icons.Default.SkipNext, "Next", 52.dp, 26.dp) {
                    scope.launch { model.next() }
                }
            }

            // The live format readout — MPD is the decoder; show what it is decoding.
            val out = state?.outputFormat
            if (out != null && playing) {
                Text(
                    "MPD → ${state?.outputDeviceName ?: ""} · ${fmtRate(out.sampleRateHz)}/${out.bitDepth}",
                    color = TextFaint,
                    fontSize = 11.sp,
                    fontFamily = AppFont,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The album's colours bleeding across the player canvas — the bloom layer.
 * Sibling of the cover's column, drawn after the wash and before the content
 * (Box children paint in declaration order), so text keeps its contrast.
 */
@Composable
private fun BoxScope.CoverBloom() {
    ChameleonBloom(LocalPalette.current, 1f)
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
            .glassSurface(RoundedCornerShape(100.dp), Glass, border = Hairline)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(iconSize))
    }
}

/**
 * The collapsed player's bar: thumbnail, title / artist / playing-on line, and
 * inline transport — the accent carries the play circle. Tap or swipe up
 * expands the cover, the inverse of the swipe that got you here.
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

    // Enough upward travel to mean it, in pixels — the Android bar's threshold.
    val expandPx = with(LocalDensity.current) { 24.dp.toPx() }
    var upTravel by remember { mutableFloatStateOf(0f) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Ink2)
            .border(1.dp, HairlineSoft, RoundedCornerShape(16.dp))
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
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Thumbnail.
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Glass),
            contentAlignment = Alignment.Center,
        ) {
            val art = track?.artUrl
            if (art != null) {
                coil3.compose.AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("♪", color = TextMuted, fontSize = 18.sp, fontFamily = AppFont)
            }
        }

        // Title + artist + where it is playing.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                track?.title ?: "Nothing playing",
                color = if (track == null) TextSecondary else TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track?.artist ?: "Queue an album from the library",
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val device = state?.outputDeviceName
            if (!device.isNullOrBlank()) {
                Text(
                    "($device)",
                    color = TextFaint,
                    fontFamily = AppFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Transport — nothing to drive when nothing is queued; the bar then stays
        // a plain tap-to-open target instead of offering dead buttons.
        if (track != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable {
                    scope.launch { model.previous() }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = TextSecondary, modifier = Modifier.size(26.dp))
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable { scope.launch { model.togglePause() } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    tint = Ink,
                    modifier = Modifier.size(24.dp),
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable {
                    scope.launch { model.next() }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.SkipNext, "Next", tint = TextSecondary, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/**
 * The bottom nav: the four tabs as icon-over-label targets, and the connection
 * status line beneath — the landscape tab strip carries the status at its far
 * edge; portrait's strip is gone, so the line lives here instead. The active
 * tab takes the album accent.
 */
@Composable
private fun PortraitNavBar(model: PanelModel, selected: PanelTab, on: (PanelTab) -> Unit) {
    val status by model.statusLine.collectAsState()
    val accent = rememberAccent()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelTab.entries.forEach { t ->
                val active = t == selected
                val tint = if (active) accent else TextMuted
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { on(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(iconFor(t), t.label, tint = tint, modifier = Modifier.size(22.dp))
                    Text(
                        t.label,
                        color = tint,
                        fontFamily = AppFont,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 10.sp,
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
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
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
package com.engabd.sendpin.linux.screensaver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.ui.AlbumArtStage
import com.engabd.sendpin.linux.ui.AlbumBleed
import com.engabd.sendpin.linux.ui.ArtTransition
import com.engabd.sendpin.linux.ui.LivingArt
import com.engabd.sendpin.linux.ui.rememberLivingArt
import com.engabd.sendpin.linux.ui.rememberNowArt
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.mpdBadgeQuality
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont
import kotlinx.coroutines.delay

/**
 * The listening view: the always-on panel's answer to a session that just plays. Music
 * playing and nobody touching for the set delay, the controls step aside and the record
 * takes the screen — the cover large and quietly alive, the room lit in its colours, the
 * title set in a face chosen for it, a hairline of progress and the signal path beneath.
 * Everything drifts a few pixels over minutes, since the screen never sleeps.
 *
 * The gallery (`ScreensaverHost`) is its counterpart for when nothing plays; the two
 * gates ([IdleGate.shouldListen] / [IdleGate.shouldShow]) can never both be open. A tap
 * wakes the panel and goes no further; the music stopping ends it on its own.
 *
 * Lives above the whole shell in `Main.kt`, next to the gallery, fed the same [gate].
 */
@Composable
fun ListeningHost(model: PanelModel, gate: IdleGate) {
    val settings = model.settings
    val enabled by settings.listeningMode.collectAsState(initial = true)
    val delayMin by settings.listeningDelayMin.collectAsState(initial = 3)
    val clock by settings.listeningClock.collectAsState(initial = true)
    val layout by settings.listeningLayout.collectAsState(initial = "classic")
    // The burn-in drift is one setting for both always-on views.
    val drift by settings.screensaverDrift.collectAsState(initial = true)
    val remote by model.state.collectAsState()
    val forced by model.listeningNow.collectAsState()
    val playing = remote?.playing == true

    var showing by remember { mutableStateOf(false) }

    // Stopping ends the view (the gallery takes over after its own delay, counted from here).
    LaunchedEffect(playing) { if (!playing && !forced) showing = false }
    LaunchedEffect(forced) { if (forced) showing = true }
    LaunchedEffect(showing) { model.coverObscured.value = showing }
    LaunchedEffect(enabled, delayMin, playing) {
        val delayMs = delayMin.coerceAtLeast(1) * 60_000L
        while (true) {
            if (IdleGate.shouldListen(enabled, playing, gate.idleMs(), delayMs)) showing = true
            delay(1_000)
        }
    }

    fun dismiss() {
        showing = false
        model.listeningNow.value = false
        gate.touch()
    }

    // The view takes touches only once it is fully up (overlayCaptures). It used to take
    // them for as long as it was drawn: the tap after the one that woke it landed in the
    // fade-out and was swallowed, so pause took two or three goes. While it fades in, a tap
    // goes through to the controls the eye still sees, and the view steps aside.
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = showing
    val captures = overlayCaptures(visibility)
    StepAsideOnPress(gate, showing, captures, onStepAside = ::dismiss)

    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(1_600)),
        exit = fadeOut(tween(600)),
    ) {
        ListeningView(model, ListeningLayout.from(layout), clock = clock, drift = drift, captures = captures, onTap = ::dismiss)
    }
}

/** How the listening view lays the record out — Settings → Listening mode → Layout. */
internal enum class ListeningLayout(val key: String, val label: String, val description: String) {
    CLASSIC("classic", "Classic", "The cover beside its words, the room lit in its colours."),
    VINYL("vinyl", "Vinyl", "The record on the platter, its cover as the label, turning while it plays."),
    POSTER("poster", "Poster", "The title set huge like a gig poster, the cover small in the corner."),
    COVER("cover", "Cover only", "Just the artwork, large, in its own light."),
    ;

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}

@Composable
private fun ListeningView(
    model: PanelModel,
    layout: ListeningLayout,
    clock: Boolean,
    drift: Boolean,
    captures: Boolean,
    onTap: () -> Unit,
) {
    val remote by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val transition by model.settings.albumTransition.collectAsState(initial = ArtTransition.FLIP)
    val track = queue.getOrNull(remote?.index?.takeIf { it >= 0 } ?: 0)
    val nowArt = rememberNowArt(model)
    val palette = rememberAlbumPalette(nowArt.url)
    val living = rememberLivingArt(model, onOverlay = true)
    val driftOffset = rememberDrift(drift)

    // One slow clock for the room's colours, read only while drawing.
    val age = rememberInfiniteTransition(label = "listeningAge").animateFloat(
        0f, 3_600_000f,
        infiniteRepeatable(tween(3_600_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "listeningClock",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!captures) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onTap() }) },
            ),
    ) {
        RoomLight(palette, living, seed = nowArt.key.hashCode()) { age.value }
        // Read once here: inside the Row and Column below, the box's own scope is not
        // reachable (Compose's layout-scope marker), so the sizes travel as plain values.
        val w = maxWidth
        val h = maxHeight
        val landscape = w > h
        val playing = remote?.playing == true
        // The poster keeps the top left for its small cover, so its clock sits right.
        val clockRight = landscape || layout == ListeningLayout.POSTER

        if (clock) {
            Clock(
                accent = null,
                neutral = false,
                scale = 0.78f,
                align = if (clockRight) Alignment.End else Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(if (clockRight) Alignment.TopEnd else Alignment.TopCenter)
                    .padding(top = 36.dp, start = maxWidth * 0.07f, end = maxWidth * 0.07f)
                    .offset { driftOffset() },
            )
        }

        val cover: @Composable (Modifier) -> Unit = { m ->
            AlbumArtStage(
                art = nowArt,
                modifier = m.aspectRatio(1f),
                transition = transition,
                glow = palette.accent,
                shape = RoundedCornerShape(14.dp),
                glowElevation = 64.dp,
                contentDescription = track?.album ?: track?.title,
            )
        }

        // The record itself: the cover, or (Vinyl) the cover as a turning record's label.
        val record: @Composable (Modifier) -> Unit = if (layout == ListeningLayout.VINYL) {
            { m -> VinylRecord(nowArt, palette.accent, playing, transition, m) }
        } else {
            cover
        }

        when {
            layout == ListeningLayout.COVER -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = maxWidth * 0.07f)
                    .padding(top = if (clock) 56.dp else 0.dp)
                    .offset { driftOffset() },
                contentAlignment = Alignment.Center,
            ) {
                cover(if (landscape) Modifier.size(minOf(h * 0.78f, w * 0.6f)) else Modifier.fillMaxWidth(0.94f))
            }

            layout == ListeningLayout.POSTER -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = maxWidth * 0.07f)
                    .padding(top = 40.dp, bottom = maxHeight * 0.08f)
                    .offset { driftOffset() },
            ) {
                cover(Modifier.size(if (landscape) h * 0.26f else w * 0.44f))
                Spacer(Modifier.weight(1f))
                TrackWords(model, track, TextAlign.Start, titleMaxSp = 150f)
            }

            landscape -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = maxWidth * 0.07f)
                    .padding(top = if (clock) 40.dp else 0.dp)
                    .offset { driftOffset() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val vinyl = layout == ListeningLayout.VINYL
                record(Modifier.size(if (vinyl) minOf(h * 0.82f, w * 0.48f) else minOf(h * 0.70f, w * 0.42f)))
                Spacer(Modifier.width(w * 0.06f))
                Box(Modifier.weight(1f)) {
                    TrackWords(model, track, TextAlign.Start, titleMaxSp = 64f)
                }
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = maxWidth * 0.07f)
                    .offset { driftOffset() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(if (clock) 1.15f else 0.8f))
                record(Modifier.fillMaxWidth(if (layout == ListeningLayout.VINYL) 0.98f else 0.92f))
                Spacer(Modifier.height(52.dp))
                TrackWords(model, track, TextAlign.Center, titleMaxSp = 54f)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The room the record is played in. With living colours on, the album's colours as a
 * slowly flowing liquid over near-black (LiquidBleed.kt); off, its palette wandering in
 * soft blooms (the gallery's colour bleed). Either way eased across on an album change
 * and darkened toward the edges so the words keep their contrast.
 */
@Composable
private fun BoxScope.RoomLight(palette: AlbumPalette, living: LivingArt, seed: Int, age: () -> Float) {
    if (living.strength > 0f) {
        AlbumBleed(palette, living, level = 1f, drift = false, seed = seed)
    } else {
        val tones = albumInk(palette).bleed
        fun tone(i: Int) = tones[i % tones.size]
        val c0 by animateColorAsState(tone(0), tween(1_600), label = "room0")
        val c1 by animateColorAsState(tone(1), tween(1_600), label = "room1")
        val c2 by animateColorAsState(tone(2), tween(1_600), label = "room2")
        val c3 by animateColorAsState(tone(3), tween(1_600), label = "room3")
        ColourBleed(listOf(c0, c1, c2, c3), level = 0.72f, age = age)
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                0.35f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.6f),
                center = Offset(size.width / 2f, size.height * 0.45f),
                radius = maxOf(size.width, size.height) * 0.72f,
            ),
        )
    }
}

/**
 * Artist, title, album, the track's progress as a hairline and the signal path — the
 * whole block crossing over to the next track's words when the track changes.
 */
@Composable
private fun TrackWords(model: PanelModel, track: LocalTrack?, align: TextAlign, titleMaxSp: Float) {
    val remote by model.state.collectAsState()
    val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = horizontal) {
        Crossfade(track, Modifier.fillMaxWidth(), tween(900), label = "listeningWords") { track ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = horizontal) {
                val artist = track?.artist
                if (!artist.isNullOrBlank()) {
                    Text(
                        artist.uppercase(),
                        color = Color.White.copy(alpha = 0.72f),
                        fontFamily = AppFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        letterSpacing = 0.32.em,
                        textAlign = align,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                val title = track?.title ?: "Nothing playing"
                val face = faceFor(listOfNotNull(track?.genre), null, title, track?.album ?: track?.id ?: title)
                FittedTitle(title, face, titleMaxSp, align, Color.White, Modifier)
                val album = track?.album
                if (!album.isNullOrBlank() && album != title) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        album,
                        color = Color.White.copy(alpha = 0.58f),
                        fontFamily = AppFont,
                        fontStyle = FontStyle.Italic,
                        fontSize = 17.sp,
                        textAlign = align,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        // The track's progress: a hairline, no knob — something to glance at, not to drag.
        val dur = remote?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L
        val target = if (dur > 0) ((remote?.positionMs ?: 0L).toFloat() / dur).coerceIn(0f, 1f) else 0f
        // the poll moves once a second; glide between readings rather than step
        val fraction by animateFloatAsState(target, tween(1_000, easing = LinearEasing), label = "listeningProgress")
        Box(
            Modifier
                .fillMaxWidth(if (align == TextAlign.Center) 0.46f else 0.7f)
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(1.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(1.dp)),
            )
        }

        val signal = signalLine(
            mpdBadgeQuality(track, remote?.outputFormat)?.label,
            remote?.outputDeviceName,
        )
        if (signal.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                signal,
                color = Color.White.copy(alpha = 0.46f),
                fontFamily = MonoFont,
                fontSize = 12.sp,
                letterSpacing = 0.14.em,
                textAlign = align,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "FLAC 24/96  →  Topping DX5 II" — what MPD is decoding and where it goes; parts may be missing. */
internal fun signalLine(quality: String?, device: String?): String =
    listOfNotNull(quality?.trim()?.takeIf { it.isNotEmpty() }, device?.trim()?.takeIf { it.isNotEmpty() })
        .joinToString("  →  ")

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
import kotlinx.coroutines.launch
import com.engabd.sendpin.linux.station.trackStarts
import com.engabd.sendpin.linux.station.sleepLabel
import com.engabd.sendpin.linux.station.sideOf
import com.engabd.sendpin.linux.station.setNeedle
import com.engabd.sendpin.linux.station.savePostcard
import com.engabd.sendpin.linux.station.locate
import com.engabd.sendpin.linux.station.grooveProgress
import com.engabd.sendpin.linux.station.SignalPath
import com.engabd.sendpin.linux.station.QueueDeck
import com.engabd.sendpin.linux.station.PostcardData
import com.engabd.sendpin.linux.station.LyricSheet
import com.engabd.sendpin.linux.station.LyricStrip
import com.engabd.sendpin.linux.station.lyricStyle
import com.engabd.sendpin.linux.formatMs
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.Animatable
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
    val delayS by settings.listeningDelayS.collectAsState(initial = 0)
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
    LaunchedEffect(enabled, delayMin, delayS, playing) {
        val delayMs = IdleGate.delayMs(delayMin, delayS, floorMin = 1)
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
    LYRICS("lyrics", "Lyric sheet", "The song's words rolling large and timed, set in the album's own lettering."),
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
    val settings = model.settings
    val remote by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val albumIds by model.albumIds.collectAsState()
    val transition by settings.albumTransition.collectAsState(initial = ArtTransition.FLIP)
    // The station's additions: each is a switch in Settings and off until switched on.
    val tonearm by settings.tonearm.collectAsState(initial = false)
    val tonearmSeek by settings.tonearmSeek.collectAsState(initial = false)
    val deck by settings.queueDeck.collectAsState(initial = false)
    val postcards by settings.postcards.collectAsState(initial = false)
    val big by settings.bigReadouts.collectAsState(initial = false)
    val signalPanel by settings.signalPanel.collectAsState(initial = false)
    val sleep by model.sleep.collectAsState()
    val karaoke by settings.karaokeFill.collectAsState(initial = true)
    val withLyrics by settings.listeningLyrics.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    val index = remote?.index?.takeIf { it >= 0 } ?: 0
    val track = queue.getOrNull(index)
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

    // The tonearm's side of the record: the album's run of the queue, laid out as groove.
    val side = remember(queue, index, albumIds) { sideOf(queue, index, albumIds) }
    val arm = if (layout == ListeningLayout.VINYL && tonearm && side != null) {
        val lengths = side.durations
        val here = side.indexOf(index)
        ArmSpec(
            starts = trackStarts(lengths),
            current = here,
            progress = grooveProgress(lengths, here, remote?.positionMs ?: 0L),
            onSeek = if (tonearmSeek) { f -> scope.launch { setNeedle(model, side, f) } } else null,
            label = { f ->
                val at = locate(lengths, f)
                "${at.track + 1} · ${side.tracks[at.track].title} · ${formatMs(at.offsetMs)}"
            },
        )
    } else null

    // A postcard: hold the cover. The flash and the line that follows are the acknowledgement.
    var toast by remember { mutableStateOf<String?>(null) }
    val flash = remember { Animatable(0f) }
    fun makePostcard() {
        val t = track ?: return
        scope.launch {
            flash.snapTo(0.55f)
            launch { flash.animateTo(0f, tween(600)) }
            toast = "Saving postcard…"
            val saved = runCatching {
                savePostcard(
                    PostcardData(
                        title = t.title, artist = t.artist, album = t.album, genre = t.genre,
                        signal = signalLine(mpdBadgeQuality(t, remote?.outputFormat)?.label, remote?.outputDeviceName),
                        coverUrl = nowArt.url ?: t.artUrl, id = t.id,
                    ),
                )
            }
            toast = saved.fold({ "Postcard saved to Pictures/CAMusic" }, { "Couldn't save the postcard" })
            delay(3_500)
            toast = null
        }
    }
    val withPostcard: (Modifier) -> Modifier = { m ->
        if (!postcards || !captures) m
        else m.pointerInput(track?.id) { detectTapGestures(onTap = { onTap() }, onLongPress = { makePostcard() }) }
    }

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
                scale = if (big) 1.25f else 0.78f,
                align = if (clockRight) Alignment.End else Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(if (clockRight) Alignment.TopEnd else Alignment.TopCenter)
                    .padding(top = 36.dp, start = maxWidth * 0.07f, end = maxWidth * 0.07f)
                    .offset { driftOffset() },
            )
        }
        sleep?.let { t ->
            Text(
                "☾  " + sleepLabel(t, System.currentTimeMillis()),
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = MonoFont,
                fontSize = 14.sp,
                letterSpacing = 0.1.em,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 40.dp, start = maxWidth * 0.07f)
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
            { m -> VinylRecord(nowArt, palette.accent, playing, transition, withPostcard(m), arm) }
        } else {
            { m -> cover(withPostcard(m)) }
        }
        val deckOn = layout == ListeningLayout.VINYL && deck
        // The song's words under the record, in every layout but the lyric sheet (which is all words).
        val strip: @Composable (TextAlign, Float, Modifier) -> Unit = { align, sp, m ->
            if (withLyrics) {
                val title = track?.title ?: ""
                val face = faceFor(listOfNotNull(track?.genre), null, title, track?.album ?: track?.id ?: title)
                LyricStrip(model, track, lyricStyle(face, if (big) sp * 1.15f else sp, align), align, palette.accent, karaoke, m)
            }
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
                record(if (landscape) Modifier.size(minOf(h * 0.78f, w * 0.6f)) else Modifier.fillMaxWidth(0.94f))
                strip(TextAlign.Center, 30f, Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.05f))
            }

            layout == ListeningLayout.POSTER -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = maxWidth * 0.07f)
                    .padding(top = 40.dp, bottom = maxHeight * 0.08f)
                    .offset { driftOffset() },
            ) {
                record(Modifier.size(if (landscape) h * 0.26f else w * 0.44f))
                Spacer(Modifier.weight(1f))
                strip(TextAlign.Start, 34f, Modifier.padding(bottom = 28.dp))
                TrackWords(model, track, TextAlign.Start, titleMaxSp = 150f, big = big, signalPanel = signalPanel, accent = palette.accent)
            }

            layout == ListeningLayout.LYRICS -> LyricsLayout(
                model, track, landscape, w, h, clock, { driftOffset() }, big, palette.accent, karaoke,
            ) { m -> record(m) }

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
                Column(Modifier.weight(1f)) {
                    TrackWords(model, track, TextAlign.Start, titleMaxSp = 64f, big = big, signalPanel = signalPanel, accent = palette.accent)
                    strip(TextAlign.Start, 28f, Modifier.padding(top = 26.dp))
                    if (deckOn) {
                        Spacer(Modifier.height(24.dp))
                        QueueDeck(model, queue, index, palette.accent)
                    }
                }
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = maxWidth * 0.07f)
                    .offset { driftOffset() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The words sit in a slot of their own, top-aligned, so a longer title grows
                // down into the slot's spare room and never moves the record. (Weighted
                // spacers on both sides of the words let the record rise as they grew.) The
                // weights put the record where a one-line title used to leave it.
                Spacer(Modifier.weight(portraitTopWeight(clock, deckOn, signalPanel)))
                // With the queue deck on, the record steps back to leave it room.
                record(Modifier.fillMaxWidth(if (layout != ListeningLayout.VINYL) 0.92f else if (deckOn) 0.80f else 0.98f))
                Spacer(Modifier.height(if (withLyrics) 28.dp else 52.dp))
                strip(TextAlign.Center, 28f, Modifier.padding(bottom = 18.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    TrackWords(model, track, TextAlign.Center, titleMaxSp = 54f, big = big, signalPanel = signalPanel, accent = palette.accent)
                }
                if (deckOn) QueueDeck(model, queue, index, palette.accent, Modifier.padding(top = 6.dp, bottom = 26.dp))
            }
        }

        // The shutter, and what it saved.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
        toast?.let { line ->
            Text(
                line,
                color = Color.White.copy(alpha = 0.9f),
                fontFamily = MonoFont,
                fontSize = 14.sp,
                letterSpacing = 0.08.em,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The lyric sheet layout: a small cover and the song's name above, the words rolling below
 * in the album's own face (landscape: the cover and name left, the words right). Without
 * lyrics for the track the sheet shows a rest and the layout still looks finished.
 */
@Composable
private fun LyricsLayout(
    model: PanelModel,
    track: LocalTrack?,
    landscape: Boolean,
    w: Dp,
    h: Dp,
    clock: Boolean,
    drift: () -> IntOffset,
    big: Boolean,
    accent: Color,
    karaoke: Boolean,
    cover: @Composable (Modifier) -> Unit,
) {
    val title = track?.title ?: "Nothing playing"
    val face = faceFor(listOfNotNull(track?.genre), null, title, track?.album ?: track?.id ?: title)
    val sheet: @Composable (Modifier, Float) -> Unit = { m, sp ->
        LyricSheet(model, track, face, m, TextAlign.Start, sizeSp = if (big) sp * 1.2f else sp, accent = accent, karaoke = karaoke) { NoLyrics() }
    }
    if (landscape) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = w * 0.07f)
                .padding(top = if (clock) 40.dp else 0.dp)
                .offset { drift() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(0.38f)) {
                cover(Modifier.size(minOf(h * 0.46f, w * 0.3f)))
                Spacer(Modifier.height(24.dp))
                LyricHeader(track, face)
                Spacer(Modifier.height(20.dp))
                ProgressHairline(model, track, 0.9f)
            }
            Spacer(Modifier.width(w * 0.05f))
            sheet(Modifier.weight(0.62f).fillMaxHeight(), 44f)
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = w * 0.07f)
                .padding(top = if (clock) 124.dp else 48.dp, bottom = h * 0.04f)
                .offset { drift() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                cover(Modifier.size(w * 0.20f))
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(1f)) { LyricHeader(track, face) }
            }
            Spacer(Modifier.height(8.dp))
            sheet(Modifier.weight(1f).fillMaxWidth(), 40f)
            ProgressHairline(model, track, 1f)
        }
    }
}

/** The song's name above its lyrics: the artist in spaced capitals, the title in its face. */
@Composable
private fun LyricHeader(track: LocalTrack?, face: Face) {
    Column(Modifier.fillMaxWidth()) {
        val artist = track?.artist
        if (!artist.isNullOrBlank()) {
            Text(
                artist.uppercase(),
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = AppFont,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = 0.3.em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
        FittedTitle(track?.title ?: "Nothing playing", face, 34f, TextAlign.Start, Color.White, Modifier)
    }
}

/** The sheet's rest: no words for this track, said quietly. */
@Composable
private fun NoLyrics() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("♪", color = Color.White.copy(alpha = 0.22f), fontSize = 72.sp, fontFamily = AppFont)
        Spacer(Modifier.height(8.dp))
        Text(
            "no lyrics for this track",
            color = Color.White.copy(alpha = 0.32f),
            fontFamily = MonoFont,
            fontSize = 13.sp,
            letterSpacing = 0.14.em,
        )
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
 * whole block crossing over to the next track's words when the track changes. [big] adds
 * the times in large figures; [signalPanel] sets the signal path as lit stages.
 */
@Composable
private fun TrackWords(
    model: PanelModel,
    track: LocalTrack?,
    align: TextAlign,
    titleMaxSp: Float,
    big: Boolean = false,
    signalPanel: Boolean = false,
    accent: Color = Color.White,
) {
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
        ProgressHairline(model, track, if (align == TextAlign.Center) 0.46f else 0.7f, align)

        if (big) {
            // For reading from across the room: where we are, and how long is left.
            val dur = remote?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L
            val pos = remote?.positionMs ?: 0L
            Spacer(Modifier.height(14.dp))
            Text(
                "${formatMs(pos)}  /  ${formatMs(dur)}",
                color = Color.White.copy(alpha = 0.82f),
                fontFamily = MonoFont,
                fontSize = 32.sp,
                letterSpacing = 0.06.em,
                textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (signalPanel) {
            Spacer(Modifier.height(18.dp))
            SignalPath(model, Modifier.fillMaxWidth(), accent = accent)
        } else {
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
                    fontSize = if (big) 16.sp else 12.sp,
                    letterSpacing = 0.14.em,
                    textAlign = align,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The track's progress: a hairline, no knob — something to glance at, not to drag. */
@Composable
private fun ProgressHairline(model: PanelModel, track: LocalTrack?, widthFraction: Float, align: TextAlign = TextAlign.Start) {
    val remote by model.state.collectAsState()
    val dur = remote?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L
    val target = if (dur > 0) ((remote?.positionMs ?: 0L).toFloat() / dur).coerceIn(0f, 1f) else 0f
    // the poll moves once a second; glide between readings rather than step
    val fraction by animateFloatAsState(target, tween(1_000, easing = LinearEasing), label = "listeningProgress")
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
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
}

/** "FLAC 24/96  →  Topping DX5 II" — what MPD is decoding and where it goes; parts may be missing. */
internal fun signalLine(quality: String?, device: String?): String =
    listOfNotNull(quality?.trim()?.takeIf { it.isNotEmpty() }, device?.trim()?.takeIf { it.isNotEmpty() })
        .joinToString("  →  ")

/**
 * The weight of the space above the record in the portrait layout, against the words' slot
 * below it (weight 1). The record sits where a one-line title used to leave it; the queue
 * deck and the signal panel each need more of the height for the words and what is
 * under them, so they take it from the space above.
 */
internal fun portraitTopWeight(clock: Boolean, deck: Boolean, signalPanel: Boolean): Float = when {
    deck -> if (clock) 0.28f else 0.20f
    signalPanel -> if (clock) 0.42f else 0.30f
    else -> if (clock) 0.53f else 0.40f
}

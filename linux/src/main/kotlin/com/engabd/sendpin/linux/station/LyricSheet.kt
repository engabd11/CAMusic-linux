package com.engabd.sendpin.linux.station

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.screensaver.Face
import com.engabd.sendpin.linux.screensaver.specOf
import com.engabd.sendpin.ma.LyricLine
import com.engabd.sendpin.ma.MaLyrics
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * The words, rolling: the lyric sheet of a record, set large in the album's own face, the
 * line being sung at the eye and the lines around it falling away. Timed lyrics follow the
 * music line by line; plain ones drift down at the pace of the track.
 */

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Found(val lyrics: MaLyrics) : LyricsState
}

/**
 * MPD's position between its once-a-second readings, carried on by the clock. A state read
 * only through `derivedStateOf` (as the sheet does) recomposes when the line changes, not
 * ten times a second.
 */
@Composable
internal fun rememberPlayhead(model: PanelModel): State<Long> {
    val remote by model.state.collectAsState()
    val latest = rememberUpdatedState(remote)
    val position = remember { mutableLongStateOf(0L) }
    var stamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(remote) { stamp = System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            val r = latest.value
            position.longValue = if (r == null) 0L else playheadMs(r.positionMs, stamp, System.currentTimeMillis(), r.playing)
            delay(100)
        }
    }
    return position
}

/**
 * The sheet for [track]. While the lyrics load, and when there are none, [fallback] is drawn
 * instead, so the layout is never an empty screen.
 */
@Composable
internal fun LyricSheet(
    model: PanelModel,
    track: LocalTrack?,
    face: Face,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    sizeSp: Float = 34f,
    fallback: @Composable () -> Unit,
) {
    val key = track?.scrobbleId ?: track?.id
    val state by produceState<LyricsState>(LyricsState.Loading, key) {
        value = LyricsState.Loading
        value = track?.let { model.lyricsFor(it) }?.let { LyricsState.Found(it) } ?: LyricsState.None
    }
    when (val s = state) {
        is LyricsState.Found -> Sheet(model, s.lyrics, face, modifier, align, sizeSp)
        else -> Box(modifier) { fallback() }
    }
}

@Composable
private fun Sheet(model: PanelModel, lyrics: MaLyrics, face: Face, modifier: Modifier, align: TextAlign, sizeSp: Float) {
    val spec = specOf(face)
    val base = sizeSp * spec.scale.coerceIn(0.86f, 1.12f)
    val style = TextStyle(
        fontFamily = spec.family,
        fontWeight = spec.weight,
        fontStyle = spec.style,
        fontSize = base.sp,
        letterSpacing = spec.tracking.em,
        lineHeight = (base * maxOf(spec.lineHeight, 1.14f)).sp,
        textAlign = align,
    )
    val lines = remember(lyrics) { sheetLines(lyrics.lines, lyrics.synced) }
    val playhead = rememberPlayhead(model)
    BoxWithConstraints(modifier.fadeEdges()) {
        val h = maxHeight
        if (lyrics.synced) {
            val active by remember(lines) { derivedStateOf { activeLyricIndex(lines, playhead.value) } }
            val listState = rememberLazyListState()
            LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active) }
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(top = h * 0.36f, bottom = h * 0.6f),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines) { i, line -> SungLine(line, i - active, style, align) }
            }
        } else {
            val scroll = rememberScrollState()
            val remote by model.state.collectAsState()
            val fraction = plainLyricFraction(remote?.positionMs ?: 0L, remote?.durationMs ?: 0L)
            LaunchedEffect(fraction, scroll.maxValue) {
                scroll.animateScrollTo((fraction * scroll.maxValue).toInt(), tween(1_000, easing = LinearEasing))
            }
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = false)) {
                Spacer(Modifier.height(h * 0.30f))
                lines.forEach { l ->
                    if (l.text.isBlank()) Spacer(Modifier.height((base * 0.9f).dp))
                    else Text(
                        l.text, style = style, color = Color.White.copy(alpha = 0.86f),
                        modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(h * 0.6f))
            }
        }
    }
}

/** One line of a timed lyric: bright and a touch larger while sung, fading with distance. */
@Composable
private fun SungLine(line: LyricLine, distance: Int, style: TextStyle, align: TextAlign) {
    val d = abs(distance)
    val alpha by animateFloatAsState(
        when { distance < 0 && d > 2 -> 0.20f; d == 0 -> 1f; d == 1 -> 0.52f; d == 2 -> 0.36f; else -> 0.26f },
        tween(420), label = "lyricAlpha",
    )
    val scale by animateFloatAsState(if (d == 0) 1.1f else 1f, tween(420), label = "lyricScale")
    val origin = TransformOrigin(if (align == TextAlign.Center) 0.5f else 0f, 0.5f)
    if (line.text.isBlank()) {
        // An instrumental gap: a rest, not a hole.
        Text("♪", style = style, color = Color.White.copy(alpha = alpha * 0.8f), modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 10.dp))
        return
    }
    Text(
        line.text,
        style = style,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .padding(vertical = 10.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = origin
            },
    )
}

/** Fades the top and bottom of whatever it wraps to nothing, so lines leave and arrive softly. */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.16f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

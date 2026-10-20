package com.engabd.sendpin.linux.station

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.ma.LyricLine
import com.engabd.sendpin.ma.LyricTiming
import com.engabd.sendpin.ma.MaLyrics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Karaoke: the line being sung filled in word by word in the album's colour, the focus
 * travelling down the sheet instead of jumping. Ported from the Android app's lyrics pane
 * (`LyricsPane.kt`), cut down for a panel with an i3 in it: no per-line blur, and only the
 * live line is redrawn on the frame clock — every other line is a layer that only changes
 * alpha and scale while the focus moves.
 */

/**
 * MPD's playhead between polls, carried on by the clock and never twitching backwards.
 *
 * Each reading re-anchors it. A reading that lands a little behind where the clock had
 * carried it (MPD's `elapsed` moves in 250 ms output chunks, so a reading is up to a chunk
 * stale) is held at rather than stepped back to; a bigger difference is a seek and is
 * taken at once. A poll that stops answering stops the playhead [capMs] past its reading.
 */
internal class SmoothPlayhead(private val capMs: Long = 2_500L, private val snapMs: Long = 350L) {
    private var anchorPos = 0L
    private var anchorAt = 0L
    private var playing = false
    private var last = Long.MIN_VALUE

    fun onReading(positionMs: Long, playing: Boolean, atMs: Long) {
        anchorPos = positionMs
        anchorAt = atMs
        this.playing = playing
    }

    fun valueAt(nowMs: Long): Long {
        val raw = if (playing) anchorPos + (nowMs - anchorAt).coerceIn(0L, capMs) else anchorPos
        // Forwards only, except across a seek.
        last = if (last == Long.MIN_VALUE || raw >= last || last - raw > snapMs) raw else last
        return last
    }
}

/** How far the fill runs ahead of the reading, for the half-chunk a reading is stale on average. */
private const val FILL_LEAD_MS = 110L

/**
 * The playhead for drawing: a function read inside a draw block, so a line that calls it
 * redraws on every frame and nothing recomposes. The frame clock only runs while [live].
 */
@Composable
internal fun rememberFramePlayhead(model: PanelModel, live: Boolean, leadMs: Long = FILL_LEAD_MS): () -> Long {
    val remote by model.state.collectAsState()
    val head = remember { SmoothPlayhead() }
    LaunchedEffect(remote) {
        val r = remote
        head.onReading(r?.positionMs ?: 0L, r?.playing == true, System.currentTimeMillis())
    }
    val frame = remember { mutableLongStateOf(0L) }
    if (live) LaunchedEffect(Unit) { while (true) withFrameMillis { frame.longValue = it } }
    return remember {
        {
            frame.longValue // read so the caller's draw block is invalidated each frame
            head.valueAt(System.currentTimeMillis()) + leadMs
        }
    }
}

/**
 * How far through the playing track MPD is, 0..1, carried on by the frame clock between
 * polls — for progress bars that should glide rather than step once a second. Read it
 * inside a draw block: only that drawing follows the frames, nothing recomposes. The
 * frame clock runs only while music plays.
 */
@Composable
internal fun rememberSmoothProgress(model: PanelModel, fallbackDurationMs: Long = 0L): () -> Float {
    val remote by model.state.collectAsState()
    val head = rememberFramePlayhead(model, live = remote?.playing == true, leadMs = 0L)
    val dur = rememberUpdatedStateCompat(remote?.durationMs?.takeIf { it > 0 } ?: fallbackDurationMs)
    return remember { { val d = dur.value; if (d > 0) (head().toFloat() / d).coerceIn(0f, 1f) else 0f } }
}

@Composable
private fun <T> rememberUpdatedStateCompat(v: T) = androidx.compose.runtime.rememberUpdatedState(v)

/** The lines as the sheet draws them: the source's word times, or estimated ones when [karaoke]. */
internal fun karaokeLines(lyrics: MaLyrics, karaoke: Boolean): List<LyricLine> {
    val lines = sheetLines(lyrics.lines, lyrics.synced)
    if (!lyrics.synced) return lines
    return if (karaoke) LyricTiming.withEstimatedWords(lines) else lines.map { if (it.words == null) it else it.copy(words = null) }
}

/** Ease with a long, soft landing: how the focus moves from one line to the next. */
private val FocusEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val FOCUS_MS = 720
private const val JUMP_LINES = 4

/**
 * The sung colour: white with a breath of the album's accent. An accent taken from the art
 * is often dark or close in brightness to the room behind it, and the fill has to read
 * from across the room against both.
 */
internal fun sungColour(accent: Color): Color = lerp(Color.White, accent, 0.22f)

/** The colour still to be sung on the live line, under the fill. */
private const val UNSUNG_ALPHA = 0.40f

/**
 * A timed lyric as a rolling sheet: the line being sung at the eye (a little over a third
 * of the way down), lit and filled as it is sung; the lines around it falling away by a
 * continuous distance from a single travelling focus.
 */
@Composable
internal fun KaraokeSheet(
    model: PanelModel,
    lines: List<LyricLine>,
    style: TextStyle,
    align: TextAlign,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val hasWords = remember(lines) { lines.any { it.words != null } }
    val head = rememberFramePlayhead(model, live = hasWords)
    val playhead = rememberPlayhead(model)
    val active by remember(lines) { derivedStateOf { activeLyricIndex(lines, playhead.value).coerceAtLeast(0) } }

    val focus = remember(lines) { Animatable(0f) }
    var seeded by remember(lines) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(active, lines) {
        val target = active.toFloat()
        if (!seeded || abs(target - focus.value) > JUMP_LINES) {
            seeded = true
            focus.snapTo(target)
            listState.scrollToItem(active)
        } else {
            // The scroll and the emphasis travel together, on the same curve.
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            coroutineScope {
                launch { focus.animateTo(target, tween(FOCUS_MS, easing = FocusEasing)) }
                if (item != null) listState.animateScrollBy(item.offset.toFloat(), tween(FOCUS_MS, easing = FocusEasing))
                else listState.animateScrollToItem(active)
            }
        }
    }

    val fill = remember(accent) { sungColour(accent) }
    val origin = TransformOrigin(if (align == TextAlign.Center) 0.5f else 0f, 0.5f)
    BoxWithConstraints(modifier) {
        val h = maxHeight
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = h * 0.36f, bottom = h * 0.6f),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines, key = { i, _ -> i }, contentType = { _, l -> if (l.text.isBlank()) "gap" else "line" }) { i, line ->
                val isActive = i == active
                val live = isActive && line.words != null
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 10.dp)
                        .graphicsLayer {
                            val offset = i - focus.value
                            val lit = smooth(1f - abs(offset).coerceAtMost(1f))
                            val reach = if (offset < 0f) 2.2f else 3.6f
                            val near = smooth(1f - (abs(offset) / reach).coerceIn(0f, 1f))
                            val s = (if (line.background) 0.8f else 1f) * (0.93f + 0.12f * lit)
                            scaleX = s
                            scaleY = s
                            alpha = 0.10f + 0.30f * near + 0.60f * lit
                            transformOrigin = origin
                        },
                ) {
                    when {
                        line.text.isBlank() -> Text("♪", style = style, color = Color.White.copy(alpha = 0.7f))
                        live -> WordFillText(line, head, style, Color.White.copy(alpha = UNSUNG_ALPHA), fill)
                        else -> Text(line.text, style = style, color = if (isActive) fill else Color.White)
                    }
                }
            }
        }
    }
}

/**
 * The song's words in a strip of two lines, for the listening layouts that are about the
 * record rather than the words: the line being sung (filled as it is sung) and the next one
 * waiting under it. Each new line rises into place. Nothing is drawn without timed lyrics.
 */
@Composable
internal fun LyricStrip(
    model: PanelModel,
    track: LocalTrack?,
    style: TextStyle,
    align: TextAlign,
    accent: Color,
    karaoke: Boolean,
    modifier: Modifier = Modifier,
    /** 2: the line and the next. 4 and more: the line before too, then the line and the ones after. */
    rows: Int = 2,
) {
    val key = track?.scrobbleId ?: track?.id
    val lyrics by produceState<MaLyrics?>(null, key) {
        value = null
        value = track?.let { model.lyricsFor(it) }?.takeIf { it.synced }
    }
    val lines = remember(lyrics, karaoke) { lyrics?.let { karaokeLines(it, karaoke) }.orEmpty() }
    val hasWords = remember(lines) { lines.any { it.words != null } }
    val head = rememberFramePlayhead(model, live = hasWords)
    val playhead = rememberPlayhead(model)
    val active by remember(lines) { derivedStateOf { if (lines.isEmpty()) -1 else activeLyricIndex(lines, playhead.value) } }
    val fill = remember(accent) { sungColour(accent) }
    val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start

    // A fixed height, so the words arriving or leaving never move the record above them.
    val small = 0.72f
    val withPrev = rows >= 4
    val after = (rows - 1 - (if (withPrev) 1 else 0)).coerceAtLeast(0)
    val extra = after + (if (withPrev) 1 else 0)
    Box(modifier.fillMaxWidth().height(with(LocalDensity.current) { (style.lineHeight * (1.25f + small * 1.1f * extra)).toDp() } + (8 * extra).dp)) {
        if (lines.isEmpty()) return@Box
        AnimatedContent(
            targetState = active,
            transitionSpec = {
                (slideInVertically(tween(560, easing = FocusEasing)) { it / 3 } + fadeIn(tween(420, 120)))
                    .togetherWith(slideOutVertically(tween(480, easing = FocusEasing)) { -it / 3 } + fadeOut(tween(260)))
            },
            label = "lyricStrip",
            modifier = Modifier.fillMaxWidth(),
        ) { at ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = horizontal) {
                val line = lines.getOrNull(at)
                val smallStyle = style.copy(fontSize = style.fontSize * small, lineHeight = style.lineHeight * small)
                if (withPrev) {
                    // The line just sung, fading out above.
                    Text(
                        lines.take(at.coerceAtLeast(0)).lastOrNull { it.text.isNotBlank() }?.text ?: "",
                        style = smallStyle, color = Color.White.copy(alpha = 0.24f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                when {
                    line == null || line.text.isBlank() -> Text("♪", style = style, color = Color.White.copy(alpha = 0.4f))
                    line.words != null -> WordFillText(line, head, style, Color.White.copy(alpha = UNSUNG_ALPHA), fill, maxLines = 2)
                    else -> Text(line.text, style = style, color = fill, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                lines.drop(at + 1).filter { it.text.isNotBlank() }.take(after).forEachIndexed { k, next ->
                    Text(
                        next.text,
                        style = smallStyle,
                        color = Color.White.copy(alpha = 0.40f - 0.10f * k),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A line whose words fill in as they are sung. Laid out once as an ordinary [Text]; the
 * fill is drawn over it from the layout's own geometry — each wrapped row clipped at the
 * sung edge and painted again in [fill], with a short feathered edge so it reads as a wipe.
 */
@Composable
internal fun WordFillText(
    line: LyricLine,
    head: () -> Long,
    style: TextStyle,
    base: Color,
    fill: Color,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val estimated = line.words?.firstOrNull()?.estimated == true
    Text(
        line.text,
        style = style,
        color = base,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            val feather = (if (estimated) 30 else 16).dp.toPx()
            val colour = if (estimated) fill.copy(alpha = fill.alpha * 0.94f) else fill
            drawSungFill(l, LyricTiming.sungChars(line, head()), colour, feather)
        },
    )
}

/** Paint [layout] in [colour] up to the fractional character index [sung]. */
private fun DrawScope.drawSungFill(layout: TextLayoutResult, sung: Float, colour: Color, feather: Float) {
    val total = layout.layoutInput.text.length
    if (sung <= 0f || total == 0) return
    val pos = sung.coerceAtMost(total.toFloat())
    for (row in 0 until layout.lineCount) {
        val start = layout.getLineStart(row)
        val end = layout.getLineEnd(row, visibleEnd = true)
        if (pos <= start) break
        val left = layout.getLineLeft(row)
        val right = layout.getLineRight(row)
        val top = layout.getLineTop(row)
        val bottom = layout.getLineBottom(row)
        if (pos >= end) {
            clipRect(left, top, right, bottom) { drawText(layout, brush = SolidColor(colour)) }
            continue
        }
        val ci = pos.toInt().coerceIn(start, (end - 1).coerceAtLeast(start))
        val box = layout.getBoundingBox(ci)
        val frac = pos - ci
        val rtl = layout.getParagraphDirection(start) == ResolvedTextDirection.Rtl
        val edge = if (rtl) box.right - frac * box.width else box.left + frac * box.width
        if (rtl) {
            val far = (edge - feather).coerceAtLeast(left)
            val brush = Brush.horizontalGradient(
                0f to Color.Transparent, ((edge - far) / (right - far)).coerceIn(0f, 1f) to colour, 1f to colour,
                startX = far, endX = right,
            )
            clipRect(far, top, right, bottom) { drawText(layout, brush = brush) }
        } else {
            val far = (edge + feather).coerceAtMost(right)
            val brush = Brush.horizontalGradient(
                0f to colour, ((edge - left) / (far - left)).coerceIn(0f, 1f) to colour, 1f to Color.Transparent,
                startX = left, endX = far,
            )
            clipRect(left, top, far, bottom) { drawText(layout, brush = brush) }
        }
    }
}

private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

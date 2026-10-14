package com.engabd.sendpin.linux.station

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.ui.theme.AppFont
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The queue as a stack of records on the floor beside the platter: the next few, leaning,
 * each with the edge of its record showing above the sleeve. Tap one to play it now; drag
 * one sideways over its neighbour to change the order. It is the same queue the Queue tab
 * edits — MPD's — only seen as things.
 */

/** One leaning record: its place in MPD's queue and what it is. */
internal data class DeckCard(val queueIndex: Int, val track: LocalTrack)

/** The [count] tracks after [nowIndex], with their queue places. */
internal fun deckCards(queue: List<LocalTrack>, nowIndex: Int, count: Int = 3): List<DeckCard> =
    if (nowIndex < 0) emptyList()
    else (nowIndex + 1 until minOf(queue.size, nowIndex + 1 + count)).map { DeckCard(it, queue[it]) }

/**
 * Where a card dropped after a drag of [dragPx] lands: the slot it started in plus the
 * whole card-widths it was carried, kept inside the deck.
 */
internal fun deckDropSlot(slot: Int, dragPx: Float, cardPx: Float, slots: Int): Int =
    if (cardPx <= 0f || slots <= 0) slot
    else (slot + (dragPx / cardPx).roundToInt()).coerceIn(0, slots - 1)

@Composable
internal fun QueueDeck(
    model: PanelModel,
    queue: List<LocalTrack>,
    nowIndex: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val cards = deckCards(queue, nowIndex)
    if (cards.isEmpty()) return
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 14.dp
        val card = ((maxWidth - gap * 2) / 3f).coerceAtMost(140.dp)
        val cardPx = with(LocalDensity.current) { (card + gap).toPx() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
            cards.forEachIndexed { slot, c ->
                key(c.track.scrobbleId ?: c.track.id, slot) {
                    LeaningRecord(
                        track = c.track,
                        lean = LEAN[slot % LEAN.size],
                        tint = tint,
                        size = card,
                        cardPx = cardPx,
                        slot = slot,
                        slots = cards.size,
                        onPlay = { scope.launch { model.playAt(c.queueIndex) } },
                        onMove = { toSlot ->
                            scope.launch { model.moveInQueue(c.queueIndex, nowIndex + 1 + toSlot) }
                        },
                    )
                }
            }
        }
    }
}

/** Each record leans a little differently, like a stack that was put down by hand. */
private val LEAN = floatArrayOf(-4f, 2.5f, -1.5f)

@Composable
private fun LeaningRecord(
    track: LocalTrack,
    lean: Float,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    cardPx: Float,
    slot: Int,
    slots: Int,
    onPlay: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    // The queue catches up within a poll; until it does the card stays where it was dropped.
    LaunchedEffect(track.scrobbleId, slot) { drag.snapTo(0f) }

    Column(
        Modifier
            .width(size)
            .offset { IntOffset(drag.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = lean * (1f - 0.6f * if (dragging) 1f else 0f)
                val s = if (dragging) 1.07f else 1f
                scaleX = s
                scaleY = s
            }
            // Out of the way of a tap that means "wake the panel": a record is a thing to touch.
            .pointerInput(track.scrobbleId, slot) {
                detectTapGestures(onTap = { onPlay() })
            }
            .pointerInput(track.scrobbleId, slot) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        val to = deckDropSlot(slot, drag.value, cardPx, slots)
                        if (to != slot) {
                            scope.launch { drag.animateTo((to - slot) * cardPx, spring(Spring.DampingRatioNoBouncy)) }
                            onMove(to)
                        } else {
                            scope.launch { drag.animateTo(0f, spring(Spring.DampingRatioLowBouncy)) }
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { drag.animateTo(0f) }
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + dx) }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(size).height(size * 1.14f)) {
            // The record in its sleeve: the top of the disc shows above the cover.
            Canvas(Modifier.fillMaxSize()) {
                val s = this.size.width
                val c = Offset(s / 2f, s * 0.5f)
                drawCircle(Color(0xFF0B0B0E), radius = s * 0.49f, center = c)
                drawCircle(tint.copy(alpha = 0.22f), radius = s * 0.42f, center = c, style = Stroke(1.5f))
                drawCircle(Color.White.copy(alpha = 0.07f), radius = s * 0.35f, center = c, style = Stroke(1f))
                drawCircle(Color.White.copy(alpha = 0.10f), radius = s * 0.49f, center = c, style = Stroke(1.5f))
            }
            ArtImage(
                track.artUrl, track.title,
                Modifier
                    .padding(top = size * 0.14f)
                    .width(size)
                    .aspectRatio(1f)
                    .shadow(14.dp, PanelShapes.Thumb, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(PanelShapes.Thumb),
                shape = PanelShapes.Thumb,
            )
        }
        Text(
            track.title,
            color = Color.White.copy(alpha = 0.78f),
            fontFamily = AppFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = size * 0.06f).fillMaxWidth(),
        )
    }
}

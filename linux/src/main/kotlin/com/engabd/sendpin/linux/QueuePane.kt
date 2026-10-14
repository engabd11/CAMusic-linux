package com.engabd.sendpin.linux

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.RemoteState
import com.engabd.sendpin.linux.station.LocalActionMenu
import com.engabd.sendpin.linux.station.MenuAction
import com.engabd.sendpin.linux.station.rowDropIndex
import com.engabd.sendpin.linux.station.rowSwipeRemoves
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.EmptyState
import com.engabd.sendpin.linux.ui.Eyebrow
import com.engabd.sendpin.linux.ui.GlassCard
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.PageHeader
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.linux.ui.PlayingBars
import com.engabd.sendpin.linux.ui.SectionHeader
import com.engabd.sendpin.linux.ui.formatTotal
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Time left in the queue from [index] on: the rest of the playing track plus every
 * track after it. Pure so the header's total is testable.
 */
internal fun queueRemainingMs(queue: List<LocalTrack>, index: Int, positionMs: Long, durationMs: Long): Long {
    if (queue.isEmpty()) return 0
    val i = index.coerceIn(0, queue.lastIndex)
    val current = (if (durationMs > 0) durationMs else queue[i].durationMs) - positionMs
    return current.coerceAtLeast(0) + queue.drop(i + 1).sumOf { it.durationMs }
}

/** What a held queue row offers: play it, bring it next, send it to the end, take it off. */
internal fun queueRowActions(
    playing: Boolean,
    isNext: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onToEnd: () -> Unit,
    onRemove: () -> Unit,
): List<MenuAction> = buildList {
    if (!playing) add(MenuAction("Play now", Icons.Default.PlayArrow, run = onPlay))
    if (!playing && !isNext) add(MenuAction("Play next", Icons.Default.SkipNext, run = onPlayNext))
    if (!playing && !isLast) add(MenuAction("Move to the end", Icons.Default.VerticalAlignBottom, run = onToEnd))
    add(MenuAction("Remove from queue", Icons.Default.Close, danger = true, run = onRemove))
}

/**
 * The queue pane: what MPD is about to play, and the room to change it. The playing
 * track leads as a card in the album's colours; below it the whole queue, with what has
 * already played dimmed rather than hidden — MPD's queue is index-addressed, so every
 * row stays exactly where MPD has it.
 *
 * MPD's queue *is* the app's queue (`docs/providers.md`), and this pane touches it purely
 * through [PanelModel]'s queue-editing passthroughs — it never writes `model.queue` itself.
 * [PanelModel.reconcileQueueFromMpd] (run every poll tick) is what turns a fired command
 * into an updated list here; see that function's doc comment for why.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueuePane(modifier: Modifier, model: PanelModel) {
    val queue by model.queue.collectAsState()
    val state by model.state.collectAsState()
    val nowIndex = state?.index?.takeIf { it >= 0 && it < queue.size }
    val scope = rememberCoroutineScope()
    // The station's additions: swipe a row away and drag one by its handle (Touch gestures),
    // hold a row for its menu (Row menus). Both off until switched on.
    val touch by model.settings.touchGestures.collectAsState(initial = false)
    val rowMenus by model.settings.rowMenus.collectAsState(initial = false)
    val menus = LocalActionMenu.current

    Column(modifier.fillMaxSize()) {
        val remaining = queueRemainingMs(queue, nowIndex ?: 0, state?.positionMs ?: 0, state?.durationMs ?: 0)
        PageHeader(
            title = "Queue",
            subtitle = if (queue.isEmpty()) null else
                "${queue.size} ${if (queue.size == 1) "song" else "songs"}  ·  ${formatTotal(remaining)} left",
        ) {
            if (queue.isNotEmpty()) {
                PillButton("Shuffle", icon = Icons.Default.Shuffle, filled = false, onClick = { scope.launch { model.shuffleQueue() } })
                PillButton("Clear", icon = Icons.Default.DeleteSweep, filled = false, onClick = { scope.launch { model.clearQueue() } })
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    "Nothing queued",
                    "Play an album from the library and it lines up here.",
                )
            }
            return
        }

        val listState = rememberLazyListState()
        // Open on the playing track, not the top of a long queue's history.
        LaunchedEffect(Unit) { nowIndex?.let { if (it > 2) listState.scrollToItem(it + 1) } }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 36.dp),
                modifier = Modifier.fillMaxSize().touchDragScroll(listState),
            ) {
                nowIndex?.let { i ->
                    item(key = "now") { NowPlayingCard(queue[i], state) }
                    item(key = "hdr") { SectionHeader("Up next", Modifier.padding(top = 12.dp)) }
                }
                itemsIndexed(queue, key = { i, t -> "$i:${t.scrobbleId ?: t.id}" }) { i, track ->
                    QueueRow(
                        track = track,
                        position = i + 1,
                        nowPlaying = i == nowIndex,
                        playing = state?.playing == true,
                        played = nowIndex != null && i < nowIndex,
                        onPlay = { scope.launch { model.playAt(i) } },
                        onRemove = { scope.launch { model.removeFromQueue(i) } },
                        touch = touch,
                        index = i,
                        count = queue.size,
                        onMoveTo = { to -> scope.launch { model.moveInQueue(i, to) } },
                        onMenu = if (rowMenus && menus != null) {
                            {
                                menus.show(
                                    track.title, track.artist,
                                    queueRowActions(
                                        playing = i == nowIndex,
                                        isNext = nowIndex != null && i == nowIndex + 1,
                                        isLast = i == queue.lastIndex,
                                        onPlay = { scope.launch { model.playAt(i) } },
                                        onPlayNext = { scope.launch { model.playQueueEntryNext(i) } },
                                        onToEnd = { scope.launch { model.moveInQueue(i, queue.lastIndex) } },
                                        onRemove = { scope.launch { model.removeFromQueue(i) } },
                                    ),
                                )
                            }
                        } else null,
                    )
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** The playing track, large, with the accent glow under its cover. */
@Composable
private fun NowPlayingCard(track: LocalTrack, state: RemoteState?) {
    val accent = rememberAccent()
    GlassCard(Modifier.padding(horizontal = PageGutter)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            ArtImage(
                track.artUrl, track.title,
                Modifier.size(132.dp).shadow(22.dp, PanelShapes.Tile, ambientColor = accent, spotColor = accent),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayingBars(accent, state?.playing == true, Modifier.size(18.dp))
                    Eyebrow(if (state?.playing == true) "Now playing" else "Paused", accent)
                }
                Text(
                    track.title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                track.artist?.let {
                    Text(it, color = TextSecondary, fontFamily = AppFont, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                track.album?.let {
                    Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    track: LocalTrack,
    position: Int,
    nowPlaying: Boolean,
    playing: Boolean,
    played: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    touch: Boolean = false,
    index: Int = 0,
    count: Int = 0,
    onMoveTo: (Int) -> Unit = {},
    onMenu: (() -> Unit)? = null,
) {
    val accent = rememberAccent()
    val scope = rememberCoroutineScope()
    // Swiping left takes the row away; the handle carries it up or down the queue. Both follow
    // the finger, and on release either commit or settle back.
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    var rowW by remember { mutableFloatStateOf(1f) }
    var rowH by remember { mutableFloatStateOf(1f) }
    var carried by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { rowW = it.width.toFloat(); rowH = it.height.toFloat() }
            .then(
                if (!touch) Modifier
                else Modifier
                    .zIndex(if (carried) 1f else 0f)
                    .offset { IntOffset(dx.value.roundToInt(), dy.value.roundToInt()) }
                    .graphicsLayer { alpha = (1f - (-dx.value / rowW) * 0.8f).coerceIn(0.2f, 1f) }
                    .pointerInput(index) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (rowSwipeRemoves(dx.value, rowW)) {
                                    scope.launch { dx.animateTo(-rowW, tween(160)) }
                                    onRemove()
                                } else {
                                    scope.launch { dx.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
                                }
                            },
                            onDragCancel = { scope.launch { dx.animateTo(0f) } },
                            onHorizontalDrag = { change, d ->
                                change.consume()
                                scope.launch { dx.snapTo((dx.value + d).coerceAtMost(0f)) }
                            },
                        )
                    },
            )
            .padding(horizontal = 12.dp)
            .heightIn(min = 76.dp)
            .clip(PanelShapes.Thumb)
            .background(if (nowPlaying) accent.a(0.14f) else Color.Transparent)
            .then(
                if (onMenu != null) Modifier.combinedClickable(onClick = onPlay, onLongClick = onMenu)
                else Modifier.clickable(onClick = onPlay),
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            // Already played: still there (MPD's index is what every row addresses),
            // but stepped back so the eye lands on what is coming.
            .alpha(if (played) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "$position",
            color = if (nowPlaying) accent else TextMuted,
            fontFamily = MonoFont,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 2.dp),
        )
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            ArtImage(track.artUrl, track.title, Modifier.size(56.dp), shape = PanelShapes.Thumb)
            if (nowPlaying) {
                Box(Modifier.size(56.dp).clip(PanelShapes.Thumb).background(Color.Black.a(0.45f)))
                PlayingBars(accent, playing)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title,
                color = if (nowPlaying) accent else TextPrimary,
                fontFamily = AppFont,
                fontWeight = if (nowPlaying) FontWeight.Bold else FontWeight.Medium,
                fontSize = PanelMetrics.RowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(track.artist, track.album).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(sub, color = TextMuted, fontFamily = AppFont, fontSize = PanelMetrics.RowSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (track.durationMs > 0) {
            Text(formatMs(track.durationMs), color = TextMuted, fontFamily = MonoFont, fontSize = 15.sp)
        }
        if (touch) {
            // The handle: drag it, and the row goes that many places up or down the queue.
            Box(
                Modifier
                    .size(56.dp)
                    .pointerInput(index) {
                        detectVerticalDragGestures(
                            onDragStart = { carried = true },
                            onDragEnd = {
                                carried = false
                                val to = rowDropIndex(index, dy.value, rowH, count)
                                if (to != index) {
                                    scope.launch { dy.animateTo((to - index) * rowH, spring(Spring.DampingRatioNoBouncy)) }
                                    onMoveTo(to)
                                } else {
                                    scope.launch { dy.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
                                }
                            },
                            onDragCancel = { carried = false; scope.launch { dy.animateTo(0f) } },
                            onVerticalDrag = { change, d ->
                                change.consume()
                                scope.launch { dy.snapTo(dy.value + d) }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.DragHandle, "Drag to reorder", tint = TextMuted, modifier = Modifier.size(26.dp))
            }
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, "Remove from queue", tint = TextMuted, modifier = Modifier.size(24.dp))
        }
    }
}

package com.engabd.sendpin.linux

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * The queue pane: what MPD is about to play, and the room to change it. Item 9 of the
 * plan's library-gap list, and the plan's own words for why it matters — "the biggest
 * functional gap for a screen you stand in front of".
 *
 * MPD's queue *is* the app's queue (`docs/providers.md`), and this pane touches it purely
 * through [PanelModel]'s queue-editing passthroughs — it never writes `model.queue` itself.
 * [PanelModel.reconcileQueueFromMpd] (run every poll tick) is what turns a fired command
 * into an updated list here; see that function's doc comment for why. A remove/shuffle
 * looks instant because the panel already polls at 1 Hz, not because this pane guessed the
 * new order.
 */
@Composable
fun QueuePane(modifier: Modifier, model: PanelModel) {
    val queue by model.queue.collectAsState()
    val state by model.state.collectAsState()
    val nowPlayingIndex = state?.index?.takeIf { it >= 0 }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Queue",
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { scope.launch { model.shuffleQueue() } }) {
                    Icon(Icons.Default.Shuffle, "Shuffle queue", tint = TextPrimary)
                }
                IconButton(onClick = { scope.launch { model.clearQueue() } }) {
                    Icon(Icons.Default.Clear, "Clear queue", tint = TextPrimary)
                }
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing queued — play an album from the library.",
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 14.sp,
                )
            }
            return
        }

        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize().touchDragScroll(listState),
            ) {
                itemsIndexed(queue, key = { i, t -> "$i:${t.scrobbleId ?: t.id}" }) { i, track ->
                    QueueRow(
                        track = track,
                        nowPlaying = i == nowPlayingIndex,
                        onPlay = { scope.launch { model.playAt(i) } },
                        onRemove = { scope.launch { model.removeFromQueue(i) } },
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

@Composable
private fun QueueRow(
    track: LocalTrack,
    nowPlaying: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 48dp minimum, same floor as TrackRow/TabStrip elsewhere on the panel.
            .heightIn(min = 48.dp)
            .clickable(onClick = onPlay)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A small dot rather than reusing TrackRow's position number — this list can be
        // reordered by MPD (shuffle) so a fixed "n" would look like a track number that
        // isn't one.
        Box(
            Modifier
                .size(8.dp)
                .background(if (nowPlaying) TextPrimary else Glass, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (nowPlaying) TextPrimary else TextPrimary,
                fontFamily = AppFont,
                fontWeight = if (nowPlaying) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist != null) {
                Text(
                    track.artist,
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatMs(track.durationMs),
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = AppFont,
            modifier = Modifier.width(40.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, "Remove from queue", tint = TextMuted)
        }
    }
}

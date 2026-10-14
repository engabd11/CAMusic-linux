package com.engabd.sendpin.linux.station

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.formatMs
import com.engabd.sendpin.linux.ui.formatTotal
import com.engabd.sendpin.ui.design.mpdBadgeQuality
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The back of the sleeve. Hold the cover and it turns over the way a record sleeve does in
 * the hand: the album's name and facts, and its tracks in order with the playing one lit —
 * touch one to play it. Turn it back with a tap; it turns itself back after a while.
 */

/** How long the sleeve stays turned over before it turns back by itself. */
private const val SLEEVE_RETURN_MS = 45_000L

/**
 * [front] (the cover) that turns to [back] when held. [enabled] off, it is only [front] in a
 * box of [modifier]'s size — nothing attached.
 */
@Composable
internal fun FlippableCover(
    enabled: Boolean,
    modifier: Modifier,
    back: @Composable () -> Unit,
    front: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { front() }
        return
    }
    var flipped by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (flipped) 1f else 0f, tween(720, easing = FastOutSlowInEasing), label = "sleeveTurn")
    LaunchedEffect(flipped) {
        if (flipped) {
            delay(SLEEVE_RETURN_MS)
            flipped = false
        }
    }
    val camera = with(LocalDensity.current) { 18.dp.toPx() }
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (flipped) flipped = false }, onLongPress = { flipped = !flipped })
            }
            .graphicsLayer {
                rotationY = turn * 180f
                cameraDistance = camera
            },
    ) {
        // Both faces stay composed, so the cover never reloads; one is simply not shown.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (turn < 0.5f) 1f else 0f }) { front() }
        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f; alpha = if (turn >= 0.5f) 1f else 0f }) { back() }
    }
}

/** The sleeve's back for the record that is playing, read from the queue. */
@Composable
internal fun SleeveBack(model: PanelModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val albumIds by model.albumIds.collectAsState()
    val accent = rememberAccent()
    val scope = rememberCoroutineScope()
    val index = state?.index?.takeIf { it >= 0 } ?: 0
    val side = remember(queue, index, albumIds) { sideOf(queue, index, albumIds) }
    val track = queue.getOrNull(index)
    val shape = RoundedCornerShape(14.dp)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(Color(0xFF0F0F12))
            .border(1.dp, accent.copy(alpha = 0.35f), shape),
    ) {
        val k = (maxWidth.value / 600f).coerceIn(0.6f, 1.2f)
        if (side == null || track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing on the sleeve yet", color = Color.White.copy(alpha = 0.4f), fontFamily = AppFont, fontSize = (16 * k).sp)
            }
            return@BoxWithConstraints
        }
        val here = side.indexOf(index)
        val total = side.tracks.sumOf { it.durationMs }
        // A long album shows the stretch around the playing track.
        val window = 11
        val from = (here - window / 2).coerceIn(0, (side.size - window).coerceAtLeast(0))
        val shown = side.tracks.withIndex().toList().subList(from, minOf(side.size, from + window))

        Column(Modifier.fillMaxSize().padding(horizontal = (28 * k).dp, vertical = (24 * k).dp)) {
            Text(
                "BACK OF THE SLEEVE", color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = (11 * k).sp, letterSpacing = 0.3.em,
            )
            Spacer(Modifier.height((8 * k).dp))
            Text(
                track.album ?: track.title, color = Color.White, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                fontSize = (28 * k).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            track.artist?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White.copy(alpha = 0.7f), fontFamily = AppFont, fontSize = (17 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val facts = listOfNotNull(
                track.genre?.takeIf { it.isNotBlank() },
                if (side.size > 1) "${side.size} tracks" else null,
                if (total > 0) formatTotal(total) else null,
            ).joinToString("  ·  ")
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height((4 * k).dp))
                Text(facts, color = Color.White.copy(alpha = 0.45f), fontFamily = AppFont, fontStyle = FontStyle.Italic, fontSize = (14 * k).sp, maxLines = 1)
            }
            Spacer(Modifier.height((16 * k).dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
            Spacer(Modifier.height((8 * k).dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                shown.forEach { (i, t) ->
                    val now = i == here
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height((38 * k).dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (now) accent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { scope.launch { model.playAt(side.firstIndex + i) } }
                            .padding(horizontal = (10 * k).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}", color = if (now) accent else Color.White.copy(alpha = 0.4f), fontFamily = MonoFont,
                            fontSize = (13 * k).sp, modifier = Modifier.width((30 * k).dp),
                        )
                        Text(
                            t.title, color = if (now) Color.White else Color.White.copy(alpha = 0.78f), fontFamily = AppFont,
                            fontWeight = if (now) FontWeight.Bold else FontWeight.Normal, fontSize = (16 * k).sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (t.durationMs > 0) {
                            Text(formatMs(t.durationMs), color = Color.White.copy(alpha = 0.4f), fontFamily = MonoFont, fontSize = (13 * k).sp)
                        }
                    }
                }
            }
            val q = mpdBadgeQuality(track, state?.outputFormat)
            if (q != null) {
                Spacer(Modifier.height((8 * k).dp))
                Text(
                    listOfNotNull(q.label, state?.outputDeviceName).joinToString("   →   "),
                    color = Color.White.copy(alpha = 0.38f), fontFamily = MonoFont, fontSize = (12 * k).sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

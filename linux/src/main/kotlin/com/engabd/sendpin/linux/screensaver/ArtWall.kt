package com.engabd.sendpin.linux.screensaver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.Motion
import com.engabd.sendpin.linux.ui.TILE_PX
import com.engabd.sendpin.linux.ui.sizedArt
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.theme.AppFont
import kotlinx.coroutines.delay
import kotlin.random.Random

/*
 * The art wall: the library's covers in columns that drift slowly up and down past each
 * other, the screen a wall of records. Every few seconds one of them steps forward, the
 * rest dim, and its name comes up at the foot of the screen. Hold to play that one.
 */

/** How many albums the wall is built from. */
private const val WALL_ALBUMS = 72

/** Seconds between one record stepping forward and the next. */
private const val SPOTLIGHT_MS = 9_000L

/** How the albums split into columns, each column's list in order. Pure, for the tests. */
internal fun wallColumns(albums: List<MaItem>, columns: Int): List<List<MaItem>> =
    List(columns) { c -> albums.filterIndexed { i, _ -> i % columns == c } }.filter { it.isNotEmpty() }

/** A column's travel: [periodPx] for one whole run of its covers, moving [up] or down. */
private fun columnShift(tPx: Float, periodPx: Float, up: Boolean): Float {
    if (periodPx <= 0f) return 0f
    val o = tPx % periodPx
    return if (up) -o else o - periodPx
}

@Composable
internal fun ArtWall(
    source: MusicSource?,
    clock: Boolean,
    holdToPlay: Boolean,
    captures: Boolean,
    onTap: () -> Unit,
    onHold: (String) -> Unit,
) {
    val albums by produceState<List<MaItem>>(emptyList(), source) {
        value = runCatching { source?.randomAlbums(WALL_ALBUMS) }.getOrNull().orEmpty().filter { !it.image.isNullOrBlank() }
    }
    var spotlight by remember { mutableStateOf<MaItem?>(null) }
    val current by rememberUpdatedState(spotlight)
    val hold by rememberUpdatedState(holdToPlay)

    // One slow clock for every column's drift, read only inside the layers.
    val travel = rememberInfiniteTransition(label = "wall").animateFloat(
        0f, 1_000_000f, infiniteRepeatable(tween(1_000_000 * 60, easing = LinearEasing), RepeatMode.Restart), label = "wallTravel",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!captures) Modifier
                else Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = {
                            val id = current?.itemId
                            if (hold && id != null) onHold(id) else onTap()
                        },
                    )
                },
            ),
    ) {
        val landscape = maxWidth > maxHeight
        val cols = if (landscape) 6 else 4
        val gap = 14.dp
        val tile: Dp = (maxWidth - gap * (cols + 1)) / cols
        val density = LocalDensity.current
        val stepPx = with(density) { (tile + gap).toPx() }
        val columns = remember(albums, cols) { wallColumns(albums, cols) }

        // Each column at its own pace, neighbours in opposite directions.
        val speeds = remember(cols) { List(cols) { 9f + Random.nextFloat() * 7f } }

        LaunchedEffect(albums) {
            if (albums.isEmpty()) return@LaunchedEffect
            delay(3_000)
            while (true) {
                spotlight = albums.random()
                delay(SPOTLIGHT_MS - 2_200)
                spotlight = null
                delay(2_200)
            }
        }

        Row(
            Modifier.fillMaxSize().padding(horizontal = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            columns.forEachIndexed { c, list ->
                val period = list.size * stepPx
                val up = c % 2 == 0
                // The column holding the record in the light is drawn over its neighbours.
                val lit = spotlight != null && list.any { it.itemId == spotlight?.itemId }
                Box(Modifier.width(tile).fillMaxHeight().zIndex(if (lit) 1f else 0f)) {
                    Column(
                        Modifier
                            .wrapContentHeight(Alignment.Top, unbounded = true)
                            .graphicsLayer {
                                // Seconds since the wall went up, times this column's pace in dp/s.
                                val t = travel.value / 1_000f * speeds[c] * density.density * 60f
                                translationY = columnShift(t, period, up)
                            },
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        // Twice round, so the column loops without a seam.
                        (list + list + list.take(4)).forEach { item ->
                            Box(Modifier.zIndex(if (item.itemId == spotlight?.itemId) 1f else 0f)) { WallTile(item, tile, spotlight) }
                        }
                    }
                }
            }
        }

        // The foot of the wall: a scrim, and the name of the record in the light.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * 0.3f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)))),
        )
        AnimatedContent(
            targetState = spotlight,
            transitionSpec = {
                (fadeIn(tween(700, 200)) + slideInVertically(tween(900, easing = Motion.Emphasized)) { it / 3 })
                    .togetherWith(fadeOut(tween(500)))
            },
            contentKey = { it?.itemId },
            label = "wallCaption",
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        ) { item ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (item != null) {
                    item.subtitle?.let {
                        Text(
                            it.uppercase(), color = Color.White.copy(alpha = 0.72f), fontFamily = AppFont,
                            fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.3.em, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val face = faceFor(item.genres, item.year, item.name, item.itemId)
                    FittedTitle(item.name, face, if (landscape) 58f else 50f, TextAlign.Center, Color.White, Modifier)
                    if (hold) {
                        Spacer(Modifier.height(14.dp))
                        Text("HOLD TO PLAY", color = Color.White.copy(alpha = 0.45f), fontFamily = AppFont, fontSize = 12.sp, letterSpacing = 0.32.em)
                    }
                }
            }
        }

        if (clock) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(maxHeight * 0.2f)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent))),
            )
            Clock(
                accent = null, neutral = false, scale = 0.9f, align = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            )
        }
    }
}

@Composable
private fun WallTile(item: MaItem, tile: Dp, spotlight: MaItem?) {
    val lit = spotlight?.itemId == item.itemId
    val dim = spotlight != null && !lit
    val scale by animateFloatAsState(if (lit) 1.14f else 1f, tween(1_100, easing = Motion.Emphasized), label = "wallScale")
    val alpha by animateFloatAsState(if (dim) 0.42f else 1f, tween(1_100), label = "wallDim")
    Box(
        Modifier
            .size(tile)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                // In the light, it is drawn over its neighbours.
                shadowElevation = if (scale > 1.01f) 40f * (scale - 1f) / 0.14f else 0f
                shape = RoundedCornerShape(8.dp)
                clip = false
            },
    ) {
        ArtImage(
            url = sizedArt(item.image, TILE_PX),
            name = item.name,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

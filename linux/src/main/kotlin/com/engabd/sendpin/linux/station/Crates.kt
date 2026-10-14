package com.engabd.sendpin.linux.station

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.LibraryRoute
import com.engabd.sendpin.linux.LibraryState
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.EmptyState
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.PageHeader
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.linux.ui.SkeletonBlock
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Crates: the library as you would dig through it in a shop — a bin for each kind of music,
 * the records standing in it, tilted as they pass the middle of the bin, the one in front
 * raised above the lip. Drag a bin along; tap a record and it is pulled out and opened.
 * Library → Crates, beside the usual grid, with Settings → Crates on.
 */

/** One bin: a name, and the albums standing in it. */
internal data class CrateBin(val name: String, val albums: List<MaItem>)

private const val UNSORTED = "Unsorted"
private const val OTHER = "Other"

/**
 * [albums] sorted into bins by their first genre: the biggest bins first, at most [maxBins],
 * [perBin] records to a bin. A genre with fewer than [minBin] albums is not worth a bin of
 * its own and goes in "Other"; albums with no genre are "Unsorted". Within a bin, by artist,
 * then year.
 */
internal fun crateBins(albums: List<MaItem>, maxBins: Int = 14, perBin: Int = 30, minBin: Int = 2): List<CrateBin> {
    fun genreOf(a: MaItem) = a.genres.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: UNSORTED
    val byGenre = albums.groupBy(::genreOf)
    val named = byGenre.filterKeys { it != UNSORTED }
    val big = named.filterValues { it.size >= minBin }
    val small = named.filterValues { it.size < minBin }.values.flatten()
    val order = compareBy<MaItem>({ (it.subtitle ?: "").lowercase() }, { it.year ?: Int.MAX_VALUE }, { it.name.lowercase() })
    val bins = big.map { (name, list) -> CrateBin(name, list.sortedWith(order).take(perBin)) }
        .sortedWith(compareByDescending<CrateBin> { big.getValue(it.name).size }.thenBy { it.name })
    val tail = buildList {
        if (small.isNotEmpty()) add(CrateBin(OTHER, small.sortedWith(order).take(perBin)))
        byGenre[UNSORTED]?.let { add(CrateBin(UNSORTED, it.sortedWith(order).take(perBin))) }
    }
    return bins.take((maxBins - tail.size).coerceAtLeast(0)) + tail
}

/**
 * How a record sits in its bin, from where it is across the bin's width: [d] is its centre's
 * distance from the middle in record-widths (negative to the left). Facing front in the
 * middle, turned away to either side, lower toward the edges; pure for the tests.
 */
internal data class CratePose(val rotationY: Float, val lift: Float, val scale: Float)

internal fun cratePose(d: Float): CratePose {
    val near = (1f - abs(d)).coerceIn(0f, 1f)
    return CratePose(
        rotationY = (-d * 34f).coerceIn(-52f, 52f),
        // 1 in the middle (raised), 0 a record-width away or more (sunk into the bin)
        lift = near,
        scale = 0.9f + 0.12f * near,
    )
}

@Composable
internal fun CratesScreen(
    source: MusicSource,
    state: LibraryState,
    open: (LibraryRoute) -> Unit,
    back: () -> Unit,
) {
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        if (state.crates != null) return@LaunchedEffect
        try {
            state.crates = crateBins(source.albums(0, CRATE_ALBUMS))
        } catch (e: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = "Crates", subtitle = "Drag a bin along. Tap a record to pull it out.", onBack = back)
        val bins = state.crates
        when {
            failed -> EmptyState(Icons.Default.ErrorOutline, "Couldn't load the crates", "The library didn't answer.")
            bins == null -> Column(Modifier.padding(horizontal = PageGutter), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(230.dp), PanelShapes.Card) }
            }
            bins.isEmpty() -> EmptyState(Icons.Default.Inventory2, "No records yet", "Nothing in the library to dig through.")
            else -> {
                val list = rememberLazyListState()
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().touchDragScroll(list),
                ) {
                    itemsIndexed(bins, key = { _, b -> b.name }) { _, bin -> Crate(bin, open) }
                }
            }
        }
    }
}

/** How many albums the crates are dug from — a first look at the library, not all of it. */
private const val CRATE_ALBUMS = 400

private val CARD = 168.dp
private val CRATE_HEIGHT = 236.dp

@Composable
private fun Crate(bin: CrateBin, open: (LibraryRoute) -> Unit) {
    val row = rememberLazyListState()
    // The record nearest the middle of the bin is the one in front; its name is on the lip.
    val front by remember(row, bin) {
        derivedStateOf {
            val info = row.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - mid) }?.index ?: 0
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PageGutter),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(bin.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
            Text(
                "${bin.albums.size} ${if (bin.albums.size == 1) "record" else "records"}",
                color = TextMuted, fontFamily = AppFont, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(CRATE_HEIGHT)) {
            LazyRow(
                state = row,
                contentPadding = PaddingValues(start = PageGutter, end = 120.dp, top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().touchDragScroll(row, Orientation.Horizontal),
            ) {
                itemsIndexed(bin.albums, key = { _, a -> a.itemId }) { i, album ->
                    Record(album, i, row) { open(LibraryRoute.Album(album)) }
                }
            }
            // The front of the bin: the lip the records stand behind, with the front record's name.
            val lead = bin.albums.getOrNull(front)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(66.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xF00A0A0D), Color(0xFF050506))),
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .padding(horizontal = PageGutter, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (lead != null) {
                    Text(lead.name, color = Color.White, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lead.subtitle?.let {
                        Text(it, color = Color.White.copy(alpha = 0.6f), fontFamily = AppFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** One record standing in a bin: turned by where it is, raised in the middle, pulled out on a tap. */
@Composable
private fun Record(album: MaItem, index: Int, row: LazyListState, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val density = LocalDensity.current
    val liftPx = with(density) { 40.dp.toPx() }
    val pullPx = with(density) { 64.dp.toPx() }
    val camera = with(density) { 14.dp.toPx() }
    ArtImage(
        album.image, album.name,
        Modifier
            .size(CARD)
            .graphicsLayer {
                // Read while drawing, so a drag turns the records without recomposing any.
                val info = row.layoutInfo
                val here = info.visibleItemsInfo.firstOrNull { it.index == index }
                val d = if (here == null) 3f else {
                    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    (here.offset + here.size / 2f - mid) / here.size
                }
                val pose = cratePose(d)
                rotationY = pose.rotationY
                cameraDistance = camera
                translationY = -pose.lift * liftPx + liftPx * 0.35f - pull.value * pullPx
                val s = pose.scale + 0.16f * pull.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .shadow(14.dp, PanelShapes.Thumb, ambientColor = Color.Black, spotColor = Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch {
                    pull.animateTo(1f, tween(240))
                    onOpen()
                    pull.snapTo(0f)
                }
            },
        shape = PanelShapes.Thumb,
    )
}

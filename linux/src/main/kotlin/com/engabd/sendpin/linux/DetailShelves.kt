package com.engabd.sendpin.linux

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.LocalCoverScope
import com.engabd.sendpin.linux.ui.LocalSharedCovers
import com.engabd.sendpin.linux.ui.Motion
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.SectionHeader
import com.engabd.sendpin.linux.ui.TILE_PX
import com.engabd.sendpin.linux.ui.formatTotal
import com.engabd.sendpin.linux.ui.pressScale
import com.engabd.sendpin.linux.ui.sizedArt
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.inkOn

/*
 * The richer album and artist pages, after the Android app's "Gallery" dress: the sleeve
 * with its record sliding out, the cover's colours as a key, the facts and the notes,
 * the latest release, the discography along a timeline, and the shelves of what is near.
 * Everything is painted from the page's own palette, so each page is that record's world.
 */

// ─── The sleeve and its record ──────────────────────────────────────────────

/**
 * The cover with its record slid half out from behind it, the label printed in the
 * cover's colours. The record slides out once as the page arrives and turns at 33⅓ while
 * this album is the one playing.
 */
@Composable
internal fun RecordSleeve(
    image: String?,
    name: String,
    palette: AlbumPalette,
    spinning: Boolean,
    cover: Dp,
    sharedKey: String?,
    modifier: Modifier = Modifier,
) {
    val slide = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(Motion.SHARED_MS.toLong() - 120)
        slide.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
    }
    Box(modifier.size(width = cover * 1.42f, height = cover)) {
        VinylDisc(
            spinning = spinning,
            label = palette.accent,
            labelEdge = palette.swatch(1),
            mark = palette.swatch(2),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = cover.toPx() * 0.44f * slide.value }
                .padding(start = cover * 0.03f)
                .size(cover * 0.94f),
        )
        ArtImage(
            url = image,
            name = name,
            sharedKey = sharedKey,
            placeholderUrl = sizedArt(image, TILE_PX),
            modifier = Modifier
                .size(cover)
                .shadow(30.dp, RoundedCornerShape(12.dp), ambientColor = palette.accent, spotColor = Color.Black),
        )
    }
}

/**
 * A vinyl record, drawn: grooves, a label in [label] ringed by [labelEdge], and a printed
 * [mark] off-centre so that turning is visible. The sheen does not turn with the disc.
 */
@Composable
internal fun VinylDisc(spinning: Boolean, label: Color, labelEdge: Color, mark: Color, modifier: Modifier = Modifier) {
    val angle = if (spinning) {
        rememberInfiniteTransition(label = "disc").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart), label = "discAngle",
        )
    } else null
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        rotate(angle?.value ?: 0f, c) {
            drawCircle(Color(0xFF0C0C0E), r, c)
            var gr = r * 0.97f
            var i = 0
            while (gr > r * 0.40f) {
                drawCircle(Color.White.copy(alpha = if (i % 3 == 0) 0.07f else 0.03f), gr, c, style = Stroke(width = 0.8f))
                gr -= r * 0.022f
                i++
            }
            drawCircle(Brush.radialGradient(listOf(label, labelEdge), c, r * 0.34f), r * 0.34f, c)
            drawCircle(labelEdge.copy(alpha = 0.8f), r * 0.34f, c, style = Stroke(width = 2f))
            drawArc(
                mark.copy(alpha = 0.9f), -150f, 110f, false,
                topLeft = Offset(c.x - r * 0.25f, c.y - r * 0.25f), size = Size(r * 0.5f, r * 0.5f),
                style = Stroke(width = r * 0.045f, cap = StrokeCap.Round),
            )
            drawArc(
                Color.White.copy(alpha = 0.55f), 20f, 60f, false,
                topLeft = Offset(c.x - r * 0.17f, c.y - r * 0.17f), size = Size(r * 0.34f, r * 0.34f),
                style = Stroke(width = r * 0.03f, cap = StrokeCap.Round),
            )
            drawCircle(mark.copy(alpha = 0.85f), r * 0.045f, Offset(c.x + r * 0.2f, c.y - r * 0.08f))
            drawCircle(Color(0xFF0C0C0E), r * 0.03f, c)
        }
        drawCircle(
            Brush.sweepGradient(
                0f to Color.Transparent, 0.10f to Color.White.copy(alpha = 0.08f), 0.2f to Color.Transparent,
                0.5f to Color.Transparent, 0.60f to Color.White.copy(alpha = 0.06f), 0.7f to Color.Transparent,
                1f to Color.Transparent, center = c,
            ),
            r, c,
        )
    }
}

/** The cover's colours as a row of dots: the page's key, in miniature. */
@Composable
internal fun PaletteDots(palette: AlbumPalette, modifier: Modifier = Modifier) {
    val dots = remember(palette) { (listOf(palette.accent) + palette.swatches).distinct().take(6) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        dots.forEach { c -> Box(Modifier.size(14.dp).clip(CircleShape).background(c).border(1.dp, Hairline, CircleShape)) }
    }
}

// ─── Notes and facts ────────────────────────────────────────────────────────

/** The facts worth stating about a record, in the order a sleeve would print them. Pure. */
internal fun albumFactRows(
    year: Int?,
    released: String?,
    label: String?,
    genres: List<String>,
    tracks: List<MaItem>,
    rating: Double?,
): List<Pair<String, String>> = buildList {
    when {
        released != null && released.length == 10 -> add("Released" to prettyDate(released))
        year != null && year > 0 -> add("Year" to year.toString())
    }
    label?.let { add("Label" to it) }
    if (genres.isNotEmpty()) add("Genre" to genres.take(3).joinToString(", "))
    if (tracks.isNotEmpty()) {
        add("Tracks" to tracks.size.toString())
        val secs = tracks.sumOf { (it.duration ?: 0).toLong() }
        if (secs > 0) add("Length" to formatTotal(secs * 1000))
        val discs = tracks.mapNotNull { it.discNumber }.distinct().size
        if (discs > 1) add("Discs" to discs.toString())
    }
    rating?.takeIf { it > 0 }?.let { add("Rating" to "%.1f / 10".format(it)) }
}

/** `2014-03-21` as "21 March 2014"; anything else as it came. */
internal fun prettyDate(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso)
    "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${d.year}"
}.getOrDefault(iso)

/**
 * "About": the notes or biography, five lines until tapped open, and the facts set out
 * as a small two-column table under them, on a glass card tinted with the page's colour.
 */
@Composable
internal fun AboutCard(title: String, text: String?, facts: List<Pair<String, String>>, accent: Color) {
    if (text.isNullOrBlank() && facts.isEmpty()) return
    var open by remember(text) { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        SectionHeader(title)
        Column(
            Modifier
                .padding(horizontal = PageGutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(accent.a(0.16f), inkOn(0.04f))))
                .border(1.dp, Hairline, RoundedCornerShape(22.dp))
                .padding(22.dp)
                .animateContentSize(tween(Motion.PAGE_MS, easing = Motion.Emphasized)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!text.isNullOrBlank()) {
                Text(
                    text,
                    color = TextSecondary,
                    fontFamily = AppFont,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    maxLines = if (open) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { open = !open },
                )
                if (text.length > 280) {
                    Text(
                        if (open) "Show less" else "Read more",
                        color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clickable { open = !open },
                    )
                }
            }
            if (facts.isNotEmpty()) {
                facts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { (k, v) ->
                            Column(Modifier.weight(1f)) {
                                Text(k.uppercase(), color = accent, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.6.sp)
                                Text(v, color = TextPrimary, fontFamily = AppFont, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ─── Artist pieces ──────────────────────────────────────────────────────────

/**
 * The top of an artist's page when the library has a backdrop: the picture edge to edge,
 * fading into the page, their logo (or their name, large) over it, and the facts line.
 */
@Composable
internal fun ArtistBackdrop(
    backdrop: String,
    logo: String?,
    name: String,
    facts: String,
    scrolled: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(420.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // A slow drift down as the page scrolls up: the picture sits behind the page.
                    translationY = scrolled() * 0.45f
                    val s = 1.06f
                    scaleX = s; scaleY = s
                },
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.a(0.25f), Color.Transparent, Color.Black.a(0.55f)))))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = PageGutter, vertical = 18.dp)) {
            if (logo != null) {
                AsyncImage(
                    model = logo, contentDescription = name, contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(110.dp).fillMaxWidth(0.7f),
                )
            } else {
                Text(
                    name, color = Color.White, fontFamily = AppFont, fontWeight = FontWeight.Black,
                    fontSize = 56.sp, lineHeight = 58.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (facts.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(facts.uppercase(), color = Color.White.a(0.78f), fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.8.sp)
            }
        }
    }
}

/** "12 albums · 1998–2023 · Hip hop" — what an artist's page says first. Pure. */
internal fun artistFacts(albums: List<MaItem>, genres: List<String>): String {
    val parts = mutableListOf<String>()
    if (albums.isNotEmpty()) parts += if (albums.size == 1) "1 album" else "${albums.size} albums"
    val years = albums.mapNotNull { it.year?.takeIf { y -> y > 0 } }
    if (years.isNotEmpty()) parts += if (years.min() == years.max()) "${years.min()}" else "${years.min()}–${years.max()}"
    (genres.firstOrNull() ?: albums.flatMap { it.genres }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)?.let { parts += it }
    return parts.joinToString("  ·  ")
}

/** The newest record: the highest year, and among equals the first listed. Pure. */
internal fun latestOf(albums: List<MaItem>): MaItem? =
    albums.filter { (it.year ?: 0) > 0 }.maxByOrNull { it.year ?: 0 }

/** The newest record as a wide card in its own colours. */
@Composable
internal fun LatestReleaseCard(album: MaItem, onClick: () -> Unit) {
    val own = rememberAlbumPalette(album.image)
    Column(Modifier.padding(top = 8.dp)) {
        SectionHeader("Latest release")
        Row(
            Modifier
                .padding(horizontal = PageGutter)
                .fillMaxWidth()
                .pressScale(0.98f)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(own.accent.a(0.38f), own.swatch(1).a(0.16f), inkOn(0.04f))))
                .border(1.dp, Hairline, RoundedCornerShape(24.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ArtImage(
                url = sizedArt(album.image, TILE_PX), name = album.name,
                modifier = Modifier.size(148.dp).shadow(22.dp, RoundedCornerShape(14.dp), ambientColor = own.accent, spotColor = Color.Black),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                album.year?.takeIf { it > 0 }?.let {
                    Text(it.toString(), color = own.accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text(album.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                album.genres.firstOrNull()?.let { Text(it, color = TextMuted, fontFamily = AppFont, fontSize = 15.sp, maxLines = 1) }
            }
        }
    }
}

/**
 * "Through the years": the discography along a line, oldest first, one stop per record,
 * the year printed over the line where it changes.
 */
@Composable
internal fun TimelineShelf(albums: List<MaItem>, accent: Color, onClick: (MaItem) -> Unit) {
    val ordered = remember(albums) { albums.sortedWith(compareBy(nullsLast()) { it.year?.takeIf { y -> y > 0 } }) }
    val tile = 150.dp
    val gap = 18.dp
    val rowState = rememberLazyListState()
    val covers = LocalSharedCovers.current
    val line = Hairline
    Column(Modifier.padding(top = 8.dp)) {
        SectionHeader("Through the years")
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxWidth().touchDragScroll(rowState, Orientation.Horizontal),
        ) {
            itemsIndexed(ordered, key = { i, a -> "tl:$i:${a.itemId}" }) { i, album ->
                val year = album.year?.takeIf { it > 0 }
                val showYear = i == 0 || ordered[i - 1].year != album.year
                val last = i == ordered.lastIndex
                Column(
                    Modifier.width(tile).pressScale().clickable {
                        covers?.lastTapped = "timeline/${album.itemId}"
                        onClick(album)
                    },
                ) {
                    Text(
                        if (showYear) (year?.toString() ?: "—") else "",
                        color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.height(22.dp),
                    )
                    Box(
                        Modifier.fillMaxWidth().height(22.dp).drawBehind {
                            val y = size.height / 2f
                            val end = if (last) size.width / 2f else size.width + gap.toPx()
                            drawLine(line, Offset(if (i == 0) size.width / 2f else 0f, y), Offset(end, y), strokeWidth = 2f)
                            drawCircle(if (showYear) accent else line, (if (showYear) 6f else 4f).dp.toPx(), Offset(size.width / 2f, y))
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.runtime.CompositionLocalProvider(LocalCoverScope provides "timeline") {
                        ArtImage(
                            url = sizedArt(album.image, TILE_PX), name = album.name,
                            sharedKey = "timeline/${album.itemId}",
                            modifier = Modifier.size(tile),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(album.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

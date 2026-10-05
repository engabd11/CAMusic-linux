package com.engabd.sendpin.linux.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.engabd.sendpin.linux.PanelMetrics
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.AlbumPalette
import com.engabd.sendpin.ui.design.Bloom
import com.engabd.sendpin.ui.design.MeltBackdrop
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.inkOn

/*
 * The panel's design kit — the pieces every pane is built from, so the library, queue,
 * lights and settings read as one room rather than four phone screens stretched to fit.
 *
 * Scale comes from PanelMetrics (the now-playing cover's touch standard); colour comes
 * from the album (LocalPalette / LocalAccent, provided once by the shell), so every page
 * takes on whatever is playing, not just the cover.
 */

/** The panel's corner radii, one per kind of surface. */
internal object PanelShapes {
    val Card = RoundedCornerShape(28.dp)
    val Tile = RoundedCornerShape(18.dp)
    val Thumb = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(100.dp)
}

/** The page's side gutter — every header, shelf and grid lines up on it. */
internal val PageGutter: Dp = 24.dp

// ─── Ambience ────────────────────────────────────────────────────────────────

/**
 * The album's colours spread over a *panel*: four blooms placed as fractions of the
 * surface, not :app's ChameleonBloom offsets, which were laid out for a phone and on an
 * 800×1280 screen lit only its top-left corner. Top-left and bottom-right carry the
 * strongest swatches so the whole height of a portrait page is tinted.
 */
@Composable
fun BoxScope.PanelBloom(palette: AlbumPalette, level: Float, drift: Boolean = false) {
    val l = level.coerceIn(0f, 1f)
    if (l <= 0f) return
    // Eased over the album change's own length (the cover's flip), so the room and the
    // record turn over together rather than the colours arriving first.
    val c0 by animateColorAsState(palette.swatch(0), tween(1_200), label = "bloom0")
    val c1 by animateColorAsState(palette.swatch(1), tween(1_200), label = "bloom1")
    val c2 by animateColorAsState(palette.swatch(2), tween(1_200), label = "bloom2")
    val acc by animateColorAsState(palette.accent, tween(1_200), label = "bloomAccent")
    // The drift: each bloom wanders on its own slow orbit (the screensaver's colour-bleed
    // motion, smaller). One clock, read only inside graphicsLayer — it never recomposes.
    val clock = if (drift) {
        rememberInfiniteTransition(label = "bloomDrift").animateFloat(
            0f, 3_600f,
            // reversing, not restarting: an hour on, the orbits run back without a jump
            infiniteRepeatable(tween(3_600_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "bloomClock",
        )
    } else null
    BoxWithConstraints(Modifier.matchParentSize()) {
        val w = maxWidth
        val h = maxHeight
        Drifting(clock, 17f, 13f, 0.06f, 0.04f) { Bloom(c0, w * 1.05f, -w * 0.35f, -w * 0.40f, 0.46f * l) }
        Drifting(clock, 19f, 11f, 0.05f, 0.05f) { Bloom(c1, w * 0.90f, w * 0.40f, h * 0.06f, 0.30f * l) }
        Drifting(clock, 23f, 15f, 0.06f, 0.03f) { Bloom(c2, w * 0.95f, -w * 0.40f, h * 0.42f, 0.22f * l) }
        Drifting(clock, 14f, 21f, 0.05f, 0.04f) { Bloom(acc, w * 1.10f, w * 0.30f, h * 0.70f, 0.26f * l) }
    }
}

/**
 * One bloom on its own slow orbit: a whole-surface layer translated by a fraction of the
 * surface, on two sine periods (seconds) that never line up. No [clock], no motion.
 */
@Composable
private fun BoxScope.Drifting(
    clock: State<Float>?,
    periodX: Float,
    periodY: Float,
    reachX: Float,
    reachY: Float,
    content: @Composable () -> Unit,
) {
    if (clock == null) return content()
    Box(
        Modifier.matchParentSize().graphicsLayer {
            val t = clock.value
            translationX = size.width * reachX * kotlin.math.sin(t / periodX)
            translationY = size.height * reachY * kotlin.math.sin(t / periodY)
        },
    ) { content() }
}

/**
 * Behind every pane: the playing album's wash, softer than the cover's, with the album's
 * colour bleed over it — the panel-scaled blooms, or the living liquid when that is on.
 * This is what makes the library, queue, lights and settings colourful — they sit in the
 * same light as the record, and the glass cards on them sample it.
 */
@Composable
fun BoxScope.PaneAmbience(
    url: String?,
    palette: AlbumPalette,
    drift: Boolean = false,
    living: LivingArt = LivingArt.Still,
    seed: Int = 0,
) {
    MeltBackdrop(url = url, intensity = 0.85f, scrim = 0.45f)
    AlbumBleed(palette, living, 0.62f, drift, seed)
}

// ─── Surfaces ────────────────────────────────────────────────────────────────

/** A glass card: the ambience behind it, blurred, under a hairline. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 22.dp,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glassSurface(PanelShapes.Card, inkOn(0.055f), border = Hairline)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

// ─── Headers ─────────────────────────────────────────────────────────────────

/**
 * A page's title block: an optional back button, an accent eyebrow, the title at
 * display size, a muted subtitle, and trailing actions.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    titleSize: TextUnit = 36.sp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val accent = rememberAccent()
    Row(
        modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (eyebrow != null) Eyebrow(eyebrow, accent)
            Text(
                title,
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = titleSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = PanelMetrics.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

/** The small tracked capitals over a title — "ALBUM", "NOW PLAYING". */
@Composable
fun Eyebrow(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color,
        fontFamily = AppFont,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        maxLines = 1,
    )
}

/** A shelf or section title, with an optional "See all" on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = rememberAccent()
    Row(
        modifier.fillMaxWidth().padding(horizontal = PageGutter).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 23.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null && onAction != null) {
            Row(
                Modifier
                    .clip(PanelShapes.Pill)
                    .clickable(onClick = onAction)
                    .heightIn(min = PanelMetrics.ChipMinHeight)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, color = accent, fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = accent, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ─── Buttons ─────────────────────────────────────────────────────────────────

/** A round glass button — back, search, favourite. [active] fills it with the accent. */
@Composable
fun CircleButton(
    icon: ImageVector,
    label: String,
    size: Dp = 56.dp,
    iconSize: Dp = 26.dp,
    active: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val accent = rememberAccent()
    Box(
        Modifier
            .size(size)
            .then(
                if (active) Modifier.clip(CircleShape).background(accent)
                else Modifier.glassSurface(CircleShape, inkOn(0.08f), border = Hairline)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Ink else tint ?: TextPrimary, modifier = Modifier.size(iconSize))
    }
}

/**
 * A pill button at the panel's touch scale. [filled] is the primary action (accent
 * ground, ink label); otherwise glass.
 */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = true,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val accent = color ?: rememberAccent()
    Row(
        modifier
            .heightIn(min = 56.dp)
            .then(
                if (filled) Modifier.clip(PanelShapes.Pill).background(accent)
                else Modifier.glassSurface(PanelShapes.Pill, inkOn(0.08f), border = Hairline)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        val fg = if (filled) Ink else TextPrimary
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
        Text(text, color = fg, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
    }
}

/**
 * A segmented control — one glass track, the chosen segment filled with the accent.
 * The panel's replacement for loose chip rows: every option is a full-height target
 * and the choice reads at a glance from across the room.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    height: Dp = 56.dp,
) {
    val accent = rememberAccent()
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .glassSurface(PanelShapes.Pill, inkOn(0.06f), border = Hairline)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            val on = opt == selected
            val bg by animateColorAsState(if (on) accent else Color.Transparent, tween(220), label = "segBg")
            val fg = if (on) Ink else TextSecondary
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(PanelShapes.Pill)
                    .background(bg)
                    .clickable { onSelect(opt) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.invoke(opt)?.let { Icon(it, null, tint = fg, modifier = Modifier.size(22.dp)) }
                Text(
                    label(opt),
                    color = fg,
                    fontFamily = AppFont,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

// ─── Artwork ─────────────────────────────────────────────────────────────────

/**
 * A stable hue for an item with no artwork, from its name — so a cover-less album is
 * a colour of its own rather than a grey hole, and the same album is the same colour
 * every time it appears. Pure for the tests.
 */
internal fun placeholderHue(name: String): Float {
    var h = 0
    name.forEach { h = h * 31 + it.code }
    return ((h % 360) + 360) % 360f
}

/** The placeholder's two-stop gradient for [name]. */
internal fun placeholderColors(name: String): List<Color> {
    val hue = placeholderHue(name)
    return listOf(
        Color.hsv(hue, 0.55f, 0.55f),
        Color.hsv((hue + 48f) % 360f, 0.70f, 0.24f),
    )
}

/**
 * Cover art in [shape], over a coloured placeholder that shows while it loads and
 * stays when there is none.
 */
@Composable
fun ArtImage(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    shape: Shape = PanelShapes.Tile,
    glyph: ImageVector = Icons.Default.MusicNote,
) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(placeholderColors(name))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, null, tint = Color.White.a(0.35f), modifier = Modifier.fillMaxSize(0.36f))
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * An album / artist / playlist tile: square art (round for artists), title, subtitle.
 * Width comes from the caller — a grid cell or a shelf's fixed tile width.
 */
@Composable
fun ArtTile(
    item: MaItem,
    onClick: (MaItem) -> Unit,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
) {
    Column(
        modifier
            .clip(PanelShapes.Tile)
            .clickable { onClick(item) }
            .padding(bottom = 6.dp),
        horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        ArtImage(
            url = item.image,
            name = item.name,
            shape = if (circular) CircleShape else PanelShapes.Tile,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            item.name,
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = PanelMetrics.TileTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circular) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        val sub = item.subtitle ?: item.year?.toString()
        if (!sub.isNullOrBlank()) {
            Text(
                sub,
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = PanelMetrics.TileSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (circular) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/**
 * One titled, horizontally-scrolling row of tiles — a shelf. Tiles are a fixed width
 * wide enough that four and a bit fit a portrait panel: the cut-off fifth is the only
 * "there is more this way" a touch screen with no scrollbar can give.
 */
@Composable
fun Shelf(
    title: String,
    items: List<MaItem>,
    onClick: (MaItem) -> Unit,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    tileWidth: Dp = 172.dp,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title, action = action, onAction = onAction)
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().touchDragScroll(rowState, Orientation.Horizontal),
        ) {
            // Index in the key: a shelf can legitimately hold one item twice (a track's
            // album and the album itself), and a duplicate key is a crash, not a glitch.
            itemsIndexed(items, key = { i, it -> "$i:${it.itemId}" }) { _, item ->
                ArtTile(item, onClick, Modifier.width(tileWidth), circular = circular)
            }
        }
    }
}

/** A shelf's stand-in while it loads — the same footprint, so nothing jumps. */
@Composable
fun ShelfSkeleton(tileWidth: Dp = 172.dp, count: Int = 5) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonBlock(Modifier.padding(horizontal = PageGutter).width(200.dp).height(26.dp), PanelShapes.Thumb)
        Row(
            Modifier.fillMaxWidth().padding(start = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(count) { SkeletonBlock(Modifier.width(tileWidth).aspectRatio(1f), PanelShapes.Tile) }
        }
    }
}

/** A breathing glass block — loading, not empty. */
@Composable
fun SkeletonBlock(modifier: Modifier, shape: Shape) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(
        0.04f, 0.10f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(modifier.clip(shape).background(inkOn(a)))
}

// ─── Category cards ──────────────────────────────────────────────────────────

/**
 * A browse-root card: a saturated two-stop gradient, the label bottom-left, the glyph
 * ghosted large off the bottom-right corner — :app's CARDS look at panel scale.
 */
@Composable
fun CategoryCard(
    label: String,
    icon: ImageVector,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(112.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick),
    ) {
        Icon(
            icon, null,
            tint = Color.White.a(0.22f),
            modifier = Modifier.size(96.dp).align(Alignment.BottomEnd).offset(x = 18.dp, y = 18.dp),
        )
        Icon(
            icon, null,
            tint = Color.White,
            modifier = Modifier.padding(16.dp).size(26.dp).align(Alignment.TopStart),
        )
        Text(
            label,
            color = Color.White,
            fontFamily = AppFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
}

// ─── Small pieces ────────────────────────────────────────────────────────────

/**
 * The now-playing mark on a list row: three bars that dance while MPD plays and
 * rest when it is paused.
 */
@Composable
fun PlayingBars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 180, 360)
    Row(
        modifier.size(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        phases.forEach { delayMs ->
            val h by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(520, delayMs, LinearEasing), RepeatMode.Reverse),
                label = "bar",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (playing) h else 0.35f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/** A centred glyph, title and line — for an empty page, never a blank one. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val accent = rememberAccent()
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(accent.a(0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(44.dp))
        }
        Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center)
        Text(body, color = TextMuted, fontFamily = AppFont, fontSize = PanelMetrics.Body, textAlign = TextAlign.Center)
    }
}

/** A thin rule between rows, inset to the gutter. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = PageGutter).height(1.dp).alpha(0.6f).background(Hairline))
}

/** "1 h 12 min" / "48 min" / "3 min" — the album and queue totals. */
internal fun formatTotal(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

/** A genre card's gradient — the placeholder's hue, lifted so a wall of them reads as colour. */
internal fun genreColors(name: String): List<Color> {
    val hue = placeholderHue(name)
    return listOf(
        Color.hsv(hue, 0.62f, 0.80f),
        Color.hsv((hue + 48f) % 360f, 0.78f, 0.34f),
    )
}

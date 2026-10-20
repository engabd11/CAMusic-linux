package com.engabd.sendpin.linux

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.Eyebrow
import com.engabd.sendpin.linux.ui.LocalSharedCovers
import com.engabd.sendpin.linux.ui.Motion
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.linux.ui.TILE_PX
import com.engabd.sendpin.linux.ui.placeholderColors
import com.engabd.sendpin.linux.ui.sizedArt
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * The library as a gallery: covers to look at as much as to browse. Cover Flow for the
 * Albums page, and the carousel at the top of Home. Both are drawn through graphics layers
 * only — position, tilt, scale and fade — so a drag costs a redraw, not a re-layout.
 */

// ─── Cover Flow ─────────────────────────────────────────────────────────────

/** How many covers either side of the centre are drawn. */
private const val FLOW_REACH = 6

/** Seconds untouched before the flow starts turning by itself, and between its turns. */
private const val FLOW_IDLE_MS = 25_000L
private const val FLOW_STEP_MS = 7_000L

/**
 * Where a cover sits in the flow, [d] covers from the centre ([d] fractional while moving):
 * its horizontal offset as a fraction of the cover size, its tilt, its scale. The centre
 * one faces the viewer; the first step out swings it most of the way round; the rest stack
 * up closely behind. Pure, for the tests.
 */
internal data class FlowPose(val x: Float, val rotationY: Float, val scale: Float, val alpha: Float)

internal fun flowPose(d: Float): FlowPose {
    val a = abs(d)
    val near = a.coerceAtMost(1f)
    val far = (a - 1f).coerceAtLeast(0f)
    return FlowPose(
        x = sign(d) * (near * 0.66f + far * 0.25f),
        rotationY = -d.coerceIn(-1f, 1f) * 58f,
        scale = 1f - 0.2f * near,
        alpha = (1f - (a - (FLOW_REACH - 1.5f)).coerceAtLeast(0f)).coerceIn(0f, 1f),
    )
}

/** The cover a release at [position] with fling [velocity] (covers per second) settles on. */
internal fun flowTarget(position: Float, velocity: Float, count: Int): Int =
    (position + velocity * 0.22f).roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

/**
 * The albums as a Cover Flow: one record facing out in the middle, the rest turned on
 * their edges either side. Drag (or fling) to flip through; tap the middle one to open
 * it, tap one beside it to bring it round. Left alone, it turns a record every few
 * seconds, so the panel is a slow show of the library's art.
 */
@Composable
internal fun CoverFlow(
    items: List<MaItem>,
    start: Int,
    onSettle: (Int) -> Unit,
    onOpen: (MaItem) -> Unit,
    onNearEnd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** True while something covers the page (the listening view, the gallery): no turning. */
    paused: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val hidden by rememberUpdatedState(paused)
    val position = remember { Animatable(start.coerceIn(0, (items.size - 1).coerceAtLeast(0)).toFloat()) }
    val centre by remember { derivedStateOf { position.value.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0)) } }
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val covers = LocalSharedCovers.current

    fun settleOn(i: Int) {
        scope.launch {
            position.animateTo(i.toFloat(), spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow))
        }
    }

    LaunchedEffect(centre) {
        onSettle(centre)
        if (onNearEnd != null && centre >= items.size - 24) onNearEnd()
    }
    // A gallery left alone turns by itself.
    LaunchedEffect(items.size) {
        while (true) {
            delay(FLOW_STEP_MS)
            if (hidden || System.currentTimeMillis() - touchedAt < FLOW_IDLE_MS || position.isRunning) continue
            val next = if (centre + 1 < items.size) centre + 1 else 0
            position.animateTo(next.toFloat(), tween(1_400, easing = Motion.Standard))
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(items.size) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking(); touchedAt = System.currentTimeMillis(); scope.launch { position.stop() } },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val coverPx = size.width * (if (size.height > size.width) 0.52f else 0.4f) * 0.66f
                        settleOn(flowTarget(position.value, -v / coverPx, items.size))
                    },
                    onDragCancel = { settleOn(centre) },
                ) { change, dx ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val coverPx = size.width * (if (size.height > size.width) 0.52f else 0.4f) * 0.66f
                    val next = (position.value - dx / coverPx).coerceIn(-0.4f, items.size - 0.6f)
                    scope.launch { position.snapTo(next) }
                }
            }
            .pointerInput(items.size) {
                // A mouse wheel steps a record at a time (a keyboard and mouse on the NUC).
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.firstOrNull()?.scrollDelta?.let { if (abs(it.y) > abs(it.x)) it.y else it.x } ?: 0f
                            if (dy != 0f) {
                                touchedAt = System.currentTimeMillis()
                                settleOn((centre + dy.sign.toInt()).coerceIn(0, items.size - 1))
                            }
                        }
                    }
                }
            },
    ) {
        // Larger in portrait, where the panel has height to spare under the stage.
        val cover: Dp = if (maxHeight > maxWidth) minOf(maxWidth * 0.52f, maxHeight * 0.5f) else minOf(maxWidth * 0.40f, maxHeight * 0.52f)
        val coverPx = with(LocalDensity.current) { cover.toPx() }
        val stageTop = (maxHeight - cover) * 0.32f

        // Far covers first, the centre last, so each is drawn over the ones behind it. The
        // order only changes as the centre passes a cover, not on every frame of a drag.
        val order = remember(centre, items.size) {
            ((centre - FLOW_REACH)..(centre + FLOW_REACH))
                .filter { it in items.indices }
                .sortedByDescending { abs(it - centre) }
        }
        order.forEach { i ->
            val item = items[i]
            val key = "flow/${item.itemId}"
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = stageTop)
                    .size(cover)
                    .graphicsLayer {
                        val pose = flowPose(i - position.value)
                        translationX = pose.x * coverPx
                        rotationY = pose.rotationY
                        scaleX = pose.scale
                        scaleY = pose.scale
                        alpha = pose.alpha
                        cameraDistance = 14f * density
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        touchedAt = System.currentTimeMillis()
                        if (i == centre) {
                            covers?.lastTapped = key
                            onOpen(item)
                        } else {
                            settleOn(i)
                        }
                    },
            ) {
                ArtImage(
                    url = sizedArt(item.image, TILE_PX + 180),
                    name = item.name,
                    shape = RoundedCornerShape(10.dp),
                    sharedKey = key,
                    modifier = Modifier.fillMaxSize().shadow(28.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black, spotColor = Color.Black),
                )
                // The record's reflection on the glossy floor, only near the middle where it shows.
                if (abs(i - centre) <= 2) Reflection(item, Modifier.align(Alignment.BottomCenter).offset(y = cover + 6.dp).size(cover))
            }
        }

        // The middle record's name, crossing over as the flow settles on another.
        val current = items.getOrNull(centre)
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(Motion.FADE_MS + 80)) togetherWith fadeOut(tween(Motion.FADE_MS)) },
            contentKey = { it?.itemId },
            label = "flowTitle",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = stageTop + cover * 1.36f)
                .fillMaxWidth()
                .padding(horizontal = PageGutter * 2),
        ) { item ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (item != null) {
                    Text(
                        item.name, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold,
                        fontSize = 28.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(item.subtitle, item.year?.toString()).joinToString("  ·  ")
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(sub, color = TextSecondary, fontFamily = AppFont, fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Where in the library the flow is: a thin rail at the foot.
        if (items.size > 1) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).width(maxWidth * 0.5f).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(TextMuted.a(0.25f)),
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = (position.value / (items.size - 1)).coerceIn(0.02f, 1f)
                    }.background(TextSecondary.a(0.8f)),
                )
            }
            Text(
                "${centre + 1} / ${items.size}${if (onNearEnd != null) "+" else ""}",
                color = TextMuted, fontFamily = AppFont, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            )
        }
    }
}

/** A cover upside down under itself, fading into the floor. */
@Composable
private fun Reflection(item: MaItem, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer {
                scaleY = -1f
                alpha = 0.28f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Flipped, so the fade runs from the bottom of the drawn cover (its top edge on screen).
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstIn)
            },
    ) {
        ArtImage(url = sizedArt(item.image, TILE_PX + 180), name = item.name, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxSize())
    }
}

// ─── The Home carousel ──────────────────────────────────────────────────────

/** One page of the carousel: an album and the line that says why it is there. */
internal data class HeroSlide(val item: MaItem, val why: String)

/** The carousel's pages: the newest records first, then a few to rediscover, no album twice. */
internal fun heroSlides(home: HomeShelves, max: Int = 8): List<HeroSlide> {
    val out = LinkedHashMap<String, HeroSlide>()
    // A slide is a picture first: a record with no cover has no place in it.
    fun ok(a: MaItem) = !a.image.isNullOrBlank()
    home.recentlyPlayed.firstOrNull(::ok)?.let { out[it.itemId] = HeroSlide(it, "Jump back in") }
    home.recentlyAdded.filter(::ok).take(4).forEach { out.putIfAbsent(it.itemId, HeroSlide(it, "New in your library")) }
    home.rediscover.filter(::ok).forEach { if (out.size < max) out.putIfAbsent(it.itemId, HeroSlide(it, "Rediscover")) }
    home.favoriteAlbums.filter(::ok).shuffled().forEach { if (out.size < max) out.putIfAbsent(it.itemId, HeroSlide(it, "A favourite")) }
    return out.values.take(max)
}

private const val HERO_STEP_MS = 9_000L

/**
 * The top of Home: one record at a time, big, on its own art blurred into the page — its
 * name, its artist, Play. Swipe for the next; left alone it moves on every few seconds.
 */
@Composable
internal fun HeroCarousel(
    slides: List<HeroSlide>,
    onOpen: (MaItem) -> Unit,
    onPlay: (MaItem) -> Unit,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    if (slides.isEmpty()) return
    val hidden by rememberUpdatedState(paused)
    var index by remember(slides) { mutableStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    val covers = LocalSharedCovers.current

    fun go(step: Int) {
        forward = step > 0
        index = (index + step + slides.size) % slides.size
    }

    LaunchedEffect(slides) {
        while (true) {
            delay(HERO_STEP_MS)
            if (!hidden && System.currentTimeMillis() - touchedAt > HERO_STEP_MS * 2) go(1)
        }
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(horizontal = PageGutter)
                .fillMaxWidth()
                .height(372.dp)
                .clip(PanelShapes.Card)
                .pointerInput(slides) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f; touchedAt = System.currentTimeMillis() },
                        onDragEnd = { if (abs(travel) > 70.dp.toPx()) go(if (travel < 0) 1 else -1) },
                    ) { _, dx -> travel += dx }
                },
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(Motion.PAGE_MS + 160, easing = Motion.Emphasized)) { dir * it / 3 } +
                        fadeIn(tween(Motion.PAGE_MS))) togetherWith
                        (slideOutHorizontally(tween(Motion.PAGE_MS + 160, easing = Motion.Emphasized)) { -dir * it / 4 } +
                            fadeOut(tween(Motion.FADE_MS)))
                },
                label = "heroSlide",
                modifier = Modifier.fillMaxSize(),
            ) { i ->
                val slide = slides[i]
                HeroPage(slide, onOpen = {
                    covers?.lastTapped = "hero/${slide.item.itemId}"
                    onOpen(slide.item)
                }, onPlay = { onPlay(slide.item) })
            }
        }
        // Which page: a row of dots, the current one drawn out into a dash.
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slides.indices.forEach { i ->
                val on = i == index
                val w by animateDpAsState(if (on) 26.dp else 8.dp, tween(Motion.PAGE_MS, easing = Motion.Emphasized), label = "dot")
                Box(
                    Modifier
                        .width(w).height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) TextPrimary.a(0.9f) else TextMuted.a(0.4f))
                        .clickable { touchedAt = System.currentTimeMillis(); forward = i > index; index = i },
                )
            }
        }
    }
}

@Composable
private fun HeroPage(slide: HeroSlide, onOpen: () -> Unit, onPlay: () -> Unit) {
    val item = slide.item
    val palette = rememberAlbumPalette(item.image)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(placeholderColors(item.name)))
            .clickable(onClick = onOpen),
    ) {
        if (!item.image.isNullOrBlank()) {
            AsyncImage(
                model = sizedArt(item.image, TILE_PX),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                    .blur(60.dp, BlurredEdgeTreatment.Rectangle),
            )
        }
        // Darker toward the words, and tinted with the record's own colour.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Ink.a(0.05f), palette.accent.copy(alpha = 0.10f), Ink.a(0.62f), Ink.a(0.82f)),
                ),
            ),
        )
        Row(
            Modifier.fillMaxSize().padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            ArtImage(
                url = item.image,
                name = item.name,
                placeholderUrl = sizedArt(item.image, TILE_PX),
                sharedKey = "hero/${item.itemId}",
                modifier = Modifier
                    .size(310.dp)
                    .shadow(36.dp, PanelShapes.Tile, ambientColor = palette.accent, spotColor = Color.Black),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(slide.why, palette.accent)
                Text(
                    item.name,
                    color = Color.White,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let {
                    Text(it, color = Color.White.a(0.8f), fontFamily = AppFont, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.year?.let { Text(it.toString(), color = Color.White.a(0.55f), fontFamily = AppFont, fontSize = 16.sp) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Play", icon = Icons.Default.PlayArrow, color = palette.accent, onClick = onPlay)
                    PillButton("Open", icon = Icons.AutoMirrored.Filled.ArrowForward, filled = false, onClick = onOpen)
                }
            }
        }
    }
}

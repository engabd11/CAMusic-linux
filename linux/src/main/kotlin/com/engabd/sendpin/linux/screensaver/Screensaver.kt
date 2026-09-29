package com.engabd.sendpin.linux.screensaver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.DisplayFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** How long one album dissolves into the next. */
private const val FADE_MS = 1_800

/** What the Settings toggles switch on around the art. */
private data class Extras(
    val clock: Boolean,
    val details: Boolean,
    val holdToPlay: Boolean,
    val drift: Boolean,
    /** Set the words in the record's own colours, the way the player does. */
    val albumText: Boolean,
    /** Multiplies every size in the words and clock — Settings' Small / Regular / Large. */
    val textScale: Float = 1f,
    /** `left` | `centre` | `right`. */
    val clockPosition: String = "right",
)

/** Settings' text size key → the scale the words are set at. */
internal fun textScaleOf(key: String): Float = when (key) {
    "small" -> 0.82f
    "large" -> 1.22f
    else -> 1f
}

/**
 * The gallery: when nothing is playing and nobody has touched the panel for the set delay,
 * the library's album art takes the screen, one record at a time. A tap wakes the panel
 * (and goes no further — the pane underneath never sees it); holding a slide plays that
 * album. Music starting from anywhere else dismisses it too.
 *
 * Lives above the whole shell in `Main.kt`. [gate] is fed every pointer event there.
 */
@Composable
fun ScreensaverHost(model: PanelModel, gate: IdleGate) {
    val settings = model.settings
    val enabled by settings.screensaverEnabled.collectAsState(initial = true)
    val delayMin by settings.screensaverDelayMin.collectAsState(initial = 10)
    val style by settings.screensaverStyle.collectAsState(initial = "gallery")
    val slideS by settings.screensaverSlideS.collectAsState(initial = 20)
    val albums by settings.screensaverAlbums.collectAsState(initial = "all")
    val clock by settings.screensaverClock.collectAsState(initial = true)
    val details by settings.screensaverDetails.collectAsState(initial = true)
    val holdToPlay by settings.screensaverHoldToPlay.collectAsState(initial = true)
    val drift by settings.screensaverDrift.collectAsState(initial = true)
    val albumText by settings.screensaverAlbumText.collectAsState(initial = false)
    val textSize by settings.screensaverTextSize.collectAsState(initial = "regular")
    val clockPosition by settings.screensaverClockPosition.collectAsState(initial = "right")
    val remote by model.state.collectAsState()
    val source by model.source.collectAsState()
    val forced by model.screensaverNow.collectAsState()
    val playing = remote?.playing == true
    val scope = rememberCoroutineScope()

    var showing by remember { mutableStateOf(false) }
    var warm by remember { mutableStateOf(false) }

    // Music starting or stopping is activity: the delay counts from the later of that
    // and the last touch. Starting also ends the gallery, wherever it was started from.
    LaunchedEffect(playing) {
        gate.touch()
        if (playing) showing = false
    }
    LaunchedEffect(forced) { if (forced) showing = true }
    LaunchedEffect(enabled, delayMin, playing) {
        val delayMs = delayMin * 60_000L
        while (true) {
            val idle = gate.idleMs()
            if (IdleGate.shouldShow(enabled, playing, idle, delayMs)) showing = true
            warm = IdleGate.shouldPrewarm(enabled, playing, idle, delayMs)
            delay(1_000)
        }
    }

    // The preloader lives only while the gallery is due or up: it starts
    // IdleGate.PREWARM_MS early so the first slide is ready, and is dropped (with its
    // bitmaps) once the panel is in use again.
    val active = warm || showing
    val feed = remember(source, albums, style, active) {
        val src = source
        if (!active || src == null) null else SlideFeed(src, albums, style, meta = { ids ->
            SlideMetaClient.fetch(
                metaHttp,
                model.jfUrl.value,
                settings.jfToken.first(),
                settings.deviceId.first(),
                ids,
            )
        })
    }
    LaunchedEffect(feed) { feed?.run() }

    fun dismiss() {
        showing = false
        model.screensaverNow.value = false
        gate.touch()
    }

    AnimatedVisibility(
        visible = showing,
        enter = fadeIn(tween(1_400)),
        exit = fadeOut(tween(600)),
    ) {
        Gallery(
            feed = feed,
            style = style,
            slideMs = slideS.coerceAtLeast(5) * 1_000,
            extras = Extras(clock, details, holdToPlay, drift, albumText, textScaleOf(textSize), clockPosition),
            onTap = ::dismiss,
            onHold = { id ->
                scope.launch { model.playAlbumById(id) }
                dismiss()
            },
        )
    }
}

private val metaHttp by lazy { OkHttpClient() }

/** A slide on screen; [serial] keys it, since an album can come round again. */
private data class Layer(val slide: Slide, val serial: Long, val leaving: Boolean = false)

@Composable
private fun Gallery(
    feed: SlideFeed?,
    style: String,
    slideMs: Int,
    extras: Extras,
    onTap: () -> Unit,
    onHold: (String) -> Unit,
) {
    var layers by remember { mutableStateOf(listOf<Layer>()) }
    var serial by remember { mutableStateOf(0L) }

    // Advance only to a slide the feed has fully prepared; if the next isn't ready,
    // the current one simply holds the screen a little longer.
    LaunchedEffect(feed, slideMs) {
        feed ?: return@LaunchedEffect
        while (true) {
            val next = feed.next()
            val entering = Layer(next, ++serial)
            layers = layers.filterNot { it.leaving }.map { it.copy(leaving = true) } + entering
            delay(FADE_MS + 200L)
            layers = layers.filterNot { it.leaving }
            delay((slideMs - FADE_MS - 200L).coerceAtLeast(1_000L))
        }
    }

    // Read through updated state: keying the gesture detector on them would restart it
    // when a slide changes mid-hold, and the hold would silently never land.
    val currentId by rememberUpdatedState(layers.lastOrNull { !it.leaving }?.slide?.item?.itemId)
    val holdToPlay by rememberUpdatedState(extras.holdToPlay)
    val currentInk = layers.lastOrNull { !it.leaving }?.slide?.look?.ink
    val driftOffset = rememberDrift(extras.drift)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        val id = currentId
                        if (holdToPlay && id != null) onHold(id) else onTap()
                    },
                )
            },
    ) {
        for (layer in layers) key(layer.serial) {
            SlideLayer(layer.slide, layer.leaving, style, slideMs, extras, driftOffset)
        }
        if (extras.clock) {
            val gutter = maxWidth * 0.08f
            val (corner, across) = when (extras.clockPosition) {
                "left" -> Alignment.TopStart to Alignment.Start
                "centre" -> Alignment.TopCenter to Alignment.CenterHorizontally
                else -> Alignment.TopEnd to Alignment.End
            }
            Clock(
                accent = if (extras.albumText) currentInk else null,
                // the clock grows less than the words: large type must not reach the print
                scale = minOf(extras.textScale, 1.1f),
                align = across,
                modifier = Modifier
                    .align(corner)
                    // the print's own gutter, so a clock on the left lines up with it
                    .padding(top = 40.dp, start = gutter, end = gutter)
                    .offset { driftOffset() },
            )
        }
    }
}

@Composable
private fun SlideLayer(
    slide: Slide,
    leaving: Boolean,
    style: String,
    slideMs: Int,
    extras: Extras,
    drift: () -> IntOffset,
) {
    // Milliseconds since this slide arrived, running linearly past its whole stay: the
    // Ken Burns move, the wash's drift and the text's staggered entrance all read it —
    // inside graphicsLayer only, so none of them recomposes a frame.
    val age = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val span = slideMs + 2 * FADE_MS
        age.animateTo(span.toFloat(), tween(span, easing = LinearEasing))
    }
    val fade = remember { Animatable(0f) }
    val textOut = remember { Animatable(1f) }
    LaunchedEffect(leaving) {
        if (leaving) {
            // the words leave first, then the picture dissolves under the next one
            launch { textOut.animateTo(0f, tween(500)) }
            fade.animateTo(0f, tween(FADE_MS, delayMillis = 200, easing = FastOutSlowInEasing))
        } else {
            fade.animateTo(1f, tween(FADE_MS, easing = FastOutSlowInEasing))
        }
    }
    val text = TextMotion({ age.value }, { textOut.value })

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        if (style == "kenburns") {
            KenBurnsSlide(slide, slideMs, { age.value }, text, extras, drift)
        } else {
            GallerySlide(slide, slideMs, { age.value }, text, extras, drift, bleed = style == "bloom")
        }
        if (extras.holdToPlay) {
            Text(
                "HOLD TO PLAY",
                color = Color.White.copy(alpha = 0.5f),
                fontFamily = AppFont,
                fontSize = 12.sp,
                letterSpacing = 0.32.em,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .graphicsLayer {
                        // a moment after the slide settles, then gone again
                        val a = age.value
                        alpha = ramp(a, 2_600f, 800f) * (1f - ramp(a, 6_400f, 900f)) * textOut.value
                    },
            )
        }
    }
}

/** The text's timing, as deferred reads. */
private class TextMotion(val age: () -> Float, val out: () -> Float) {
    /** Alpha for an element that enters at [startMs]. */
    fun alpha(startMs: Float): Float = ramp(age(), startMs, 900f) * out()

    /** A settling rise: 14 px below to rest, as it fades in. */
    fun rise(startMs: Float): Float = (1f - ramp(age(), startMs, 1_300f)) * 14f
}

/** 0 before [start], 1 after [start] + [length], eased in between. */
private fun ramp(t: Float, start: Float, length: Float): Float {
    val x = ((t - start) / length).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(x)
}

// ── Gallery wall: the cover as a lit print on a wash of its own light ─────

@Composable
private fun GallerySlide(
    slide: Slide,
    slideMs: Int,
    age: () -> Float,
    text: TextMotion,
    extras: Extras,
    drift: () -> IntOffset,
    /** The album-colours style: the palette's own bleed behind the print, not the blurred cover. */
    bleed: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (bleed) {
            ColourBleed(slide.look.bleed, if (extras.albumText) slide.look.bleedLevel else 1f, age)
        } else {
            CoverWash(slide, age)
        }

        val w = maxWidth
        if (landscape) {
            // A clock on the left or in the middle sits above the print, not beside it:
            // the print shrinks and drops so the clock (up to 1.1x, ~150 dp) clears it.
            val clockOverPrint = extras.clock && extras.clockPosition != "right"
            val print = if (clockOverPrint) minOf(maxHeight - 340.dp, w * 0.40f)
            else minOf(maxHeight * 0.64f, w * 0.40f)
            Row(
                Modifier.fillMaxSize()
                    .padding(horizontal = w * 0.08f)
                    .padding(top = if (clockOverPrint) 110.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Print(slide, print, slideMs, age)
                Spacer(Modifier.width(w * 0.06f))
                Box(Modifier.weight(1f).offset { drift() }) {
                    TextBlock(slide, text, extras, TextAlign.Start, titleMaxSp = 76f)
                }
            }
        } else {
            val print = maxWidth * 0.70f
            Column(
                Modifier.fillMaxSize().padding(horizontal = maxWidth * 0.08f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(0.9f))
                Print(slide, print, slideMs, age)
                Spacer(Modifier.height(56.dp))
                Box(Modifier.fillMaxWidth().offset { drift() }) {
                    TextBlock(slide, text, extras, TextAlign.Center, titleMaxSp = 64f)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The gallery wall's backdrop: the pre-blurred cover, far larger than the screen, turning
 * and breathing too slowly to notice — only to feel — darkened toward the edges and
 * warmed in the middle with the record's accent.
 */
@Composable
private fun CoverWash(slide: Slide, age: () -> Float) {
    Image(
        slide.wash,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val t = age() / 1_000f
            val s = 1.45f + 0.06f * sin(t / 7f)
            scaleX = s
            scaleY = s
            rotationZ = 5f * sin(t / 19f)
            translationX = size.width * 0.03f * sin(t / 11f)
            translationY = size.height * 0.03f * sin(t / 13f)
            alpha = 0.9f
        },
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                0f to slide.palette.accent.copy(alpha = 0.10f),
                0.55f to Color.Black.copy(alpha = 0.45f),
                1f to Color.Black.copy(alpha = 0.82f),
            ),
        ),
    )
}

/**
 * The album-colours backdrop: the record's palette bleeding across near-black, the way
 * CAMusic's player lights its canvas (ChameleonBloom) — four soft blooms, each wandering
 * on its own slow orbit. Painted from [AlbumInk.bleed], every album colour *except* the
 * words' ink, so the words never sit on their own colour, and dimmed to [level] where
 * the exact ink would otherwise not read against it. Drawn in one Canvas that reads
 * the slide's age only while drawing, so the motion never recomposes.
 */
@Composable
private fun ColourBleed(bleed: List<Color>, level: Float, age: () -> Float) {
    fun tone(i: Int) = bleed[i % bleed.size]
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF07070A))
        val t = age() / 1_000f
        val w = size.width
        val h = size.height
        val reach = maxOf(w, h)
        fun bloom(color: Color, x: Float, y: Float, radius: Float, alpha: Float) {
            val c = Offset(x, y)
            drawCircle(
                Brush.radialGradient(
                    0f to color.copy(alpha = alpha),
                    0.55f to color.copy(alpha = alpha * 0.35f),
                    1f to Color.Transparent,
                    center = c,
                    radius = radius,
                ),
                radius = radius,
                center = c,
            )
        }
        bloom(tone(0), w * (0.12f + 0.06f * sin(t / 17f)), h * (0.18f + 0.05f * sin(t / 13f)), reach * 0.78f, 0.62f * level)
        bloom(tone(1), w * (0.88f + 0.05f * sin(t / 19f)), h * (0.28f + 0.06f * sin(t / 11f)), reach * 0.62f, 0.46f * level)
        bloom(tone(2), w * (0.25f + 0.06f * sin(t / 23f)), h * (0.92f + 0.04f * sin(t / 15f)), reach * 0.66f, 0.40f * level)
        bloom(tone(3), w * (0.80f + 0.05f * sin(t / 14f)), h * (0.82f + 0.05f * sin(t / 21f)), reach * 0.70f, 0.44f * level)
        // a quiet vignette, so the edges settle and the words keep their contrast
        drawRect(
            Brush.radialGradient(
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.55f),
                center = Offset(w / 2f, h / 2f),
                radius = reach * 0.75f,
            ),
        )
    }
}

/** The cover as a print on the wall: a soft glow of its own colour, a deep shadow, a slow breath. */
@Composable
private fun Print(slide: Slide, side: Dp, slideMs: Int, age: () -> Float) {
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        // the light the print is lit by, spilling past its edges — requiredSize, so it
        // overflows the print's box instead of pushing the words away from it
        Box(
            Modifier.requiredSize(side * 1.5f).background(
                Brush.radialGradient(
                    0f to slide.palette.accent.copy(alpha = 0.28f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Image(
            slide.cover,
            contentDescription = slide.item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(side)
                .graphicsLayer {
                    val s = 1f + 0.03f * (age() / (slideMs + FADE_MS)).coerceIn(0f, 1f)
                    scaleX = s
                    scaleY = s
                    shadowElevation = 36.dp.toPx()
                    shape = RoundedCornerShape(3.dp)
                    clip = true
                }
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(3.dp)),
        )
    }
}

// ── Full bleed: Moonfin's featured-media slide, for a record ─────────────

@Composable
private fun KenBurnsSlide(
    slide: Slide,
    slideMs: Int,
    age: () -> Float,
    text: TextMotion,
    extras: Extras,
    drift: () -> IntOffset,
) {
    // Which way this slide travels — fixed per album, varied between albums.
    val (dx, dy) = remember(slide) {
        val h = slide.item.itemId.hashCode()
        (if (h and 1 == 0) 1f else -1f) to (if (h and 2 == 0) 1f else -1f)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Image(
            slide.backdrop ?: slide.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = (age() / (slideMs + 2 * FADE_MS)).coerceIn(0f, 1f)
                val s = 1.06f + 0.10f * p
                scaleX = s
                scaleY = s
                translationX = dx * size.width * 0.025f * (2f * p - 1f)
                translationY = dy * size.height * 0.02f * (2f * p - 1f)
            },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.45f to Color.Black.copy(alpha = 0.10f),
                    1f to Color.Black.copy(alpha = 0.88f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.60f),
                    0.65f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = maxWidth * 0.07f, bottom = maxHeight * 0.10f)
                .width(if (landscape) maxWidth * 0.58f else maxWidth * 0.86f)
                .offset { drift() },
        ) {
            TextBlock(slide, text, extras, TextAlign.Start, titleMaxSp = if (landscape) 92f else 72f)
        }
    }
}

// ── The words ────────────────────────────────────────────────────────────

/**
 * Artist, title, year · genre — or the logos, when the library has them. Staggered in:
 * the accent rule, the artist, then the title settling into place, then the details.
 */
@Composable
private fun TextBlock(
    slide: Slide,
    text: TextMotion,
    extras: Extras,
    align: TextAlign,
    titleMaxSp: Float,
) {
    val horizontal = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    val ink = Inks(if (extras.albumText) slide.look.ink else null)
    val k = extras.textScale
    Column(horizontalAlignment = horizontal, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(44.dp, 2.dp)
                .graphicsLayer { alpha = text.alpha(500f) }
                .background(slide.look.ink),
        )
        Spacer(Modifier.height(22.dp))
        val artistMotion = Modifier.graphicsLayer {
            alpha = text.alpha(700f)
            translationY = text.rise(700f)
        }
        if (slide.artistLogo != null) {
            Image(
                slide.artistLogo,
                contentDescription = slide.item.subtitle,
                contentScale = ContentScale.Fit,
                alignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
                modifier = artistMotion.height(64.dp * k).widthIn(max = 340.dp * k),
            )
        } else if (!slide.item.subtitle.isNullOrBlank()) {
            Text(
                slide.item.subtitle!!.uppercase(),
                color = ink.artist,
                fontFamily = AppFont,
                fontWeight = FontWeight.Medium,
                fontSize = (15f * k).sp,
                letterSpacing = 0.32.em,
                textAlign = align,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = artistMotion,
            )
        }
        Spacer(Modifier.height(18.dp))
        val titleMotion = Modifier.graphicsLayer {
            alpha = text.alpha(1_050f)
            translationY = text.rise(1_050f) * 1.5f
            // the title settles from a hair wider, like type being set
            val settle = 1f + 0.025f * (1f - ramp(text.age(), 1_050f, 1_600f))
            scaleX = settle
            scaleY = settle
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                if (align == TextAlign.Center) 0.5f else 0f, 0.5f,
            )
        }
        if (slide.logo != null) {
            Image(
                slide.logo,
                contentDescription = slide.item.name,
                contentScale = ContentScale.Fit,
                alignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
                modifier = titleMotion.fillMaxWidth(0.9f).heightIn(max = 170.dp * k),
            )
        } else {
            FittedTitle(slide.item.name, slide.face, titleMaxSp * k, align, ink.title, titleMotion)
        }
        val line = detailsLine(slide.year, slide.genres)
        if (extras.details && line.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                line,
                color = ink.details,
                fontFamily = AppFont,
                fontSize = (14f * k).sp,
                letterSpacing = 0.22.em,
                textAlign = align,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = text.alpha(1_600f)
                    translationY = text.rise(1_600f)
                },
            )
        }
    }
}

/** "1973 · SOUL · FUNK" — the year and the first two genres; empty when there is neither. */
internal fun detailsLine(year: Int?, genres: List<String>): String =
    (listOfNotNull(year?.toString()) +
        genres.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(2))
        .joinToString("  ·  ") { it.uppercase() }

/** How a [Face] is set: the font, and the spacing that face wants at display size. */
private data class FaceSpec(
    val family: FontFamily,
    val weight: FontWeight = FontWeight.Normal,
    val style: FontStyle = FontStyle.Normal,
    val caps: Boolean = false,
    val tracking: Float = 0f,
    val lineHeight: Float = 1.06f,
    /** Relative size — a condensed face sets larger than a wide one for the same presence. */
    val scale: Float = 1f,
)

private fun specOf(face: Face): FaceSpec = when (face) {
    Face.CLASSICAL -> FaceSpec(DisplayFonts.CormorantGaramond, tracking = 0.01f, lineHeight = 1.0f, scale = 1.15f)
    Face.JAZZ -> FaceSpec(DisplayFonts.PoiretOne, tracking = 0.04f, lineHeight = 1.1f)
    Face.SOUL -> FaceSpec(DisplayFonts.DMSerifDisplay, tracking = -0.01f)
    Face.FOLK -> FaceSpec(DisplayFonts.DMSerifDisplay, style = FontStyle.Italic)
    Face.ROCK -> FaceSpec(DisplayFonts.BebasNeue, caps = true, tracking = 0.035f, lineHeight = 0.95f, scale = 1.3f)
    Face.HIPHOP -> FaceSpec(DisplayFonts.Anton, caps = true, tracking = 0.01f, lineHeight = 1.02f, scale = 1.08f)
    Face.ELECTRONIC -> FaceSpec(DisplayFonts.SpaceMono, weight = FontWeight.Bold, tracking = -0.03f, scale = 0.78f)
    Face.RETRO -> FaceSpec(DisplayFonts.AbrilFatface, scale = 0.95f)
    Face.POP -> FaceSpec(DisplayFonts.Italiana, tracking = 0.05f, lineHeight = 1.1f, scale = 1.05f)
    Face.PLAIN -> FaceSpec(AppFont, weight = FontWeight.Normal, scale = 0.9f)
}

/**
 * The title at the largest size that sets it in two lines without breaking a word —
 * shrinking step by step, and only at the floor allowing a third line.
 */
@Composable
private fun FittedTitle(title: String, face: Face, maxSp: Float, align: TextAlign, color: Color, modifier: Modifier) {
    val spec = specOf(face)
    val shown = if (spec.caps) title.uppercase() else title
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val style = remember(shown, face, maxSp, widthPx, density) {
            fun styleAt(sp: Float) = TextStyle(
                fontFamily = spec.family,
                fontWeight = spec.weight,
                fontStyle = spec.style,
                fontSize = sp.sp,
                letterSpacing = spec.tracking.em,
                lineHeight = (sp * spec.lineHeight).sp,
                textAlign = align,
                lineBreak = LineBreak.Heading,
            )
            val floor = maxSp * spec.scale * 0.38f
            var sp = maxSp * spec.scale
            while (sp > floor) {
                val st = styleAt(sp)
                val laid = measurer.measure(shown, st, constraints = Constraints(maxWidth = widthPx))
                val longestWord = shown.split(' ').maxByOrNull { it.length }.orEmpty()
                val wordFits = measurer.measure(longestWord, st).size.width <= widthPx
                if (laid.lineCount <= 2 && wordFits) break
                sp *= 0.92f
            }
            styleAt(sp.coerceAtLeast(floor))
        }
        Text(
            shown,
            style = style,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            // full width, so the alignment has a line to align within
            modifier = modifier.fillMaxWidth(),
        )
    }
}

// ── Clock and burn-in drift ─────────────────────────────────────────────

/**
 * The words' colours. White by default; with album-coloured text, every word is the
 * record's ink exactly as the palette extracted it — no tint toward white, no
 * transparency, both of which shift it off the sleeve's colour. Legibility is the
 * bleed's job (see [albumInk]); the hierarchy is carried by size and spacing.
 */
private class Inks(ink: Color?) {
    val title = ink ?: Color.White
    val artist = ink ?: Color.White.copy(alpha = 0.74f)
    val details = ink ?: Color.White.copy(alpha = 0.56f)
    val time = ink ?: Color.White.copy(alpha = 0.9f)
    val meta = ink ?: Color.White.copy(alpha = 0.6f)
}

/**
 * Time in the light Garamond, the date in spaced capitals; ticks on the minute. With
 * album-coloured text it takes the showing record's ink, easing across as slides change.
 */
@Composable
private fun Clock(accent: Color?, scale: Float, align: Alignment.Horizontal, modifier: Modifier) {
    val eased by animateColorAsState(accent ?: Color.White, tween(FADE_MS), label = "clockInk")
    val ink = Inks(if (accent != null) eased else null)
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50L)
        }
    }
    val twelveHour = remember {
        val pattern = java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            null, FormatStyle.SHORT, java.time.chrono.IsoChronology.INSTANCE, Locale.getDefault(),
        )
        pattern.contains('a')
    }
    val time = now.format(DateTimeFormatter.ofPattern(if (twelveHour) "h:mm" else "HH:mm"))
    val meridiem = if (twelveHour) now.format(DateTimeFormatter.ofPattern("a", Locale.getDefault())) else null
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())).uppercase()
    Column(horizontalAlignment = align, modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                time,
                color = ink.time,
                // Cormorant's default figures are old-style, where 1 reads as I and 4 and
                // 7 drop below the line: lining for a clock, tabular so it doesn't twitch.
                style = TextStyle(
                    fontFamily = DisplayFonts.CormorantGaramond,
                    fontSize = (72f * scale).sp,
                    lineHeight = (72f * scale).sp,
                    fontFeatureSettings = "lnum, tnum",
                ),
            )
            if (meridiem != null) {
                Text(
                    meridiem.uppercase(),
                    color = ink.meta,
                    fontFamily = AppFont,
                    fontSize = (13f * scale).sp,
                    letterSpacing = 0.2.em,
                    modifier = Modifier.padding(start = 8.dp, bottom = 14.dp * scale),
                )
            }
        }
        Text(
            date,
            color = ink.meta,
            fontFamily = AppFont,
            fontSize = (12f * scale).sp,
            letterSpacing = 0.28.em,
            modifier = Modifier.alpha(0.95f),
        )
    }
}

/**
 * A slow wander for everything drawn in fixed places — the panel never turns off, so
 * nothing may sit on the same pixels for hours. A Lissajous path of about ±14 × ±10 dp,
 * with periods of ten and seven minutes, stepped every few seconds: invisible as motion.
 */
@Composable
private fun rememberDrift(enabled: Boolean): () -> IntOffset {
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    LaunchedEffect(enabled, density) {
        if (!enabled) {
            offset = IntOffset.Zero
            return@LaunchedEffect
        }
        while (true) {
            val t = System.currentTimeMillis() / 1_000.0
            with(density) {
                offset = IntOffset(
                    (14.dp.toPx() * sin(2 * PI * t / 600.0)).roundToInt(),
                    (10.dp.toPx() * sin(2 * PI * t / 420.0)).roundToInt(),
                )
            }
            delay(4_000)
        }
    }
    return { offset }
}

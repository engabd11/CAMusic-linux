package com.engabd.sendpin.linux.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.engabd.sendpin.ui.design.a
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * The now-playing cover as a stage with two places on it: the record leaving and the
 * record arriving. A change of *album* plays the chosen transition between them; a skip
 * inside the album never reaches here as a change at all (NowArt holds its cover), so
 * the art sits perfectly still for the whole record.
 *
 * Same two-layer shape as the screensaver's slides: the leaving face stays composed
 * under the arriving one until the move is over (and a moment longer, in case the new
 * image is late), so there is never a frame of placeholder between two covers.
 */

/** Settings' `album_transition` keys. */
internal object ArtTransition {
    const val FLIP = "flip"
    const val DISSOLVE = "dissolve"
    const val SLIDE = "slide"
    const val OFF = "off"
    /** Not a setting: the quiet cross-fade for a first cover, or a cover changing inside one album. */
    const val FADE = "fade"

    fun durationMs(kind: String): Int = when (kind) {
        FLIP -> 1_050
        DISSOLVE -> 1_200
        SLIDE -> 900
        FADE -> 420
        else -> 0
    }

    /**
     * Which motion a change from [from] to [to] plays: the chosen [setting] only when the
     * album itself changed and there was a real cover before it.
     */
    fun kindFor(from: NowArt, to: NowArt, setting: String): String = when {
        from.key == null && from.url == null -> FADE
        from.key == to.key -> FADE
        else -> setting
    }
}

/** How long the leaving face stays underneath once the move is done. */
private const val UNDERLAY_MS = 450L

private data class Slot(val art: NowArt, val serial: Long)

/**
 * The cover: [art] in [shape], lifted on a [glow] of the album's own colour, changing
 * albums with [transition]. [rest] (0..1, read while drawing) settles it back and dims
 * it while paused. Otherwise the cover holds perfectly still — the living colours move
 * the room behind it (LiquidBleed.kt), never the picture.
 */
@Composable
fun AlbumArtStage(
    art: NowArt,
    modifier: Modifier,
    transition: String,
    glow: Color,
    shape: Shape = RoundedCornerShape(28.dp),
    glowElevation: Dp = 48.dp,
    rest: () -> Float = { 0f },
    contentDescription: String? = null,
) {
    var current by remember { mutableStateOf<Slot?>(null) }
    var leaving by remember { mutableStateOf<Slot?>(null) }
    var kind by remember { mutableStateOf(ArtTransition.FADE) }
    val progress = remember { Animatable(1f) }
    val glowColor by animateColorAsState(glow, tween(1_200), label = "stageGlow")

    LaunchedEffect(art) {
        val cur = current
        if (cur == null) {
            current = Slot(art, 0L)
            return@LaunchedEffect
        }
        if (cur.art == art) return@LaunchedEffect
        kind = ArtTransition.kindFor(cur.art, art, transition)
        leaving = cur
        current = Slot(art, cur.serial + 1)
        val ms = ArtTransition.durationMs(kind)
        progress.snapTo(0f)
        if (ms > 0) progress.animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) else progress.snapTo(1f)
        delay(UNDERLAY_MS)
        leaving = null
    }

    Box(modifier) {
        leaving?.let { face ->
            key(face.serial) {
                StageFace(
                    face.art, shape, contentDescription,
                    Modifier.fillMaxSize().graphicsLayer {
                        shapeAndGlow(shape, glowColor, glowElevation)
                        leavingMotion(kind, progress.value)
                    },
                    sheen = { faceSheen(kind, progress.value, entering = false) },
                )
            }
        }
        current?.let { face ->
            key(face.serial) {
                StageFace(
                    face.art, shape, contentDescription,
                    Modifier.fillMaxSize().graphicsLayer {
                        shapeAndGlow(shape, glowColor, glowElevation)
                        val p = progress.value
                        enteringMotion(kind, p)
                        val r = rest().coerceIn(0f, 1f)
                        if (r > 0f) {
                            scaleX *= 1f - 0.03f * r
                            scaleY *= 1f - 0.03f * r
                            shadowElevation *= 1f - 0.45f * r
                        }
                    },
                    sheen = { faceSheen(kind, progress.value, entering = true) },
                    rest = rest,
                )
            }
        }
    }
}

private fun GraphicsLayerScope.shapeAndGlow(shape: Shape, glow: Color, elevation: Dp) {
    this.shape = shape
    clip = true
    shadowElevation = elevation.toPx()
    ambientShadowColor = glow
    spotShadowColor = glow
    cameraDistance = 16f * density
}

/** The face turning away (flip), sinking back (dissolve) or leaving left (slide). */
private fun GraphicsLayerScope.leavingMotion(kind: String, p: Float) {
    when (kind) {
        ArtTransition.FLIP -> {
            val angle = 180f * p
            rotationY = angle
            alpha = if (angle < 90f) 1f else 0f
            val dip = 1f - 0.06f * sin(PI.toFloat() * p)
            scaleX = dip
            scaleY = dip
            shadowElevation *= 1f - 0.6f * sin(PI.toFloat() * p)
        }
        ArtTransition.DISSOLVE -> {
            alpha = 1f - p
            scaleX = 1f - 0.08f * p
            scaleY = 1f - 0.08f * p
            val r = 24.dp.toPx() * p
            renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Decal) else null
        }
        ArtTransition.SLIDE -> {
            translationX = -size.width * 1.15f * p
            rotationZ = -5f * p
            alpha = 1f - 0.5f * p
        }
        // fade and off: the old face simply waits underneath the new one
    }
}

/** The face turning in (flip), rising out of a blur (dissolve) or arriving from the right (slide). */
private fun GraphicsLayerScope.enteringMotion(kind: String, p: Float) {
    when (kind) {
        ArtTransition.FLIP -> {
            val angle = 180f * p
            rotationY = angle - 180f
            alpha = if (angle >= 90f) 1f else 0f
            val dip = 1f - 0.06f * sin(PI.toFloat() * p)
            scaleX = dip
            scaleY = dip
            shadowElevation *= 1f - 0.6f * sin(PI.toFloat() * p)
        }
        ArtTransition.DISSOLVE -> {
            alpha = p
            scaleX = 1.04f - 0.04f * p
            scaleY = 1.04f - 0.04f * p
            val r = 16.dp.toPx() * (1f - p)
            renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Decal) else null
        }
        ArtTransition.SLIDE -> {
            translationX = size.width * 1.15f * (1f - p)
            rotationZ = 5f * (1f - p)
        }
        ArtTransition.FADE -> alpha = p
    }
}

/**
 * What the light does to a face mid-flip: the edge turned away darkens, and a soft
 * specular band crosses the face as it turns — the difference between a picture
 * rotating and a sleeve being turned over in the hand. Zero for the other moves.
 */
private class Sheen(val shade: Float, val band: Float, val bandX: Float)

private fun faceSheen(kind: String, p: Float, entering: Boolean): Sheen? {
    if (kind != ArtTransition.FLIP || p >= 1f) return null
    val angle = 180f * p
    val own = if (entering) angle - 180f else angle
    val turned = abs(sin(own * PI.toFloat() / 180f))
    return Sheen(shade = 0.55f * turned, band = 0.22f * sin(PI.toFloat() * p), bandX = p)
}

@Composable
private fun StageFace(
    art: NowArt,
    shape: Shape,
    contentDescription: String?,
    modifier: Modifier,
    sheen: () -> Sheen?,
    rest: () -> Float = { 0f },
) {
    var failed by remember(art.url) { mutableStateOf(false) }
    val url = art.url
    Box(
        modifier.drawWithContent {
            drawContent()
            // paused: the record rests a shade darker
            val r = rest().coerceIn(0f, 1f)
            if (r > 0f) drawRect(Color.Black.a(0.22f * r))
            val s = sheen() ?: return@drawWithContent
            if (s.shade > 0.001f) drawRect(Color.Black.a(s.shade))
            if (s.band > 0.001f) {
                val cx = size.width * (1.3f - 1.6f * s.bandX)
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.a(s.band),
                        1f to Color.Transparent,
                        start = Offset(cx - size.width * 0.35f, 0f),
                        end = Offset(cx + size.width * 0.35f, size.height),
                    ),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // A placeholder only where there is truly no cover. While a cover is loading the
        // face stays clear, so the leaving face underneath shows through instead of a flash.
        if (url == null || failed) {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(placeholderColors(art.key ?: ""))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White.a(0.35f), modifier = Modifier.fillMaxSize(0.36f))
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                onSuccess = { failed = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

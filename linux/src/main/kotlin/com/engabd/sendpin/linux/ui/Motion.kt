@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.engabd.sendpin.linux.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.delay
import okio.Path.Companion.toOkioPath
import java.io.File

/*
 * The panel's motion language in one place: the curves and durations every page shares,
 * the cover that grows from a grid into its album page, the give of a tile under a finger,
 * and the way a page's tiles rise into place when it opens. Everything here animates
 * through graphics layers (scale, alpha, translation), never through layout, so a moving
 * page costs the i3 in the station a redraw and not a re-measure.
 */
object Motion {
    /** Material's emphasised decelerate: a quick start and a long, soft landing. */
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** For things that leave: an accelerating exit. */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** A standard ease for colour and fades. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val PAGE_MS = 420
    const val FADE_MS = 220
    const val SHARED_MS = 520

    /** How long after the one before it each tile on a freshly opened page starts to rise. */
    const val STAGGER_MS = 26L
    /** Only the first screenful rises; anything below it is simply there when scrolled to. */
    const val STAGGER_LIMIT = 24

    val Press = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
}

// ─── The cover that grows into its page ─────────────────────────────────────

/** The route transition's two scopes, provided by the library's `SharedTransitionLayout`. */
val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalRouteVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Which tile was tapped. The same album can sit on two shelves of one page (recently
 * played and recently added), and a shared element must be one of a kind, so each tile's
 * key carries its shelf ([LocalCoverScope]) and the album page borrows the tapped one's.
 */
@Stable
class SharedCovers {
    var lastTapped by mutableStateOf<String?>(null)

    /** The key the album page for [id] should use: the tapped tile's, else one of its own. */
    fun keyFor(id: String): String = lastTapped?.takeIf { it.endsWith("/$id") } ?: "page/$id"
}

val LocalSharedCovers = staticCompositionLocalOf<SharedCovers?> { null }

/** The shelf or grid a tile belongs to, so two copies of one album have different keys. */
val LocalCoverScope = compositionLocalOf { "grid" }

private val CoverBounds = BoundsTransform { _, _ -> tween(Motion.SHARED_MS, easing = Motion.Emphasized) }

/**
 * This cover's part in the grow-into-page move, under [key] — nothing at all when the
 * library is not inside a shared transition (Settings → page motion off, the previews).
 */
@Composable
fun Modifier.sharedCover(key: String?): Modifier {
    val shared = LocalSharedTransition.current
    val visibility = LocalRouteVisibility.current
    if (shared == null || visibility == null || key == null) return this
    return with(shared) {
        this@sharedCover.sharedElement(
            rememberSharedContentState("cover:$key"),
            visibility,
            boundsTransform = CoverBounds,
        )
    }
}

// ─── Under the finger ───────────────────────────────────────────────────────

/**
 * The give of a tile under a finger: it sinks a little while pressed and springs back.
 * Watches the pointer on the Initial pass and consumes nothing, so the tile's own
 * `clickable` and the list's drag-scroll work exactly as before.
 */
@Composable
fun Modifier.pressScale(pressed: Float = 0.95f): Modifier {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) pressed else 1f, Motion.Press, label = "press")
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var origin: androidx.compose.ui.geometry.Offset? = null
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull() ?: continue
                    when {
                        c.pressed && !c.previousPressed -> { origin = c.position; down = true }
                        !c.pressed -> { origin = null; down = false }
                        // A drag that has started scrolling is not a press: let go of the sink.
                        origin != null && (c.position - origin).getDistance() > 14f -> down = false
                    }
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

// ─── A page's tiles rising into place ───────────────────────────────────────

/**
 * Rise and fade in, [index] steps after the page opened. Tiles past [Motion.STAGGER_LIMIT]
 * (and every tile once the page has been seen, coming back to it) skip straight to rest.
 */
@Composable
fun Modifier.riseIn(index: Int, enabled: Boolean = true): Modifier {
    if (!enabled || index >= Motion.STAGGER_LIMIT) return this
    val p = remember { Animatable(0f) }
    val rise = with(LocalDensity.current) { 22.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(index * Motion.STAGGER_MS)
        p.animateTo(1f, tween(Motion.PAGE_MS, easing = Motion.Emphasized))
    }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * rise
    }
}

// ─── Images ─────────────────────────────────────────────────────────────────

/**
 * The image loader the whole panel shares: a memory cache a quarter of the heap (covers
 * are the panel's whole look, and the station has the RAM), a disk cache so a reboot does
 * not fetch the library's art again, and a short crossfade so a cover arrives rather than
 * appears.
 */
fun panelImageLoader(context: PlatformContext, mpd: () -> com.engabd.sendpin.mpd.MpdClient? = { null }): ImageLoader =
    ImageLoader.Builder(context)
        // Covers MPD serves down its socket (a queue the panel did not build itself).
        .components { add(com.engabd.sendpin.mpd.MpdArtFetcher.Factory(mpd)) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(File(System.getProperty("user.home"), ".cache/camusic/art").toOkioPath())
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .crossfade(Motion.FADE_MS)
        .build()

/**
 * [url] asking the server for a cover [px] wide rather than the library's 1000 px default —
 * a grid tile is 175 dp, and decoding a thousand-pixel JPEG for it is most of what made a
 * long album grid stutter on the station. Only Jellyfin's `maxWidth` is rewritten.
 */
fun sizedArt(url: String?, px: Int): String? {
    if (url == null) return null
    val m = MAX_WIDTH.find(url) ?: return url
    return url.replaceRange(m.range, "maxWidth=$px")
}

private val MAX_WIDTH = Regex("""maxWidth=\d+""")

/** Grid and shelf tiles. */
const val TILE_PX = 420

// ─── Tabs ───────────────────────────────────────────────────────────────────

/**
 * The switch between the panel's tabs: the old pane sinks back and fades as the new one
 * comes forward. A touch more than a cross-fade, so a tab change reads as going somewhere.
 */
@Composable
fun <T> TabSwitch(tab: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    androidx.compose.animation.AnimatedContent(
        targetState = tab,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(Motion.FADE_MS + 60, delayMillis = 40, easing = Motion.Standard)) +
                androidx.compose.animation.scaleIn(tween(Motion.PAGE_MS, easing = Motion.Emphasized), initialScale = 0.985f))
                .togetherWith(
                    androidx.compose.animation.fadeOut(tween(Motion.FADE_MS - 60)) +
                        androidx.compose.animation.scaleOut(tween(Motion.PAGE_MS), targetScale = 1.01f),
                )
        },
        label = "tab",
        modifier = modifier,
    ) { content(it) }
}

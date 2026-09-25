package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The desktop fork of :app's Backdrop.kt. The machinery — [backdropSource],
 * [glassSurface], the recursion trap, the one-blur-for-all-screens economics —
 * is verbatim; the fork exists because :app's [ProvideBackdrop] reads
 * `AppSettings(context)` for the blur flag, which is Android-deep (DataStore +
 * Context). The fork takes the flag as a parameter instead; Main.kt feeds it
 * from [com.engabd.sendpin.linux.LinuxSettings.glassBlur].
 *
 * Skiko carries GraphicsLayer, BlurEffect and RenderEffect on the JVM, so the
 * recording-and-sampling scheme works as designed on the desktop. Whether GL
 * acceleration survives this NUC's software renderer is a live question the
 * panel will answer; glassSurface's flat-fill fallback covers the worst case.
 */
class BackdropState internal constructor(internal val layer: GraphicsLayer) {
    /** Whether panels blur what they sample. */
    internal var blur: Boolean = false
    /** Where the recorded region sits in root coordinates, so consumers can align to it. */
    internal var originInRoot by mutableStateOf(Offset.Zero)

    /** False until a frame has actually been recorded — drawing the layer before that is empty. */
    internal var recorded by mutableStateOf(false)
}

/** The backdrop a [glassSurface] samples, or null where there is nothing behind it. */
val LocalBackdrop = compositionLocalOf<BackdropState?> { null }

/**
 * Creates a backdrop and provides it to [content], with [blur] as the user's
 * glass-blur call. Deliberately not a layout — it wraps nothing and introduces
 * no `Box`, so it cannot change how the caller's tree measures. Apply
 * [backdropSource] inside [content] to whatever should be blurred.
 */
@Composable
fun ProvideBackdrop(blur: Boolean = true, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val state = remember(layer) { BackdropState(layer) }
    state.blur = blur
    CompositionLocalProvider(LocalBackdrop provides state, content = content)
}

/**
 * Record this composable's drawn output as the backdrop for any [glassSurface] below it.
 *
 * The content is still drawn normally — recording captures, it does not redirect — so the
 * backdrop stays visible in its own right.
 */
fun Modifier.backdropSource(): Modifier = composed {
    val state = LocalBackdrop.current ?: return@composed this
    // Leaving composition has to clear the flag, or a screen with no backdrop of its own
    // goes on sampling the last one recorded — the previous screen's album art, frozen,
    // showing through this screen's panels.
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { state.recorded = false }
    }
    this
        .onGloballyPositioned { state.originInRoot = it.positionInRoot() }
        .drawWithContent {
            state.layer.record(IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
                this@drawWithContent.drawContent()
            }
            state.recorded = true
            drawLayer(state.layer)
        }
}

/**
 * Paint this surface as glass: the backdrop behind it, blurred, under a tint and hairline.
 * Verbatim from :app — see the original for the tint-over-blur rationale.
 */
fun Modifier.glassSurface(
    shape: Shape,
    tint: Color,
    blurRadius: Dp = 24.dp,
    border: Color? = null,
): Modifier = composed {
    val state = LocalBackdrop.current
    val bordered = { m: Modifier -> if (border != null) m.border(1.dp, border, shape) else m }

    // Nothing behind worth sampling — see the class doc on why this is not a downgrade.
    if (state == null) return@composed bordered(this.clip(shape).background(tint))

    val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
    state.layer.renderEffect =
        if (state.blur) BlurEffect(radiusPx, radiusPx, TileMode.Clamp) else null
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }

    bordered(
        this
            .clip(shape)
            .onGloballyPositioned { positionInRoot = it.positionInRoot() }
            .drawWithContent {
                if (state.recorded) {
                    val delta = state.originInRoot - positionInRoot
                    translate(delta.x, delta.y) { drawLayer(state.layer) }
                }
                drawRect(tint)
                drawContent()
            }
    )
}
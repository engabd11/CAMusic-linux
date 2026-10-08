package com.engabd.sendpin.ui.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.engabd.sendpin.ui.theme.Ink

/** [Ink] with another alpha — the scrim arithmetic reads better with it. */
fun Color.a(alpha: Float): Color = copy(alpha = alpha)

/**
 * The full-bleed album wash behind the player pane — the desktop fork of :app's
 * [MeltBackdrop]. The cover is blown up, blurred past recognition and
 * over-saturated, then drowned in a scrim that reaches pure Ink before the
 * bottom. Sampled small (192 px) on purpose: it is never seen in focus.
 *
 * Recorded as the backdrop for every glass surface on the screen — see
 * Backdrop.kt. One difference from :app: Coil 3 on the desktop takes the URL
 * directly (no Android Context), so the request is the raw url; the held-url
 * settling of [rememberSettledArt] belongs to the phone's shared-element
 * flights and is not reproduced here — the panel has one screen.
 */
@Composable
fun BoxScope.MeltBackdrop(url: String?, intensity: Float = 1f, scrim: Float = 0f) {
    val s = scrim.coerceIn(0f, 1f)
    // The wash takes Settings' colour mood, as the palette does (ColourMood.kt).
    val mood = LocalColourMood.current
    val lead = LocalPalette.current.accent
    val filter = remember(mood, lead) { washFilter(mood, 2.7f, lead) }
    Box(Modifier.matchParentSize().backdropSource()) {
        // A new album's wash melts in over the old one rather than reloading from black.
        // The now-playing URL is held per album (linux/ui/NowArt.kt), so this only ever
        // runs on an album change — a skip inside the record leaves the wash untouched.
        Crossfade(url, Modifier.matchParentSize(), tween(1_200), label = "meltWash") { u ->
            Box(Modifier.fillMaxSize()) {
                if (u != null) {
                    AsyncImage(
                        model = u,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = filter,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.3f
                                scaleY = 1.3f
                            }
                            .blur(64.dp, BlurredEdgeTreatment.Unbounded)
                            .alpha(0.62f * (1f - 0.42f * s) * intensity.coerceIn(0f, 1f)),
                    )
                }
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Ink.a(0.28f + 0.62f * s),
                    0.46f to Ink.a(0.66f + 0.30f * s),
                    0.88f to Ink,
                    1f to Ink,
                )
            )
        )
    }
}
package com.engabd.sendpin.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.engabd.sendpin.ui.design.MeltBackdrop
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary

/**
 * The player pane: cover, title block, seek bar, transport row. Rebuilt against
 * the shared theme tokens (the originals in NowPlayingParts.kt are bound to
 * AppSettings(context) and NowPlayingViewModel, which the desktop does not have).
 */
@Composable
fun PlayerPane(modifier: Modifier, model: PanelModel) {
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val scope = rememberCoroutineScope()

    val index = state?.index?.takeIf { it >= 0 } ?: 0
    val track = queue.getOrNull(index)
    val posMs = state?.positionMs ?: 0L
    val durMs = state?.durationMs ?: track?.durationMs ?: 0L
    val playing = state?.playing ?: false
    var scrub by remember { mutableStateOf<Float?>(null) }

    Box(modifier) {
        // The album wash — recorded as the backdrop so the glass surfaces below
        // sample it; see the forked Backdrop.kt.
        MeltBackdrop(url = queue.getOrNull(index)?.artUrl)
    // `modifier` carries the `weight()` PanelShell handed down — a RowScope/ColumnScope
    // parent-data modifier that only means something on the direct child of that Row/
    // Column, which is this outer Box. Applying it again here silently did nothing
    // useful while doubling up `fillMaxHeight()`; the Column only needs to fill what
    // the Box already claimed.
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cover — real glass now: sampled backdrop under the tint, hairline edge.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .glassSurface(RoundedCornerShape(28.dp), Glass, border = Hairline),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            val art = queue.getOrNull(index)?.artUrl
            if (art != null) {
                coil3.compose.AsyncImage(
                    model = art,
                    contentDescription = null,
                    // Without a ContentScale, a tall cover inside this weighted Box could
                    // exceed its bounds and get clipped by the parent instead of scaled to
                    // fit — square crop matches every other cover in the app (AlbumTile,
                    // AlbumDetailView).
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                Text(
                    "♪",
                    color = TextMuted,
                    fontSize = 48.sp,
                    fontFamily = AppFont,
                )
            }
        }

        // Title block
        Text(
            queue.getOrNull(index)?.title ?: "Nothing playing",
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            queue.getOrNull(index)?.artist ?: "Queue an album from the library",
            color = TextSecondary,
            fontFamily = AppFont,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Seek bar — scrub locally, commit on release (the poll reconciles it).
        Slider(
            value = scrub ?: if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { f -> scope.launch { model.seekTo((f * durMs).toLong()) } }
                scrub = null
            },
            enabled = durMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(posMs), color = TextMuted, fontSize = 11.sp, fontFamily = AppFont)
            Text(formatMs(durMs), color = TextMuted, fontSize = 11.sp, fontFamily = AppFont)
        }

        // Transport row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(Icons.Default.SkipPrevious, "Previous") { scope.launch { model.previous() } }
            TransportButton(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                "Play/Pause",
                big = true,
            ) { scope.launch { model.togglePause() } }
            TransportButton(Icons.Default.SkipNext, "Next") { scope.launch { model.next() } }
        }

        // The live format readout — MPD is the decoder; show what it is decoding.
        val out = state?.outputFormat
        if (out != null && state?.playing == true) {
            Text(
                "MPD → ${state?.outputDeviceName ?: ""} · ${fmtRate(out.sampleRateHz)}/${out.bitDepth}",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = AppFont,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    }
}

/** A transport target at least 48dp — a panel is touched, not clicked. */
@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .size(if (big) 64.dp else 48.dp)
            .glassSurface(RoundedCornerShape(100.dp), Glass, border = Hairline)
            .clickable { onClick() },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(icon, label, tint = TextPrimary, modifier = Modifier.size(if (big) 30.dp else 24.dp))
    }
}

internal fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun fmtRate(hz: Int): String =
    if (hz == 0) "—" else "${hz / 1000.0}".let { if (it.length > 5) it.take(5) else it } + "k"
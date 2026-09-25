package com.engabd.sendpin.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.inkOn

/**
 * The desktop twin of :app's `QualityPill` (SendspinDesign.kt) — the quality
 * badge: what is actually coming out of the pipe. Sample rate and bit depth are
 * set in mono so the digits line up as the track changes, and a hi-res stream
 * lights the pill up rather than adding another chip. Geometry and colour rules
 * verbatim from the original; the `compact` variant is not ported because the
 * panel shows the standalone badge only.
 *
 * :app's tap-through detail overlay ([QualityDetailOverlay]) is not ported —
 * the pill here is a badge, not a button; the panel has no detail sheet to
 * open, and a chip that can do nothing must not pretend otherwise.
 */
@Composable
fun QualityPill(
    text: String,
    modifier: Modifier = Modifier,
    hiRes: Boolean = false,
    lossless: Boolean = true,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(11.dp)
    val tint = if (lossless) accent else TextMuted
    Row(
        modifier
            .then(
                if (hiRes) Modifier.shadow(14.dp, shape, ambientColor = accent, spotColor = accent)
                else Modifier
            )
            .clip(shape)
            .background(if (lossless) tint.a(if (hiRes) 0.16f else 0.12f) else Glass)
            .border(1.dp, tint.a(if (hiRes) 0.55f else 0.35f), shape)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .then(
                    if (hiRes) Modifier.shadow(8.dp, CircleShape, ambientColor = tint, spotColor = tint)
                    else Modifier
                )
                .clip(CircleShape)
                .background(tint)
        )
        Text(
            text,
            color = if (lossless) inkOn(0.92f) else TextMuted,
            style = TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp),
            maxLines = 1,
            // The label grew a live bitrate on the MPD path, where it moves from
            // second to second. Clipping the tail of a long one is the right
            // failure here; pushing the transport apart is not.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The badge's reading for an MPD queue entry, assembled from the two honest
 * halves — the same split [StreamQuality.live] documents:
 *
 *  - **tagged** — the codec, named by the file. A Jellyfin-queued entry carries
 *    its tags in [LocalTrack.sourceQuality]; an MPD-originated entry (another
 *    client's `mpc add`, a queue MPD already had running) carries none, so the
 *    file's own extension names the codec. Nothing else is invented: numbers
 *    come from MPD's live reading or not at all.
 *  - **live** — [com.engabd.sendpin.mpd.MpdRemote]'s polled audio format: the
 *    rate, depth, channel count and decode bitrate MPD reports each second.
 */
fun mpdBadgeQuality(track: LocalTrack?, outputFormat: com.engabd.sendpin.audio.RemoteAudioFormat?): StreamQuality? {
    if (track == null) return null
    val tagged = track.sourceQuality?.takeIf { it.codec.isNotBlank() }
        ?: track.scrobbleId?.substringAfterLast('.', "")?.lowercase()?.let { ext ->
            val codec = when (ext) {
                "flac" -> "FLAC"
                "mp3" -> "MP3"
                "wav" -> "WAV"
                "aiff", "aif" -> "AIFF"
                "dsf" -> "DSF"
                "dff" -> "DFF"
                "ape" -> "APE"
                "wv" -> "Wavpack"
                "ogg" -> "OGG"
                "opus" -> "OPUS"
                "m4a", "mp4", "aac" -> "AAC"
                else -> null
            } ?: return@let null
            StreamQuality(codec = codec)
        }
    return StreamQuality.live(tagged, outputFormat)
}

/** The badge pill for what [state] reports, or null when there is no track to badge. */
@Composable
fun MpdQualityBadge(track: LocalTrack?, outputFormat: com.engabd.sendpin.audio.RemoteAudioFormat?) {
    val q = mpdBadgeQuality(track, outputFormat) ?: return
    QualityPill(text = q.label, hiRes = q.hiRes, lossless = q.lossless)
}
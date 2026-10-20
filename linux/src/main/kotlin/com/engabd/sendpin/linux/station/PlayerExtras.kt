package com.engabd.sendpin.linux.station

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.screensaver.faceFor
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.inkOn

/*
 * Two chips beside the player's loudness chip: the radio, while one is keeping the queue
 * going (tap to stop it), and the lyrics, which turn the cover into the song's words.
 */

/** "RADIO · from Kind of Blue": shown only while a radio runs, its dot breathing. A tap stops it. */
@Composable
internal fun RadioChip(model: PanelModel) {
    val radio by model.radio.collectAsState()
    val accent = rememberAccent()
    AnimatedVisibility(
        visible = radio != null,
        enter = fadeIn(tween(260)) + expandHorizontally(tween(320)),
        exit = fadeOut(tween(180)) + shrinkHorizontally(tween(260)),
    ) {
        val r = radio ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(11.dp)
        val breath by rememberInfiniteTransition(label = "radio").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "radioDot",
        )
        Row(
            Modifier
                .clip(shape)
                .background(accent.a(0.12f))
                .border(1.dp, accent.a(0.35f), shape)
                .clickable { model.stopRadio() }
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .widthIn(max = 280.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).graphicsLayer { alpha = breath }.clip(RoundedCornerShape(50)).background(accent))
            Icon(Icons.Default.Radio, "Radio", tint = accent, modifier = Modifier.size(18.dp))
            Text(
                "Radio · ${r.seedName}",
                color = inkOn(0.92f),
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.Close, "Stop radio", tint = TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** The lyrics toggle: lit while the cover is showing the words. */
@Composable
internal fun LyricsChip(open: Boolean, onToggle: () -> Unit) {
    val accent = rememberAccent()
    val shape = RoundedCornerShape(11.dp)
    val tint by animateColorAsState(if (open) accent else TextMuted, tween(220), label = "lyricsTint")
    Row(
        Modifier
            .clip(shape)
            .background(if (open) tint.a(0.12f) else inkOn(0.06f))
            .border(1.dp, tint.a(if (open) 0.35f else 0.25f), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Lyrics, "Lyrics", tint = tint, modifier = Modifier.size(18.dp))
        Text(
            "Lyrics",
            color = if (open) inkOn(0.92f) else TextMuted,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * The song's words where the cover was: the karaoke sheet on dark glass, set in the
 * album's own lettering and filled in its colour. A tap closes it.
 */
@Composable
internal fun PlayerLyrics(model: PanelModel, track: LocalTrack?, accent: Color, karaoke: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val title = track?.title ?: ""
    val face = faceFor(listOfNotNull(track?.genre), null, title, track?.album ?: track?.id ?: title)
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
            .clickable(onClick = onClose)
            .padding(horizontal = 28.dp),
    ) {
        LyricSheet(model, track, face, Modifier.fillMaxSize(), TextAlign.Start, sizeSp = 34f, accent = accent, karaoke = karaoke) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("♪", color = Color.White.copy(alpha = 0.25f), fontSize = 64.sp, fontFamily = AppFont)
                Spacer(Modifier.height(8.dp))
                Text(
                    "no lyrics for this track",
                    color = Color.White.copy(alpha = 0.4f),
                    fontFamily = MonoFont,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * The Queue page's word on the radio: a strip under the header, while one runs, saying what
 * it started from and that it keeps the music going, with Stop. It opens and closes rather
 * than appearing.
 */
@Composable
internal fun RadioBanner(model: PanelModel, modifier: Modifier = Modifier) {
    val radio by model.radio.collectAsState()
    val accent = rememberAccent()
    AnimatedVisibility(
        visible = radio != null,
        enter = fadeIn(tween(320)) + androidx.compose.animation.expandVertically(tween(380)),
        exit = fadeOut(tween(200)) + androidx.compose.animation.shrinkVertically(tween(300)),
        modifier = modifier,
    ) {
        val r = radio ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(18.dp)
        val breath by rememberInfiniteTransition(label = "radioBanner").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "radioBannerDot",
        )
        Row(
            Modifier
                .clip(shape)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(accent.a(0.20f), accent.a(0.06f))))
                .border(1.dp, accent.a(0.30f), shape)
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(9.dp).graphicsLayer { alpha = breath }.clip(RoundedCornerShape(50)).background(accent))
            Icon(Icons.Default.Radio, null, tint = accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Radio from ${r.seedName}", color = inkOn(0.95f), fontFamily = AppFont, fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("More like it is added as the queue runs down", color = TextMuted, fontFamily = AppFont, fontSize = 13.sp, maxLines = 1)
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { model.stopRadio() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                Text("Stop", color = TextMuted, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

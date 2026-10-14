package com.engabd.sendpin.linux.station

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.ui.theme.AppFont

/*
 * When MPD or Jellyfin cannot be reached, a calm banner across the top says which, and has
 * a Retry — in place of a small line of status text nobody reads from across the room.
 * Settings → Status banner.
 */

/** What the banner says. */
internal data class Banner(val title: String, val detail: String)

/**
 * The banner for the connection's health, or null when everything answers. MPD is the one
 * that plays, so it leads when both are down.
 */
internal fun bannerMessage(mpdUp: Boolean, mpdAddress: String, jellyfinProblem: String?): Banner? = when {
    !mpdUp && jellyfinProblem != null -> Banner("Can't reach MPD or Jellyfin", "Check the network and that both are running.")
    !mpdUp -> Banner("Can't reach MPD", mpdAddress.ifBlank { "MPD" } + " is not answering — nothing can play until it does.")
    jellyfinProblem != null -> Banner("Can't reach Jellyfin", "The library is unavailable; what is playing carries on.  " + jellyfinProblem.take(80))
    else -> null
}

@Composable
internal fun StatusBanner(model: PanelModel, modifier: Modifier = Modifier) {
    val enabled by model.settings.statusBanner.collectAsState(initial = false)
    val mpdUp by model.mpdUp.collectAsState()
    val problem by model.jellyfinProblem.collectAsState()
    val address by model.mpdAddress.collectAsState()
    val banner = if (enabled) bannerMessage(mpdUp, address, problem) else null
    // Held while it slides away, so the words do not vanish before the strip does.
    val last = remember { arrayOfNulls<Banner>(1) }
    if (banner != null) last[0] = banner
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter = slideInVertically(tween(300)) { -it } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(260)) { -it } + fadeOut(tween(260)),
    ) {
        val shown = banner ?: last[0] ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .background(Color(0xF2271A12), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFFFFB84D).copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFFB84D), modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(shown.title, color = Color.White, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(
                    shown.detail, color = Color.White.copy(alpha = 0.72f), fontFamily = AppFont, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            PillButton("Retry", filled = false, onClick = { model.reconnect() })
        }
    }
}

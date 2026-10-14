package com.engabd.sendpin.linux.station

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.inkOn

/**
 * The sleep timer's chip, beside the loudness chip: one tap steps Off → End of album →
 * 30 → 60 → 90 min → Off. Running, it counts down in place. Shown only with Settings'
 * Sleep timer on.
 */
@Composable
internal fun SleepChip(model: PanelModel) {
    val timer by model.sleep.collectAsState()
    // The poll's once-a-second reading is what keeps a countdown honest on screen.
    @Suppress("UNUSED_VARIABLE") val tick by model.state.collectAsState()
    val accent = rememberAccent()
    val on = timer != null
    val shape = RoundedCornerShape(11.dp)
    val tint = if (on) accent else TextMuted
    Row(
        Modifier
            .clip(shape)
            .background(if (on) tint.a(0.12f) else inkOn(0.06f))
            .border(1.dp, tint.a(if (on) 0.35f else 0.25f), shape)
            .clickable { model.setSleep((timer?.choice ?: SleepChoice.OFF).next()) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Bedtime, "Sleep timer", tint = tint, modifier = Modifier.size(18.dp))
        Text(
            sleepLabel(timer, System.currentTimeMillis()),
            color = if (on) inkOn(0.92f) else TextMuted,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

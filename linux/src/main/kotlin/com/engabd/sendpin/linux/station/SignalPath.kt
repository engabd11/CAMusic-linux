package com.engabd.sendpin.linux.station

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.ui.design.mpdBadgeQuality
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont

/*
 * The signal path as the front of an amplifier: the stages MPD reports lit one after
 * another, an amber lamp on any that alters the audio, and BIT-PERFECT when none does.
 * What it knows is what MPD and the library say — it cannot see inside an ALSA plugin or a
 * DAC driver, and Settings says so.
 */

private val Amber = Color(0xFFFFB84D)

/** The chain for the playing track, read from [model]; nothing is drawn with nothing playing. */
@Composable
internal fun SignalPath(model: PanelModel, modifier: Modifier = Modifier, lightText: Boolean = true, accent: Color? = null) {
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val gain by model.replayGain.collectAsState()
    val track = queue.getOrNull(state?.index?.takeIf { it >= 0 } ?: 0) ?: return
    val s = state ?: return
    if (s.stopped) return
    // The source is the file as the library tags it; the decode stage is MPD's live reading.
    val source = mpdBadgeQuality(track, null)
    val chain = signalChain(source, s.outputFormat, s.outputDeviceName, gain, s.volume)
    SignalPathRow(chain, modifier, lightText, accent ?: rememberAccent())
}

@Composable
internal fun SignalPathRow(chain: Chain, modifier: Modifier = Modifier, lightText: Boolean = true, accent: Color) {
    val ink = if (lightText) Color.White else Color.Black
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            chain.stages.forEachIndexed { i, stage ->
                if (i > 0) {
                    Box(Modifier.padding(top = 25.dp).width(10.dp).height(1.dp).background(ink.copy(alpha = 0.22f)))
                }
                StageCell(stage, accent, ink, Modifier.weight(WEIGHTS[i]))
            }
        }
        Lamp(chain, accent, ink)
    }
}

private val WEIGHTS = floatArrayOf(1.25f, 1.05f, 1.05f, 0.8f, 1.5f)

@Composable
private fun StageCell(stage: Stage, accent: Color, ink: Color, modifier: Modifier) {
    val led by animateColorAsState(
        when (stage.state) {
            StageState.LIT -> accent
            StageState.ALTERED -> Amber
            StageState.UNKNOWN -> ink.copy(alpha = 0.18f)
        },
        tween(400), label = "led",
    )
    Column(modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(7.dp)
                .then(
                    if (stage.state == StageState.UNKNOWN) Modifier
                    else Modifier.shadow(8.dp, CircleShape, ambientColor = led, spotColor = led),
                )
                .clip(CircleShape)
                .background(led),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            stage.name.uppercase(),
            color = ink.copy(alpha = 0.50f),
            fontFamily = AppFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.2.em,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            stage.value,
            color = ink.copy(alpha = if (stage.state == StageState.UNKNOWN) 0.35f else 0.88f),
            fontFamily = MonoFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** BIT-PERFECT lit, or the reason it is not (nothing at all while the stages are unknown). */
@Composable
private fun Lamp(chain: Chain, accent: Color, ink: Color) {
    val on = chain.bitPerfect
    val tint by animateColorAsState(if (on) accent else Amber, tween(400), label = "lamp")
    val text = when {
        on -> "BIT-PERFECT"
        chain.why != null -> chain.why.uppercase()
        else -> return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier
                .then(if (on) Modifier.shadow(14.dp, RoundedCornerShape(50), ambientColor = tint, spotColor = tint) else Modifier)
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = if (on) 0.16f else 0.10f))
                .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            Text(
                text,
                color = ink.copy(alpha = if (on) 0.95f else 0.75f),
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.14.em,
                maxLines = 1,
            )
        }
    }
}

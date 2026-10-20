package com.engabd.sendpin.linux

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.ui.CircleButton
import com.engabd.sendpin.linux.ui.EmptyState
import com.engabd.sendpin.linux.ui.Eyebrow
import com.engabd.sendpin.linux.ui.GlassCard
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.PageHeader
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.inkOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** HueGhost's music-sync offset range, in seconds, and the nudge step. */
private const val OFFSET_RANGE_S = 2.0
private const val OFFSET_STEP_S = 0.1

/** One nudge of the offset, rounded to the step and held inside the range. Pure for the tests. */
internal fun nudgeOffset(current: Double, direction: Int): Double {
    val next = current + direction * OFFSET_STEP_S
    return ((next * 10).roundToInt() / 10.0).coerceIn(-OFFSET_RANGE_S, OFFSET_RANGE_S)
}

/** "+0.3 s" / "−0.5 s" / "0.0 s" — signed, so which way the lights lean is readable. */
internal fun formatOffset(s: Double): String {
    val r = (s * 10).roundToInt() / 10.0
    return when {
        r > 0 -> "+%.1f s".format(r)
        r < 0 -> "−%.1f s".format(-r)
        else -> "0.0 s"
    }
}

/**
 * The lights pane: HueGhost's control surface. A hero card says at a glance whether the
 * room is syncing (the album's colours glowing when it is) and carries the one big
 * Start/Stop; below it the mode and intensity as large tiles, and the sync offset with
 * fine ± nudges a finger can hit.
 *
 * Every read reflects the daemon (`/api/status`, polled every 2 s while shown); every
 * control fires `/api/set` or `/api/on|off` and re-reads.
 */
@Composable
fun LightsPane(modifier: Modifier, model: PanelModel) {
    val hg = model.hueGhost.collectAsState().value
    val scope = rememberCoroutineScope()

    if (hg == null || !hg.isConfigured()) {
        Column(modifier) {
            PageHeader(title = "Lights")
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Default.Lightbulb,
                    "Connect HueGhost",
                    "Add the HueGhost address in Settings — the daemon must be reachable over the LAN.",
                )
            }
        }
        return
    }

    var status by remember { mutableStateOf<HueGhostClient.Status?>(null) }
    var polled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(hg) {
        while (true) {
            status = hg.status()
            polled = true
            delay(2000)
        }
    }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }
            status = hg.status()
            busy = false
        }
    }

    val scrollState = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .touchDragScroll(scrollState, Orientation.Vertical)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PageHeader(
                title = "Lights",
                subtitle = "Philips Hue Sync, driven by HueGhost" + (status?.version?.let { " $it" } ?: ""),
            )

            SyncHero(
                status = status,
                reachable = !polled || status != null,
                busy = busy,
                // Act on what the button showed, not on a fresh read: Stop stops.
                onToggle = {
                    val wasOn = status?.on == true
                    status = status?.copy(enabled = !wasOn, running = !wasOn && status?.running == true)
                    act { if (wasOn) hg.off() else hg.on() }
                },
            )

            GlassCard(Modifier.padding(horizontal = PageGutter)) {
                CardTitle("Mode", "What the lights follow.")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(
                        Triple("music", "Music", Icons.Default.MusicNote),
                        Triple("video", "Video", Icons.Default.Movie),
                        Triple("games", "Games", Icons.Default.SportsEsports),
                    ).forEach { (key, label, icon) ->
                        OptionTile(
                            label = label,
                            selected = status?.mode.equals(key, ignoreCase = true),
                            modifier = Modifier.weight(1f),
                            onClick = { act { hg.set(mode = key) } },
                        ) { tint -> Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp)) }
                    }
                }
            }

            GlassCard(Modifier.padding(horizontal = PageGutter)) {
                CardTitle("Intensity", "How hard the room reacts.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("subtle", "moderate", "high", "extreme").forEachIndexed { i, key ->
                        OptionTile(
                            label = key.replaceFirstChar { it.uppercase() },
                            selected = status?.intensity.equals(key, ignoreCase = true),
                            modifier = Modifier.weight(1f),
                            onClick = { act { hg.set(intensity = key) } },
                        ) { tint -> IntensityGlyph(level = i + 1, tint = tint) }
                    }
                }
            }

            OffsetCard(
                offset = status?.offsetS ?: 0.0,
                onSet = { v -> act { hg.set(offsetS = v) } },
            )

            status?.followItem?.let { item ->
                GlassCard(Modifier.padding(horizontal = PageGutter), spacing = 6.dp) {
                    Eyebrow("Following", rememberAccent())
                    Text(item, color = TextPrimary, fontFamily = AppFont, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * The state of the room, large: glowing orbs in the album's colours that breathe while
 * syncing (grey and still when not), the state in words, and the Start/Stop button.
 */
@Composable
private fun SyncHero(
    status: HueGhostClient.Status?,
    reachable: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = rememberAccent()
    val running = status?.running == true
    // Switched on (the button) is not the same as moving (the orbs): between songs, or
    // while HueGhost waits for the music, sync is on and the lights are still.
    val on = status?.on == true
    val t = rememberInfiniteTransition(label = "hero")
    val pulse by t.animateFloat(
        0.75f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val glow = if (running) pulse else 0.35f
    val c0 by animateColorAsState(if (running) palette.swatch(0) else Color.Gray, tween(600), label = "orb0")
    val c1 by animateColorAsState(if (running) palette.swatch(1) else Color.DarkGray, tween(600), label = "orb1")
    val c2 by animateColorAsState(if (running) accent else Color.Gray, tween(600), label = "orb2")

    Box(
        Modifier
            .padding(horizontal = PageGutter)
            .fillMaxWidth()
            .height(280.dp)
            .glassSurface(PanelShapes.Card, inkOn(0.05f), border = Hairline),
    ) {
        // The orbs — three lamps' worth of colour, the way the room looks right now.
        Orb(c0, 340, (-60), (-80), 0.85f * glow)
        Orb(c1, 300, 230, 10, 0.70f * glow)
        Orb(c2, 360, 450, (-100), 0.80f * glow)
        Row(
            Modifier.fillMaxSize().padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(if (running) "Syncing" else if (on) "On" else "Off", if (on) accent else TextMuted)
                Text(
                    when {
                        !reachable -> "HueGhost unreachable"
                        status == null -> "Connecting…"
                        running -> "The room is in sync"
                        on -> "Waiting for the music"
                        else -> "Lights are resting"
                    },
                    color = TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                )
                val detail = listOfNotNull(
                    status?.mode?.replaceFirstChar { it.uppercase() },
                    status?.intensity?.replaceFirstChar { it.uppercase() },
                ).joinToString("  ·  ")
                if (detail.isNotBlank()) {
                    Text(detail, color = TextSecondary, fontFamily = AppFont, fontSize = 18.sp)
                }
            }
            // The big switch: accent when it would start, glass when it would stop.
            Box(
                Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(if (on) inkOn(0.12f) else accent)
                    .border(2.dp, if (on) accent else Color.Transparent, CircleShape)
                    .clickable(enabled = !busy && reachable, onClick = onToggle)
                    .alpha(if (busy) 0.5f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PowerSettingsNew, null,
                        tint = if (on) accent else com.engabd.sendpin.ui.theme.Ink,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        if (on) "Stop" else "Start",
                        color = if (on) accent else com.engabd.sendpin.ui.theme.Ink,
                        fontFamily = AppFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Orb(color: Color, sizeDp: Int, x: Int, y: Int, alpha: Float) {
    Box(
        Modifier
            .offset(x.dp, y.dp)
            .size(sizeDp.dp)
            .background(Brush.radialGradient(listOf(color.a(alpha), Color.Transparent)), CircleShape)
    )
}

@Composable
private fun CardTitle(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TextPrimary, fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(hint, color = TextMuted, fontFamily = AppFont, fontSize = 15.sp)
    }
}

/** A large selectable tile: glyph over label, the accent ringing the chosen one. */
@Composable
private fun OptionTile(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val accent = rememberAccent()
    val shape = RoundedCornerShape(20.dp)
    val tint = if (selected) accent else TextSecondary
    Column(
        modifier
            .height(112.dp)
            .clip(shape)
            .background(if (selected) accent.a(0.16f) else inkOn(0.05f))
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else Hairline, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        glyph(tint)
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontFamily = AppFont,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 17.sp,
            maxLines = 1,
        )
    }
}

/** Four rising bars with [level] of them lit — intensity drawn, not just named. */
@Composable
private fun IntensityGlyph(level: Int, tint: Color) {
    Row(
        Modifier.height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        (1..4).forEach { i ->
            Box(
                Modifier
                    .width(7.dp)
                    .fillMaxHeight(0.25f * i)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) tint else tint.a(0.2f))
            )
        }
    }
}

/**
 * The sync offset: the value large, a slider for sweeping it, and ± buttons for the
 * tenth-of-a-second trims a finger can't land on a slider.
 */
@Composable
private fun OffsetCard(offset: Double, onSet: (Double) -> Unit) {
    val accent = rememberAccent()
    var scrub by remember { mutableStateOf<Double?>(null) }
    val shown = scrub ?: offset
    GlassCard(Modifier.padding(horizontal = PageGutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("Timing", "Negative pulls the lights earlier, positive later.")
            }
            Text(formatOffset(shown), color = accent, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircleButton(Icons.Default.Remove, "Earlier", onClick = { onSet(nudgeOffset(offset, -1)) })
            Slider(
                value = shown.toFloat(),
                onValueChange = { scrub = it.toDouble() },
                onValueChangeFinished = {
                    scrub?.let { v -> onSet((v * 10).roundToInt() / 10.0) }
                    scrub = null
                },
                valueRange = -OFFSET_RANGE_S.toFloat()..OFFSET_RANGE_S.toFloat(),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.weight(1f),
            )
            CircleButton(Icons.Default.Add, "Later", onClick = { onSet(nudgeOffset(offset, +1)) })
        }
        // Only offered when there is something to reset.
        if (formatOffset(offset) != formatOffset(0.0)) {
            Row {
                Spacer(Modifier.weight(1f))
                PillButton("Reset to 0", filled = false, onClick = { onSet(0.0) })
            }
        }
    }
}

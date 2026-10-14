package com.engabd.sendpin.linux.station

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.engabd.sendpin.linux.PanelModel
import com.engabd.sendpin.linux.screensaver.Clock
import com.engabd.sendpin.linux.screensaver.IdleGate
import com.engabd.sendpin.ui.theme.MonoFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/*
 * Deep idle: the stage past the listening view and the gallery, for a panel that stays on
 * for days. After a long time untouched it goes to a dim clock alone — somewhere new each
 * minute, so nothing is lit in one place for long — or switches the screen off. A touch
 * wakes it and goes no further, as it does for the other two. Settings → Deep idle.
 */

internal enum class DeepIdleStage { NONE, CLOCK, SCREEN_OFF }

/** The stage for Settings' [mode] (`off` | `clock` | `screen_off`) after [idleMs] untouched. */
internal fun deepIdleStage(mode: String, idleMs: Long, delayMs: Long): DeepIdleStage = when {
    idleMs < delayMs -> DeepIdleStage.NONE
    mode == "clock" -> DeepIdleStage.CLOCK
    mode == "screen_off" -> DeepIdleStage.SCREEN_OFF
    else -> DeepIdleStage.NONE
}

/**
 * Where the clock sits for minute [seed]: a fraction of the free width and height, spread by
 * a fixed stride so consecutive minutes land far apart and the walk never repeats within
 * an hour. Pure so the spread is testable.
 */
internal fun clockSpot(seed: Long): Pair<Float, Float> {
    val x = ((seed * 0.6180339887) % 1.0).toFloat()
    val y = ((seed * 0.4142135623 + 0.25) % 1.0).toFloat()
    // Kept off the very edges, where it would be clipped or hard to read.
    return (0.08f + 0.84f * x) to (0.12f + 0.70f * y)
}

/**
 * The commands that switch the display off or on through X's power saving. `xset` is on every
 * X install; a machine without it (or a Wayland session) simply does nothing here.
 */
internal fun screenCommands(on: Boolean): List<List<String>> =
    if (on) listOf(listOf("xset", "dpms", "force", "on"), listOf("xset", "s", "reset"))
    else listOf(listOf("xset", "+dpms"), listOf("xset", "dpms", "force", "off"))

/** Runs [screenCommands] — on Linux only, and never throwing. */
internal object ScreenPower {
    private val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    suspend fun set(on: Boolean) = withContext(Dispatchers.IO) {
        if (!linux) return@withContext
        for (cmd in screenCommands(on)) {
            runCatching {
                ProcessBuilder(cmd).redirectErrorStream(true).start().also { it.waitFor(3, TimeUnit.SECONDS) }
            }
        }
    }
}

@Composable
fun DeepIdleHost(model: PanelModel, gate: IdleGate) {
    val settings = model.settings
    val mode by settings.deepIdle.collectAsState(initial = "off")
    val delayMin by settings.deepIdleMin.collectAsState(initial = 60)
    var stage by remember { mutableStateOf(DeepIdleStage.NONE) }

    LaunchedEffect(mode, delayMin) {
        while (true) {
            stage = deepIdleStage(mode, gate.idleMs(), delayMin.coerceAtLeast(1) * 60_000L)
            delay(1_000)
        }
    }
    // A finger down wakes it at once, without waiting for the next look at the clock.
    val pressAt by gate.lastPress.collectAsState()
    LaunchedEffect(pressAt) { if (stage != DeepIdleStage.NONE) stage = DeepIdleStage.NONE }

    // The screen follows the stage — and is only ever switched on again if this switched it off.
    var darkened by remember { mutableStateOf(false) }
    LaunchedEffect(stage) {
        if (stage == DeepIdleStage.SCREEN_OFF) {
            ScreenPower.set(false)
            darkened = true
        } else if (darkened) {
            ScreenPower.set(true)
            darkened = false
        }
    }

    AnimatedVisibility(visible = stage != DeepIdleStage.NONE, enter = fadeIn(tween(2_500)), exit = fadeOut(tween(300))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // The waking touch is the panel's alone — it does not press what is underneath.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (stage == DeepIdleStage.CLOCK) DimClock(model)
        }
    }
}

/** A dim clock, and what is playing, in a new place each minute. */
@Composable
private fun DimClock(model: PanelModel) {
    val remote by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val track = if (remote?.playing == true) queue.getOrNull(remote?.index?.takeIf { it >= 0 } ?: 0) else null
    var minute by remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            minute = System.currentTimeMillis() / 60_000L
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = minute,
            transitionSpec = { fadeIn(tween(2_000)) togetherWith fadeOut(tween(2_000)) },
            label = "dimClock",
        ) { m ->
            val (fx, fy) = clockSpot(m)
            // Everything here is dimmed together, so the clock is a glow rather than a light.
            Column(
                Modifier.offset(x = maxWidth * fx * 0.55f, y = maxHeight * fy).graphicsLayer { alpha = 0.42f },
                horizontalAlignment = Alignment.Start,
            ) {
                Clock(accent = null, neutral = false, scale = 0.9f, align = Alignment.Start, modifier = Modifier)
                if (track != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        listOfNotNull(track.artist, track.title).joinToString("  —  "),
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = MonoFont,
                        fontSize = 14.sp,
                        letterSpacing = 0.1.em,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

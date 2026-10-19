package com.engabd.sendpin.linux.screensaver

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * When the gallery may take the screen: switched on, nothing playing, and nobody has
 * touched the panel for the configured delay — and, while music plays, when the
 * listening view may ([shouldListen]).
 *
 * "Untouched" counts from the later of the last touch and the last time the music
 * stopped — the host calls [touch] on every pointer event and on every change of
 * the playing state — so a record ending after an hour of listening still gets the
 * full delay before the gallery comes up.
 */
class IdleGate(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile
    var lastActivityMs: Long = clock()
        private set

    private val _lastPress = MutableStateFlow(0L)

    /**
     * When a finger last went down, 0 for never. Observable, unlike [lastActivityMs]: a
     * view still fading in lets a press through to the controls under it, and has to hear
     * about that press to step aside (see [pressEndsEntrance]). Presses only — a mouse
     * hovering over a dev box must not end a preview.
     */
    val lastPress: StateFlow<Long> = _lastPress

    fun touch() {
        lastActivityMs = clock()
    }

    /** A finger (or button) going down: activity, and a press the views can react to. */
    fun press() {
        touch()
        _lastPress.value = lastActivityMs
    }

    fun now(): Long = clock()

    fun idleMs(): Long = clock() - lastActivityMs

    companion object {
        /** How far ahead of the threshold the first slide is loaded, so it appears whole. */
        const val PREWARM_MS = 30_000L

        fun shouldShow(enabled: Boolean, playing: Boolean, idleMs: Long, delayMs: Long): Boolean =
            enabled && !playing && idleMs >= delayMs

        /**
         * Loads ahead by [PREWARM_MS], or by half the delay when that is shorter — a typed
         * 10 s delay must not keep the preloader running while the panel is in use.
         */
        fun shouldPrewarm(enabled: Boolean, playing: Boolean, idleMs: Long, delayMs: Long): Boolean =
            enabled && !playing && idleMs >= delayMs - minOf(PREWARM_MS, delayMs / 2)

        /** Shortest and longest typed delay: under 5 s the panel could never be used; a day is plenty. */
        const val CUSTOM_MIN_S = 5
        const val CUSTOM_MAX_S = 86_400

        /**
         * The idle delay a view waits for: the typed [customS] seconds when one is set
         * (anything above 0), otherwise the [presetMin] minutes of the preset row, never
         * under [floorMin].
         */
        fun delayMs(presetMin: Int, customS: Int, floorMin: Int = 0): Long =
            if (customS > 0) customS.coerceIn(CUSTOM_MIN_S, CUSTOM_MAX_S) * 1_000L
            else presetMin.coerceAtLeast(floorMin) * 60_000L

        /** Typed digits to a delay in seconds, kept in range; null when nothing usable was typed. */
        fun parseCustomSeconds(text: String): Int? =
            text.filter(Char::isDigit).take(6).toIntOrNull()
                ?.takeIf { it > 0 }
                ?.coerceIn(CUSTOM_MIN_S, CUSTOM_MAX_S)

        /**
         * The listening view — the other half of the always-on panel: music playing and
         * nobody touching for [delayMs]. Never true when [shouldShow] is, so the gallery
         * and the listening view can't both claim the screen.
         */
        fun shouldListen(enabled: Boolean, playing: Boolean, idleMs: Long, delayMs: Long): Boolean =
            enabled && playing && idleMs >= delayMs
    }
}

/**
 * Whether a full-screen view (the gallery, the listening view) takes touches itself: only
 * once it is fully up ([current] reached, no animation [running]) and staying up
 * ([target]). While it fades in, the controls are still what the eye sees, so a tap
 * belongs to them; while it fades out, the panel is already awake. The view used to take
 * every touch for as long as it was drawn at all, so the first taps on pause after waking
 * it — inside the 600 ms fade-out — did nothing.
 */
internal fun overlayCaptures(target: Boolean, current: Boolean, running: Boolean): Boolean =
    target && current && !running

/** [overlayCaptures] for an `AnimatedVisibility` driven by [state]. */
internal fun overlayCaptures(state: MutableTransitionState<Boolean>): Boolean =
    overlayCaptures(state.targetState, state.currentState, !state.isIdle)

/**
 * Whether a press should end a view that is still fading in: it went through to the
 * controls (see [overlayCaptures]), so the view steps aside rather than finish arriving
 * over a panel someone is using. Only presses after the view began count.
 */
internal fun pressEndsEntrance(showing: Boolean, captures: Boolean, pressAtMs: Long, shownAtMs: Long): Boolean =
    showing && !captures && pressAtMs > shownAtMs

/**
 * The host's half of [pressEndsEntrance]: notes when the view began to show, and calls
 * [onStepAside] for a press that landed while it was still arriving.
 */
@Composable
internal fun StepAsideOnPress(gate: IdleGate, showing: Boolean, captures: Boolean, onStepAside: () -> Unit) {
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(showing) { if (showing) shownAt = gate.now() }
    val pressAt by gate.lastPress.collectAsState()
    val nowShowing by rememberUpdatedState(showing)
    val nowCaptures by rememberUpdatedState(captures)
    val stepAside by rememberUpdatedState(onStepAside)
    LaunchedEffect(pressAt) {
        if (pressEndsEntrance(nowShowing, nowCaptures, pressAt, shownAt)) stepAside()
    }
}

package com.engabd.sendpin.linux.screensaver

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

    fun touch() {
        lastActivityMs = clock()
    }

    fun idleMs(): Long = clock() - lastActivityMs

    companion object {
        /** How far ahead of the threshold the first slide is loaded, so it appears whole. */
        const val PREWARM_MS = 30_000L

        fun shouldShow(enabled: Boolean, playing: Boolean, idleMs: Long, delayMs: Long): Boolean =
            enabled && !playing && idleMs >= delayMs

        fun shouldPrewarm(enabled: Boolean, playing: Boolean, idleMs: Long, delayMs: Long): Boolean =
            enabled && !playing && idleMs >= delayMs - PREWARM_MS

        /**
         * The listening view — the other half of the always-on panel: music playing and
         * nobody touching for [delayMs]. Never true when [shouldShow] is, so the gallery
         * and the listening view can't both claim the screen.
         */
        fun shouldListen(enabled: Boolean, playing: Boolean, idleMs: Long, delayMs: Long): Boolean =
            enabled && playing && idleMs >= delayMs
    }
}

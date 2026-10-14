package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.RemoteState

/*
 * The sleep timer, as a pure state machine the poll loop asks once a tick. Two kinds:
 * after a set time, and at the end of the album — "the end of the side", on the Vinyl
 * layout, where the stylus then lifts into the run-out and the arm parks.
 */

/** What the chip steps through when it is tapped. */
internal enum class SleepChoice(val label: String, val minutes: Int?) {
    OFF("Sleep", null),
    END_OF_ALBUM("End of album", null),
    M30("30 min", 30),
    M60("60 min", 60),
    M90("90 min", 90),
    ;

    fun next(): SleepChoice = entries[(ordinal + 1) % entries.size]
}

/**
 * A timer that is running. [deadlineMs] (wall clock) ends it after a time; [sideEnd] (a queue
 * index) ends it with the track at that index — MPD is asked to stop after that track, so it
 * plays out whole. [armed] is set once MPD has been asked.
 */
internal data class SleepTimer(
    val choice: SleepChoice,
    val deadlineMs: Long? = null,
    val sideEnd: Int? = null,
    val armed: Boolean = false,
)

internal enum class SleepAction { NONE, PAUSE, ARM_STOP_AFTER_CURRENT, CLEAR }

/** The timer for [choice], started at [nowMs]; [sideLastIndex] is the last queue index of the side playing. */
internal fun startSleep(choice: SleepChoice, nowMs: Long, sideLastIndex: Int?): SleepTimer? = when (choice) {
    SleepChoice.OFF -> null
    SleepChoice.END_OF_ALBUM -> sideLastIndex?.let { SleepTimer(choice, sideEnd = it) }
    else -> SleepTimer(choice, deadlineMs = nowMs + choice.minutes!! * 60_000L)
}

/**
 * What the poll should do for [timer] given MPD's [state] at [nowMs]. A timed sleep pauses
 * when its time is up. An album sleep asks MPD to stop after the current track once the
 * last track of the side begins, and clears itself once MPD has stopped — or if the
 * listener skipped out past the end of the side.
 */
internal fun sleepAction(timer: SleepTimer, state: RemoteState?, nowMs: Long): SleepAction {
    if (state == null) return SleepAction.NONE
    timer.deadlineMs?.let { due ->
        return if (nowMs >= due) SleepAction.PAUSE else SleepAction.NONE
    }
    val end = timer.sideEnd ?: return SleepAction.NONE
    return when {
        state.stopped -> SleepAction.CLEAR
        state.index > end -> SleepAction.CLEAR
        state.index == end && state.playing && !timer.armed -> SleepAction.ARM_STOP_AFTER_CURRENT
        else -> SleepAction.NONE
    }
}

/** The chip's words: the choice, or what is left of a running timer. */
internal fun sleepLabel(timer: SleepTimer?, nowMs: Long): String = when {
    timer == null -> SleepChoice.OFF.label
    timer.deadlineMs != null -> {
        val left = ((timer.deadlineMs - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L
        "Sleep in $left min"
    }
    else -> "Stops after this album"
}

package com.engabd.sendpin.linux.station

import com.engabd.sendpin.linux.PanelModel

/**
 * Set the stylus down at [fraction] of the way along [side]: the track under it plays from
 * where the groove puts it. Jumping to another track is a play of that queue entry first,
 * then a seek into it.
 */
internal suspend fun setNeedle(model: PanelModel, side: Side, fraction: Float) {
    val at = locate(side.durations, fraction)
    val queueIndex = side.firstIndex + at.track
    if (model.state.value?.index != queueIndex) model.playAt(queueIndex)
    // The first moments of a track need no seek: it begins there.
    if (at.offsetMs > NEEDLE_SEEK_MIN_MS) model.seekTo(at.offsetMs)
}

/** A landing this close to a track's start just plays it from the top. */
internal const val NEEDLE_SEEK_MIN_MS = 1_500L

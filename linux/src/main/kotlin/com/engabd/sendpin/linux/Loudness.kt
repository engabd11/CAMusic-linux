package com.engabd.sendpin.linux

/**
 * The three ReplayGain modes the Android app offers (`PlayerOptionsSheet`'s
 * `ReplayGainModes`, `AudioSettings`' Loudness card), in the order they read as a
 * choice: nothing, every track to the same loudness, or the album kept in proportion
 * with itself. [key] is spelled exactly as MPD's `replay_gain_mode` spells it, so the
 * choice passes straight through `MpdClient.setReplayGainMode`.
 *
 * MPD applies the gain itself as it decodes — the panel only tells it which tag to
 * read. That is also why [OFF]'s blurb can promise untouched samples: with no gain to
 * apply, MPD's software ReplayGain stage has nothing to scale.
 */
internal enum class LoudnessMode(val key: String, val label: String, val blurb: String) {
    OFF(
        "off", "Off",
        "Files play at their mastered level. MPD applies no gain, so the samples reach " +
            "the DAC untouched.",
    ),
    TRACK(
        "track", "Track",
        "Levels every track to the same loudness. Better for shuffled singles; flattens " +
            "an album's quiet passages.",
    ),
    ALBUM(
        "album", "Album",
        "Levels whole albums against each other and keeps the dynamics within one. What " +
            "you want for records.",
    );

    companion object {
        fun from(key: String?): LoudnessMode? = entries.firstOrNull { it.key.equals(key, true) }
    }
}

/**
 * The mode the panel shows as in force: MPD's own reading when it has answered, the
 * saved preference until then. MPD is shared with the Android app, which sets the same
 * `replay_gain_mode` — showing the preference over MPD's reading would claim a mode
 * the DAC is not getting.
 */
internal fun shownLoudness(saved: String, mpd: String?): String = mpd ?: saved

/**
 * The mode one tap on the cover's loudness chip moves to: Off → Track → Album → Off.
 * MPD's fourth mode, `auto`, is not one the panel offers, so it steps to Off — the
 * start of the cycle — rather than to a neighbour it has no place between.
 */
internal fun nextLoudness(current: String): String = when (LoudnessMode.from(current)) {
    LoudnessMode.OFF -> LoudnessMode.TRACK.key
    LoudnessMode.TRACK -> LoudnessMode.ALBUM.key
    LoudnessMode.ALBUM, null -> LoudnessMode.OFF.key
}

/** The chip's label: the mode by name, or MPD's own word for one the panel does not offer. */
internal fun loudnessLabel(mode: String): String =
    LoudnessMode.from(mode)?.label ?: mode.replaceFirstChar { it.uppercase() }

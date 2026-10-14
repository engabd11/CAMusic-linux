package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.RemoteAudioFormat
import com.engabd.sendpin.audio.StreamQuality

/*
 * The signal path as stages: what the file is, what MPD decodes it to, whether anything
 * touches the level, and where it goes. Built only from what MPD and the library report,
 * so the BIT-PERFECT lamp says "nothing MPD reports would change these bits" — it cannot
 * see inside an ALSA plugin or a DAC driver, and the panel says so.
 */

/** How a stage reads: lit when it leaves the audio alone, amber when it alters it. */
internal enum class StageState { LIT, ALTERED, UNKNOWN }

internal data class Stage(val name: String, val value: String, val state: StageState)

/**
 * The chain for the playing track. [bitPerfect] is true only when every stage is known and
 * leaves the audio alone; otherwise [why] names the first thing that stops it (or null when
 * the stages are simply unknown).
 */
internal data class Chain(val stages: List<Stage>, val bitPerfect: Boolean, val why: String?)

/**
 * @param source the file as the library describes it
 * @param decoded what MPD says it is decoding to right now
 * @param gainMode MPD's replay-gain mode: `off`, `track` or `album` (null if unread)
 * @param volume MPD's software/hardware volume 0..1, or null when it has no mixer (a fixed output)
 */
internal fun signalChain(
    source: StreamQuality?,
    decoded: RemoteAudioFormat?,
    device: String?,
    gainMode: String?,
    volume: Float?,
): Chain {
    val sourceStage = Stage(
        "Source",
        source?.takeIf { it.codec.isNotBlank() }?.shortLabel ?: "—",
        if (source == null || source.codec.isBlank()) StageState.UNKNOWN
        else if (source.lossless) StageState.LIT else StageState.ALTERED,
    )

    val rate = decoded?.sampleRateHz ?: 0
    val bits = decoded?.bitDepth ?: 0
    val decodedKnown = decoded != null && rate > 0
    val decodeLabel = if (!decodedKnown) "—" else buildString {
        append(StreamQuality.khz(rate)).append(" kHz")
        if (bits > 0) append(" / $bits")
    }
    val sameFormat = source != null && decodedKnown &&
        (source.sampleRateHz == 0 || source.sampleRateHz == rate) &&
        (source.bitDepth == 0 || bits == 0 || source.bitDepth == bits)
    val decodeState = when {
        !decodedKnown -> StageState.UNKNOWN
        source != null && source.sampleRateHz > 0 && source.sampleRateHz != rate -> StageState.ALTERED
        source != null && source.bitDepth > 0 && bits > 0 && source.bitDepth != bits -> StageState.ALTERED
        else -> StageState.LIT
    }
    val decodeStage = Stage("Decode", decodeLabel, decodeState)

    val gainOn = gainMode == "track" || gainMode == "album"
    val levelStage = Stage(
        "Level",
        when (gainMode) { "track" -> "Gain: track"; "album" -> "Gain: album"; "off" -> "Untouched"; else -> "—" },
        when { gainMode == null -> StageState.UNKNOWN; gainOn -> StageState.ALTERED; else -> StageState.LIT },
    )

    val volumeAltered = volume != null && volume < 0.995f
    val volumeStage = Stage(
        "Volume",
        when { volume == null -> "Fixed"; volumeAltered -> "${Math.round(volume * 100)}%"; else -> "100%" },
        if (volumeAltered) StageState.ALTERED else StageState.LIT,
    )

    val outStage = Stage(
        "Output",
        device?.trim()?.takeIf { it.isNotEmpty() } ?: "—",
        if (device.isNullOrBlank()) StageState.UNKNOWN else StageState.LIT,
    )

    val stages = listOf(sourceStage, decodeStage, levelStage, volumeStage, outStage)

    val why = when {
        source != null && source.codec.isNotBlank() && !source.lossless -> "Lossy source"
        decodeState == StageState.ALTERED -> "Resampled"
        gainOn -> "Loudness gain on"
        volumeAltered -> "Volume below 100%"
        else -> null
    }
    val allKnown = sourceStage.state != StageState.UNKNOWN && decodeStage.state != StageState.UNKNOWN &&
        levelStage.state != StageState.UNKNOWN && outStage.state != StageState.UNKNOWN
    val bitPerfect = why == null && allKnown && sameFormat && source?.lossless == true
    return Chain(stages, bitPerfect, why)
}

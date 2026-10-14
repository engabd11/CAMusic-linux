package com.engabd.sendpin.linux.station

import com.engabd.sendpin.audio.LocalTrack
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The record, as numbers: which tracks make up the side on the platter, how far into the
 * groove the music is, and where a tonearm has to point to be there. All pure, so the
 * choreography in Tonearm.kt is only drawing.
 */

/**
 * The side on the platter: the run of the queue that belongs to the playing track's album,
 * with [firstIndex] its place in the queue. A track from nowhere in particular is a side
 * of one — a smooth record with no rings between tracks.
 */
internal data class Side(val firstIndex: Int, val tracks: List<LocalTrack>) {
    val size: Int get() = tracks.size

    /** Each track's length for laying out the groove; unknown lengths take the side's average. */
    val durations: List<Long> get() = effectiveDurations(tracks.map { it.durationMs })

    /** The playing track's place within the side, given its place in the queue. */
    fun indexOf(queueIndex: Int): Int = (queueIndex - firstIndex).coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
}

/**
 * The album a queue entry belongs to, for finding the side: the library's album id when this
 * panel queued it, else the album's title. Null when the entry says nothing about an album.
 */
internal fun sideKey(track: LocalTrack, albumIds: Map<String, String>): String? =
    albumIds[track.id]?.let { "id:$it" }
        ?: track.album?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "album:$it" }

/** The run of [queue] around [index] that shares its album — or just that one track. */
internal fun sideOf(queue: List<LocalTrack>, index: Int, albumIds: Map<String, String>): Side? {
    val here = queue.getOrNull(index) ?: return null
    val key = sideKey(here, albumIds) ?: return Side(index, listOf(here))
    var from = index
    while (from > 0 && sideKey(queue[from - 1], albumIds) == key) from--
    var to = index
    while (to < queue.lastIndex && sideKey(queue[to + 1], albumIds) == key) to++
    return Side(from, queue.subList(from, to + 1).toList())
}

/** [raw] with every unknown (zero) length replaced by the average of the known ones. */
internal fun effectiveDurations(raw: List<Long>): List<Long> {
    if (raw.isEmpty()) return raw
    val known = raw.filter { it > 0 }
    val fill = if (known.isEmpty()) 240_000L else known.sum() / known.size
    return raw.map { if (it > 0) it else fill }
}

/**
 * How far along the side the music is, 0..1: the lengths of the tracks before [track] plus
 * [positionMs] into this one, over the side's whole length.
 */
internal fun grooveProgress(durations: List<Long>, track: Int, positionMs: Long): Float {
    if (durations.isEmpty()) return 0f
    val d = effectiveDurations(durations)
    val i = track.coerceIn(0, d.lastIndex)
    val before = d.take(i).sum()
    val into = positionMs.coerceIn(0L, d[i])
    return ((before + into).toFloat() / d.sum()).coerceIn(0f, 1f)
}

/** Where each track begins along the side, 0..1 — the first is always 0. */
internal fun trackStarts(durations: List<Long>): List<Float> {
    if (durations.isEmpty()) return emptyList()
    val d = effectiveDurations(durations)
    val total = d.sum().toFloat()
    var acc = 0L
    return d.map { len -> (acc / total).also { acc += len } }
}

/** A place on the side: which track, and how far into it. */
internal data class GroovePoint(val track: Int, val offsetMs: Long)

/** The track and offset at [fraction] of the way along the side — the inverse of [grooveProgress]. */
internal fun locate(durations: List<Long>, fraction: Float): GroovePoint {
    if (durations.isEmpty()) return GroovePoint(0, 0L)
    val d = effectiveDurations(durations)
    val at = (fraction.coerceIn(0f, 1f) * d.sum()).toLong()
    var acc = 0L
    for ((i, len) in d.withIndex()) {
        if (at < acc + len || i == d.lastIndex) return GroovePoint(i, (at - acc).coerceIn(0L, len))
        acc += len
    }
    return GroovePoint(d.lastIndex, 0L)
}

/**
 * The turntable's geometry, in units of the deck's square (0..1 across and down). The
 * platter is the lower-left 88%; the arm's pivot sits in the upper-right corner where the
 * deck has room for it, and the stylus travels from the outer groove inward as the side
 * plays. Everything the drawing and the finger need comes from here.
 */
internal object Deck {
    const val PLATTER_FRACTION = 0.88f

    // The platter's centre and radius in deck units.
    const val CX = PLATTER_FRACTION / 2f
    const val CY = 1f - PLATTER_FRACTION / 2f
    const val R = PLATTER_FRACTION / 2f

    // The arm's pivot and the pivot-to-stylus length.
    const val PX = 0.92f
    const val PY = 0.10f
    const val ARM = 0.60f

    /** Groove radii, as a fraction of the platter's radius: the lead-in and the run-out. */
    const val OUTER = 0.95f
    const val INNER = 0.43f

    /** Where the label ends: the grooves begin just beyond it. */
    const val LABEL = 0.36f

    /** The groove radius at [fraction] along the side, in units of the platter's radius. */
    fun grooveRadius(fraction: Float): Float = OUTER + (INNER - OUTER) * fraction.coerceIn(0f, 1f)

    /** The inverse: how far along the side a groove of [radius] (platter radii) is. */
    fun fractionAt(radius: Float): Float = ((OUTER - radius) / (OUTER - INNER)).coerceIn(0f, 1f)

    /** The direction (degrees, screen coordinates) the arm points to put the stylus on [fraction]. */
    fun angleAt(fraction: Float): Float {
        val rho = grooveRadius(fraction) * R
        val dx = CX - PX
        val dy = CY - PY
        val d = hypot(dx, dy)
        val toCentre = atan2(dy, dx)
        val cosPhi = ((d * d + ARM * ARM - rho * rho) / (2f * d * ARM)).coerceIn(-1f, 1f)
        val phi = acos(cosPhi)
        // The intersection on the right-hand side of the line to the centre: the stylus
        // meets the record at its right edge and runs in toward the label.
        return ((toCentre - phi) * 180.0 / PI).toFloat()
    }

    /** The arm at rest, off the record's edge. */
    const val PARKED = 86f

    /** The stylus tip for an arm pointing [angleDeg]. */
    fun tip(angleDeg: Float): Pair<Float, Float> {
        val a = angleDeg * PI / 180.0
        return (PX + ARM * cos(a)).toFloat() to (PY + ARM * sin(a)).toFloat()
    }

    /**
     * How far along the side a finger at [x], [y] (deck units) would put the stylus,
     * by its distance from the platter's centre.
     */
    fun fractionAtPoint(x: Float, y: Float): Float = fractionAt(hypot(x - CX, y - CY) / R)
}

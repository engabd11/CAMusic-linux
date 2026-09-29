package com.engabd.sendpin.linux.screensaver

import com.engabd.sendpin.ma.MaItem
import kotlin.random.Random

/**
 * The order albums take the screen in, like a shuffled deck: nothing repeats until
 * every album has been shown, and never the same artist twice running. The host
 * tops it up from the library whenever [needsMore] says so.
 */
class SlideDeck(private val random: Random = Random.Default) {

    private val pending = ArrayDeque<MaItem>()
    /** Shown in the current pass through the library. */
    private val shown = HashSet<String>()
    private var lastArtist: String? = null

    val size: Int get() = pending.size

    fun needsMore(): Boolean = pending.size < LOW_WATER

    /**
     * Shuffle in whatever of [items] is new to this pass. Albums without art are dropped —
     * there is nothing to show. Once the library is exhausted (nothing new arrives while
     * the deck is running dry), a fresh pass begins.
     */
    fun add(items: List<MaItem>) {
        val withArt = items.filter { !it.image.isNullOrBlank() }.distinctBy { it.itemId }
        val queued = pending.mapTo(HashSet()) { it.itemId }
        var fresh = withArt.filter { it.itemId !in shown && it.itemId !in queued }
        if (fresh.isEmpty() && pending.size < 2) {
            shown.clear()
            fresh = withArt.filter { it.itemId !in queued }
        }
        pending.addAll(spread(fresh))
    }

    /**
     * Shuffled, then spaced: each step takes from whichever artist has the most albums
     * still waiting, never the one just placed. A plain shuffle leaves a prolific
     * artist's records bunched at the end of a pass, played back to back.
     */
    private fun spread(items: List<MaItem>): List<MaItem> {
        // grouped from a shuffled list, so ties between equal groups break at random
        val groups = items.shuffled(random)
            .groupBy { artistOf(it) ?: "\u0000${it.itemId}" }
            .values.map { ArrayDeque(it) }.toMutableList()
        val out = ArrayList<MaItem>(items.size)
        var last = pending.lastOrNull()?.let(::artistOf) ?: lastArtist
        while (groups.isNotEmpty()) {
            val group = groups.filter { artistOf(it.first()).let { a -> a == null || a != last } }
                .ifEmpty { groups }
                .maxBy { it.size }
            val item = group.removeFirst()
            out += item
            last = artistOf(item)
            if (group.isEmpty()) groups.remove(group)
        }
        return out
    }

    /** The next album, avoiding the artist just shown when the deck allows it. */
    fun next(): MaItem? {
        if (pending.isEmpty()) return null
        val at = pending.indexOfFirst { artistOf(it) == null || artistOf(it) != lastArtist }
            .takeIf { it >= 0 } ?: 0
        val item = pending.removeAt(at)
        shown += item.itemId
        lastArtist = artistOf(item)
        return item
    }

    private fun artistOf(item: MaItem): String? = item.subtitle?.trim()?.lowercase()?.ifBlank { null }

    companion object {
        const val LOW_WATER = 8
    }
}

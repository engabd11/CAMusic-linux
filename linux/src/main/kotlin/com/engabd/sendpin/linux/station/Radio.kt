package com.engabd.sendpin.linux.station

import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.ma.MaItem
import kotlinx.coroutines.CancellationException

/*
 * Radio: start from a song, an album or an artist and the music keeps going, the queue
 * topped up from the library a few tracks before it runs out. Ported from the Android
 * app's `LocalRadio` ladder.
 */

/** Where a radio's next batch of tracks can come from. An interface so the ladder can be tested without a server. */
internal interface RadioSource {
    suspend fun similarTo(id: String, count: Int): List<MaItem>
    suspend fun topSongs(artistName: String, count: Int): List<MaItem>
    suspend fun byGenre(genre: String, count: Int): List<MaItem>
    suspend fun random(count: Int): List<MaItem>
}

/** The real one, over the connected library. A rung that fails is a rung that is skipped. */
internal class LibraryRadioSource(private val source: MusicSource) : RadioSource {
    override suspend fun similarTo(id: String, count: Int) = quiet { source.similarSongs(id, count) }
    override suspend fun topSongs(artistName: String, count: Int) = quiet { source.topSongs(artistName, count) }
    override suspend fun byGenre(genre: String, count: Int) = quiet { source.songsByGenre(genre, count) }
    override suspend fun random(count: Int) = quiet { source.randomSongs(count) }

    private inline fun quiet(block: () -> List<MaItem>): List<MaItem> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}

/** A radio that is playing: what it started from, for the chip that says so. */
data class RadioSession(val seedName: String, val seedKind: String)

/**
 * The ladder, strongest claim first, stopping at the first rung that answers: tracks like
 * this one (Jellyfin's instant mix), tracks from around this record, the artist's best
 * known songs, the genre, then anything at all. Holds a rolling history so a radio does not
 * circle back on itself within a session.
 */
internal class LocalRadio(private val historyLimit: Int = 300) {

    private val history = LinkedHashSet<String>()

    fun remember(ids: Collection<String>) {
        history.addAll(ids)
        while (history.size > historyLimit) history.remove(history.first())
    }

    fun forget() = history.clear()

    /** The next batch, seeded from [seed]; [exclude] is what is already queued. */
    suspend fun next(source: RadioSource, seed: MaItem?, count: Int = 12, exclude: Set<String> = emptySet()): List<MaItem> {
        val want = count * 4
        val rungs: List<suspend () -> List<MaItem>> = buildList {
            seed?.itemId?.takeIf { it.isNotBlank() }?.let { id -> add { source.similarTo(id, want) } }
            seed?.parentId?.takeIf { it.isNotBlank() && it != seed.itemId }?.let { album -> add { source.similarTo(album, want) } }
            seed?.subtitle?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }?.let { artist ->
                add { source.topSongs(artist, want) }
            }
            seed?.genres?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre -> add { source.byGenre(genre, want) } }
            add { source.random(want) }
        }
        for (rung in rungs) {
            val picked = rung()
                .filter { it.itemId.isNotBlank() && it.mediaType.let { t -> t.isBlank() || t == "track" } }
                .filterNot { it.itemId in exclude || it.itemId in history }
                .distinctBy { it.itemId }
                // An instant mix comes back grouped; a little shuffle keeps one artist from holding the room.
                .let { spreadArtists(it) }
                .take(count)
            if (picked.isNotEmpty()) {
                remember(picked.map { it.itemId })
                return picked
            }
        }
        return emptyList()
    }
}

/**
 * [tracks] reordered so the same artist does not play twice in a row where that can be
 * helped, keeping the order otherwise. Pure for the tests.
 */
internal fun spreadArtists(tracks: List<MaItem>): List<MaItem> {
    val left = tracks.toMutableList()
    val out = ArrayList<MaItem>(tracks.size)
    while (left.isNotEmpty()) {
        val prev = out.lastOrNull()?.subtitle
        val i = left.indexOfFirst { prev == null || it.subtitle != prev }.takeIf { it >= 0 } ?: 0
        out += left.removeAt(i)
    }
    return out
}

/** Tracks left after the playing one at which the radio tops the queue up. */
internal const val RADIO_REFILL_AT = 3

/** Whether a radio should top the queue up now: [index] playing out of [queueSize]. Pure for the tests. */
internal fun radioNeedsMore(index: Int, queueSize: Int): Boolean =
    index >= 0 && queueSize - index - 1 < RADIO_REFILL_AT

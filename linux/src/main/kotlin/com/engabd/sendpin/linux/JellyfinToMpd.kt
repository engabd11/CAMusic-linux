package com.engabd.sendpin.linux

import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.ma.MaAudioFormat
import com.engabd.sendpin.ma.MaItem

/**
 * Jellyfin → MPD translation: turn a Jellyfin item's file path into MPD's
 * relative path, and build the [LocalTrack] the shared [MpdRemote] transport
 * understands.
 *
 * Two identities ride on every queue entry, and keeping them straight is the
 * plan's flagged trap: `id` stays the Jellyfin item id (what session reporting
 * must name), while `scrobbleId`/`scrobbleProvider` carry the MPD path with the
 * `mpd` provider — which is what [com.engabd.sendpin.mpd.MpdRemote.file] guards
 * on when it addresses the queue. A track stamped the other way round is
 * addressed by nothing and reported against nothing.
 */
object JellyfinToMpd {

    /**
     * Strip [prefix] from a Jellyfin item's absolute file path to get MPD's
     * relative path. Null when the mapping is not configured, does not match, or
     * the remainder would escape upward — refusal is always better than a wrong
     * path, which MPD would answer with a queue that silently never plays.
     */
    fun toMpdPath(jfPath: String, prefix: String): String? {
        val p = prefix.trim()
        if (p.isEmpty()) return null
        val normalised = if (p.endsWith("/")) p else "$p/"
        val path = jfPath.trim()
        if (!path.startsWith(normalised)) return null
        val rest = path.removePrefix(normalised)
        // A mapping must leave a non-empty, non-escaping, relative path. Escapes are
        // judged per path segment — `foo/../../etc` must refuse, but a filename that
        // merely CONTAINS dots must not: "04 - how long is... cold.flac" is a real
        // track name (Orions Belte, Villa Amorini), and the old whole-string
        // `contains("..")` check dropped it from every queue it appeared in. A
        // dropped track shifts every index below it — tapping a row played the song
        // after the one you touched, the app's own off-by-one bug report.
        if (rest.isEmpty() || rest.startsWith("/") || rest.split('/').any { it == ".." }) return null
        return rest
    }

    /**
     * Build the queue entry: [LocalTrack.id] stays the Jellyfin item id (session
     * reporting keys on it), [LocalTrack.scrobbleId] becomes the MPD path with
     * `scrobbleProvider = "mpd"` — exactly the stamp [MpdRemote] requires.
     */
    fun toLocalTrack(item: MaItem, mpdPath: String): LocalTrack = LocalTrack(
        id = item.itemId,
        title = item.name,
        artist = item.subtitle,
        album = null,
        durationMs = (item.duration ?: 0) * 1000L,
        artUrl = item.image,
        scrobbleId = mpdPath,
        scrobbleProvider = "mpd",
        sourceQuality = item.audioFormat?.let {
            StreamQuality(
                codec = it.codec,
                sampleRateHz = it.sampleRate,
                bitDepth = it.bitDepth,
                bitrateKbps = it.bitRate,
                channels = item.audioFormat?.channels ?: 0,
                sizeBytes = item.audioFormat?.sizeBytes ?: 0,
            )
        },
    )
}
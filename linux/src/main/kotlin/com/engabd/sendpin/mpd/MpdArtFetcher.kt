package com.engabd.sendpin.mpd

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem

/**
 * Teaches the image loader to fetch an [MpdArt] cover (`mpd-art://cover/<id>`) from MPD,
 * ported from the Android app.
 *
 * A queue the panel did not build itself — one MPD was already playing when the panel
 * started, or one another client queued — carries these URLs, and without this every
 * one of its covers was the placeholder: on the station, the whole now-playing screen
 * after every restart until a new album was queued from the library.
 *
 * [client] is the panel's live MPD, read at fetch time so a reconnect is followed.
 */
class MpdArtFetcher(private val data: Uri, private val client: () -> MpdClient?) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val mpd = client() ?: return null
        val id = MpdArt.idFrom(data.toString()) ?: return null
        val file = if (MpdArt.isAlbumId(id)) mpd.anySongIn(id) else id
        val bytes = file?.let { mpd.coverArt(it) }
        // Null, not an exception: a song with no art is an ordinary song.
        if (bytes == null || bytes.isEmpty()) return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            // Let the decoder sniff it: `albumart` reports no type at all.
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val client: () -> MpdClient?) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == MpdArt.SCHEME) MpdArtFetcher(data, client) else null
    }
}

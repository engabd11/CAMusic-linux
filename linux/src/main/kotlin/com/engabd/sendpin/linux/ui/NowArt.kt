package com.engabd.sendpin.linux.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.linux.PanelModel
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The cover the panel is showing — held per album, not per track.
 *
 * Jellyfin names a track's cover after the *track* whenever the file carries its own
 * embedded art (`/Items/<trackId>/Images/Primary`), and after the album only when it
 * doesn't. With embedded FLAC art that is every track, so each skip inside one album
 * handed every cover on screen a new URL: the art dropped to its placeholder while it
 * loaded, the wash reloaded, the palette was re-extracted — the flash on every skip.
 *
 * [rememberNowArt] keys the cover on the album instead and holds the first URL it saw
 * for as long as the album keeps playing. A new album's cover is fetched into Coil's
 * cache *before* it is published, so whatever draws it (the cover, the wash, the
 * palette) has it ready on the first frame.
 */

/** The cover on screen: which album it belongs to ([key]) and the image ([url]). */
data class NowArt(val key: String?, val url: String?)

/** The held cover for the playing album, provided once by the shell. */
val LocalNowArt = compositionLocalOf { NowArt(null, null) }

/**
 * Which album [track] belongs to, for deciding whether a track change is an album change:
 * the library's album id when known, else the album's title, else the cover URL itself.
 * The track's *artist* is deliberately not part of it — on a compilation it changes on
 * every track while the record stays the same.
 */
internal fun albumKey(track: LocalTrack?, albumId: String?): String? {
    if (track == null) return null
    albumId?.trim()?.takeIf { it.isNotEmpty() }?.let { return "id:$it" }
    track.album?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return "album:$it" }
    return track.artUrl?.takeIf { it.isNotBlank() }?.let { "art:$it" }
}

/**
 * The cover to show next, given the one [held] and the playing track's album [key] and
 * cover [url]. Inside one album the held URL stays (unless [holdAlbum] is off); a new album,
 * or the held album finally offering a cover where it had none, takes the new URL.
 */
internal fun nextNowArt(held: NowArt, key: String?, url: String?, holdAlbum: Boolean): NowArt = when {
    key == null -> NowArt(null, url)
    holdAlbum && key == held.key && held.url != null -> held
    else -> NowArt(key, url)
}

/** How long a new cover may take to arrive before it is shown anyway (as a late load). */
private const val PRELOAD_TIMEOUT_MS = 4_000L

/**
 * The playing album's cover, held across the album's tracks and preloaded on a change —
 * see the file comment. Recomposes only when the *album* changes (or its cover does).
 */
@Composable
fun rememberNowArt(model: PanelModel): NowArt {
    val state by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val albumIds by model.albumIds.collectAsState()
    val hold by model.settings.artHoldAlbum.collectAsState(initial = true)
    val track = queue.getOrNull(state?.index?.takeIf { it >= 0 } ?: 0)
    val key = albumKey(track, track?.let { albumIds[it.id] })
    val url = track?.artUrl?.takeIf { it.isNotBlank() }

    val context = LocalPlatformContext.current
    var shown by remember { mutableStateOf(NowArt(null, null)) }
    LaunchedEffect(key, url, hold) {
        val next = nextNowArt(shown, key, url, hold)
        if (next == shown) return@LaunchedEffect
        val nextUrl = next.url
        if (nextUrl != null && nextUrl != shown.url) {
            // Into the same memory cache AsyncImage reads, so the cover is whole on its
            // first frame. Bounded: a slow server delays the change, never freezes it.
            withTimeoutOrNull(PRELOAD_TIMEOUT_MS) {
                runCatching {
                    SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(nextUrl).build())
                }
            }
        }
        shown = next
    }
    return shown
}

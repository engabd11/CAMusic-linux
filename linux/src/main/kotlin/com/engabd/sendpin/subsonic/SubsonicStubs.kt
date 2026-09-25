package com.engabd.sendpin.subsonic

import androidx.compose.runtime.Immutable
import com.engabd.sendpin.ma.MaItem

/**
 * Desktop stand-ins for the three data shapes `MusicSource` expects from
 * `subsonic/SubsonicClient.kt` — [AlbumInfo], [ArtistInfo] and [SavedQueue].
 *
 * The 965-line Subsonic client itself is not shared (the Linux panel browses
 * Jellyfin only, per the plan), but `MusicSource.kt` is shared and names these
 * types in its defaults and signatures, so the shapes must exist here. Field
 * order, defaults and semantics mirror SubsonicClient.kt verbatim; if the
 * originals change shape, this file must follow — that coupling is the price of
 * the shared interface, and the sync task keeps the source of truth visible.
 */

/** What `getAlbumInfo2` carries, beyond the notes the app was reading. */
data class AlbumInfo(
    val notes: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
)

/**
 * What `getArtistInfo2` actually carries. Only the biography was ever read, and the
 * call explicitly asked for zero similar artists.
 */
@Immutable
data class ArtistInfo(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val similar: List<MaItem> = emptyList(),
)

/**
 * The server-side queue snapshot [MusicSource.savedQueue] hands back, so playback
 * resumes where it left off on another device.
 *
 * @param changedBy the client name that saved it — worth showing, because "resume
 *   from your laptop" is a different offer from "resume from this phone".
 */
data class SavedQueue(
    val tracks: List<MaItem>,
    val index: Int,
    val positionMs: Long,
    val changedBy: String?,
)
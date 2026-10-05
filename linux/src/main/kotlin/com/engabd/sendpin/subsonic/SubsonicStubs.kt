package com.engabd.sendpin.subsonic

import androidx.compose.runtime.Immutable
import com.engabd.sendpin.ma.MaItem

/**
 * The three data shapes `MusicSource` names in its defaults and signatures —
 * [AlbumInfo], [ArtistInfo] and [SavedQueue]. The panel browses Jellyfin only, so
 * there is no Subsonic client behind them; they exist so the interface keeps its
 * shape for any source that wants to fill them.
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
package com.engabd.sendpin.library

/**
 * What a record's or an artist's own page can say beyond the browse fields: the notes or
 * biography, the label, where it came from, a backdrop. Every part is optional; a page
 * shows the parts a library has and leaves out the rest.
 */
data class ItemExtras(
    /** Album notes, or an artist's biography (Jellyfin's `Overview`). */
    val overview: String? = null,
    /** The record label (Jellyfin's `Studios`). */
    val label: String? = null,
    val genres: List<String> = emptyList(),
    /** `yyyy-mm-dd` of release, when the library knows the day and not only the year. */
    val released: String? = null,
    /** A wide picture of the artist (fanart), for the top of their page. */
    val backdropUrl: String? = null,
    /** The artist's logo, a transparent PNG, when the library has one. */
    val logoUrl: String? = null,
    val tags: List<String> = emptyList(),
    val musicBrainzId: String? = null,
    val rating: Double? = null,
)

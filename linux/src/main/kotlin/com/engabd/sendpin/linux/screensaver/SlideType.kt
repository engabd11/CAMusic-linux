package com.engabd.sendpin.linux.screensaver

/**
 * The lettering each album is set in — the screensaver's answer to Moonfin's title
 * logos. Films come with logo art; albums almost never do, so the panel sets the title
 * itself, in a face chosen to feel like the record: a classical recording in a light
 * Garamond, a jazz date in an art-deco line, a rock record in condensed capitals.
 *
 * Pure (genre strings, year, text, id in; a [Face] out) so the choice is tested
 * without drawing anything; `Screensaver.kt` maps a [Face] to its font and spacing.
 */
enum class Face {
    /** Cormorant Garamond, light — classical, soundtrack, ambient. */
    CLASSICAL,
    /** Poiret One — jazz, lounge, swing. */
    JAZZ,
    /** DM Serif Display — soul, R&B, blues, gospel. */
    SOUL,
    /** DM Serif Display italic — folk, country, singer-songwriter. */
    FOLK,
    /** Bebas Neue, tracked capitals — rock, metal, punk. */
    ROCK,
    /** Anton, capitals — hip-hop, rap. */
    HIPHOP,
    /** Space Mono — electronic, techno, house, experimental. */
    ELECTRONIC,
    /** Abril Fatface — disco, funk, latin, reggae. */
    RETRO,
    /** Italiana — pop, indie. */
    POP,
    /** The panel's own sans — for titles in scripts the display faces don't carry. */
    PLAIN,
}

/** Genre keywords per face, checked in this order: the first face any genre matches wins. */
private val GENRE_FACES: List<Pair<Face, List<String>>> = listOf(
    Face.HIPHOP to listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "grime", "drill"),
    Face.ELECTRONIC to listOf(
        "electronic", "electro", "techno", "house", "trance", "dance", "edm", "idm", "dubstep",
        "drum and bass", "drum & bass", "synth", "garage", "breakbeat", "experimental", "downtempo",
    ),
    Face.CLASSICAL to listOf(
        "classical", "opera", "baroque", "orchestral", "symphon", "soundtrack", "score",
        "chamber", "choral", "ambient", "new age", "piano", "instrumental",
    ),
    Face.JAZZ to listOf("jazz", "swing", "lounge", "bossa", "bebop", "big band", "easy listening"),
    Face.RETRO to listOf("disco", "funk", "latin", "reggae", "salsa", "afrobeat", "ska", "boogie"),
    Face.SOUL to listOf("soul", "r&b", "rnb", "rhythm and blues", "gospel", "blues", "motown"),
    Face.FOLK to listOf("folk", "country", "americana", "singer-songwriter", "acoustic", "bluegrass"),
    Face.ROCK to listOf("rock", "metal", "punk", "grunge", "hardcore", "emo", "alternative", "shoegaze"),
    Face.POP to listOf("pop", "indie", "dream"),
)

/** The fallbacks when no genre says anything: the calm faces, never the loud ones. */
private val QUIET_FACES = listOf(Face.POP, Face.SOUL, Face.CLASSICAL, Face.RETRO)

/**
 * The face for one album. Genres decide first; with none that match, the decade
 * leans the choice (older records to the serif faces) and the item id settles it, so
 * an album is always set the same way. Titles in a script the display faces lack
 * (Arabic, CJK, Cyrillic…) get [Face.PLAIN], which the system fonts back up cleanly,
 * rather than a display face with fallback glyphs patched in.
 */
fun faceFor(genres: List<String>, year: Int?, text: String, itemId: String): Face {
    if (!isLatin(text)) return Face.PLAIN
    val lower = genres.map { it.lowercase() }
    for ((face, keys) in GENRE_FACES) {
        if (lower.any { g -> keys.any { k -> g.contains(k) } }) return face
    }
    val h = itemId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    if (year != null && year < 1975) return if (h % 2 == 0) Face.SOUL else Face.RETRO
    return QUIET_FACES[h % QUIET_FACES.size]
}

/** Whether every letter of [text] is Latin — digits, punctuation and spaces are neutral. */
internal fun isLatin(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN,
            Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED -> Unit
            else -> return false
        }
        i += Character.charCount(cp)
    }
    return true
}

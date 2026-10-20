package com.engabd.sendpin.linux

import com.engabd.sendpin.linux.ui.pressScale
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import com.engabd.sendpin.library.ItemExtras
import com.engabd.sendpin.linux.ui.LocalCoverScope
import com.engabd.sendpin.linux.ui.Shelf
import com.engabd.sendpin.ui.design.LocalPalette
import kotlinx.coroutines.coroutineScope
import androidx.compose.material.icons.filled.Radio
import androidx.compose.animation.core.tween
import com.engabd.sendpin.linux.ui.LocalSharedCovers
import com.engabd.sendpin.linux.ui.Motion
import com.engabd.sendpin.linux.ui.TILE_PX
import com.engabd.sendpin.linux.ui.riseIn
import com.engabd.sendpin.linux.ui.sizedArt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.linux.station.LocalTrackMenu
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.CircleButton
import com.engabd.sendpin.linux.ui.EmptyState
import com.engabd.sendpin.linux.ui.Eyebrow
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.linux.ui.PlayingBars
import com.engabd.sendpin.linux.ui.SectionHeader
import com.engabd.sendpin.linux.ui.SkeletonBlock
import com.engabd.sendpin.linux.ui.formatTotal
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.MonoFont
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/*
 * The library's detail pages — album, artist, playlist, genre. Each opens on a hero:
 * its own artwork blurred behind a large cover, the title at display size, and the
 * play actions, all in *that record's* colours (its own palette, not the one playing),
 * so opening an album feels like walking into it.
 */

/** What leads a track row: its number on an album page, its cover everywhere else. */
internal enum class TrackLead { Number, Art }

/**
 * Tracks grouped by disc, in play order, with the disc number as the group key — a
 * one-disc album is one group, which callers read as "no disc headers". Pure so the
 * grouping is testable; the index in each pair is the track's position in [tracks],
 * which is what a tap must hand to [PanelModel.playAlbum].
 */
internal fun discGroups(tracks: List<MaItem>): List<Pair<Int, List<Pair<Int, MaItem>>>> =
    tracks.withIndex()
        .groupBy { it.value.discNumber ?: 1 }
        .toSortedMap()
        .map { (disc, rows) -> disc to rows.map { it.index to it.value } }

/** "2019 · 12 songs · 48 min" — the facts line under a title; missing parts drop out. */
internal fun albumFacts(year: Int?, tracks: List<MaItem>): String {
    val parts = mutableListOf<String>()
    year?.let { parts += it.toString() }
    if (tracks.isNotEmpty()) {
        parts += if (tracks.size == 1) "1 song" else "${tracks.size} songs"
        val totalMs = tracks.sumOf { (it.duration ?: 0).toLong() } * 1000
        if (totalMs > 0) parts += formatTotal(totalMs)
    }
    return parts.joinToString("  ·  ")
}

/** Wrap a page in [image]'s own palette, so its buttons and marks take its colours. */
@Composable
private fun InItsColours(image: String?, content: @Composable () -> Unit) {
    val palette = rememberAlbumPalette(image)
    CompositionLocalProvider(LocalPalette provides palette, LocalAccent provides palette.accent) {
        content()
    }
}

@Composable
internal fun AlbumScreen(
    album: MaItem,
    source: MusicSource,
    now: NowPlayingMark,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    back: () -> Unit,
) {
    var header by remember(album.itemId) { mutableStateOf(album) }
    var tracks by remember(album.itemId) { mutableStateOf<List<MaItem>?>(null) }
    var error by remember(album.itemId) { mutableStateOf<String?>(null) }

    var extras by remember(album.itemId) { mutableStateOf<ItemExtras?>(null) }
    var moreBy by remember(album.itemId) { mutableStateOf<List<MaItem>>(emptyList()) }
    var related by remember(album.itemId) { mutableStateOf<List<MaItem>>(emptyList()) }

    LaunchedEffect(album.itemId) {
        try {
            val (full, t) = source.albumDetail(album.itemId)
            if (full != null) header = full
            tracks = t
        } catch (e: Exception) {
            error = e.message ?: "Album failed to load"
        }
        // The page's second wave, each on its own so a slow one holds up nothing.
        coroutineScope {
            launch { extras = runCatching { source.itemExtras(album.itemId) }.getOrNull() }
            launch { related = runCatching { source.similarItems(album.itemId, 12) }.getOrDefault(emptyList()) }
            launch {
                val artistId = header.artistId ?: return@launch
                moreBy = runCatching { source.artistDetail(artistId).second }.getOrDefault(emptyList())
                    .filter { it.itemId != album.itemId }
            }
        }
    }

    InItsColours(header.image) {
        DetailPage(
            header = header,
            eyebrow = "Album",
            tracks = tracks,
            error = error,
            lead = TrackLead.Number,
            now = now,
            source = source,
            play = play,
            back = back,
            extras = extras,
            moreBy = moreBy,
            related = related,
            open = open,
        )
    }
}

/** A playlist's or a genre's songs — an album page with each row carrying its own cover. */
@Composable
internal fun TrackListScreen(
    header: MaItem,
    eyebrow: String,
    now: NowPlayingMark,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    back: () -> Unit,
    load: suspend () -> List<MaItem>,
) {
    var tracks by remember(header.itemId) { mutableStateOf<List<MaItem>?>(null) }
    var error by remember(header.itemId) { mutableStateOf<String?>(null) }
    LaunchedEffect(header.itemId) {
        try { tracks = load() } catch (e: Exception) { error = e.message ?: "Failed to load" }
    }
    // A playlist rarely has art of its own; its first song's cover stands in, so the
    // hero is never an empty square.
    val art = header.image ?: tracks?.firstNotNullOfOrNull { it.image }
    InItsColours(art) {
        DetailPage(
            header = header.copy(image = art),
            eyebrow = eyebrow,
            tracks = tracks,
            error = error,
            lead = TrackLead.Art,
            now = now,
            source = null,
            play = play,
            back = back,
            glyph = if (eyebrow == "Genre") Icons.Default.Category else Icons.AutoMirrored.Filled.PlaylistPlay,
        )
    }
}

@Composable
private fun DetailPage(
    header: MaItem,
    eyebrow: String,
    tracks: List<MaItem>?,
    error: String?,
    lead: TrackLead,
    now: NowPlayingMark,
    source: MusicSource?,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    back: () -> Unit,
    glyph: ImageVector = Icons.Default.MusicNote,
    extras: ItemExtras? = null,
    moreBy: List<MaItem> = emptyList(),
    related: List<MaItem> = emptyList(),
    open: ((LibraryRoute) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val palette = LocalPalette.current
    val isAlbum = header.mediaType == "album"
    // This record is the one playing: its disc turns.
    val spinning = now.playing && now.id != null && tracks.orEmpty().any { it.itemId == now.id }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier.fillMaxSize().touchDragScroll(listState),
        ) {
            item(key = "hero") {
                DetailHero(
                    item = header,
                    eyebrow = eyebrow,
                    facts = albumFacts(header.year, tracks.orEmpty()),
                    back = back,
                    glyph = glyph,
                    onPlay = tracks?.takeIf { it.isNotEmpty() }?.let { t -> { play(header, t, 0) } },
                    onShuffle = tracks?.takeIf { it.size > 1 }?.let { t -> { play(header, t.shuffled(), 0) } },
                    favourite = source?.takeIf { it.has(Capability.FAVORITES) },
                    onRadio = LocalStartRadio.current?.takeIf { source != null && header.mediaType == "album" }?.let { r -> { r(header) } },
                    scrolled = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2_000f },
                    sleeve = isAlbum,
                    spinning = spinning,
                    onArtist = header.artistId?.takeIf { open != null }?.let { id ->
                        {
                            open?.invoke(
                                LibraryRoute.Artist(
                                    MaItem(
                                        itemId = id, provider = header.provider, name = header.subtitle.orEmpty(),
                                        uri = id, mediaType = "artist", subtitle = null, image = null, duration = null,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
            when {
                error != null -> item(key = "error") {
                    EmptyState(Icons.Default.ErrorOutline, "Couldn't load this", error)
                }
                tracks == null -> item(key = "loading") { TrackSkeleton() }
                tracks.isEmpty() -> item(key = "empty") {
                    EmptyState(glyph, "Nothing here", "This has no songs to play.")
                }
                else -> {
                    val groups = discGroups(tracks)
                    groups.forEach { (disc, rows) ->
                        if (groups.size > 1) {
                            item(key = "disc-$disc") {
                                val title = header.discTitles[disc]
                                SectionHeader(if (title != null) "Disc $disc · $title" else "Disc $disc", Modifier.padding(top = 12.dp))
                            }
                        }
                        rows.forEach { (i, t) ->
                            item(key = "t-$i-${t.itemId}") {
                                TrackRow(
                                    track = t,
                                    lead = lead,
                                    index = i,
                                    number = t.trackNumber ?: (i + 1),
                                    now = now,
                                    // An album's own artist on every row is noise;
                                    // a guest artist is worth the line.
                                    showArtist = lead == TrackLead.Art || (t.subtitle != null && t.subtitle != header.subtitle),
                                    onClick = { play(header, tracks, i) },
                                )
                            }
                        }
                    }
                }
            }
            if (isAlbum && error == null) albumShelves(header, tracks, extras, moreBy, related, palette.accent, open)
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** The shelves under a record's tracks: about it, more by its artist, and what is near it. */
private fun androidx.compose.foundation.lazy.LazyListScope.albumShelves(
    header: MaItem,
    tracks: List<MaItem>?,
    extras: ItemExtras?,
    moreBy: List<MaItem>,
    related: List<MaItem>,
    accent: Color,
    open: ((LibraryRoute) -> Unit)?,
) {
    val facts = albumFactRows(header.year, extras?.released, extras?.label, extras?.genres?.ifEmpty { null } ?: header.genres, tracks.orEmpty(), extras?.rating)
    if (!extras?.overview.isNullOrBlank() || facts.isNotEmpty()) {
        item(key = "about") { Box(Modifier.padding(top = 24.dp)) { AboutCard("About this album", extras?.overview, facts, accent) } }
    }
    if (open != null && moreBy.isNotEmpty()) item(key = "moreBy") {
        CompositionLocalProvider(LocalCoverScope provides "moreBy") {
            Shelf("More by ${header.subtitle ?: "this artist"}", moreBy, { open(LibraryRoute.Album(it)) }, Modifier.padding(top = 20.dp))
        }
    }
    if (open != null && related.isNotEmpty()) item(key = "related") {
        CompositionLocalProvider(LocalCoverScope provides "related") {
            Shelf("You might also like", related, { open(LibraryRoute.Album(it)) }, Modifier.padding(top = 20.dp))
        }
    }
}

/**
 * The hero: the record's art blown up and blurred behind a big cover, the eyebrow and
 * title beside it, the facts line, and the actions — Play (accent), Shuffle, Favourite.
 */
@Composable
private fun DetailHero(
    item: MaItem,
    eyebrow: String,
    facts: String,
    back: () -> Unit,
    glyph: ImageVector,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    favourite: MusicSource?,
    coverSize: Dp = 288.dp,
    circular: Boolean = false,
    onRadio: (() -> Unit)? = null,
    /** How far the page has scrolled past the hero's top, for the backdrop's parallax. */
    scrolled: () -> Float = { 0f },
    /** An album: the cover as a sleeve with its record slid out. */
    sleeve: Boolean = false,
    spinning: Boolean = false,
    onArtist: (() -> Unit)? = null,
) {
    val accent = rememberAccent()
    val scope = rememberCoroutineScope()
    val covers = LocalSharedCovers.current
    Box(Modifier.fillMaxWidth()) {
        HeroBackdrop(item.image, scrolled)
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Row(Modifier.padding(start = PageGutter, top = 20.dp, bottom = 16.dp)) {
                CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = back)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PageGutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                val shape = if (circular) CircleShape else PanelShapes.Tile
                if (sleeve) RecordSleeve(
                    image = item.image, name = item.name, palette = LocalPalette.current, spinning = spinning,
                    cover = 250.dp, sharedKey = covers?.keyFor(item.itemId),
                ) else ArtImage(
                    url = item.image,
                    name = item.name,
                    shape = shape,
                    glyph = glyph,
                    // Grown from the tile that was tapped; that tile's smaller copy shows
                    // until the full-size cover has loaded, so the move never flashes blank.
                    sharedKey = covers?.keyFor(item.itemId),
                    placeholderUrl = sizedArt(item.image, TILE_PX),
                    modifier = Modifier
                        .size(coverSize)
                        .shadow(32.dp, shape, ambientColor = accent, spotColor = accent),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(eyebrow, accent)
                    Text(
                        item.name,
                        color = TextPrimary,
                        fontFamily = AppFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        lineHeight = 39.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = if (onArtist != null) accent else TextSecondary,
                            fontFamily = AppFont, fontWeight = if (onArtist != null) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = if (onArtist != null) Modifier.clickable(onClick = onArtist) else Modifier,
                        )
                    }
                    if (facts.isNotBlank()) {
                        Text(facts, color = TextMuted, fontFamily = AppFont, fontSize = 15.sp, maxLines = 2)
                    }
                    if (sleeve) PaletteDots(LocalPalette.current, Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(
                            "Play",
                            icon = Icons.Default.PlayArrow,
                            onClick = { onPlay?.invoke() },
                            modifier = Modifier.alpha(if (onPlay != null) 1f else 0.4f),
                        )
                        if (onShuffle != null) CircleButton(Icons.Default.Shuffle, "Shuffle", onClick = onShuffle)
                        if (onRadio != null) CircleButton(Icons.Default.Radio, "Start radio", onClick = onRadio)
                        if (favourite != null) {
                            var fav by remember(item.itemId) { mutableStateOf(item.favorite) }
                            CircleButton(
                                if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                if (fav) "Remove from favourites" else "Add to favourites",
                                tint = if (fav) accent else null,
                                onClick = {
                                    val next = !fav
                                    fav = next
                                    scope.launch {
                                        // A refused write puts the heart back rather than
                                        // leaving it claiming a favourite that isn't.
                                        if (runCatching { favourite.setStarred(item, next) }.isFailure) fav = !next
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The hero's backdrop: the art, huge and blurred, fading out into the page below. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HeroBackdrop(image: String?, scrolled: () -> Float) {
    if (image.isNullOrBlank()) return
    // Masked to fade out, not scrimmed to a colour: the page's own ambience lies under
    // the hero, so darkening its foot to ink would draw a hard band where it ends.
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                // Drifts at under half the page's pace, so the hero has depth as it scrolls away.
                .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f; translationY = scrolled() * 0.45f }
                .blur(56.dp, BlurredEdgeTreatment.Unbounded)
                .alpha(0.6f),
        )
    }
}

@Composable
private fun TrackSkeleton() {
    Column(
        Modifier.padding(horizontal = PageGutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(8) { SkeletonBlock(Modifier.fillMaxWidth().height(56.dp), PanelShapes.Thumb) }
    }
}

// ─── Artist ──────────────────────────────────────────────────────────────────

@Composable
internal fun ArtistScreen(
    artist: MaItem,
    source: MusicSource,
    now: NowPlayingMark,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    back: () -> Unit,
) {
    var header by remember(artist.itemId) { mutableStateOf(artist) }
    var albums by remember(artist.itemId) { mutableStateOf<List<MaItem>?>(null) }
    var popular by remember(artist.itemId) { mutableStateOf<List<MaItem>>(emptyList()) }
    var error by remember(artist.itemId) { mutableStateOf<String?>(null) }
    var extras by remember(artist.itemId) { mutableStateOf<ItemExtras?>(null) }
    var similar by remember(artist.itemId) { mutableStateOf<List<MaItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(artist.itemId) {
        coroutineScope {
            launch { extras = runCatching { source.itemExtras(artist.itemId) }.getOrNull() }
            launch { similar = runCatching { source.similarItems(artist.itemId, 14) }.getOrDefault(emptyList()) }
            try {
                val (full, a) = source.artistDetail(artist.itemId)
                if (full != null) header = full
                albums = a
            } catch (e: Exception) {
                error = e.message ?: "Artist failed to load"
            }
            popular = runCatching { source.topSongs(header.name, 5) }.getOrDefault(emptyList())
        }
    }

    // "Play" on an artist is every song under them, in album order; "Shuffle" the same, mixed.
    fun playAll(shuffle: Boolean) {
        scope.launch {
            val all = runCatching { source.tracksUnder(header) }.getOrDefault(emptyList())
            if (all.isNotEmpty()) play(header, if (shuffle) all.shuffled() else all, 0)
        }
    }

    InItsColours(header.image ?: albums?.firstNotNullOfOrNull { it.image }) {
        val gridState = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
            ) {
                val backdrop = extras?.backdropUrl
                val scrolled = { if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset.toFloat() else 2_000f }
                if (backdrop != null) fullSpan("backdrop") {
                    Box {
                        ArtistBackdrop(backdrop, extras?.logoUrl, header.name, artistFacts(albums.orEmpty(), extras?.genres.orEmpty()), scrolled)
                        Row(Modifier.padding(start = PageGutter, top = 20.dp)) {
                            CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = back)
                        }
                    }
                }
                if (backdrop != null) fullSpan("actions") {
                    Row(
                        Modifier.padding(horizontal = PageGutter, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillButton("Play", icon = Icons.Default.PlayArrow, onClick = { playAll(shuffle = false) })
                        CircleButton(Icons.Default.Shuffle, "Shuffle", onClick = { playAll(shuffle = true) })
                        LocalStartRadio.current?.let { r -> CircleButton(Icons.Default.Radio, "Start radio", onClick = { r(header) }) }
                    }
                } else fullSpan("hero") {
                    val count = albums?.size
                    DetailHero(
                        item = header.copy(subtitle = null),
                        eyebrow = "Artist",
                        facts = albums?.let { artistFacts(it, extras?.genres.orEmpty()) } ?: (count?.let { if (it == 1) "1 album" else "$it albums" } ?: ""),
                        back = back,
                        glyph = Icons.Default.Person,
                        onPlay = { playAll(shuffle = false) },
                        onShuffle = { playAll(shuffle = true) },
                        favourite = source.takeIf { it.has(Capability.FAVORITES) },
                        coverSize = 260.dp,
                        circular = true,
                        onRadio = LocalStartRadio.current?.let { r -> { r(header) } },
                        scrolled = scrolled,
                    )
                }
                albums?.let(::latestOf)?.takeIf { (albums?.size ?: 0) > 1 }?.let { latest ->
                    fullSpan("latest") { LatestReleaseCard(latest) { open(LibraryRoute.Album(latest)) } }
                }
                if (popular.isNotEmpty()) {
                    fullSpan("popularHdr") { SectionHeader("Popular") }
                    fullSpan("popular") {
                        Column {
                            popular.forEachIndexed { i, t ->
                                TrackRow(track = t, lead = TrackLead.Art, number = i + 1, now = now, onClick = { play(t, popular, i) })
                            }
                        }
                    }
                }
                val a = albums
                when {
                    error != null -> fullSpan("error") { EmptyState(Icons.Default.ErrorOutline, "Couldn't load this artist", error!!) }
                    a == null -> fullSpan("loading") { TrackSkeleton() }
                    a.isNotEmpty() -> {
                        if (a.size > 2) fullSpan("timeline") { TimelineShelf(a, LocalPalette.current.accent) { open(LibraryRoute.Album(it)) } }
                        fullSpan("albumsHdr") { SectionHeader("Albums") }
                        tileRows("albums", a) { open(LibraryRoute.Album(it)) }
                    }
                }
                extras?.overview?.takeIf { it.isNotBlank() }?.let { bio ->
                    fullSpan("bio") { AboutCard("About ${header.name}", bio, emptyList(), LocalPalette.current.accent) }
                }
                if (similar.isNotEmpty()) fullSpan("similar") {
                    CompositionLocalProvider(LocalCoverScope provides "similar") {
                        Shelf("Similar artists", similar, { open(LibraryRoute.Artist(it)) }, Modifier.padding(top = 12.dp), circular = true, tileWidth = 150.dp)
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(gridState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

// ─── Rows ────────────────────────────────────────────────────────────────────

/**
 * One song: its number or cover, title and artist, and duration. The playing one is
 * lit — an accent wash behind the row, the title in the accent, and the dancing bars
 * in place of its number.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    track: MaItem,
    lead: TrackLead,
    now: NowPlayingMark,
    onClick: () -> Unit,
    number: Int = 0,
    showArtist: Boolean = true,
    /** Its place on a freshly opened page, for the rise-in; -1 for none. */
    index: Int = -1,
) {
    val accent = rememberAccent()
    val isNow = now.id != null && now.id == track.itemId
    val menu = LocalTrackMenu.current
    // The playing row's wash fades in and out rather than switching.
    val wash by androidx.compose.animation.animateColorAsState(
        if (isNow) accent.a(0.14f) else Color.Transparent, tween(Motion.PAGE_MS), label = "rowWash",
    )
    Row(
        Modifier
            .riseIn(index, enabled = index >= 0)
            .pressScale(0.985f)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 72.dp)
            .clip(PanelShapes.Thumb)
            .background(wash)
            // Held, a song offers its menu (Settings → Row menus); otherwise a plain tap target.
            .then(
                if (menu != null) Modifier.combinedClickable(onClick = onClick, onLongClick = { menu(track) })
                else Modifier.clickable(onClick = onClick),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (lead) {
            TrackLead.Number -> Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (isNow) PlayingBars(accent, now.playing)
                else Text("$number", color = TextMuted, fontFamily = MonoFont, fontSize = 16.sp)
            }
            TrackLead.Art -> Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                ArtImage(track.image, track.name, Modifier.size(56.dp), shape = PanelShapes.Thumb)
                if (isNow) {
                    Box(Modifier.size(56.dp).clip(PanelShapes.Thumb).background(Color.Black.a(0.45f)))
                    PlayingBars(accent, now.playing)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.name,
                color = if (isNow) accent else TextPrimary,
                fontFamily = AppFont,
                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                fontSize = PanelMetrics.RowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArtist && !track.subtitle.isNullOrBlank()) {
                Text(
                    track.subtitle,
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = PanelMetrics.RowSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.duration?.takeIf { it > 0 }?.let {
            Text(formatMs(it * 1000L), color = TextMuted, fontFamily = MonoFont, fontSize = 15.sp)
        }
    }
}

/** Starts a radio from an album, an artist or a song; null where no radio can start (the previews). */
internal val LocalStartRadio = androidx.compose.runtime.staticCompositionLocalOf<((MaItem) -> Unit)?> { null }

package com.engabd.sendpin.linux

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import com.engabd.sendpin.linux.ui.OnScreenKeyboard
import com.engabd.sendpin.linux.ui.OnScreenKeyboardKey
import com.engabd.sendpin.linux.ui.applyKey
import com.engabd.sendpin.linux.ui.touchDragScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Glass
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink2
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The library pane's root category — the plan's items 7 ("Albums/Artists toggle") and 10
 * ("more browse roots") collapse into this one selector rather than being built twice: a
 * browse-root row already *is* the artists entry point, so a separate two-way toggle would
 * just be the same control built a second time and then deleted.
 */
internal enum class LibraryCategory(val label: String) {
    RECENT("Recently added"),
    ALBUMS("All albums"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    GENRES("Genres"),
    FAVORITES("Favourites"),
}

/**
 * The library pane: Jellyfin browse — a category row over albums grid / all albums /
 * artists / playlists / genres / favourites, album detail with tracks, artist detail
 * (via [ArtistDetailView], previously written but never called from anywhere), and
 * search. Queueing hands tracks to MPD via [PanelModel.playAlbum] (Jellyfin file paths →
 * MPD relative paths).
 *
 * The grid is LibraryScreen's proportion system at desktop scale: 6-column base,
 * covers spanning 2, 12dp gaps, 20dp edges.
 */
@Composable
fun LibraryPane(modifier: Modifier, model: PanelModel) {
    val sourceFlow by model.source.collectAsState()
    val source = sourceFlow ?: return NotConnected(modifier)
    val scope = rememberCoroutineScope()

    // Root shows the current category's items; an open album, playlist, genre or artist
    // replaces it; search overlays.
    var category by remember { mutableStateOf(LibraryCategory.RECENT) }
    var rootItems by remember { mutableStateOf<List<MaItem>>(emptyList()) }
    var favorites by remember { mutableStateOf<MaSearchResults?>(null) }
    // AlbumDetailView only needs a "header" MaItem and a track list to play through
    // PanelModel.playAlbum (which reads the tracks, not the header) — so a playlist's or a
    // genre's tracks reuse it directly rather than a near-identical view being written
    // twice more.
    var albumDetail by remember { mutableStateOf<Pair<MaItem, List<MaItem>>?>(null) }
    var artistDetail by remember { mutableStateOf<Pair<MaItem, List<MaItem>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<MaSearchResults?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Root load: re-fetches whenever the category changes. A cold fetch used to show an
    // empty pane with nothing to say why — `loading` drives a skeleton instead (item 11).
    LaunchedEffect(source, category) {
        loading = true
        loadError = null
        try {
            when (category) {
                LibraryCategory.RECENT -> rootItems = source.recentlyAdded(200)
                LibraryCategory.ALBUMS -> rootItems = source.albums(0, 200)
                LibraryCategory.ARTISTS -> rootItems = source.artists()
                LibraryCategory.PLAYLISTS -> rootItems = source.playlists()
                LibraryCategory.GENRES -> rootItems = source.genres()
                LibraryCategory.FAVORITES -> favorites = source.favorites()
            }
        } catch (e: Exception) {
            loadError = e.message ?: "browse failed"
        }
        loading = false
    }

    // Search debounce: 350 ms after the last keystroke, then ask the source.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(350)
        try {
            results = source.search(query)
        } catch (e: Exception) {
            loadError = e.message
        }
    }

    // Jellyfin (or MPD, for a genre/playlist pulled through it) returning nothing for an id
    // that was clickable a moment ago — deleted between the grid loading and the tap — used
    // to hit a `!!` here. On this undecorated, WM-less panel that throw took the whole
    // screen to black with no crash dialog and no way back; every drill-in below routes the
    // null case to loadError instead.
    fun openAlbum(id: String) {
        scope.launch {
            try {
                val (album, tracks) = source.albumDetail(id)
                if (album != null) albumDetail = album to tracks else loadError = "Album not found"
            } catch (e: Exception) {
                loadError = e.message
            }
        }
    }
    fun openArtist(id: String) {
        scope.launch {
            try {
                val (artist, albums) = source.artistDetail(id)
                if (artist != null) artistDetail = artist to albums else loadError = "Artist not found"
            } catch (e: Exception) {
                loadError = e.message
            }
        }
    }
    fun openPlaylist(playlist: MaItem) {
        scope.launch {
            try {
                albumDetail = playlist to source.playlistTracks(playlist.itemId)
            } catch (e: Exception) {
                loadError = e.message
            }
        }
    }
    fun openGenre(genre: MaItem) {
        scope.launch {
            try {
                albumDetail = genre to source.songsByGenre(genre.name)
            } catch (e: Exception) {
                loadError = e.message
            }
        }
    }

    Box(modifier) {
        when {
            albumDetail != null -> AlbumDetailView(
                album = albumDetail!!.first,
                tracks = albumDetail!!.second,
                onBack = { albumDetail = null },
                onPlay = { start -> scope.launch { model.playAlbum(albumDetail!!.first, albumDetail!!.second, start) } },
            )
            artistDetail != null -> ArtistDetailView(
                artist = artistDetail!!.first,
                albums = artistDetail!!.second,
                onBack = { artistDetail = null },
                onAlbum = { a -> openAlbum(a.itemId) },
            )
            searching -> SearchView(
                query = query,
                onQuery = { query = it },
                results = results,
                onAlbum = { a -> openAlbum(a.itemId) },
                onArtist = { a -> openArtist(a.itemId) },
                onTrack = { t -> scope.launch { model.playAlbum(t, listOf(t), 0) } },
                onBack = { searching = false; query = ""; results = null },
            )
            else -> LibraryRoot(
                category = category,
                onCategory = { category = it },
                items = rootItems,
                favorites = favorites,
                loading = loading,
                error = loadError,
                onAlbum = { a -> openAlbum(a.itemId) },
                onArtist = { a -> openArtist(a.itemId) },
                onPlaylist = ::openPlaylist,
                onGenre = ::openGenre,
                onSearch = { searching = true },
            )
        }
    }
}

@Composable
private fun LibraryRoot(
    category: LibraryCategory,
    onCategory: (LibraryCategory) -> Unit,
    items: List<MaItem>,
    favorites: MaSearchResults?,
    loading: Boolean,
    error: String?,
    onAlbum: (MaItem) -> Unit,
    onArtist: (MaItem) -> Unit,
    onPlaylist: (MaItem) -> Unit,
    onGenre: (MaItem) -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                category.label,
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, "Search", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LibraryCategory.entries.forEach { c ->
                val active = c == category
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (active) Glass else Color.Transparent)
                        .clickable { onCategory(c) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        c.label,
                        color = if (active) TextPrimary else TextMuted,
                        fontFamily = AppFont,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (error != null) {
            Text(
                error,
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        when {
            loading -> LoadingGrid()
            category == LibraryCategory.FAVORITES -> FavoritesView(favorites, onAlbum, onArtist)
            else -> {
                val onClick: (MaItem) -> Unit = when (category) {
                    LibraryCategory.RECENT, LibraryCategory.ALBUMS -> onAlbum
                    LibraryCategory.ARTISTS -> onArtist
                    LibraryCategory.PLAYLISTS -> onPlaylist
                    LibraryCategory.GENRES -> onGenre
                    LibraryCategory.FAVORITES -> onAlbum // unreachable, handled above
                }
                ItemGrid(items, onClick)
            }
        }
    }
}

@Composable
private fun ItemGrid(items: List<MaItem>, onClick: (MaItem) -> Unit) {
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
        ) {
            items(items, span = { GridItemSpan(2) }, key = { it.itemId }) { item ->
                AlbumTile(item, onClick)
            }
        }
        // The only sign a touch panel gets that a grid continues below the fold —
        // there is no wheel and no cursor to hint at it otherwise.
        VerticalScrollbar(
            rememberScrollbarAdapter(gridState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * A cold fetch of 200 albums used to show a blank pane with nothing on it to say whether
 * that was "empty library" or "still loading" — this is that difference made visible.
 * Shape copied from `:app`'s `ui/screens/LibraryStates.kt` `SkeletonRow`/`ConnectingState`
 * (not the file itself — it depends on Android-only types this module doesn't have).
 */
@Composable
private fun LoadingGrid() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .glassSurface(RoundedCornerShape(12.dp), Glass, border = Hairline),
                    )
                }
            }
        }
    }
}

/**
 * Favourites returns artists/albums/tracks/playlists together ([MaSearchResults]) rather
 * than one flat list, so it can't share [ItemGrid] — item 10 asked for its albums and
 * artists specifically. Each section is its own horizontally-scrolling shelf inside one
 * vertically-scrolling column, which sidesteps the nested-vertical-scrollable problem a
 * vertical grid-of-grids would have (and, not needing a keyed header inside a single
 * `LazyVerticalGrid`, sidesteps the duplicate-section-title crash `:app` documents for that
 * pattern too — see [SearchView] for where that trap is real).
 */
@Composable
private fun FavoritesView(results: MaSearchResults?, onAlbum: (MaItem) -> Unit, onArtist: (MaItem) -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .touchDragScroll(scrollState, Orientation.Vertical)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val albums = results?.albums ?: emptyList()
        val artists = results?.artists ?: emptyList()
        if (results != null && albums.isEmpty() && artists.isEmpty()) {
            Text(
                "No favourites yet.",
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (albums.isNotEmpty()) ItemShelf("Albums", albums, onAlbum)
        if (artists.isNotEmpty()) ItemShelf("Artists", artists, onArtist)
    }
}

/** One titled, horizontally-scrolling row of tiles — a "shelf". */
@Composable
private fun ItemShelf(title: String, items: List<MaItem>, onClick: (MaItem) -> Unit) {
    Column {
        Text(
            title,
            color = TextPrimary,
            fontFamily = AppFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().touchDragScroll(rowState, Orientation.Horizontal),
        ) {
            items(items, key = { it.itemId }) { item ->
                Box(Modifier.width(140.dp)) { AlbumTile(item, onClick) }
            }
        }
    }
}

@Composable
private fun AlbumTile(album: MaItem, onClick: (MaItem) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(album) },
    ) {
        AsyncImage(
            model = album.image,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .glassSurface(RoundedCornerShape(12.dp), Glass, border = Hairline),
        )
        Text(
            album.name,
            color = TextPrimary,
            fontFamily = AppFont,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            album.subtitle ?: "",
            color = TextMuted,
            fontFamily = AppFont,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AlbumDetailView(
    album: MaItem,
    tracks: List<MaItem>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            AsyncImage(
                model = album.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Glass),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    album.name,
                    color = TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    album.subtitle ?: "",
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = { onPlay(0) },
                modifier = Modifier.padding(end = 16.dp),
            ) {
                Text("Play album", fontFamily = AppFont)
            }
        }
        val trackState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = trackState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize().touchDragScroll(trackState),
            ) {
                itemsIndexed(tracks) { i, t ->
                    TrackRow(
                        n = i + 1,
                        title = t.name,
                        artist = t.subtitle,
                        duration = t.duration,
                        onClick = { onPlay(i) },
                    )
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(trackState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ArtistDetailView(
    artist: MaItem,
    albums: List<MaItem>,
    onBack: () -> Unit,
    onAlbum: (MaItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            Text(
                artist.name,
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
        }
        val gridState = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
            ) {
                items(albums, span = { GridItemSpan(2) }, key = { it.itemId }) { album -> AlbumTile(album, onAlbum) }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(gridState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SearchView(
    query: String,
    onQuery: (String) -> Unit,
    results: MaSearchResults?,
    onAlbum: (MaItem) -> Unit,
    onArtist: (MaItem) -> Unit,
    onTrack: (MaItem) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search the library", fontFamily = AppFont) },
                singleLine = true,
                colors = TextFieldDefaults.colors(),
                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
            )
        }
        if (results != null) {
            // The results shrink to fit above the keyboard rather than sliding under it —
            // weight(1f) here leaves the keyboard below its own fixed size.
            SearchResultsSections(
                results = results,
                onAlbum = onAlbum,
                onArtist = onArtist,
                onTrack = onTrack,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        // This panel is touch-only with no window manager and no way to run a system
        // on-screen keyboard (see OnScreenKeyboard.kt's doc comment for why that was tried
        // and abandoned) — so search draws its own keyboard, directly below the results,
        // only while this pane is showing.
        OnScreenKeyboard(
            onKey = { c -> onQuery(applyKey(query, OnScreenKeyboardKey.Char(c))) },
            onBackspace = { onQuery(applyKey(query, OnScreenKeyboardKey.Backspace)) },
            onClear = { onQuery(applyKey(query, OnScreenKeyboardKey.Clear)) },
            onDone = onBack,
        )
    }
}

/**
 * Search results as titled sections — artists, albums, tracks — instead of the old
 * albums-only rendering that dropped the other two entirely (item 8).
 *
 * Deliberately *not* a single `LazyVerticalGrid` keyed on a per-section header item: `:app`
 * documents that pattern crashing on a duplicate key when two sections share a title, and
 * the fix here is simpler than deduping — don't give the headers grid keys at all. This is
 * one scrolling `Column` (shelves for artists/albums, plain rows for tracks, all un-lazy),
 * which also sidesteps a `LazyColumn`-inside-a-scrolling-`Column` nesting problem. Search
 * result counts are server-capped, not "every track in the library", so skipping laziness
 * here costs nothing in practice.
 */
@Composable
private fun SearchResultsSections(
    results: MaSearchResults,
    onAlbum: (MaItem) -> Unit,
    onArtist: (MaItem) -> Unit,
    onTrack: (MaItem) -> Unit,
    modifier: Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier
            .verticalScroll(scrollState)
            .touchDragScroll(scrollState, Orientation.Vertical)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (results.artists.isEmpty() && results.albums.isEmpty() && results.tracks.isEmpty()) {
            Text(
                "No results.",
                color = TextMuted,
                fontFamily = AppFont,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (results.artists.isNotEmpty()) ItemShelf("Artists", results.artists, onArtist)
        if (results.albums.isNotEmpty()) ItemShelf("Albums", results.albums, onAlbum)
        if (results.tracks.isNotEmpty()) {
            Column {
                Text(
                    "Tracks",
                    color = TextPrimary,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                )
                results.tracks.forEachIndexed { i, t ->
                    TrackRow(
                        n = i + 1,
                        title = t.name,
                        artist = t.subtitle,
                        duration = t.duration,
                        onClick = { onTrack(t) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    n: Int,
    title: String,
    artist: String?,
    duration: Int?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 48dp minimum — "a panel is touched, not clicked" (PlayerPane's own words);
            // the old 10dp padding only reached ~44dp with this type scale.
            .heightIn(min = 48.dp)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "$n",
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = AppFont,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                fontFamily = AppFont,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist != null) {
                Text(
                    artist,
                    color = TextMuted,
                    fontFamily = AppFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            duration?.let { formatMsLocal(it * 1000L) } ?: "",
            color = TextMuted,
            fontSize = 12.sp,
            fontFamily = AppFont,
        )
    }
}

@Composable
private fun NotConnected(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "Configure Jellyfin in Settings, then Apply.",
            color = TextMuted,
            fontFamily = AppFont,
            fontSize = 14.sp,
        )
    }
}

private fun formatMsLocal(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
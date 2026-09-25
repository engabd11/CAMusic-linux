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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The library pane: Jellyfin browse — albums grid (recently added first), album
 * detail with tracks, artists list, and search. Queueing hands tracks to MPD via
 * [PanelModel.playAlbum] (Jellyfin file paths → MPD relative paths).
 *
 * The grid is LibraryScreen's proportion system at desktop scale: 6-column base,
 * covers spanning 2, 12dp gaps, 20dp edges.
 */
@Composable
fun LibraryPane(modifier: Modifier, model: PanelModel) {
    val sourceFlow by model.source.collectAsState()
    val source = sourceFlow ?: return NotConnected(modifier)
    val scope = rememberCoroutineScope()

    // Root shows the albums grid; an open album or artist replaces it; search overlays.
    var albums by remember { mutableStateOf<List<MaItem>>(emptyList()) }
    var albumDetail by remember { mutableStateOf<Pair<MaItem, List<MaItem>>?>(null) }
    var artistDetail by remember { mutableStateOf<Pair<MaItem, List<MaItem>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<MaSearchResults?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Initial load: recently added albums.
    LaunchedEffect(source) {
        try {
            albums = source.recentlyAdded(200)
        } catch (e: Exception) {
            loadError = e.message ?: "browse failed"
        }
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
                onAlbum = { a ->
                    scope.launch {
                        try {
                            val (album, tracks) = source.albumDetail(a.itemId)
                            if (album != null) albumDetail = album to tracks else loadError = "Album not found"
                        } catch (e: Exception) {
                            loadError = e.message
                        }
                    }
                },
            )
            searching -> SearchView(
                query = query,
                onQuery = { query = it },
                results = results,
                onAlbum = { a ->
                    scope.launch {
                        try {
                            val (album, tracks) = source.albumDetail(a.itemId)
                            if (album != null) albumDetail = album to tracks else loadError = "Album not found"
                        } catch (e: Exception) {
                            loadError = e.message
                        }
                    }
                },
                onBack = { searching = false; query = ""; results = null },
            )
            else -> AlbumsGrid(
                albums = albums,
                error = loadError,
                onAlbum = { a ->
                    scope.launch {
                        try {
                            val (album, tracks) = source.albumDetail(a.itemId)
                            // Jellyfin returning no album (e.g. it was deleted after the grid
                            // loaded) used to hit a `!!` here — on this undecorated, WM-less
                            // panel that throw took the whole screen to black with no crash
                            // dialog and no way back. Route it to the error text instead.
                            if (album != null) albumDetail = album to tracks else loadError = "Album not found"
                        } catch (e: Exception) {
                            loadError = e.message
                        }
                    }
                },
                onSearch = { searching = true },
            )
        }
    }
}

@Composable
private fun AlbumsGrid(
    albums: List<MaItem>,
    error: String?,
    onAlbum: (MaItem) -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recently added",
                color = TextPrimary,
                fontFamily = AppFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, "Search", tint = TextPrimary, modifier = Modifier.size(22.dp))
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
                items(albums, span = { GridItemSpan(2) }, key = { it.itemId }) { album ->
                    AlbumTile(album, onAlbum)
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
        val found = results?.albums ?: emptyList()
        if (results != null) {
            // The results list shrinks to fit above the keyboard rather than sliding
            // under it — weight(1f) here leaves the keyboard below its own fixed size.
            val gridState = rememberLazyGridState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
                ) {
                    items(found, span = { GridItemSpan(2) }, key = { it.itemId }) { album -> AlbumTile(album, onAlbum) }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(gridState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
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
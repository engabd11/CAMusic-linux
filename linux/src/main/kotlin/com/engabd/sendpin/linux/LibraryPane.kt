package com.engabd.sendpin.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val source = model.source ?: return NotConnected(modifier)
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
                onAlbum = { a -> scope.launch { albumDetail = source.albumDetail(a.itemId).let { p -> p.first!! to p.second } } },
            )
            searching -> SearchView(
                query = query,
                onQuery = { query = it },
                results = results,
                onAlbum = { a -> scope.launch { albumDetail = source.albumDetail(a.itemId).let { p -> p.first!! to p.second } } },
                onBack = { searching = false; query = ""; results = null },
            )
            else -> AlbumsGrid(
                albums = albums,
                error = loadError,
                onAlbum = { a ->
                    scope.launch {
                        try {
                            albumDetail = source.albumDetail(a.itemId).let { p -> p.first!! to p.second }
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, span = { GridItemSpan(2) }, key = { it.itemId }) { album ->
                AlbumTile(album, onAlbum)
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, span = { GridItemSpan(2) }, key = { it.itemId }) { album -> AlbumTile(album, onAlbum) }
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(found, span = { GridItemSpan(2) }, key = { it.itemId }) { album -> AlbumTile(album, onAlbum) }
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
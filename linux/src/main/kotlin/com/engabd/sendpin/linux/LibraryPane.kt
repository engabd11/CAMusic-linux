@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.engabd.sendpin.linux

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.ViewCarousel
import com.engabd.sendpin.linux.ui.LocalCoverScope
import com.engabd.sendpin.linux.ui.LocalRouteVisibility
import com.engabd.sendpin.linux.ui.LocalSharedCovers
import com.engabd.sendpin.linux.ui.LocalSharedTransition
import com.engabd.sendpin.linux.ui.Motion
import com.engabd.sendpin.linux.ui.SharedCovers
import com.engabd.sendpin.linux.ui.SegmentedControl
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.linux.ui.ArtImage
import com.engabd.sendpin.linux.ui.ArtTile
import com.engabd.sendpin.linux.ui.CategoryCard
import com.engabd.sendpin.linux.ui.CircleButton
import com.engabd.sendpin.linux.ui.EmptyState
import com.engabd.sendpin.linux.ui.Eyebrow
import com.engabd.sendpin.linux.ui.OnScreenKeyboard
import com.engabd.sendpin.linux.ui.OnScreenKeyboardKey
import com.engabd.sendpin.linux.ui.PageGutter
import com.engabd.sendpin.linux.station.CrateBin
import com.engabd.sendpin.linux.station.CratesScreen
import com.engabd.sendpin.linux.station.LocalActionMenu
import com.engabd.sendpin.linux.station.LocalTrackMenu
import com.engabd.sendpin.linux.station.libraryTrackActions
import com.engabd.sendpin.linux.ui.PageHeader
import com.engabd.sendpin.linux.ui.pressScale
import com.engabd.sendpin.linux.ui.PanelShapes
import com.engabd.sendpin.linux.ui.PillButton
import com.engabd.sendpin.linux.ui.SectionHeader
import com.engabd.sendpin.linux.ui.Shelf
import com.engabd.sendpin.linux.ui.ShelfSkeleton
import com.engabd.sendpin.linux.ui.SkeletonBlock
import com.engabd.sendpin.linux.ui.applyKey
import com.engabd.sendpin.linux.ui.genreColors
import com.engabd.sendpin.linux.ui.placeholderColors
import com.engabd.sendpin.linux.ui.touchDragScroll
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaSearchResults
import com.engabd.sendpin.ui.design.a
import com.engabd.sendpin.ui.design.glassSurface
import com.engabd.sendpin.ui.design.rememberAccent
import com.engabd.sendpin.ui.theme.AppFont
import com.engabd.sendpin.ui.theme.Hairline
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.TextMuted
import com.engabd.sendpin.ui.theme.TextPrimary
import com.engabd.sendpin.ui.theme.TextSecondary
import com.engabd.sendpin.ui.theme.inkOn
import java.time.LocalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A browse root — one of the Home page's category cards, each opening its own page.
 * The old flat chip row (and its "Favourites is two shelves and then nothing" page)
 * is gone: Home is the landing page now, and these are what it links into.
 */
internal enum class LibraryCategory(val label: String) {
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    GENRES("Genres"),
    FAVORITES("Favourites"),
    RECENT("Recently added"),
    SONGS("Songs"),
}

/**
 * Where the library is. A back stack of these replaces the old tangle of nullable
 * `albumDetail`/`artistDetail`/`searching` flags, which could only ever go one level
 * deep — an album opened from an artist's page used to lose the artist on Back.
 */
internal sealed interface LibraryRoute {
    /** Identity for the saveable-state holder: scroll positions survive Back. */
    val key: String

    data object Home : LibraryRoute { override val key = "home" }
    data object Search : LibraryRoute { override val key = "search" }
    data class Browse(val category: LibraryCategory) : LibraryRoute {
        override val key = "browse:${category.name}"
    }
    data class Album(val item: MaItem) : LibraryRoute { override val key = "album:${item.itemId}" }
    data class Artist(val item: MaItem) : LibraryRoute { override val key = "artist:${item.itemId}" }
    data class Playlist(val item: MaItem) : LibraryRoute { override val key = "playlist:${item.itemId}" }
    data class Genre(val item: MaItem) : LibraryRoute { override val key = "genre:${item.itemId}" }
    /** The albums as records in bins (Settings → Crates). */
    data object Crates : LibraryRoute { override val key = "crates" }
}

/** Push [route], unless it is already on top (a double tap must not stack two copies). */
internal fun List<LibraryRoute>.pushed(route: LibraryRoute): List<LibraryRoute> =
    if (lastOrNull() == route) this else this + route

/** Pop one level; the root is never popped, so Back on Home is a no-op, not a blank pane. */
internal fun List<LibraryRoute>.popped(): List<LibraryRoute> = if (size > 1) dropLast(1) else this

/** Home's shelves, each loaded independently so one failing query costs one shelf. */
internal data class HomeShelves(
    val continueListening: List<MaItem> = emptyList(),
    val recentlyPlayed: List<MaItem> = emptyList(),
    val recentlyAdded: List<MaItem> = emptyList(),
    val mostPlayed: List<MaItem> = emptyList(),
    val favoriteAlbums: List<MaItem> = emptyList(),
    val favoriteArtists: List<MaItem> = emptyList(),
    val rediscover: List<MaItem> = emptyList(),
) {
    /** The hero card's album: the last one played, else the newest in the library. */
    val featured: MaItem? get() = recentlyPlayed.firstOrNull() ?: recentlyAdded.firstOrNull()
}

/**
 * The library's state, hoisted above the tab switch by the shell — so leaving for the
 * Queue and coming back finds the same page, scrolled to the same place, without
 * re-fetching every shelf. Rebound (and so emptied) whenever the source changes.
 */
@Stable
class LibraryState {
    internal var stack by mutableStateOf<List<LibraryRoute>>(listOf(LibraryRoute.Home))
    internal var home by mutableStateOf<HomeShelves?>(null)
    internal val browse = mutableStateMapOf<LibraryCategory, List<MaItem>>()
    internal val exhausted = mutableStateMapOf<LibraryCategory, Boolean>()
    internal var favorites by mutableStateOf<MaSearchResults?>(null)
    internal var crates by mutableStateOf<List<CrateBin>?>(null)
    internal var query by mutableStateOf("")
    /** Which tile grew into the page on top, for the shared cover move. */
    internal val covers = SharedCovers()
    /** Where the Albums Cover Flow was left, so coming back finds the same record in front. */
    internal var flowIndex by mutableStateOf(0)
    private var bound: MusicSource? = null

    internal fun push(route: LibraryRoute) { stack = stack.pushed(route) }
    internal fun pop() { stack = stack.popped() }

    /**
     * Bumped by [home]; Home scrolls back to its top whenever it changes — smoothly when
     * Home was already showing, at once when it is coming back from a deeper page.
     */
    internal var homeSignal by mutableStateOf(0)
        private set
    internal var homeSmooth = false
        private set

    /** The nav's Library tab tapped again: straight back to the library's home page. */
    internal fun home() {
        homeSmooth = stack.size == 1
        stack = listOf(LibraryRoute.Home)
        homeSignal++
    }

    internal fun bind(source: MusicSource) {
        if (bound === source) return
        bound = source
        stack = listOf(LibraryRoute.Home)
        home = null
        browse.clear()
        exhausted.clear()
        favorites = null
        crates = null
        query = ""
        flowIndex = 0
    }
}

/** What is playing, as the library needs it: which row to mark, and whether it dances. */
internal data class NowPlayingMark(val id: String?, val playing: Boolean)

/** How many items one page of a paged category asks the server for. */
internal const val BROWSE_PAGE = 120

/**
 * Whether a paged list should fetch its next page: the last visible row is within
 * [threshold] of the end of what is loaded, and the server has not already said
 * there is no more. Pure so the trigger is testable without a scrolling grid.
 */
internal fun shouldLoadMore(lastVisible: Int, loaded: Int, exhausted: Boolean, threshold: Int = 16): Boolean =
    !exhausted && loaded > 0 && lastVisible >= loaded - threshold

/** "Good morning" / "Good afternoon" / "Good evening" for [hour] (0–23). */
internal fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * The library pane: Home (category cards, a featured album, shelves), category pages,
 * album / artist / playlist / genre pages, and search — all browsing Jellyfin, all
 * queueing through MPD via [PanelModel.playAlbum].
 */
@Composable
fun LibraryPane(modifier: Modifier, model: PanelModel, state: LibraryState = remember { LibraryState() }) {
    val sourceFlow by model.source.collectAsState()
    val source = sourceFlow ?: return NotConnected(modifier)
    val scope = rememberCoroutineScope()

    val remote by model.state.collectAsState()
    val queue by model.queue.collectAsState()
    val now = NowPlayingMark(
        id = remote?.index?.takeIf { it >= 0 }?.let { queue.getOrNull(it)?.id },
        playing = remote?.playing == true,
    )

    LaunchedEffect(source) { state.bind(source) }

    val play: (MaItem, List<MaItem>, Int) -> Unit = { header, tracks, start ->
        scope.launch { model.playAlbum(header, tracks, start) }
    }
    val open: (LibraryRoute) -> Unit = { state.push(it) }
    val back: () -> Unit = { state.pop() }

    // The station's additions: Crates beside the grid, and a menu on a held song. Both off
    // until switched on in Settings.
    val crateMode by model.settings.crateMode.collectAsState(initial = false)
    val rowMenus by model.settings.rowMenus.collectAsState(initial = false)
    val heroOn by model.settings.homeHero.collectAsState(initial = true)
    val albumsView by model.settings.albumsView.collectAsState(initial = "grid")
    val sharedMotion by model.settings.sharedCoverMotion.collectAsState(initial = true)
    val obscured by model.coverObscured.collectAsState()
    val startRadio: (MaItem) -> Unit = { seed -> scope.launch { model.startRadio(seed) } }
    val menus = LocalActionMenu.current
    val trackMenu: ((MaItem) -> Unit)? = if (rowMenus && menus != null) { t ->
        menus.show(
            t.name, t.subtitle,
            libraryTrackActions(
                hasAlbum = !t.parentId.isNullOrBlank(),
                hasArtist = !t.subtitle.isNullOrBlank(),
                onPlayNext = { scope.launch { model.enqueue(listOf(t), next = true) } },
                onAddToQueue = { scope.launch { model.enqueue(listOf(t), next = false) } },
                onAlbum = {
                    scope.launch {
                        runCatching { source.albumDetail(t.parentId.orEmpty()).first }.getOrNull()
                            ?.let { open(LibraryRoute.Album(it)) }
                    }
                },
                onArtist = {
                    scope.launch {
                        runCatching { source.search(t.subtitle.orEmpty(), 8).artists }.getOrNull()
                            ?.firstOrNull { it.name.equals(t.subtitle, ignoreCase = true) }
                            ?.let { open(LibraryRoute.Artist(it)) }
                    }
                },
                onRadio = { startRadio(t) },
            ),
        )
    } else null

    val pageTransitions by model.settings.pageTransitions.collectAsState(initial = true)
    val shared = pageTransitions && sharedMotion
    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(
        LocalTrackMenu provides trackMenu,
        LocalStartRadio provides startRadio,
        LocalSharedCovers provides state.covers,
    ) {
        SharedTransitionLayout(modifier) {
            CompositionLocalProvider(LocalSharedTransition provides if (shared) this else null) {
                // A deeper page slides in from the right over the one it came from; going back
                // (or home) runs the same move the other way. Into or out of a record's own
                // page the cover itself carries the eye, so the pages only cross-fade.
                AnimatedContent(
                    targetState = RouteAt(state.stack.last(), state.stack.size),
                    contentKey = { it.route.key },
                    transitionSpec = {
                        val detail = targetState.route.hasCover || initialState.route.hasCover
                        when {
                            !pageTransitions -> EnterTransition.None togetherWith ExitTransition.None
                            shared && detail ->
                                (fadeIn(tween(Motion.PAGE_MS, delayMillis = 60, easing = Motion.Standard)) +
                                    scaleIn(tween(Motion.SHARED_MS, easing = Motion.Emphasized), initialScale = 0.97f)) togetherWith
                                    (fadeOut(tween(Motion.FADE_MS)) + scaleOut(tween(Motion.SHARED_MS), targetScale = 1.02f))
                            else -> {
                                val forward = targetState.depth >= initialState.depth
                                val dir = if (forward) 1 else -1
                                (fadeIn(tween(Motion.FADE_MS + 60, delayMillis = 40)) +
                                    slideInHorizontally(tween(Motion.PAGE_MS, easing = Motion.Emphasized)) { dir * it / 5 }) togetherWith
                                    (fadeOut(tween(Motion.FADE_MS - 40)) +
                                        slideOutHorizontally(tween(Motion.PAGE_MS, easing = Motion.Emphasized)) { -dir * it / 10 })
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "libraryRoute",
                ) { at ->
                    val route = at.route
                    CompositionLocalProvider(LocalRouteVisibility provides this) {
                        holder.SaveableStateProvider(route.key) {
                            when (route) {
                                LibraryRoute.Home -> HomeScreen(source, state, open, play, crates = crateMode, hero = heroOn, onRadio = startRadio, paused = obscured)
                                LibraryRoute.Crates -> CratesScreen(source, state, open, back)
                                LibraryRoute.Search -> SearchScreen(source, state, open, play, back)
                                is LibraryRoute.Browse -> BrowseScreen(
                                    route.category, source, state, now, open, play, back,
                                    albumsView = albumsView,
                                    onAlbumsView = { v -> scope.launch { model.settings.setAlbumsView(v) } },
                                    paused = obscured,
                                )
                                is LibraryRoute.Album -> AlbumScreen(route.item, source, now, open, play, back)
                                is LibraryRoute.Artist -> ArtistScreen(route.item, source, now, open, play, back)
                                is LibraryRoute.Playlist -> TrackListScreen(
                                    header = route.item, eyebrow = "Playlist", now = now, play = play, back = back,
                                    load = { source.playlistTracks(route.item.itemId) },
                                )
                                is LibraryRoute.Genre -> TrackListScreen(
                                    header = route.item, eyebrow = "Genre", now = now, play = play, back = back,
                                    load = { source.songsByGenre(route.item.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pages whose hero is a cover a tile can grow into. */
private val LibraryRoute.hasCover: Boolean
    get() = this is LibraryRoute.Album || this is LibraryRoute.Artist || this is LibraryRoute.Playlist

/** A library page and how deep in the stack it sits — the depth decides which way it slides. */
private data class RouteAt(val route: LibraryRoute, val depth: Int)

// ─── Home ────────────────────────────────────────────────────────────────────

/** A category card's look: label, glyph, and its own gradient — the page's colour. */
private data class CategoryLook(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val colors: List<Color>)

private fun lookOf(c: LibraryCategory): CategoryLook = when (c) {
    LibraryCategory.ALBUMS -> CategoryLook(c.label, Icons.Default.Album, listOf(Color(0xFF8B6CFF), Color(0xFF3B1FA6)))
    LibraryCategory.ARTISTS -> CategoryLook(c.label, Icons.Default.Person, listOf(Color(0xFFFF7A6B), Color(0xFFB0305F)))
    LibraryCategory.PLAYLISTS -> CategoryLook(c.label, Icons.AutoMirrored.Filled.PlaylistPlay, listOf(Color(0xFF1FD1B9), Color(0xFF0B6680)))
    LibraryCategory.GENRES -> CategoryLook(c.label, Icons.Default.Category, listOf(Color(0xFFFFB84D), Color(0xFFD9502B)))
    LibraryCategory.FAVORITES -> CategoryLook(c.label, Icons.Default.Favorite, listOf(Color(0xFFFF5FA2), Color(0xFF8E2A91)))
    LibraryCategory.RECENT -> CategoryLook(c.label, Icons.Default.NewReleases, listOf(Color(0xFF4FA9FF), Color(0xFF2443C4)))
    LibraryCategory.SONGS -> CategoryLook(c.label, Icons.Default.MusicNote, listOf(Color(0xFF8EDB4F), Color(0xFF2B8656)))
}

private val CratesLook = CategoryLook("Crates", Icons.Default.Inventory2, listOf(Color(0xFFC98B52), Color(0xFF5A3417)))

private val RadioLook = CategoryLook("Radio", Icons.Default.Radio, listOf(Color(0xFFFF8A3D), Color(0xFF9B1F5E)))

private val ShuffleLook = CategoryLook("Shuffle all", Icons.Default.Shuffle, listOf(Color(0xFF5C7CFA), Color(0xFF1FB5C9)))

@Composable
private fun HomeScreen(
    source: MusicSource,
    state: LibraryState,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    crates: Boolean = false,
    hero: Boolean = true,
    onRadio: ((MaItem) -> Unit)? = null,
    paused: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    // Stale-while-revalidate: the shelves from last time show at once, and a fresh load
    // replaces them — Home is the page the panel sits on, so it must never be blank.
    LaunchedEffect(source) {
        state.home = loadHome(source)
    }

    val listState = rememberLazyListState()
    // The Library tab tapped again (LibraryState.home): back to the top of the page.
    var seenSignal by rememberSaveable { mutableStateOf(state.homeSignal) }
    LaunchedEffect(state.homeSignal) {
        if (state.homeSignal == seenSignal) return@LaunchedEffect
        seenSignal = state.homeSignal
        if (state.homeSmooth) listState.animateScrollToItem(0) else listState.scrollToItem(0)
    }
    val home = state.home
    // Remembered: the slides take a shuffled pick of favourites, and must hold still.
    val slides = remember(home, hero) { if (hero && home != null) heroSlides(home) else emptyList() }
    val heroShown = slides.size > 1
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize().touchDragScroll(listState),
        ) {
            item(key = "header") {
                Column {
                    PageHeader(
                        title = "Library",
                        eyebrow = greetingFor(LocalTime.now().hour),
                        titleSize = 44.sp,
                    )
                    SearchBar(onClick = { open(LibraryRoute.Search) })
                }
            }
            // The carousel first: on a panel that is looked at, the covers lead.
            if (heroShown) {
                item(key = "hero") {
                    CompositionLocalProvider(LocalCoverScope provides "hero") {
                        HeroCarousel(
                            paused = paused,
                            slides = slides,
                            onOpen = { open(LibraryRoute.Album(it)) },
                            onPlay = { a ->
                                scope.launch {
                                    val (_, tracks) = runCatching { source.albumDetail(a.itemId) }
                                        .getOrDefault(null to emptyList())
                                    if (tracks.isNotEmpty()) play(a, tracks, 0)
                                }
                            },
                        )
                    }
                }
            }
            item(key = "cards") {
                CategoryGrid(
                    onCrates = if (crates) ({ open(LibraryRoute.Crates) }) else null,
                    onRadio = onRadio?.let { r ->
                        {
                            scope.launch {
                                runCatching { source.randomSongs(1) }.getOrNull()?.firstOrNull()?.let(r)
                            }
                        }
                    },
                    onCategory = { open(LibraryRoute.Browse(it)) },
                    onShuffle = {
                        scope.launch {
                            val songs = runCatching { source.randomSongs(100) }.getOrDefault(emptyList())
                            songs.firstOrNull()?.let { play(it, songs, 0) }
                        }
                    },
                )
            }
            if (home == null) {
                item(key = "skeleton") {
                    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        SkeletonBlock(
                            Modifier.padding(horizontal = PageGutter).fillMaxWidth().height(248.dp),
                            PanelShapes.Card,
                        )
                        ShelfSkeleton()
                        ShelfSkeleton()
                    }
                }
            } else {
                if (!heroShown) home.featured?.let { f ->
                    item(key = "featured") {
                        FeaturedCard(
                            item = f,
                            eyebrow = if (home.recentlyPlayed.isNotEmpty()) "Jump back in" else "New in your library",
                            onOpen = { open(LibraryRoute.Album(f)) },
                            onPlay = {
                                scope.launch {
                                    val (_, tracks) = runCatching { source.albumDetail(f.itemId) }
                                        .getOrDefault(null to emptyList())
                                    if (tracks.isNotEmpty()) play(f, tracks, 0)
                                }
                            },
                        )
                    }
                }
                homeShelf("continue", "Continue listening", home.continueListening) { t -> play(t, listOf(t), 0) }
                homeShelf("played", "Recently played", home.recentlyPlayed) { open(LibraryRoute.Album(it)) }
                homeShelf(
                    "added", "Recently added", home.recentlyAdded,
                    onSeeAll = { open(LibraryRoute.Browse(LibraryCategory.RECENT)) },
                ) { open(LibraryRoute.Album(it)) }
                homeShelf("most", "Most played", home.mostPlayed) { open(LibraryRoute.Album(it)) }
                homeShelf(
                    "favAlbums", "Favourite albums", home.favoriteAlbums,
                    onSeeAll = { open(LibraryRoute.Browse(LibraryCategory.FAVORITES)) },
                ) { open(LibraryRoute.Album(it)) }
                homeShelf("favArtists", "Favourite artists", home.favoriteArtists, circular = true) {
                    open(LibraryRoute.Artist(it))
                }
                homeShelf("rediscover", "Rediscover", home.rediscover) { open(LibraryRoute.Album(it)) }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

private fun LazyListScope.homeShelf(
    key: String,
    title: String,
    items: List<MaItem>,
    circular: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    onClick: (MaItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key) {
        Shelf(
            title = title,
            items = items,
            onClick = onClick,
            circular = circular,
            action = onSeeAll?.let { "See all" },
            onAction = onSeeAll,
        )
    }
}

/** Every home query at once; a failed one is an empty shelf, not a failed page. */
private suspend fun loadHome(source: MusicSource): HomeShelves = coroutineScope {
    suspend fun <T> safe(block: suspend () -> List<T>): List<T> = runCatching { block() }.getOrDefault(emptyList())
    val cont = async { safe { source.continueListening(12) } }
    val played = async { safe { source.recentlyPlayed(24) } }
    val added = async { safe { source.recentlyAdded(24) } }
    val most = async { safe { source.mostPlayed(24) } }
    val favs = async { runCatching { source.favorites() }.getOrNull() }
    val random = async { safe { source.randomAlbums(16) } }
    val f = favs.await()
    HomeShelves(
        continueListening = cont.await(),
        recentlyPlayed = played.await(),
        recentlyAdded = added.await(),
        mostPlayed = most.await(),
        favoriteAlbums = f?.albums.orEmpty(),
        favoriteArtists = f?.artists.orEmpty(),
        rediscover = random.await(),
    )
}

/** The tap-to-search bar under the title — a whole-width target, not a small icon. */
@Composable
private fun SearchBar(onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = PageGutter)
            .fillMaxWidth()
            .height(64.dp)
            .glassSurface(PanelShapes.Pill, inkOn(0.07f), border = Hairline)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = TextSecondary, modifier = Modifier.size(28.dp))
        Text("Artists, albums, songs", color = TextMuted, fontFamily = AppFont, fontSize = 19.sp)
    }
}

/** The category cards, four to a row — two rows fill the panel's width with colour. */
@Composable
private fun CategoryGrid(
    onCategory: (LibraryCategory) -> Unit,
    onShuffle: () -> Unit,
    onCrates: (() -> Unit)? = null,
    onRadio: (() -> Unit)? = null,
) {
    val cards: List<Pair<CategoryLook, () -> Unit>> =
        LibraryCategory.entries.map { c -> lookOf(c) to { onCategory(c) } } + (ShuffleLook to onShuffle) +
            listOfNotNull(onRadio?.let { RadioLook to it }, onCrates?.let { CratesLook to it })
    Column(
        Modifier.padding(horizontal = PageGutter),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        cards.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (look, onClick) ->
                    CategoryCard(look.label, look.icon, look.colors, onClick, Modifier.weight(1f).pressScale())
                }
                // A short last row stretches across: one card alone becomes a full-width banner.
            }
        }
    }
}

/**
 * The hero card: one album, big — its own art blurred behind it, the cover, and a
 * Play button. The page's focal point, so Home never opens on a wall of equal tiles.
 */
@Composable
private fun FeaturedCard(item: MaItem, eyebrow: String, onOpen: () -> Unit, onPlay: () -> Unit) {
    val accent = rememberAccent()
    Box(
        Modifier
            .padding(horizontal = PageGutter)
            .fillMaxWidth()
            .height(252.dp)
            .clip(PanelShapes.Card)
            .background(Brush.linearGradient(placeholderColors(item.name)))
            .clickable(onClick = onOpen),
    ) {
        if (!item.image.isNullOrBlank()) {
            AsyncImage(
                model = item.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(48.dp, BlurredEdgeTreatment.Rectangle)
                    .alpha(0.85f),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(Ink.a(0.15f), Ink.a(0.55f), Ink.a(0.78f)))
            )
        )
        Row(
            Modifier.fillMaxSize().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ArtImage(
                url = item.image,
                name = item.name,
                modifier = Modifier
                    .size(208.dp)
                    .shadow(24.dp, PanelShapes.Tile, ambientColor = Color.Black, spotColor = Color.Black),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(eyebrow, Color.White.a(0.85f))
                Text(
                    item.name,
                    color = Color.White,
                    fontFamily = AppFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let {
                    Text(it, color = Color.White.a(0.78f), fontFamily = AppFont, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                PillButton("Play", icon = Icons.Default.PlayArrow, color = accent, onClick = onPlay)
            }
        }
    }
}

// ─── Category pages ──────────────────────────────────────────────────────────

@Composable
private fun BrowseScreen(
    category: LibraryCategory,
    source: MusicSource,
    state: LibraryState,
    now: NowPlayingMark,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    back: () -> Unit,
    albumsView: String = "grid",
    onAlbumsView: (String) -> Unit = {},
    paused: Boolean = false,
) {
    var error by remember(category) { mutableStateOf<String?>(null) }
    var loadingMore by remember(category) { mutableStateOf(false) }
    val paged = category == LibraryCategory.ALBUMS || category == LibraryCategory.SONGS

    suspend fun fetch(offset: Int): List<MaItem> = when (category) {
        LibraryCategory.ALBUMS -> source.albums(offset, BROWSE_PAGE)
        LibraryCategory.SONGS -> source.tracks(offset, BROWSE_PAGE)
        LibraryCategory.RECENT -> source.recentlyAdded(200)
        LibraryCategory.ARTISTS -> source.artists()
        LibraryCategory.PLAYLISTS -> source.playlists()
        LibraryCategory.GENRES -> source.genres()
        LibraryCategory.FAVORITES -> emptyList()
    }

    LaunchedEffect(category, source) {
        try {
            if (category == LibraryCategory.FAVORITES) {
                state.favorites = source.favorites()
            } else if (state.browse[category] == null) {
                val first = fetch(0)
                state.browse[category] = first
                state.exhausted[category] = !paged || first.size < BROWSE_PAGE
            }
        } catch (e: Exception) {
            error = e.message ?: "Browse failed"
        }
    }

    val scope = rememberCoroutineScope()
    fun loadMore() {
        val have = state.browse[category] ?: return
        if (loadingMore || state.exhausted[category] == true) return
        loadingMore = true
        scope.launch {
            try {
                val next = fetch(have.size)
                state.browse[category] = have + next
                state.exhausted[category] = next.size < BROWSE_PAGE
            } catch (_: Exception) {
                state.exhausted[category] = true
            }
            loadingMore = false
        }
    }

    val items = state.browse[category]
    // Cover Flow is a walk through the whole shelf: it keeps loading in the background
    // until every album is in, so the count is the library's and there is no end to hit.
    val flowing = category == LibraryCategory.ALBUMS && albumsView == "flow"
    LaunchedEffect(flowing, items?.size, loadingMore) {
        if (flowing && items != null && !loadingMore) loadMore()
    }
    val count = if (category == LibraryCategory.FAVORITES) null else items?.size
    val noun = when (category) {
        LibraryCategory.ALBUMS, LibraryCategory.RECENT -> "albums"
        LibraryCategory.ARTISTS -> "artists"
        LibraryCategory.PLAYLISTS -> "playlists"
        LibraryCategory.GENRES -> "genres"
        LibraryCategory.SONGS -> "songs"
        LibraryCategory.FAVORITES -> ""
    }
    val subtitle = count?.let {
        if (paged && state.exhausted[category] != true) "$it+ $noun" else "$it $noun"
    }

    Column(Modifier.fillMaxSize()) {
        val flow = category == LibraryCategory.ALBUMS && albumsView == "flow"
        PageHeader(title = category.label, subtitle = subtitle, onBack = back) {
            if (category == LibraryCategory.ALBUMS) {
                SegmentedControl(
                    options = listOf("grid", "flow"),
                    selected = albumsView,
                    label = { if (it == "grid") "Grid" else "Flow" },
                    icon = { if (it == "grid") Icons.Default.GridView else Icons.Default.ViewCarousel },
                    onSelect = onAlbumsView,
                    modifier = Modifier.width(250.dp),
                )
            }
        }
        when {
            error != null -> EmptyState(Icons.Default.ErrorOutline, "Couldn't load ${category.label.lowercase()}", error!!)
            category == LibraryCategory.FAVORITES -> FavoritesBody(state.favorites, now, open, play)
            items == null -> GridSkeleton(circular = category == LibraryCategory.ARTISTS)
            items.isEmpty() -> EmptyState(lookOf(category).icon, "No ${category.label.lowercase()}", "Nothing in the library here yet.")
            category == LibraryCategory.SONGS -> SongList(items, now, play, onNearEnd = ::loadMore)
            category == LibraryCategory.GENRES -> GenreGrid(items) { open(LibraryRoute.Genre(it)) }
            flow -> CompositionLocalProvider(LocalCoverScope provides "flow") {
                CoverFlow(
                    paused = paused,
                    items = items,
                    start = state.flowIndex,
                    onSettle = { state.flowIndex = it },
                    onOpen = { open(LibraryRoute.Album(it)) },
                    onNearEnd = if (state.exhausted[category] != true) ::loadMore else null,
                )
            }
            else -> ItemGrid(
                scopeName = "grid:${category.name}",
                items = items,
                circular = category == LibraryCategory.ARTISTS,
                onNearEnd = if (paged) ::loadMore else null,
                onClick = { item ->
                    open(
                        when (category) {
                            LibraryCategory.ARTISTS -> LibraryRoute.Artist(item)
                            LibraryCategory.PLAYLISTS -> LibraryRoute.Playlist(item)
                            else -> LibraryRoute.Album(item)
                        }
                    )
                },
            )
        }
    }
}

/** Four columns on the portrait panel — ~175dp covers, big enough to recognise at a glance. */
private const val GRID_COLUMNS = 4

@Composable
private fun ItemGrid(
    scopeName: String = "grid",
    items: List<MaItem>,
    circular: Boolean,
    onNearEnd: (() -> Unit)?,
    onClick: (MaItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    if (onNearEnd != null) {
        LaunchedEffect(gridState, items.size) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .collect { last -> if (shouldLoadMore(last, items.size, exhausted = false)) onNearEnd() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(GRID_COLUMNS),
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 8.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
        ) {
            itemsIndexed(items, key = { i, it -> "$i:${it.itemId}" }) { i, item ->
                CompositionLocalProvider(LocalCoverScope provides scopeName) {
                    ArtTile(item, onClick, circular = circular, index = i)
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(gridState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun GridSkeleton(circular: Boolean) {
    Column(
        Modifier.padding(horizontal = PageGutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(GRID_COLUMNS) {
                    SkeletonBlock(
                        Modifier.weight(1f).aspectRatio(1f),
                        if (circular) androidx.compose.foundation.shape.CircleShape else PanelShapes.Tile,
                    )
                }
            }
        }
    }
}

/** Genres as colour cards — a genre has no artwork, so it gets a gradient of its own. */
@Composable
private fun GenreGrid(items: List<MaItem>, onClick: (MaItem) -> Unit) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
    ) {
        itemsIndexed(items, key = { i, it -> "$i:${it.itemId}" }) { _, g ->
            CategoryCard(g.name, Icons.Default.Category, genreColors(g.name), onClick = { onClick(g) })
        }
    }
}

@Composable
private fun SongList(
    items: List<MaItem>,
    now: NowPlayingMark,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    onNearEnd: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (shouldLoadMore(last, items.size, exhausted = false)) onNearEnd() }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize().touchDragScroll(listState),
        ) {
            itemsIndexed(items, key = { i, it -> "$i:${it.itemId}" }) { i, t ->
                TrackRow(
                    track = t,
                    lead = TrackLead.Art,
                    now = now,
                    // A song list is the whole library; the queue is the tapped song and
                    // the next hundred after it, not every song on the server.
                    onClick = { play(t, items.subList(i, minOf(items.size, i + 100)), 0) },
                )
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * Favourites as a full page: albums as a grid, artists as a round shelf, then the
 * favourite songs (playable as a list) and playlists — every kind Jellyfin returns,
 * where the old page showed two shelves and left the rest of the panel empty.
 */
@Composable
private fun FavoritesBody(
    results: MaSearchResults?,
    now: NowPlayingMark,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
) {
    if (results == null) return GridSkeleton(circular = false)
    if (results.albums.isEmpty() && results.artists.isEmpty() && results.tracks.isEmpty() && results.playlists.isEmpty()) {
        return EmptyState(
            Icons.Default.Favorite,
            "No favourites yet",
            "Heart an album or an artist and it lands here.",
        )
    }
    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(GRID_COLUMNS),
            contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize().touchDragScroll(gridState),
        ) {
            if (results.artists.isNotEmpty()) {
                fullSpan("artists") {
                    Shelf("Artists", results.artists, { open(LibraryRoute.Artist(it)) }, circular = true, tileWidth = 150.dp)
                }
            }
            if (results.albums.isNotEmpty()) {
                fullSpan("albumsHdr") { SectionHeader("Albums") }
                tileRows("album", results.albums) { open(LibraryRoute.Album(it)) }
            }
            if (results.tracks.isNotEmpty()) {
                fullSpan("tracksHdr") {
                    SectionHeader("Songs", action = "Play all", onAction = { play(results.tracks.first(), results.tracks, 0) })
                }
                fullSpan("tracks") {
                    Column {
                        results.tracks.forEachIndexed { i, t ->
                            TrackRow(track = t, lead = TrackLead.Art, now = now, onClick = { play(t, results.tracks, i) })
                        }
                    }
                }
            }
            if (results.playlists.isNotEmpty()) {
                fullSpan("playlists") {
                    Shelf("Playlists", results.playlists, { open(LibraryRoute.Playlist(it)) })
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(gridState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

internal fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/**
 * Tiles laid into a full-width grid inside a grid whose own edge padding is zero (so
 * the full-span shelves can bleed to the edge): each tile pads itself onto the gutter.
 * One row per item-group keeps the gutter on the outer tiles only.
 */
internal fun LazyGridScope.tileRows(
    prefix: String,
    items: List<MaItem>,
    circular: Boolean = false,
    onClick: (MaItem) -> Unit,
) {
    items.chunked(GRID_COLUMNS).forEachIndexed { r, row ->
        fullSpan("$prefix-row-$r") {
            Row(
                Modifier.padding(horizontal = PageGutter),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                row.forEach { ArtTile(it, onClick, Modifier.weight(1f), circular = circular) }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ─── Search ──────────────────────────────────────────────────────────────────

@Composable
private fun SearchScreen(
    source: MusicSource,
    state: LibraryState,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
    back: () -> Unit,
) {
    var results by remember { mutableStateOf<MaSearchResults?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val query = state.query

    // 350 ms after the last key, then ask the source.
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(350)
        try {
            results = source.search(query)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = back)
            Row(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .glassSurface(PanelShapes.Pill, inkOn(0.08f), border = Hairline)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.Search, null, tint = TextSecondary, modifier = Modifier.size(28.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Artists, albums, songs", color = TextMuted, fontFamily = AppFont, fontSize = 20.sp)
                    }
                    // Still a real text field, so a USB keyboard plugged into the NUC
                    // types here too; the drawn keyboard below is the touch path.
                    BasicTextField(
                        value = query,
                        onValueChange = { state.query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontFamily = AppFont, fontSize = 20.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(rememberAccent()),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val r = results
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                error != null -> EmptyState(Icons.Default.ErrorOutline, "Search failed", error!!)
                query.isBlank() -> EmptyState(
                    Icons.Default.Search,
                    "Search your library",
                    "Type an artist, an album or a song.",
                )
                r == null -> Column(Modifier.padding(top = 8.dp)) { ShelfSkeleton(tileWidth = 150.dp) }
                r.artists.isEmpty() && r.albums.isEmpty() && r.tracks.isEmpty() && r.playlists.isEmpty() ->
                    EmptyState(Icons.Default.LibraryMusic, "No results", "Nothing matches “$query”.")
                else -> SearchResults(r, open, play)
            }
        }

        OnScreenKeyboard(
            onKey = { c -> state.query = applyKey(state.query, OnScreenKeyboardKey.Char(c)) },
            onBackspace = { state.query = applyKey(state.query, OnScreenKeyboardKey.Backspace) },
            onClear = { state.query = applyKey(state.query, OnScreenKeyboardKey.Clear) },
            onDone = back,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SearchResults(
    r: MaSearchResults,
    open: (LibraryRoute) -> Unit,
    play: (MaItem, List<MaItem>, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize().touchDragScroll(listState),
    ) {
        if (r.artists.isNotEmpty()) item(key = "artists") {
            Shelf("Artists", r.artists, { open(LibraryRoute.Artist(it)) }, circular = true, tileWidth = 140.dp)
        }
        if (r.albums.isNotEmpty()) item(key = "albums") {
            Shelf("Albums", r.albums, { open(LibraryRoute.Album(it)) }, tileWidth = 150.dp)
        }
        if (r.tracks.isNotEmpty()) item(key = "tracks") {
            Column {
                SectionHeader("Songs")
                r.tracks.forEachIndexed { i, t ->
                    TrackRow(track = t, lead = TrackLead.Art, now = NowPlayingMark(null, false), onClick = { play(t, r.tracks, i) })
                }
            }
        }
        if (r.playlists.isNotEmpty()) item(key = "playlists") {
            Shelf("Playlists", r.playlists, { open(LibraryRoute.Playlist(it)) }, tileWidth = 150.dp)
        }
    }
}

@Composable
private fun NotConnected(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        EmptyState(
            Icons.Default.LibraryMusic,
            "Connect your library",
            "Add your Jellyfin server in Settings, then Apply.",
        )
    }
}

package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.Route
import com.signal.data.VodRepository
import com.signal.data.VodSort
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.ListRow
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SText
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.VDivider
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the poster grid shows: a list, or one provider category. */
private sealed interface Shelf {
    val key: String
    data object Library : Shelf { override val key = "library" }
    /** Search within this section only (milestone 6); the nav bar's Search stays universal. */
    data object Search : Shelf { override val key = "search" }
    data class Category(val entry: VodRepository.CategoryEntry) : Shelf { override val key = "${entry.sourceId}|${entry.categoryId}" }
    /** A TMDB list or genre from the companion (PLAN §4.3.12): "list:trending", "genre:Action". */
    data class Tmdb(val shelf: String, val label: String) : Shelf {
        override val key = shelf
        val isList get() = shelf.startsWith(com.signal.data.VodEnrichment.LIST)
    }
}

/**
 * Movies and Series (PLAN §4.3.6–4.3.7, handoff screen 5): the category column on the left and a
 * 6-column poster grid. The TMDB lists and genres come from the companion; without it, Library,
 * Library and the provider categories still work.
 */
@Composable
fun VodBrowsePage(kind: VodKind) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val chrome = remember(kind) { PageChrome() }
    val route = if (kind == VodKind.MOVIE) Route.Movies else Route.Series
    val categoriesOrNull by remember(kind) { app.vod.categories(kind) }.collectAsState(null)
    val categories = categoriesOrNull.orEmpty()
    // Read by the first focus, which waits for the categories: before they load, Library is the only row.
    val categoriesLoaded = rememberUpdatedState(categoriesOrNull != null)
    val shelves by remember(kind) { app.vod.shelves(kind) }.collectAsState(emptyList())
    val shelfCounts = shelves.associate { it.shelf to it.count }
    val lists = com.signal.data.VodEnrichment.lists(kind).mapNotNull { (id, label) ->
        val shelf = com.signal.data.VodEnrichment.LIST + id
        shelfCounts[shelf]?.let { Shelf.Tmdb(shelf, label) to it }
    }
    val genres = shelves.filter { it.shelf.startsWith(com.signal.data.VodEnrichment.GENRE) }
        .map { Shelf.Tmdb(it.shelf, it.shelf.removePrefix(com.signal.data.VodEnrichment.GENRE)) to it.count }.sortedBy { it.first.label }
    val vodVersion by app.vodVersion.collectAsState()
    // The shelf picked in the column. Until then: Library when it has titles, else the first TMDB list, else the
    // first category. ("Recently added" is gone, 0.2.9: not wanted, and it loaded on every warm-up.)
    var chosen by remember(kind) { mutableStateOf<Shelf?>(null) }
    val watchNow by app.watchState.collectAsState()
    val defaultShelf: Shelf = when {
        watchNow.library.any { it.kind == kind } -> Shelf.Library
        lists.isNotEmpty() -> lists.first().first
        categories.isNotEmpty() -> Shelf.Category(categories.first())
        else -> Shelf.Library
    }
    val shelf = chosen ?: defaultShelf
    var query by remember(kind) { mutableStateOf("") }
    // Sort per category for this visit; TMDB lists will keep their own order (PLAN §4.3.6 → Poster grid).
    val sorts = remember(kind) { mutableStateMapOf<String, VodSort>() }
    val sort = sorts[shelf.key] ?: VodSort.RECENT
    val watch by app.watchState.collectAsState()
    var libraryFilter by remember(kind) { mutableStateOf(LibraryFilter.ALL) }
    var librarySort by remember(kind) { mutableStateOf(LibrarySort.ADDED) }
    val libraryEntries = watch.library.filter { it.kind == kind }
    val newEpisodes by app.newEpisodes.collectAsState()
    val catalogVersion by app.catalogVersion.collectAsState()
    val items by produceState(emptyList<VodItem>(), kind, shelf, sort, watch, libraryFilter, librarySort, vodVersion, newEpisodes, catalogVersion, query) {
        value = when (val s = shelf) {
            // Restarted on every keystroke, so the pause is the typing debounce, as on the Search page.
            Shelf.Search -> if (query.trim().length < 2) emptyList() else { kotlinx.coroutines.delay(SEARCH_PAUSE_MS); app.vod.search(query, kind, SEARCH_LIMIT) }
            Shelf.Library -> libraryItems(app, libraryEntries, watch, libraryFilter, librarySort, newEpisodes)
            is Shelf.Category -> app.vod.items(kind, s.entry.sourceId, s.entry.categoryId, sort)
            // Lists keep TMDB's order; genres sort like categories (PLAN §4.3.6 → Poster grid).
            is Shelf.Tmdb -> app.vod.shelfItems(kind, s.shelf).let { if (s.isList) it else VodRepository.sorted(it, sort) }
        }
    }
    val multiSource = categories.map { it.sourceId }.distinct().size > 1
    val sourceNames = app.sourceList.collectAsState().value.associate { it.id to it.name }

    val firstShelf = remember(kind) { FocusRequester() }
    val firstPoster = remember(kind) { FocusRequester() }
    val gridState = rememberLazyGridState()
    var columnHasFocus by remember { mutableStateOf(false) }
    var gridHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val returnTo = remember(kind) { FocusRequester() }
    val gridFocus = remember(kind) { FocusRequester() }
    val searchField = remember(kind) { FocusRequester() }
    var fieldHasFocus by remember { mutableStateOf(false) }
    // The poster last focused, to return to after its page (the grid's own restorer forgets it while hidden).
    var lastPoster by remember(kind) { mutableStateOf(-1) }
    val returnPoster = remember(kind) { FocusRequester() }

    fun focusGrid() {
        if (items.isEmpty()) return
        scope.launch {
            gridState.scrollToItem(0)
            firstPoster.requestFocusWhenReady()
        }
    }

    fun sortPage(): PanelPage = PanelPage.Actions("Sort", VodSort.entries.map { s ->
        PanelAction(s.label, selected = s == sort) { sorts[shelf.key] = s; null }
    })

    // Library (PLAN §4.3.11): All / Unwatched / Watched, and its own sorts.
    fun libraryPage(): PanelPage = PanelPage.Actions(
        "Library",
        LibraryFilter.entries.map { f -> PanelAction("Show ${f.label.lowercase()}", selected = f == libraryFilter) { libraryFilter = f; null } } +
            LibrarySort.entries.map { s -> PanelAction("Sort: ${s.label}", selected = s == librarySort) { librarySort = s; null } },
    )

    fun open(item: VodItem) {
        if (item.id.startsWith(UNAVAILABLE)) return
        app.navigator.go(if (item.kind == VodKind.MOVIE) Route.MovieDetail(item.id) else Route.SeriesDetail(item.id))
    }

    // The page is kept alive between visits (KeptPage). Back from a title returns to its poster; from
    // the nav bar, the column takes focus, on the shelf it was on. The column is lazy: its first row
    // may not exist yet when the page first appears (see requestFocusWhenReady).
    var shownBefore by remember(kind) { mutableStateOf(false) }
    var leftFromGrid by remember(kind) { mutableStateOf(false) }
    com.signal.ui.components.OnPageShown(kind) {
        when {
            !shownBefore -> {
                androidx.compose.runtime.snapshotFlow { categoriesLoaded.value }.first { it }
                firstShelf.requestFocusWhenReady()
            }
            leftFromGrid && lastPoster in items.indices -> {
                gridState.requestScrollToItem(lastPoster)
                (if (lastPoster == 0) firstPoster else returnPoster).requestFocusWhenReady()
            }
            else -> chrome.contentFocus.requestFocusWhenReady()
        }
        shownBefore = true
    }
    com.signal.ui.components.OnPageHidden { leftFromGrid = gridHasFocus }
    // A new shelf or sort starts at the top, not where the last one was scrolled to.
    LaunchedEffect(shelf.key, sort) { lastPoster = -1; gridState.requestScrollToItem(0) }

    TopLevelPage(route, chrome) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(400.dp).fillMaxHeight()
                    .onFocusChanged { columnHasFocus = it.hasFocus }
                    .focusRequester(chrome.contentFocus).focusRestorer(firstShelf).focusRequester(returnTo),
            ) {
                item { Box(Modifier.padding(top = 24.dp)) }
                item {
                    ListRow(
                        if (kind == VodKind.MOVIE) "Search movies" else "Search series", selected = shelf == Shelf.Search,
                        onFocused = { if (categoriesLoaded.value) chosen = Shelf.Search }, onSelect = { chosen = Shelf.Search; scope.launch { searchField.requestFocusWhenReady() } },
                    )
                }
                item {
                    ListRow(
                        "Library", detail = libraryEntries.size.toString(), selected = shelf == Shelf.Library,
                        focusRequester = if (categoriesLoaded.value && defaultShelf == Shelf.Library) firstShelf else null,
                        onFocused = { if (categoriesLoaded.value) chosen = Shelf.Library }, onSelect = { chosen = Shelf.Library; focusGrid() },
                        onOptions = { app.panel.open(libraryPage(), returnTo) },
                    )
                }
                if (lists.isNotEmpty()) item { SText("Lists", 22, Modifier.padding(start = 40.dp, top = 24.dp, bottom = 8.dp), color = c.muted, weight = FontWeight.SemiBold) }
                items(lists, key = { it.first.key }) { (s, count) ->
                    ListRow(
                        s.label, detail = count.toString(), selected = shelf.key == s.key,
                        focusRequester = if (defaultShelf.key == s.key) firstShelf else null,
                        onFocused = { if (categoriesLoaded.value) chosen = s }, onSelect = { chosen = s; focusGrid() },
                    )
                }
                if (genres.isNotEmpty()) item { SText("Genres", 22, Modifier.padding(start = 40.dp, top = 24.dp, bottom = 8.dp), color = c.muted, weight = FontWeight.SemiBold) }
                items(genres, key = { it.first.key }) { (s, count) ->
                    ListRow(
                        s.label, detail = count.toString(), selected = shelf.key == s.key,
                        onFocused = { if (categoriesLoaded.value) chosen = s }, onSelect = { chosen = s; focusGrid() },
                        onOptions = { app.panel.open(sortPage(), returnTo) },
                    )
                }
                if (categories.isNotEmpty()) item { SText("Categories", 22, Modifier.padding(start = 40.dp, top = 24.dp, bottom = 8.dp), color = c.muted, weight = FontWeight.SemiBold) }
                items(categories, key = { "${it.sourceId}|${it.categoryId}" }) { cat ->
                    val s = Shelf.Category(cat)
                    ListRow(
                        cat.name + if (multiSource) " · ${sourceNames[cat.sourceId] ?: ""}" else "",
                        detail = cat.count.toString(),
                        selected = shelf.key == s.key,
                        focusRequester = if (defaultShelf.key == s.key) firstShelf else null,
                        onFocused = { if (categoriesLoaded.value) chosen = s },
                        onSelect = { chosen = s; focusGrid() },
                        onOptions = { app.panel.open(sortPage(), returnTo) },
                    )
                }
            }
            VDivider()
            Column(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = SignalSizes.EDGE_MARGIN.dp)) {
                val title = when (val s = shelf) {
                    Shelf.Library -> "Library"
                    Shelf.Search -> if (kind == VodKind.MOVIE) "Search movies" else "Search series"
                    is Shelf.Category -> s.entry.name
                    is Shelf.Tmdb -> s.label
                }
                SText(title, 48, weight = FontWeight.ExtraBold, maxLines = 1)
                if (shelf == Shelf.Search) {
                    Box(Modifier.padding(top = 8.dp).onFocusChanged { fieldHasFocus = it.hasFocus }) {
                        com.signal.ui.components.SField(
                            if (kind == VodKind.MOVIE) "Movie title" else "Series title", query, { query = it },
                            focusRequester = searchField, down = gridFocus,
                        )
                    }
                }
                val line = when {
                    shelf == Shelf.Search -> if (query.trim().length < 2) "Only this section; Search in the nav bar covers everything" else "${titleCount(items.size)}"
                    shelf == Shelf.Library -> "${titleCount(items.size)} · ${libraryFilter.label} · Sort: ${librarySort.label}"
                    (shelf as? Shelf.Tmdb)?.isList == true -> "${titleCount(items.size)} · from TMDB"
                    else -> "${titleCount(items.size)} · Sort: ${sort.label}"
                }
                SText(line, 24, Modifier.padding(top = 8.dp, bottom = 28.dp), color = c.muted)
                if (items.isEmpty()) {
                    val empty = when {
                        shelf == Shelf.Library && libraryEntries.isEmpty() -> "Nothing in your Library yet. Long-press a poster, or use Add to Library on its page."
                        shelf == Shelf.Library -> "Nothing ${libraryFilter.label.lowercase()} in your Library."
                        shelf == Shelf.Search -> if (query.trim().length < 2) "Type at least 2 letters." else "No matches."
                        kind == VodKind.MOVIE -> "No movies here."
                        else -> "No series here."
                    }
                    SText(empty, 26, color = c.secondaryText, maxLines = 2)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus }.focusRequester(gridFocus).focusRestorer(firstPoster),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                            val key = com.signal.data.ItemKeys.of(item)
                            PosterTile(
                                item,
                                focusRequester = if (i == 0) firstPoster else if (i == lastPoster) returnPoster else null,
                                onFocused = { lastPoster = i },
                                onSelect = { open(item) },
                                onOptions = if (item.id.startsWith(UNAVAILABLE)) null else ({ com.signal.ui.menus.VodMenus.open(app, item, watch, returnTo) }),
                                // Watched shows full, a resume position its fraction (PLAN §4.3.6 → Poster grid).
                                progress = if (key in watch.watched) 1f else watch.positions[key]?.fraction,
                                unavailable = item.id.startsWith(UNAVAILABLE),
                                newEpisode = item.kind == VodKind.SERIES && com.signal.data.ItemKeys.of(item) in newEpisodes,
                            )
                        }
                    }
                }
            }
        }
    }

    // Column ↔ grid, and Back goes up a level before the nav bar (PLAN §12.1), as on Settings.
    RemoteHandler { action ->
        when {
            action == RemoteAction.Right && columnHasFocus && shelf == Shelf.Search -> { scope.launch { searchField.requestFocusWhenReady() }; true }
            action == RemoteAction.Right && columnHasFocus -> { focusGrid(); true }
            action == RemoteAction.Back && fieldHasFocus -> { chrome.focusContent(); true }
            // Search results: Back goes to the field first, then the column, like the Search page.
            action == RemoteAction.Back && gridHasFocus && shelf == Shelf.Search -> { scope.launch { searchField.requestFocusWhenReady() }; true }
            action == RemoteAction.Back && gridHasFocus -> { chrome.focusContent(); true }
            else -> false
        }
    }
}

/**
 * A poster (handoff screen 5): 2:3 artwork, the title and the year. Focus is a 4px accent outline
 * offset by 4px, the one exception to the accent fill (PLAN §3).
 */
@Composable
fun PosterTile(
    item: VodItem,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
    onOptions: (() -> Unit)?,
    progress: Float? = null,
    unavailable: Boolean = false,
    newEpisode: Boolean = false,
    onFocused: (() -> Unit)? = null,
) {
    val c = SignalTheme.colors
    val name = VodRepository.displayName(item)
    Focusable(Modifier.fillMaxWidth(), onSelect = onSelect, onOptions = onOptions, focusRequester = focusRequester, onFocused = onFocused) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().border(4.dp, if (focused) c.accent else Color.Transparent).padding(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(c.neutral300), contentAlignment = Alignment.Center) {
                    val placeholder = @Composable {
                        SText(name, 24, Modifier.padding(16.dp), weight = FontWeight.ExtraBold, color = c.secondaryText, maxLines = 4)
                    }
                    // The companion's copy first; if it fails (out of reach), the provider's own poster.
                    com.signal.ui.components.ImageOrPlaceholder(item.posterUrl, item.fallbackPosterUrl, ContentScale.Crop, Modifier.fillMaxSize(), placeholderWhileLoading = false, placeholder = placeholder)
                    if (progress != null && progress > 0f) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                            com.signal.ui.components.ProgressBar(progress, height = 6)
                        }
                    }
                    // A series with an episode added since the user last watched it (PLAN §4.3.7).
                    if (newEpisode) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) { NewEpisodeBadge() }
                }
            }
            SText(name, 22, Modifier.padding(start = 8.dp, top = 4.dp), weight = FontWeight.SemiBold, maxLines = 1)
            val meta = listOfNotNull(item.year?.toString(), if (unavailable) "Unavailable" else null).joinToString(" · ")
            if (meta.isNotEmpty()) SText(meta, 20, Modifier.padding(start = 8.dp), color = c.muted)
        }
    }
}

/** Library filters (PLAN §4.3.11). For series, Unwatched means it has unwatched episodes. */
private enum class LibraryFilter(val label: String) { ALL("All"), UNWATCHED("Unwatched"), WATCHED("Watched") }

/** Library sorts (PLAN §4.3.11): recently added to the Library is the default. */
private enum class LibrarySort(val label: String) { ADDED("Recently added"), A_Z("A–Z"), WATCHED("Recently watched") }

private fun titleCount(n: Int) = if (n == 1) "1 title" else "$n titles"

/** Search in a section: the typing pause, and how many posters a search shows. */
private const val SEARCH_PAUSE_MS = 300L
private const val SEARCH_LIMIT = 120

/** The ID of a Library title no source has any more, shown as unavailable (PLAN §4.3.11). */
private const val UNAVAILABLE = "unavailable:"

/**
 * The Library's titles as posters, each from whichever source has it now. Series with new episodes
 * sort first; a title no enabled source has stays, marked unavailable.
 */
private suspend fun libraryItems(
    app: com.signal.app.AppController,
    entries: List<com.signal.data.WatchRepository.LibraryEntry>,
    watch: com.signal.data.WatchRepository.State,
    filter: LibraryFilter,
    sort: LibrarySort,
    newEpisodes: Set<String>,
): List<VodItem> {
    val rows = entries.map { e ->
        // Whichever enabled source has the title today (PLAN §4.3.11 → Across providers).
        val item = app.vod.resolve(e.key, e.kind, e.vodItemId, e.title, e.year)
            ?: VodItem("$UNAVAILABLE${e.key}", "", e.kind, "", e.title, "", posterUrl = e.posterUrl, year = e.year)
        Triple(e, item, isWatched(app, e, item, watch))
    }
    val filtered = rows.filter { (_, _, watched) ->
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.UNWATCHED -> !watched
            LibraryFilter.WATCHED -> watched
        }
    }
    fun lastWatched(e: com.signal.data.WatchRepository.LibraryEntry): Long = maxOf(
        watch.positions.values.filter { it.key == e.key || it.seriesKey == e.key }.maxOfOrNull { it.updatedAt } ?: 0,
        watch.watched.values.filter { it.key == e.key || it.seriesKey == e.key }.maxOfOrNull { it.watchedAt } ?: 0,
    )
    return when (sort) {
        // Series with new episodes first under the default sort (PLAN §4.3.11).
        LibrarySort.ADDED -> filtered.sortedWith(compareBy<Triple<com.signal.data.WatchRepository.LibraryEntry, VodItem, Boolean>> { it.first.key !in newEpisodes }.thenByDescending { it.first.addedAt })
        LibrarySort.A_Z -> filtered.sortedBy { VodRepository.displayName(it.second).lowercase() }
        LibrarySort.WATCHED -> filtered.sortedByDescending { lastWatched(it.first) }
    }.map { it.second }
}

private suspend fun isWatched(app: com.signal.app.AppController, e: com.signal.data.WatchRepository.LibraryEntry, item: VodItem, watch: com.signal.data.WatchRepository.State): Boolean {
    if (e.kind == VodKind.MOVIE) return e.key in watch.watched
    val detail = if (item.id.startsWith(UNAVAILABLE)) null else app.vodDetails.cachedSeries(item)
    val episodes = detail?.episodes ?: return false
    return episodes.isNotEmpty() && episodes.all { com.signal.data.ItemKeys.episode(e.key, it) in watch.watched }
}

/** The "New episode" badge on a series poster or Search card (PLAN §4.3.7, §4.3.14). */
@Composable
fun NewEpisodeBadge(modifier: Modifier = Modifier) {
    val c = SignalTheme.colors
    SText("New episode", 18, modifier.background(c.accent).padding(horizontal = 10.dp, vertical = 4.dp), weight = FontWeight.SemiBold, color = c.onAccent)
}

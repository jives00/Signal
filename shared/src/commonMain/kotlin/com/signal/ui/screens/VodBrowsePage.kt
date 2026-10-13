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
import coil3.compose.SubcomposeAsyncImage
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
import kotlinx.coroutines.launch

/** What the poster grid shows: a list, or one provider category. */
private sealed interface Shelf {
    val key: String
    data object Library : Shelf { override val key = "library" }
    data object Recent : Shelf { override val key = "recent" }
    data class Category(val entry: VodRepository.CategoryEntry) : Shelf { override val key = "${entry.sourceId}|${entry.categoryId}" }
}

/**
 * Movies and Series (PLAN §4.3.6–4.3.7, handoff screen 5): the category column on the left and a
 * 6-column poster grid. Library and the TMDB lists and genres join with later slices; the provider
 * categories and Recently added work without the NAS.
 */
@Composable
fun VodBrowsePage(kind: VodKind) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val chrome = remember(kind) { PageChrome() }
    val route = if (kind == VodKind.MOVIE) Route.Movies else Route.Series
    val categories by remember(kind) { app.vod.categories(kind) }.collectAsState(emptyList())
    var shelf by remember(kind) { mutableStateOf<Shelf>(Shelf.Recent) }
    // Sort per category for this visit; TMDB lists will keep their own order (PLAN §4.3.6 → Poster grid).
    val sorts = remember(kind) { mutableStateMapOf<String, VodSort>() }
    val sort = sorts[shelf.key] ?: VodSort.RECENT
    val watch by app.watchState.collectAsState()
    var libraryFilter by remember(kind) { mutableStateOf(LibraryFilter.ALL) }
    var librarySort by remember(kind) { mutableStateOf(LibrarySort.ADDED) }
    val libraryEntries = watch.library.filter { it.kind == kind }
    val items by produceState(emptyList<VodItem>(), kind, shelf, sort, watch, libraryFilter, librarySort) {
        value = when (val s = shelf) {
            Shelf.Library -> libraryItems(app, libraryEntries, watch, libraryFilter, librarySort)
            Shelf.Recent -> VodRepository.sorted(app.vod.recentlyAdded(kind), sort)
            is Shelf.Category -> app.vod.items(kind, s.entry.sourceId, s.entry.categoryId, sort)
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

    // The column is lazy: its first row may not exist yet when the page appears (see requestFocusWhenReady).
    LaunchedEffect(kind) { firstShelf.requestFocusWhenReady() }
    // A new shelf or sort starts at the top, not where the last one was scrolled to.
    LaunchedEffect(shelf.key, sort) { gridState.requestScrollToItem(0) }

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
                        "Library", detail = libraryEntries.size.toString(), selected = shelf == Shelf.Library,
                        focusRequester = if (libraryEntries.isNotEmpty()) firstShelf else null,
                        onFocused = { shelf = Shelf.Library }, onSelect = { shelf = Shelf.Library; focusGrid() },
                        onOptions = { app.panel.open(libraryPage(), returnTo) },
                    )
                }
                item { SText("Lists", 22, Modifier.padding(start = 40.dp, top = 24.dp, bottom = 8.dp), color = c.muted, weight = FontWeight.SemiBold) }
                item {
                    ListRow(
                        "Recently added", selected = shelf == Shelf.Recent, focusRequester = if (libraryEntries.isEmpty()) firstShelf else null,
                        onFocused = { shelf = Shelf.Recent }, onSelect = { shelf = Shelf.Recent; focusGrid() },
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
                        onFocused = { shelf = s },
                        onSelect = { shelf = s; focusGrid() },
                        onOptions = { app.panel.open(sortPage(), returnTo) },
                    )
                }
            }
            VDivider()
            Column(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = SignalSizes.EDGE_MARGIN.dp)) {
                val title = when (val s = shelf) {
                    Shelf.Library -> "Library"
                    Shelf.Recent -> "Recently added"
                    is Shelf.Category -> s.entry.name
                }
                SText(title, 48, weight = FontWeight.ExtraBold, maxLines = 1)
                val line = if (shelf == Shelf.Library) "${items.size} titles · ${libraryFilter.label} · Sort: ${librarySort.label}" else "${items.size} titles · Sort: ${sort.label}"
                SText(line, 24, Modifier.padding(top = 8.dp, bottom = 28.dp), color = c.muted)
                if (items.isEmpty()) {
                    val empty = when {
                        shelf == Shelf.Library && libraryEntries.isEmpty() -> "Nothing in your Library yet. Long-press a poster, or use Add to Library on its page."
                        shelf == Shelf.Library -> "Nothing ${libraryFilter.label.lowercase()} in your Library."
                        kind == VodKind.MOVIE -> "No movies here."
                        else -> "No series here."
                    }
                    SText(empty, 26, color = c.secondaryText, maxLines = 2)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus }.focusRestorer(firstPoster),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                            val key = com.signal.data.ItemKeys.of(item)
                            PosterTile(
                                item,
                                focusRequester = if (i == 0) firstPoster else null,
                                onSelect = { open(item) },
                                onOptions = if (item.id.startsWith(UNAVAILABLE)) null else ({ app.panel.open(com.signal.ui.menus.VodMenus.item(app, item, watch), returnTo) }),
                                // Watched shows full, a resume position its fraction (PLAN §4.3.6 → Poster grid).
                                progress = if (key in watch.watched) 1f else watch.positions[key]?.fraction,
                                unavailable = item.id.startsWith(UNAVAILABLE),
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
            action == RemoteAction.Right && columnHasFocus -> { focusGrid(); true }
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
fun PosterTile(item: VodItem, focusRequester: FocusRequester?, onSelect: () -> Unit, onOptions: (() -> Unit)?, progress: Float? = null, unavailable: Boolean = false) {
    val c = SignalTheme.colors
    val name = VodRepository.displayName(item)
    Focusable(Modifier.fillMaxWidth(), onSelect = onSelect, onOptions = onOptions, focusRequester = focusRequester) { focused ->
        Column {
            Box(Modifier.fillMaxWidth().border(4.dp, if (focused) c.accent else Color.Transparent).padding(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(c.neutral300), contentAlignment = Alignment.Center) {
                    val placeholder = @Composable {
                        SText(name, 24, Modifier.padding(16.dp), weight = FontWeight.ExtraBold, color = c.secondaryText, maxLines = 4)
                    }
                    if (item.posterUrl.isNullOrBlank()) placeholder() else {
                        SubcomposeAsyncImage(
                            model = item.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = { placeholder() },
                            error = { placeholder() },
                        )
                    }
                    if (progress != null && progress > 0f) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                            com.signal.ui.components.ProgressBar(progress, height = 6)
                        }
                    }
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

/** The ID of a Library title no source has any more, shown as unavailable (PLAN §4.3.11). */
private const val UNAVAILABLE = "unavailable:"

/**
 * The Library's titles as posters. Series with new episodes sorting first arrives with slice 4's
 * badge; a title no enabled source has stays, marked unavailable.
 */
private suspend fun libraryItems(
    app: com.signal.app.AppController,
    entries: List<com.signal.data.WatchRepository.LibraryEntry>,
    watch: com.signal.data.WatchRepository.State,
    filter: LibraryFilter,
    sort: LibrarySort,
): List<VodItem> {
    val enabled = app.sourceList.value.filter { it.enabled }.map { it.id }.toSet()
    val rows = entries.map { e ->
        val item = app.vod.item(e.vodItemId)?.takeIf { it.sourceId in enabled }
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
        LibrarySort.ADDED -> filtered.sortedByDescending { it.first.addedAt }
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

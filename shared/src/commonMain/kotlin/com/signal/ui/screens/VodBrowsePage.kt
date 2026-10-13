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
    val items by produceState(emptyList<VodItem>(), kind, shelf, sort) {
        value = when (val s = shelf) {
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
                        "Recently added", selected = shelf == Shelf.Recent, focusRequester = firstShelf,
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
                    Shelf.Recent -> "Recently added"
                    is Shelf.Category -> s.entry.name
                }
                SText(title, 48, weight = FontWeight.ExtraBold, maxLines = 1)
                SText("${items.size} titles · Sort: ${sort.label}", 24, Modifier.padding(top = 8.dp, bottom = 28.dp), color = c.muted)
                if (items.isEmpty()) {
                    SText(if (kind == VodKind.MOVIE) "No movies here." else "No series here.", 26, color = c.secondaryText)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().onFocusChanged { gridHasFocus = it.hasFocus }.focusRestorer(firstPoster),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                            PosterTile(
                                item,
                                focusRequester = if (i == 0) firstPoster else null,
                                // Movie and series pages arrive in slice 2.
                                onSelect = {},
                                onOptions = null,
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
fun PosterTile(item: VodItem, focusRequester: FocusRequester?, onSelect: () -> Unit, onOptions: (() -> Unit)?, progress: Float? = null) {
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
            item.year?.let { SText(it.toString(), 20, Modifier.padding(start = 8.dp), color = c.muted) }
        }
    }
}

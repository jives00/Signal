package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.data.ItemKeys
import com.signal.data.Settings
import com.signal.data.VodRepository
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.search.SearchRepository
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.ListRow
import com.signal.ui.components.LogoTile
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SButton
import com.signal.ui.components.SField
import com.signal.ui.components.SText
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.menus.VodMenus
import com.signal.ui.theme.SignalSizes
import com.signal.ui.components.labelOf
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Search's result types (PLAN §4.3.14). Movies and Series show only when a source has them. */
internal enum class SearchType(val label: String) {
    CHANNELS("Channels"), PROGRAMS("Programs"), MOVIES("Movies"), SERIES("Series");

    val vodKind: VodKind? get() = when (this) { MOVIES -> VodKind.MOVIE; SERIES -> VodKind.SERIES; else -> null }
}

/**
 * Search (PLAN §4.3.14): the field and mic, the type chips, then one row per type (or a grid of one
 * type), or the recent searches while the field is empty. Results follow typing after a 300ms pause.
 */
@Composable
fun SearchPage() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val chrome = remember { PageChrome() }
    // The field is what Down from the nav bar reaches. Down goes field → chips → results, explicitly:
    // the wide field is closer, by geometry, to the results than to the small chips.
    val field = chrome.contentFocus
    val chips = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    // The first item of whatever shows below the chips: where entering the results lands first.
    val firstResult = remember { FocusRequester() }
    var fieldHasFocus by remember { mutableStateOf(false) }
    // Find in Movies/Series can open Search with a title and Movies + Series (PLAN §4.3.4).
    val request = remember { app.searchRequest.value.also { app.searchRequest.value = null } }
    var query by remember { mutableStateOf(request?.query.orEmpty()) }
    // Movies and Series are offered only when a source has them (PLAN §4.3.14).
    val movieCount by app.movieCount.collectAsState()
    val seriesCount by app.seriesCount.collectAsState()
    val types = SearchType.entries.filter { t -> when (t) { SearchType.MOVIES -> movieCount > 0; SearchType.SERIES -> seriesCount > 0; else -> true } }
    // The type filter: empty is All. Remembered on the device (PLAN §4.3.14); a Find request's isn't.
    var filter by remember {
        mutableStateOf(request?.types?.mapNotNull { n -> SearchType.entries.firstOrNull { it.name == n } }?.toSet() ?: loadFilter(app))
    }
    val shown = (if (filter.isEmpty()) types else filter.filter { it in types }).toSet()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var programs by remember { mutableStateOf<List<SearchRepository.ProgramHit>>(emptyList()) }
    var movies by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var recents by remember { mutableStateOf(loadRecents(app)) }
    val now = rememberNow()
    val watch by app.watchState.collectAsState()
    val newEpisodes by app.newEpisodes.collectAsState()

    // Kept alive between visits (KeptPage): the field takes focus each time Search opens.
    com.signal.ui.components.OnPageShown { field.requestFocusWhenReady() }
    // A request while Search is already open (Find from a program Search opened).
    LaunchedEffect(Unit) {
        app.searchRequest.collect { r ->
            if (r == null) return@collect
            app.searchRequest.value = null
            query = r.query
            filter = r.types.mapNotNull { n -> SearchType.entries.firstOrNull { it.name == n } }.toSet()
        }
    }

    // Search as you type: 300ms after typing stops, from 2 characters. Each type fills in as its query returns.
    LaunchedEffect(query, shown) {
        if (query.trim().length < 2) { channels = emptyList(); programs = emptyList(); movies = emptyList(); series = emptyList(); return@LaunchedEffect }
        delay(300)
        val q = query
        coroutineScope {
            launch { channels = if (SearchType.CHANNELS in shown) app.search.channels(q) else emptyList() }
            launch { programs = if (SearchType.PROGRAMS in shown) app.search.programs(q, now) else emptyList() }
            launch { movies = if (SearchType.MOVIES in shown) app.vod.search(q, VodKind.MOVIE) else emptyList() }
            launch { series = if (SearchType.SERIES in shown) app.vod.search(q, VodKind.SERIES) else emptyList() }
        }
    }
    val found = Found(channels, programs, movies, series)

    // A search is saved when a result opens, or on leaving Search after typing (PLAN §4.3.14).
    val queryNow = rememberUpdatedState(query)
    fun saveSearch(q: String) {
        if (q.trim().length < 2) return
        recents = (listOf(q.trim()) + recents.filter { !it.equals(q.trim(), ignoreCase = true) }).take(10)
        saveRecents(app, recents)
    }
    // Leaving saves the search and starts the next visit fresh, on the recent searches (PLAN §4.3.14).
    com.signal.ui.components.OnPageHidden { saveSearch(queryNow.value); query = "" }

    fun openChannel(ch: Channel) {
        saveSearch(query)
        val group = ChannelGroup.Provider(ch.stream.sourceId, ch.stream.groupName, ch.stream.groupName)
        app.playback.tune(ch, fullScreen = true, group = group)
        app.navigator.go(Route.FullScreen(group))
    }

    fun openProgram(hit: SearchRepository.ProgramHit, options: Boolean, card: FocusRequester) {
        saveSearch(query)
        if (!options && hit.program.isOn(now)) openChannel(hit.channel)
        else app.panel.open(programDetails(app, hit.channel, hit.program, now), card)
    }

    fun openVod(item: VodItem) {
        saveSearch(query)
        app.navigator.go(if (item.kind == VodKind.MOVIE) Route.MovieDetail(item.id) else Route.SeriesDetail(item.id))
    }

    fun vodMenu(item: VodItem, card: FocusRequester) {
        saveSearch(query)
        VodMenus.open(app, item, watch, card)
    }

    val vodCard: @Composable (VodItem, FocusRequester?) -> Unit = { item, first ->
        val key = ItemKeys.of(item)
        VodCard(item, if (key in watch.watched) null else watch.positions[key]?.fraction, item.kind == VodKind.SERIES && key in newEpisodes, first, ::openVod, ::vodMenu)
    }

    TopLevelPage(Route.Search, chrome) {
        Column(Modifier.fillMaxSize().padding(horizontal = SignalSizes.EDGE_MARGIN.dp)) {
            // Voice is the on-screen keyboard's mic. The remote's mic button belongs to Assistant,
            // which keeps the audio to itself (PLAN §4.3.14).
            Box(Modifier.fillMaxWidth().padding(top = 16.dp).onFocusChanged { fieldHasFocus = it.hasFocus }) {
                val hint = if (SearchType.MOVIES in types || SearchType.SERIES in types) "Search channels, programs, movies and series" else "Search channels and programs"
                SField(hint, query, { query = it }, focusRequester = field, down = chips)
            }
            // The type chips. All is on by default; the others toggle (PLAN §4.3.14).
            Row(Modifier.padding(top = 16.dp).focusProperties { down = results }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("All", filter.isEmpty(), Modifier.focusRequester(chips)) { filter = emptySet(); saveFilter(app, filter) }
                types.forEach { t ->
                    Chip(t.label, t in filter) {
                        filter = if (t in filter) filter - t else filter + t
                        if (filter.containsAll(types)) filter = emptySet()
                        saveFilter(app, filter)
                    }
                }
            }
            Box(Modifier.fillMaxSize().focusRequester(results).focusRestorer(firstResult)) { when {
                query.trim().length < 2 -> RecentSearches(recents, firstResult, onRun = { query = it }, onRemove = { r ->
                    recents = recents - r
                    saveRecents(app, recents)
                }, onClear = { recents = emptyList(); saveRecents(app, recents) })
                shown.size == 1 -> ResultGrid(shown.single(), found, now, firstResult, ::openChannel, ::openProgram, vodCard)
                else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    // All: one row per type, in the order Channels, Programs, Movies, Series. The first
                    // card of the first row with results is where entering the results lands.
                    val firstType = types.firstOrNull { it in shown && found.count(it) > 0 }
                    fun first(t: SearchType, i: Int) = if (i == 0 && t == firstType) firstResult else null
                    item {
                        ResultRow("Channels", channels.size, onSeeAll = { filter = setOf(SearchType.CHANNELS) }) {
                            itemsIndexed(channels.take(20), key = { _, ch -> ch.stream.id }) { i, ch -> ChannelCard(app, ch, now, first(SearchType.CHANNELS, i), ::openChannel) }
                        }
                    }
                    item {
                        ResultRow("Programs", programs.size, onSeeAll = { filter = setOf(SearchType.PROGRAMS) }) {
                            itemsIndexed(programs.take(20), key = { _, hit -> hit.program.id }) { i, hit -> ProgramCard(app, hit, now, first(SearchType.PROGRAMS, i), ::openProgram) }
                        }
                    }
                    item {
                        ResultRow("Movies", movies.size, onSeeAll = { filter = setOf(SearchType.MOVIES) }) {
                            itemsIndexed(movies.take(20), key = { _, m -> m.id }) { i, m -> vodCard(m, first(SearchType.MOVIES, i)) }
                        }
                    }
                    item {
                        ResultRow("Series", series.size, onSeeAll = { filter = setOf(SearchType.SERIES) }) {
                            itemsIndexed(series.take(20), key = { _, s -> s.id }) { i, s -> vodCard(s, first(SearchType.SERIES, i)) }
                        }
                    }
                    if (firstType == null) item {
                        SText("No results.", 24, Modifier.padding(top = 32.dp), color = c.muted)
                    }
                }
            } }
        }
    }

    // Back: from the results to the field; from the field, the nav bar. Registered after TopLevelPage's
    // handler, so it's asked first.
    RemoteHandler { action ->
        if (action == RemoteAction.Back && !fieldHasFocus && !chrome.navHasFocus) {
            runCatching { field.requestFocus() }
            true
        } else false
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val c = SignalTheme.colors
    Focusable(modifier, onSelect = onSelect) { focused ->
        SText(
            label, 24,
            Modifier.background(if (focused) c.accent else if (on) c.surface else c.bg).padding(horizontal = 20.dp, vertical = 10.dp),
            weight = FontWeight.SemiBold, color = if (focused) c.onAccent else if (on) c.text else c.muted,
        )
    }
}

/** One type's row: a header naming the type and its count, the cards, and "See all →" (PLAN §4.3.14). */
@Composable
private fun ResultRow(title: String, count: Int, onSeeAll: () -> Unit, cards: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    if (count == 0) return
    val c = SignalTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        SText("$title · ${countLabel(count)}", 28, Modifier.padding(bottom = 12.dp), weight = FontWeight.ExtraBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(end = SignalSizes.EDGE_MARGIN.dp)) {
            cards()
            item {
                Focusable(Modifier.width(220.dp), onSelect = onSeeAll) { focused ->
                    Box(Modifier.height(136.dp).fillMaxWidth().background(if (focused) c.accent else c.surface).padding(16.dp), contentAlignment = Alignment.CenterStart) {
                        SText("See all →", 22, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
                    }
                }
            }
        }
    }
}

/** One search's results, per type. */
internal class Found(
    val channels: List<Channel>,
    val programs: List<SearchRepository.ProgramHit>,
    val movies: List<VodItem>,
    val series: List<VodItem>,
) {
    fun count(t: SearchType) = when (t) {
        SearchType.CHANNELS -> channels.size
        SearchType.PROGRAMS -> programs.size
        SearchType.MOVIES -> movies.size
        SearchType.SERIES -> series.size
    }
}

@Composable
private fun ResultGrid(
    type: SearchType,
    found: Found,
    now: Long,
    first: FocusRequester,
    openChannel: (Channel) -> Unit,
    openProgram: (SearchRepository.ProgramHit, Boolean, FocusRequester) -> Unit,
    vodCard: @Composable (VodItem, FocusRequester?) -> Unit,
) {
    val app = LocalApp.current
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        SText("${type.label} · ${countLabel(found.count(type))}", 28, Modifier.padding(bottom = 12.dp), weight = FontWeight.ExtraBold)
        LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            when (type) {
                SearchType.CHANNELS -> itemsIndexed(found.channels, key = { _, ch -> ch.stream.id }) { i, ch -> ChannelCard(app, ch, now, if (i == 0) first else null, openChannel) }
                SearchType.PROGRAMS -> itemsIndexed(found.programs, key = { _, hit -> hit.program.id }) { i, hit -> ProgramCard(app, hit, now, if (i == 0) first else null, openProgram) }
                SearchType.MOVIES -> itemsIndexed(found.movies, key = { _, m -> m.id }) { i, m -> vodCard(m, if (i == 0) first else null) }
                SearchType.SERIES -> itemsIndexed(found.series, key = { _, s -> s.id }) { i, s -> vodCard(s, if (i == 0) first else null) }
            }
        }
    }
}

/**
 * A movie or series (PLAN §4.3.14): the poster, title and year, a Movie or Series label, and the
 * resume bar for a started movie or the New episode badge for a series.
 */
@Composable
private fun VodCard(item: VodItem, progress: Float?, newEpisode: Boolean, first: FocusRequester?, open: (VodItem) -> Unit, menu: (VodItem, FocusRequester) -> Unit) {
    val c = SignalTheme.colors
    val own = remember { FocusRequester() }
    val me = first ?: own
    Focusable(Modifier.width(400.dp), focusRequester = me, onSelect = { open(item) }, onOptions = { menu(item, me) }) { focused ->
        val ink = if (focused) c.onAccent else c.text
        val muted = if (focused) c.onAccent else c.muted
        Row(Modifier.fillMaxWidth().height(136.dp).background(if (focused) c.accent else c.surface).padding(12.dp)) {
            Box(Modifier.width(75.dp).height(112.dp).background(c.neutral300)) {
                com.signal.ui.components.ImageOrPlaceholder(item.posterUrl, item.fallbackPosterUrl, ContentScale.Crop, Modifier.fillMaxSize()) {}
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(VodRepository.displayName(item), 22, Modifier.weight(1f), weight = FontWeight.SemiBold, color = ink, maxLines = 2)
                    SText(
                        if (item.kind == VodKind.MOVIE) "Movie" else "Series", 20,
                        Modifier.padding(start = 8.dp).background(if (focused) c.accent600 else c.tagBg).padding(horizontal = 10.dp, vertical = 2.dp),
                        weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.tagText,
                    )
                }
                item.year?.let { SText(it.toString(), 20, Modifier.padding(top = 4.dp), color = muted) }
                if (newEpisode) NewEpisodeBadge(Modifier.padding(top = 8.dp))
                if (progress != null && progress > 0f) ProgressBar(progress, Modifier.padding(top = 12.dp), onAccent = focused)
            }
        }
    }
}

/** A channel: logo, number and name, and the program on now with a progress bar. */
@Composable
private fun ChannelCard(app: AppController, ch: Channel, now: Long, first: FocusRequester?, open: (Channel) -> Unit) {
    val c = SignalTheme.colors
    // A menu or details panel returns focus to this card when it closes.
    val own = remember { FocusRequester() }
    val me = first ?: own
    Focusable(
        Modifier.width(400.dp),
        focusRequester = me,
        onSelect = { open(ch) },
        onOptions = { app.panel.open(app.menus.channelMenu(ch, null, null), me) },
    ) { focused ->
        val on = rememberNowAndNext(ch, now).first
        Column(Modifier.fillMaxWidth().height(136.dp).background(if (focused) c.accent else c.surface).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoTile(ch.displayName, ch.logoUrl, 72, 48, onAccent = focused)
                SText(labelOf(ch), 22, Modifier.padding(start = 16.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
            }
            SText(on?.title ?: "No guide information", 20, Modifier.padding(top = 10.dp), color = if (focused) c.onAccent else c.muted)
            if (on != null) ProgressBar(((now - on.start).toFloat() / on.durationMs).coerceIn(0f, 1f), Modifier.padding(top = 8.dp), onAccent = focused)
        }
    }
}

/** A program: the channel's logo, the title, the channel and when it airs, and a type label (programs and movies can share titles). */
@Composable
private fun ProgramCard(app: AppController, hit: SearchRepository.ProgramHit, now: Long, first: FocusRequester?, open: (SearchRepository.ProgramHit, Boolean, FocusRequester) -> Unit) {
    val c = SignalTheme.colors
    val p = hit.program
    val own = remember { FocusRequester() }
    val me = first ?: own
    Focusable(Modifier.width(400.dp), focusRequester = me, onSelect = { open(hit, false, me) }, onOptions = { open(hit, true, me) }) { focused ->
        val ink = if (focused) c.onAccent else c.text
        val muted = if (focused) c.onAccent else c.muted
        Column(Modifier.fillMaxWidth().height(136.dp).background(if (focused) c.accent else c.surface).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoTile(hit.channel.displayName, hit.channel.logoUrl, 72, 48, onAccent = focused)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    SText(p.title, 22, weight = FontWeight.SemiBold, color = ink)
                    SText(hit.channel.displayName, 20, color = muted)
                }
                SText("Program", 20, Modifier.background(if (focused) c.accent600 else c.tagBg).padding(horizontal = 10.dp, vertical = 2.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.tagText)
            }
            SText(if (p.isOn(now)) "On now" else programTime(app, p, now).let { if (dayLabel(p.start, now) == "Today") "Today $it" else it }, 20, Modifier.padding(top = 10.dp), color = muted)
            if (p.isOn(now)) ProgressBar(((now - p.start).toFloat() / p.durationMs).coerceIn(0f, 1f), Modifier.padding(top = 8.dp), onAccent = focused)
        }
    }
}

/** The last 10 searches while the field is empty: OK runs one, long-press removes it (PLAN §4.3.14). */
@Composable
private fun RecentSearches(recents: List<String>, first: FocusRequester, onRun: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    val c = SignalTheme.colors
    LazyColumn(Modifier.fillMaxSize().padding(top = 24.dp).width(900.dp)) {
        item { SText(if (recents.isEmpty()) "Type at least two letters to search." else "Recent searches", 24, Modifier.padding(bottom = 8.dp), weight = FontWeight.SemiBold, color = c.muted) }
        itemsIndexed(recents, key = { _, r -> r }) { i, r -> ListRow(r, onSelect = { onRun(r) }, onOptions = { onRemove(r) }, focusRequester = if (i == 0) first else null) }
        if (recents.isNotEmpty()) item { SButton("Clear all", onSelect = onClear, modifier = Modifier.padding(top = 16.dp)) }
    }
}

private val json = Json

/** Recent searches: this device only, never synced (PLAN §4.3.14). */
private fun loadRecents(app: AppController): List<String> =
    app.settings.raw(Settings.Key.RECENT_SEARCHES)?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

private fun saveRecents(app: AppController, recents: List<String>) =
    app.settings.put(Settings.Key.RECENT_SEARCHES, json.encodeToString(recents))

private fun loadFilter(app: AppController): Set<SearchType> =
    app.settings.raw(Settings.Key.SEARCH_FILTER)?.split(',')?.mapNotNull { n -> SearchType.entries.firstOrNull { it.name == n } }?.toSet().orEmpty()

private fun saveFilter(app: AppController, filter: Set<SearchType>) =
    app.settings.put(Settings.Key.SEARCH_FILTER, filter.joinToString(",") { it.name }.ifEmpty { null })

/** Results are capped at 60 per type; a full page says so rather than claiming exactly 60. */
private fun countLabel(n: Int) = if (n >= 60) "60+" else n.toString()

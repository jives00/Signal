package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import coil3.compose.AsyncImage
import com.signal.app.Route
import com.signal.data.ItemKeys
import com.signal.data.VodRepository
import com.signal.data.WatchRepository
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.model.Episode
import com.signal.model.SeriesDetail
import com.signal.model.VodItem
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.HDivider
import com.signal.ui.components.Icon
import com.signal.ui.components.ListRow
import com.signal.ui.components.ProgressBar
import com.signal.ui.components.SButton
import com.signal.ui.components.SText
import com.signal.ui.components.SignalIcons
import com.signal.ui.components.VDivider
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.menus.VodMenus
import com.signal.ui.menus.clockTime
import com.signal.ui.menus.runtime
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch

/** What the series page's primary button does (PLAN §4.3.9): continue, next up, or start. */
data class SeriesNext(val episode: Episode, val label: String)

/** Continue the episode in progress, else the one after the last watched, else S1 E1. */
fun seriesNext(seriesKey: String, detail: SeriesDetail, state: WatchRepository.State): SeriesNext? {
    val episodes = detail.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    if (episodes.isEmpty()) return null
    val keyed = episodes.associateBy { ItemKeys.episode(seriesKey, it) }
    // The most recently played episode with a position.
    state.positions.values.filter { it.seriesKey == seriesKey }.maxByOrNull { it.updatedAt }?.let { p ->
        keyed[p.key]?.let { return SeriesNext(it, "Continue ${it.code} · ${clockTime(p.positionMs)}") }
    }
    val lastWatched = episodes.lastOrNull { ItemKeys.episode(seriesKey, it) in state.watched }
    if (lastWatched != null) {
        val next = detail.after(lastWatched.season, lastWatched.episode)?.takeIf { ItemKeys.episode(seriesKey, it) !in state.watched }
        return next?.let { SeriesNext(it, "Next up ${it.code}") }
    }
    return SeriesNext(episodes.first(), "Play ${episodes.first().code}")
}

/**
 * Series and episodes (PLAN §4.3.9, handoff screen 7): the series and its seasons on the left, the
 * selected season's episodes on the right. OK plays an episode, resuming it; watched ones carry a check.
 */
@Composable
fun SeriesPage(route: Route.SeriesDetail) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val item by produceState<VodItem?>(null, route.itemId) { value = app.vod.item(route.itemId) }
    val provided by produceState<SeriesDetail?>(null, item) { item?.let { value = app.vodDetails.series(it) } }
    // The companion's TMDB metadata on top: episode titles, overviews and stills (PLAN §4.3.12).
    val meta by produceState<com.signal.nas.VodMeta?>(null, item) { item?.let { value = app.vodEnrichment.meta(it) } }
    val detail = remember(provided, meta) { com.signal.data.VodEnrichment.merge(provided, meta, app.nas::imageUrl) }
    val state by app.watchState.collectAsState()
    var season by remember(route) { mutableStateOf(route.season) }
    val primary = remember { FocusRequester() }
    val firstEpisode = remember { FocusRequester() }
    // The episode Find in Series asked for (PLAN §4.3.4), focused once the detail is in.
    val targetEpisode = remember { FocusRequester() }
    val returnTo = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var leftHasFocus by remember { mutableStateOf(false) }
    var episodesHaveFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val it = item ?: return
    val d = detail
    val seriesKey = ItemKeys.series(it, d?.tmdbId ?: it.tmdbId)
    val next = d?.let { seriesNext(seriesKey, it, state) }
    val shownSeason = season?.takeIf { s -> d == null || s in d.seasons } ?: next?.episode?.season ?: d?.seasons?.firstOrNull()
    val episodes = shownSeason?.let { s -> d?.inSeason(s) }.orEmpty()
    val inLibrary = state.inLibrary(seriesKey) || state.inLibrary(ItemKeys.of(it))

    fun play(e: Episode, fromStart: Boolean = false) {
        val series = d ?: return
        app.scope.launch {
            app.vodPlayback.unattendedEpisodes = 0
            app.vodPlayback.playEpisode(it, series, e, fromStart)
            app.navigator.go(Route.VodPlayer)
        }
    }

    fun focusEpisodes() {
        if (episodes.isEmpty()) return
        scope.launch { listState.scrollToItem(0); firstEpisode.requestFocusWhenReady() }
    }

    fun episodeMenu(e: Episode): PanelPage {
        val key = ItemKeys.episode(seriesKey, e)
        val watched = key in state.watched
        return PanelPage.Actions("${e.code} · ${e.title}", listOf(
            PanelAction(if (watched) "Mark unwatched" else "Mark watched") {
                app.scope.launch { if (watched) app.watch.markUnwatched(key) else app.watch.markWatched(key, "EPISODE", it.id, seriesKey, e.season, e.episode) }
                null
            },
            PanelAction("Mark all previous as watched") {
                app.scope.launch {
                    d?.episodes?.filter { x -> x.season < e.season || (x.season == e.season && x.episode < e.episode) }?.forEach { x ->
                        app.watch.markWatched(ItemKeys.episode(seriesKey, x), "EPISODE", it.id, seriesKey, x.season, x.episode)
                    }
                }
                null
            },
            PanelAction("Play from start") { play(e, fromStart = true); null },
        ))
    }

    fun seasonMenu(s: Int): PanelPage = PanelPage.Actions("Season $s", listOf(true, false).map { watched ->
        PanelAction(if (watched) "Mark season watched" else "Mark season unwatched") {
            app.scope.launch {
                d?.inSeason(s)?.forEach { e ->
                    val key = ItemKeys.episode(seriesKey, e)
                    if (watched) app.watch.markWatched(key, "EPISODE", it.id, seriesKey, e.season, e.episode) else app.watch.markUnwatched(key)
                }
            }
            null
        }
    })

    // Once the detail is in: the episode asked for, else the primary button (Play or Continue, else Add to Library).
    val targetIndex = if (route.episode != null && shownSeason == route.season) episodes.indexOfFirst { e -> e.episode == route.episode } else -1
    LaunchedEffect(route, d != null, next != null) {
        if (d == null) return@LaunchedEffect
        if (targetIndex >= 0) {
            listState.requestScrollToItem(targetIndex)
            (if (targetIndex == 0) firstEpisode else targetEpisode).requestFocusWhenReady()
        } else primary.requestFocusWhenReady()
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(640.dp).fillMaxHeight().padding(start = 96.dp, end = 64.dp)
                .onFocusChanged { leftHasFocus = it.hasFocus }.focusRequester(returnTo).focusRestorer(primary),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { SText(listOfNotNull("Series", d?.genres?.firstOrNull()).joinToString(" · "), 24, Modifier.padding(top = 96.dp), color = c.accentText, weight = FontWeight.SemiBold) }
            item { SText(VodRepository.displayName(it), 72, weight = FontWeight.ExtraBold, maxLines = 3) }
            item {
                Column {
                    HDivider()
                    SText(
                        metaLine(it.year, null, d?.rating ?: it.rating, d?.genres.orEmpty(), d?.contentRating) + (d?.seasons?.size?.let { n -> "  ·  $n season${if (n == 1) "" else "s"}" } ?: ""),
                        24, Modifier.padding(vertical = 14.dp), weight = FontWeight.SemiBold,
                    )
                    HDivider()
                }
            }
            (d?.plot ?: it.plot)?.let { plot -> item { SText(plot, 24, color = c.secondaryText, maxLines = 4) } }
            item {
                if (next != null) {
                    SButton(next.label, { play(next.episode) }, Modifier.fillMaxWidth(), primary = true, focusRequester = primary, fillWidth = true,
                        onOptions = { VodMenus.open(app, it, state, returnTo, seriesKey) })
                } else if (d != null) {
                    SText("No episodes from the source yet.", 24, color = c.muted)
                }
            }
            item {
                SButton(if (inLibrary) "In Library" else "Add to Library",
                    { app.scope.launch { if (inLibrary) app.watch.removeFromLibrary(seriesKey) else app.watch.addToLibrary(it, seriesKey) } },
                    Modifier.fillMaxWidth(), focusRequester = if (d != null && next == null) primary else null, fillWidth = true,
                    onOptions = { VodMenus.open(app, it, state, returnTo, seriesKey) })
            }
            itemsIndexed(d?.seasons.orEmpty()) { _, s ->
                ListRow("Season $s", detail = d?.inSeason(s)?.size?.toString(), selected = s == shownSeason,
                    onFocused = { season = s }, onSelect = { season = s; focusEpisodes() }, onOptions = { app.panel.open(seasonMenu(s), returnTo) })
            }
            item { Box(Modifier.padding(bottom = 64.dp)) }
        }
        VDivider()
        Column(Modifier.fillMaxSize()) {
            SText(shownSeason?.let { s -> "Season $s · ${episodes.size} episodes" } ?: "", 28, Modifier.padding(start = 64.dp, top = 40.dp, bottom = 20.dp), weight = FontWeight.ExtraBold)
            HDivider()
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { episodesHaveFocus = it.hasFocus }.focusRestorer(firstEpisode), state = listState) {
                itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
                    val key = ItemKeys.episode(seriesKey, e)
                    EpisodeRow(e, watched = key in state.watched, progress = state.positions[key]?.fraction,
                        focusRequester = if (i == 0) firstEpisode else if (i == targetIndex) targetEpisode else null, onSelect = { play(e) }, onOptions = { app.panel.open(episodeMenu(e), returnTo) })
                    HDivider()
                }
            }
        }
    }

    RemoteHandler { action ->
        when {
            action == RemoteAction.Right && leftHasFocus -> { focusEpisodes(); true }
            action == RemoteAction.Back && episodesHaveFocus -> { scope.launch { primary.requestFocusWhenReady() }; true }
            else -> false
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, watched: Boolean, progress: Float?, focusRequester: FocusRequester?, onSelect: () -> Unit, onOptions: () -> Unit) {
    val c = SignalTheme.colors
    Focusable(Modifier.fillMaxWidth(), onSelect = onSelect, onOptions = onOptions, focusRequester = focusRequester) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (focused) c.accent else Color.Transparent).padding(start = 64.dp, end = 96.dp, top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(Modifier.size(256.dp, 144.dp).background(c.neutral300), contentAlignment = Alignment.BottomStart) {
                e.stillUrl?.let { url -> AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (progress != null && progress > 0f) ProgressBar(progress, height = 6, onAccent = focused)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(listOfNotNull(e.code, e.durationSec?.let { runtime(it) }).joinToString(" · "), 22, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.secondaryText)
                    if (watched) Icon(SignalIcons.check, 26, if (focused) c.onAccent else c.accentText, Modifier.padding(start = 12.dp))
                }
                SText(e.title, 32, Modifier.padding(top = 4.dp), weight = FontWeight.ExtraBold, color = if (focused) c.onAccent else c.text, maxLines = 1)
                e.plot?.let { SText(it, 22, Modifier.padding(top = 4.dp), color = if (focused) c.onAccent else c.muted, maxLines = 1) }
            }
        }
    }
}

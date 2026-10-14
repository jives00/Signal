package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.signal.app.Route
import com.signal.data.ItemKeys
import com.signal.data.VodRepository
import com.signal.model.MovieDetail
import com.signal.model.VodItem
import com.signal.ui.LocalApp
import com.signal.ui.components.HDivider
import com.signal.ui.components.ProgressBar
import com.signal.ui.components.SButton
import com.signal.ui.components.SText
import com.signal.ui.components.VDivider
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.menus.VodMenus
import com.signal.ui.menus.clockTime
import com.signal.ui.menus.runtime
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch

/** The meta row's pieces: year, runtime, score, genres (PLAN §4.3.8 → Information). */
internal fun metaLine(year: Int?, durationSec: Int?, rating: Double?, genres: List<String>, contentRating: String? = null): String = listOfNotNull(
    year?.toString(),
    contentRating,
    durationSec?.let { runtime(it) },
    rating?.let { "★ ${(kotlin.math.round(it * 10) / 10.0)}" },
    genres.take(3).joinToString(", ").ifEmpty { null },
).joinToString("  ·  ")

/** The backdrop column, in grayscale as designed (handoff screen 6). */
@Composable
internal fun Backdrop(url: String?, modifier: Modifier) {
    val c = SignalTheme.colors
    Box(modifier.background(c.surface)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            )
        }
    }
}

/**
 * Movie detail (PLAN §4.3.8, handoff screen 6): the information on the left and the backdrop on the
 * right, the resume point, and the 2×2 buttons. Long-press on any button opens the VOD item menu.
 */
@Composable
fun MovieDetailPage(route: Route.MovieDetail) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val item by produceState<VodItem?>(null, route.itemId) { value = app.vod.item(route.itemId) }
    val provided by produceState<MovieDetail?>(null, item) { item?.let { value = app.vodDetails.movie(it) } }
    // The companion's TMDB metadata on top, when it has the title (PLAN §4.3.12).
    val meta by produceState<com.signal.nas.VodMeta?>(null, item) { item?.let { value = app.vodEnrichment.meta(it) } }
    val detail = remember(provided, meta) { com.signal.data.VodEnrichment.merge(provided, meta, app.nas::imageUrl) }
    val state by app.watchState.collectAsState()
    val primary = remember { FocusRequester() }
    val returnTo = remember { FocusRequester() }

    val it = item ?: return
    val key = ItemKeys.movie(it, detail?.tmdbId ?: it.tmdbId)
    val position = state.positions[key]
    val inLibrary = state.inLibrary(key) || state.inLibrary(ItemKeys.of(it))
    val menu = { VodMenus.open(app, it, state, returnTo, key) }
    // Play uses the preferred source when several have the movie (PLAN §4.3.8 → Stream choice).
    fun play(fromStart: Boolean) {
        app.scope.launch {
            app.vodPlayback.playMovie(app.vod.copies(it).first(), fromStart, asKey = key)
            app.navigator.go(Route.VodPlayer)
        }
    }

    LaunchedEffect(route.itemId) { primary.requestFocusWhenReady() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(SignalSizes.EDGE_MARGIN.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            SText(listOfNotNull("Movie", detail?.genres?.firstOrNull()).joinToString(" · "), 24, color = c.accentText, weight = FontWeight.SemiBold)
            SText(VodRepository.displayName(it), if (VodRepository.displayName(it).length > 24) 72 else 96, weight = FontWeight.ExtraBold, maxLines = 2)
            Column {
                HDivider()
                SText(metaLine(it.year, detail?.durationSec, detail?.rating ?: it.rating, detail?.genres.orEmpty(), detail?.contentRating), 26, Modifier.padding(vertical = 16.dp), weight = FontWeight.SemiBold)
                HDivider()
            }
            (detail?.plot ?: it.plot)?.let { plot -> SText(plot, 28, color = c.secondaryText, maxLines = 5) }
            val credits = listOfNotNull(detail?.director?.let { d -> "Director: $d" }, detail?.cast?.let { cast -> "Cast: $cast" }).joinToString("   ")
            if (credits.isNotEmpty()) SText(credits, 24, color = c.muted, maxLines = 2)
            Spacer(Modifier.weight(1f))
            if (position != null && position.durationMs > 0) {
                Column {
                    SText("Resume from ${clockTime(position.positionMs)} · ${runtime(((position.durationMs - position.positionMs) / 1000).toInt())} left", 24, color = c.muted)
                    ProgressBar(position.fraction, Modifier.padding(top = 12.dp), height = 6)
                }
            }
            Column(Modifier.width(980.dp).focusRequester(returnTo).focusRestorer(primary), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SButton(
                        if (position != null) "Resume ${clockTime(position.positionMs)}" else "Play", { play(false) },
                        Modifier.weight(1f), focusRequester = primary, onOptions = menu, fillWidth = true,
                    )
                    SButton("Play from start", { play(true) }, Modifier.weight(1f), onOptions = menu, fillWidth = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    detail?.trailerKey?.let { trailer ->
                        SButton("Trailer", { app.platform.openUrl("https://www.youtube.com/watch?v=$trailer") }, Modifier.weight(1f), onOptions = menu, fillWidth = true)
                    }
                    SButton(
                        if (inLibrary) "In Library" else "Add to Library",
                        { app.scope.launch { if (inLibrary) app.watch.removeFromLibrary(key) else app.watch.addToLibrary(it, key) } },
                        Modifier.weight(1f), onOptions = menu, fillWidth = true,
                    )
                    if (detail?.trailerKey == null) Spacer(Modifier.weight(1f))
                }
            }
        }
        VDivider()
        Backdrop(detail?.backdropUrl ?: detail?.posterUrl ?: it.posterUrl, Modifier.width(960.dp).fillMaxHeight())
    }
}

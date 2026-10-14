package com.signal.ui.menus

import androidx.compose.ui.focus.FocusRequester
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.data.ItemKeys
import com.signal.data.WatchRepository
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import kotlinx.coroutines.launch

/** "42:10" or "1:42:10", for resume points (PLAN §4.3.8). */
fun clockTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "$m:${sec.toString().padStart(2, '0')}"
}

/** "1h 36m", for runtimes. */
fun runtime(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/**
 * The VOD item menu (PLAN §4.2, §4.3.6, §4.3.8): Library, watched state, Play from start, and Play
 * from… when more than one source has the movie. [state] is the watch state now; the menu doesn't
 * wait on the database. [copies] are the movie's copies in each source, preferred first.
 */
object VodMenus {
    /** Looks up the other sources' copies (an in-memory lookup), then opens the menu. */
    fun open(app: AppController, item: VodItem, state: WatchRepository.State, returnTo: FocusRequester, key: String = ItemKeys.of(item)) {
        app.scope.launch {
            val copies = if (item.kind == VodKind.MOVIE) app.vod.copies(item) else listOf(item)
            app.panel.open(item(app, item, state, copies, key), returnTo)
        }
    }

    fun item(app: AppController, item: VodItem, state: WatchRepository.State, copies: List<VodItem> = listOf(item), key: String = ItemKeys.of(item)): PanelPage.Actions {
        val inLibrary = state.inLibrary(key)
        val launch = { block: suspend () -> Unit -> app.scope.launch { block() }; Unit }
        val actions = buildList {
            add(PanelAction(if (inLibrary) "Remove from Library" else "Add to Library") {
                launch { if (inLibrary) app.watch.removeFromLibrary(key) else app.watch.addToLibrary(item, key) }
                null
            })
            if (item.kind == VodKind.MOVIE) {
                val watched = key in state.watched
                if (!watched) add(PanelAction("Mark watched") { launch { app.watch.markWatched(key, "MOVIE", item.id) }; null })
                // Reset: unwatched, with no resume point (milestone 6).
                if (watched || key in state.positions) add(PanelAction("Reset", detail = "Unwatched, from the start") { launch { app.watch.markUnwatched(key) }; null })
                add(PanelAction("Play from start") {
                    launch {
                        app.vodPlayback.playMovie(copies.first(), fromStart = true, asKey = key)
                        app.navigator.go(Route.VodPlayer)
                    }
                    null
                })
                if (copies.size > 1) add(PanelAction("Play from…") { playFrom(app, copies, key) })
            } else {
                add(PanelAction("Mark series watched") { seriesWatched(app, item, true); null })
                add(PanelAction("Reset series", detail = "Every episode unwatched") { resetSeries(app, item); null })
            }
        }
        return PanelPage.Actions(com.signal.data.VodRepository.displayName(item), actions)
    }

    /** Each source with the movie, the preferred one first (PLAN §4.3.8 → Stream choice). Resume carries across them. */
    private fun playFrom(app: AppController, copies: List<VodItem>, key: String): PanelPage {
        val names = app.sourceList.value.associate { it.id to it.name }
        return PanelPage.Actions("Play from…", copies.mapIndexed { i, copy ->
            PanelAction(names[copy.sourceId] ?: copy.sourceId, detail = if (i == 0) "Preferred" else null) {
                app.scope.launch {
                    app.vodPlayback.playMovie(copy, asKey = key)
                    app.navigator.go(Route.VodPlayer)
                }
                null
            }
        })
    }

    /** Reset on a series: every episode unwatched and no resume points, under the key the series page uses. */
    fun resetSeries(app: AppController, item: VodItem) {
        app.scope.launch {
            val tmdbId = app.vodDetails.series(item)?.tmdbId ?: item.tmdbId
            app.watch.resetSeries(ItemKeys.series(item, tmdbId))
        }
    }

    /** Every episode of a series watched or unwatched (PLAN §4.3.7). */
    fun seriesWatched(app: AppController, item: VodItem, watched: Boolean) {
        app.scope.launch {
            val detail = app.vodDetails.series(item) ?: return@launch
            val seriesKey = ItemKeys.series(item, detail.tmdbId ?: item.tmdbId)
            for (e in detail.episodes) {
                val key = ItemKeys.episode(seriesKey, e)
                if (watched) app.watch.markWatched(key, "EPISODE", item.id, seriesKey, e.season, e.episode) else app.watch.markUnwatched(key)
            }
        }
    }
}

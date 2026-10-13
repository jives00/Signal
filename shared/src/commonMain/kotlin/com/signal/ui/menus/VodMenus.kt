package com.signal.ui.menus

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
 * The VOD item menu (PLAN §4.2, §4.3.6): Library, watched state, and Play from start for movies.
 * [state] is the watch state now; the menu doesn't wait on the database.
 */
object VodMenus {
    fun item(app: AppController, item: VodItem, state: WatchRepository.State): PanelPage.Actions {
        val key = ItemKeys.of(item)
        val inLibrary = state.inLibrary(key)
        val launch = { block: suspend () -> Unit -> app.scope.launch { block() }; Unit }
        val actions = buildList {
            add(PanelAction(if (inLibrary) "Remove from Library" else "Add to Library") {
                launch { if (inLibrary) app.watch.removeFromLibrary(key) else app.watch.addToLibrary(item, key) }
                null
            })
            if (item.kind == VodKind.MOVIE) {
                val watched = key in state.watched
                add(PanelAction(if (watched) "Mark unwatched" else "Mark watched") {
                    launch { if (watched) app.watch.markUnwatched(key) else app.watch.markWatched(key, "MOVIE", item.id) }
                    null
                })
                add(PanelAction("Play from start") {
                    launch {
                        app.vodPlayback.playMovie(item, fromStart = true)
                        app.navigator.go(Route.VodPlayer)
                    }
                    null
                })
            } else {
                add(PanelAction("Mark series watched") { seriesWatched(app, item, true); null })
                add(PanelAction("Mark series unwatched") { seriesWatched(app, item, false); null })
            }
        }
        return PanelPage.Actions(com.signal.data.VodRepository.displayName(item), actions)
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

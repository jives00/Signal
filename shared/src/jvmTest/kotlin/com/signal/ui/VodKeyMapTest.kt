package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertTrue

/** Movies and Series browse (PLAN §4.3.6–4.3.7, §12.1): the category column, the poster grid, Back. */
class VodKeyMapTest {
    @Test fun columnGridAndBack() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        assertTrue(shows("300 titles"))
        // Right goes into the grid, on the first poster.
        press(RemoteAction.Right)
        eventually("a poster focused") { focusedText()?.startsWith("Recently added") == false && focusedText() != null }
        val first = focusedText()
        press(RemoteAction.Right)
        eventually("the next poster") { focusedText() != first }
        // Back returns to the column, then Back again goes to the nav bar (PLAN §12.1).
        press(RemoteAction.Back)
        focusedStartsWith("Recently added")
        press(RemoteAction.Back)
        focusedStartsWith("Movies")
    }

    @Test fun focusingACategoryShowsIt() = keyMapTest(start = Route.Series) {
        focusedStartsWith("Recently added")
        press(RemoteAction.Down)
        focusedStartsWith("Drama Series")
        eventually("the category's titles") { shows("12 titles") }
    }

    @Test fun longPressSortsTheShelf() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        press(RemoteAction.Options)
        focusedStartsWith("Recently added") // the Sort panel's first choice
        press(RemoteAction.Down)
        focusedStartsWith("A–Z")
        press(RemoteAction.Select)
        eventually("sorted A–Z") { shows("Sort: A–Z") }
    }

    @Test fun aMovieOpensPlaysAndResumes() = keyMapTest(start = Route.Movies) {
        // The Library is empty, so the page starts on Recently added.
        focusedStartsWith("Recently added")
        press(RemoteAction.Right)
        eventually("a poster focused") { focusedText()?.startsWith("Recently added") == false }
        press(RemoteAction.Select)
        eventually("the movie page") { app.navigator.current is Route.MovieDetail }
        focusedStartsWith("Play")
        press(RemoteAction.Select)
        route(Route.VodPlayer)
        eventually("playing") { platform.player.state.value.request?.isLive == false }
        // Two minutes in, Back saves the position and returns to the page, which offers Resume (PLAN §4.3.10).
        platform.player.positionMs = 120_000
        press(RemoteAction.Back)
        eventually("back on the page") { app.navigator.current is Route.MovieDetail }
        focusedStartsWith("Resume 2:00")
        press(RemoteAction.Select)
        route(Route.VodPlayer)
        eventually("resumed from 2:00") { platform.player.state.value.request?.startPositionMs == 120_000L }
    }

    @Test fun anEpisodeAutoplaysTheNextOne() = keyMapTest(start = Route.Series) {
        focusedStartsWith("Recently added")
        press(RemoteAction.Right)
        eventually("a poster focused") { focusedText()?.startsWith("Recently added") == false }
        press(RemoteAction.Select)
        eventually("the series page") { app.navigator.current is Route.SeriesDetail }
        focusedStartsWith("Play S1 E1")
        press(RemoteAction.Select)
        route(Route.VodPlayer)
        eventually("S1 E1 playing") { platform.player.played.lastOrNull()?.startsWith("S1 E1") == true }
        // Near the end, the card counts down; OK plays the next episode now (PLAN §4.3.9).
        platform.player.positionMs = platform.player.durationMs - 20_000
        // The screen polls the position with delay(), which runs on the test clock.
        eventually("the autoplay card") { ui.mainClock.advanceTimeBy(600); shows("Next episode in") }
        press(RemoteAction.Select)
        eventually("S1 E2 playing") { platform.player.played.lastOrNull()?.startsWith("S1 E2") == true }
        // S1 E1 counts as watched.
        eventually("S1 E1 watched") { app.watchState.value.watched.keys.any { it.endsWith(":s1e1") } }
    }

    @Test fun continueWatchingOnHomeResumes() = keyMapTest {
        val movie = app.vod.recentlyAdded(com.signal.model.VodKind.MOVIE, 1).single()
        app.watch.savePosition(com.signal.data.WatchRepository.Position(com.signal.data.ItemKeys.movie(movie), "MOVIE", movie.id, null, null, null, null, "T", 90_000, 600_000, 0))
        heroFocused()
        eventually("the Continue watching row") { shows("Continue watching") }
        press(RemoteAction.Down) // Favorites' "Add favorites" tile
        press(RemoteAction.Down)
        press(RemoteAction.Select)
        route(Route.VodPlayer)
        eventually("resumed") { platform.player.state.value.request?.startPositionMs == 90_000L }
    }
}

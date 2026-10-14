package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertTrue

/** Movies and Series browse (PLAN §4.3.6–4.3.7, §12.1): the category column, the poster grid, Back. */
class VodKeyMapTest {
    /** The shelf and the categories load from the database after the page appears; under a full test run, after the first key could. */
    private suspend fun KeyMapScope.pageLoaded() = eventually("the posters and categories") {
        shows("Categories") && !shows("No movies here.") && !shows("No series here.")
    }

    @Test fun columnGridAndBack() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        pageLoaded()
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
        pageLoaded()
        press(RemoteAction.Down)
        focusedStartsWith("Drama Series")
        eventually("the category's titles") { shows("12 titles") }
    }

    @Test fun longPressSortsTheShelf() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        pageLoaded()
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
        pageLoaded()
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

    @Test fun backFromATitleReturnsToItsPoster() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        pageLoaded()
        eventually("the posters loaded") { shows("300 titles") }
        press(RemoteAction.Right)
        eventually("a poster focused") { focusedText()?.startsWith("Recently added") == false }
        press(RemoteAction.Right, 2)
        eventually("the third poster") { focusedText() != null }
        val poster = focusedText()
        press(RemoteAction.Select)
        eventually("the movie page") { app.navigator.current is Route.MovieDetail }
        press(RemoteAction.Back)
        route(Route.Movies)
        // The page was kept, not rebuilt (PLAN §8 → milestone 5): focus is back on the same poster.
        eventually("the same poster") { focusedText() == poster }
        // Left through the nav bar, Movies opens on the column.
        press(RemoteAction.Back)
        focusedStartsWith("Recently added")
        press(RemoteAction.Back)
        focusedStartsWith("Movies")
        ui.runOnIdle { app.navigator.go(Route.Series) }
        focusedStartsWith("Recently added")
        ui.runOnIdle { app.navigator.go(Route.Movies) }
        focusedStartsWith("Recently added")
    }

    @Test fun anEpisodeAutoplaysTheNextOne() = keyMapTest(start = Route.Series) {
        focusedStartsWith("Recently added")
        pageLoaded()
        eventually("the posters loaded") { shows("60 titles") }
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

    @Test fun aPickerOverAMovieOwnsUpAndDown() = keyMapTest(start = Route.Movies) {
        pageLoaded()
        press(RemoteAction.Right)
        eventually("a poster focused") { focusedText()?.startsWith("Recently added") == false }
        press(RemoteAction.Select)
        eventually("the movie page") { app.navigator.current is Route.MovieDetail }
        focusedStartsWith("Play")
        press(RemoteAction.Select)
        route(Route.VodPlayer)
        press(RemoteAction.Options)
        focusedStartsWith("Audio")
        press(RemoteAction.Right, 2)
        focusedStartsWith("Aspect ratio")
        press(RemoteAction.Select)
        eventually("aspect picker") { panelTitle == "Aspect ratio" }
        focusedStartsWith("Fit")
        press(RemoteAction.Down)
        focusedStartsWith("Fill")
        press(RemoteAction.Up)
        focusedStartsWith("Fit")
        kotlin.test.assertEquals("Aspect ratio", panelTitle)
    }

    private suspend fun KeyMapScope.startMovies(n: Int): List<com.signal.model.VodItem> {
        val movies = app.vod.recentlyAdded(com.signal.model.VodKind.MOVIE, n)
        movies.forEachIndexed { i, m ->
            app.watch.savePosition(com.signal.data.WatchRepository.Position(com.signal.data.ItemKeys.movie(m), "MOVIE", m.id, null, null, null, null, "T", 90_000, 600_000, i.toLong()))
        }
        return movies
    }

    private suspend fun KeyMapScope.removeFocusedCard() {
        press(RemoteAction.Options)
        eventually("the card menu") { panelTitle != null }
        press(RemoteAction.Down, 2)
        focusedStartsWith("Remove from Continue watching")
        press(RemoteAction.Select)
    }

    // Removing a card used to leave nothing focused, and the remote went dead (user, 2026-10-13).
    @Test fun removingACardMovesFocusToItsNeighbor() = keyMapTest {
        startMovies(2)
        heroFocused()
        eventually("the Continue watching row") { shows("Continue watching") }
        press(RemoteAction.Down, 2)
        val removed = focusedText()
        removeFocusedCard()
        eventually("the card gone") { app.continueWatching.value.size == 1 }
        eventually("focus on the other card") { focusedText().let { it != null && it != "" && it != removed } }
        // The remote still works: Up leaves the row.
        press(RemoteAction.Up)
        eventually("focus left the row") { focusedText()?.contains("Add favorites") == true }
    }

    @Test fun removingTheLastCardMovesFocusOutOfTheRow() = keyMapTest {
        startMovies(1)
        heroFocused()
        eventually("the Continue watching row") { shows("Continue watching") }
        press(RemoteAction.Down, 2)
        removeFocusedCard()
        eventually("the row gone") { !shows("Continue watching") }
        eventually("focus on Favorites") { focusedText()?.contains("Add favorites") == true }
        press(RemoteAction.Up)
        heroFocused()
    }
}

package com.signal.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTextReplacement
import com.signal.app.Route
import com.signal.data.VodRepository
import com.signal.input.RemoteAction
import com.signal.model.ChannelGroup
import com.signal.model.Program
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.ui.screens.programDetails
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** Movies and Series in Search (PLAN §4.3.14), and Find in Movies/Series from program details (PLAN §4.3.4). */
class VodFindKeyMapTest {
    private fun KeyMapScope.firstChannel() = runBlocking {
        app.channels.channelsNow(app.channels.groupsNow().first { it.group is ChannelGroup.Provider }.group).first()
    }

    /** A Demo title whose name finds only it: Demo names repeat, with different years. */
    private fun KeyMapScope.unique(kind: VodKind): VodItem = runBlocking {
        app.vod.recentlyAdded(kind, 100).first { app.vod.find(VodRepository.displayName(it), null, kind)?.id == it.id }
    }

    /** Program details for a program starting in an hour, as from the guide. */
    private suspend fun KeyMapScope.details(title: String, categories: List<String> = emptyList(), year: Int? = null, season: Int? = null, episode: Int? = null) {
        guideReady()
        val now = Clock.System.now().toEpochMilliseconds()
        val p = Program(1, "", "", now + 3_600_000, now + 7_200_000, title, categories = categories, year = year, season = season, episode = episode)
        val ch = firstChannel()
        ui.runOnIdle { app.panel.open(programDetails(app, ch, p, now), null) }
        focusedStartsWith("Remind me")
    }

    // Milestone 6: a search on the Movies page that finds only movies.
    @Test fun searchingInsideMovies() = keyMapTest(start = Route.Movies) {
        val movie = unique(VodKind.MOVIE)
        val name = VodRepository.displayName(movie)
        focusedStartsWith("Action")
        press(RemoteAction.Up, times = 2)
        focusedStartsWith("Search movies")
        eventually("the search shelf") { shows("Type at least 2 letters.") }
        press(RemoteAction.Right)
        typeInFocusedField(name)
        ui.mainClock.advanceTimeBy(400)
        eventually("the movie's poster") { shows("1 title") }
        press(RemoteAction.Down)
        eventually("the poster focused") { focusedText()?.startsWith(name) == true }
        // Back: the field, then the column, then the nav bar.
        press(RemoteAction.Back)
        eventually("the field") { textFieldFocused() }
        press(RemoteAction.Back)
        focusedStartsWith("Search movies")
        press(RemoteAction.Select)
        eventually("the field again, query kept") { textFieldFocused() && shows("1 title") }
    }

    @Test fun moviesInSearchOpenTheirPage() = keyMapTest {
        guideReady()
        val movie = unique(VodKind.MOVIE)
        val name = VodRepository.displayName(movie)
        ui.runOnIdle { app.navigator.go(Route.Search) }
        typeInFocusedField(name)
        ui.mainClock.advanceTimeBy(400)
        eventually("movie results") { shows("Movies · ") }
        // Only Movies: All, Channels, Programs, Movies.
        press(RemoteAction.Down)
        press(RemoteAction.Right, 3)
        press(RemoteAction.Select)
        eventually("the movies grid") { shows("Movies · ") && !shows("Programs · ") }
        press(RemoteAction.Down)
        eventually("the movie's card") { focusedText()?.startsWith(name) == true }
        press(RemoteAction.Select)
        eventually("its page") { app.navigator.current == Route.MovieDetail(movie.id) }
    }

    @Test fun watchInMoviesOpensTheMatch() = keyMapTest {
        val movie = unique(VodKind.MOVIE)
        details(VodRepository.displayName(movie), categories = listOf("Movie"), year = movie.year)
        press(RemoteAction.Down)
        focusedStartsWith("Watch in Movies")
        press(RemoteAction.Select)
        eventually("the movie page") { app.navigator.current == Route.MovieDetail(movie.id) }
    }

    @Test fun watchInSeriesOpensTheEpisode() = keyMapTest {
        val series = unique(VodKind.SERIES)
        details(VodRepository.displayName(series), season = 2, episode = 3)
        press(RemoteAction.Down)
        focusedStartsWith("Watch in Series")
        press(RemoteAction.Select)
        eventually("the series page") { app.navigator.current == Route.SeriesDetail(series.id, 2, 3) }
        eventually("S2 E3 focused") { focusedText()?.startsWith("S2 E3") == true }
    }

    @Test fun noMatchSearchesMoviesAndSeries() = keyMapTest {
        details("Nothing Like This At All", categories = listOf("Movie"))
        press(RemoteAction.Down)
        focusedStartsWith("Search Movies & Series")
        press(RemoteAction.Select)
        route(Route.Search)
        eventually("the title filled in") { shows("Nothing Like This At All") }
        // Limited to Movies and Series for this visit; the saved filter is untouched.
        eventually("no results row") { shows("No results.") }
        assertEquals(null, app.settings.raw(com.signal.data.Settings.Key.SEARCH_FILTER))
    }
}

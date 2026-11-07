package com.signal.ui

import com.signal.app.Route
import com.signal.data.Settings
import com.signal.db.user.Stream
import com.signal.input.RemoteAction
import com.signal.nas.NasPaths
import com.signal.nas.NasRecordingPaths
import com.signal.sports.Sport
import com.signal.sports.SportsRegion
import com.signal.sports.SportsWhen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** live-sports.md: the page on event channels named like a provider's, in hidden groups as they usually are. */
class LiveSportsKeyMapTest {
    private val health = """{"status":"ok","version":"test","database":"ok"}"""

    private suspend fun KeyMapScope.addGame(id: Int, group: String, name: String): String {
        val source = app.channels.groupsNow().first { it.group is com.signal.model.ChannelGroup.Provider }.group as com.signal.model.ChannelGroup.Provider
        val streamId = "${source.sourceId}:x:$id"
        app.db.streamQueries.insert(Stream(streamId, source.sourceId, "$id", name, name, group, null, null, null, 100_000L + id, "http://demo.test/$id.ts", null))
        return streamId
    }

    private suspend fun KeyMapScope.open() {
        ui.runOnIdle { app.navigator.go(Route.LiveSports) }
        route(Route.LiveSports)
        eventually("the games", 20_000) { shows("Jets vs. Chiefs") }
    }

    private suspend fun KeyMapScope.games() {
        addGame(901, "US| NFL PPV", "LIVE | Jets vs. Chiefs | all | 8K EXCLUSIVE | US: NFL PPV 1")
        addGame(902, "US| NBA PPV", "LIVE | Knicks vs. Celtics | all | 8K EXCLUSIVE | US: NBA PPV 1")
        addGame(903, "US| NHL PPV", "LIVE | Rangers vs. Bruins | all | 8K EXCLUSIVE | US: NHL PPV 1")
        addGame(904, "UK| SPORT HD", "LIVE | Arsenal vs. Spurs | all | 8K EXCLUSIVE | UK: SPORT 1")
    }

    @Test fun rowsTakeFocusAndPlay() = keyMapTest {
        games()
        open()
        eventually("a game focused") { focusedText()?.contains(" vs. ") == true }
        val row = focusedText()!!
        assertTrue(!shows("Arsenal vs. Spurs"), "US shows only US channels")
        press(RemoteAction.Select)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
        eventually("playing") { app.playback.current.value != null }
        assertEquals("LIVE", row.substringBefore(' '))
        press(RemoteAction.Back)
        route(Route.LiveSports)
        eventually("back on its row") { focusedText() == row }
    }

    @Test fun choosesSeveralSportsAndKeepsTheFilters() = keyMapTest {
        games()
        open()
        eventually("a game focused") { focusedText()?.contains(" vs. ") == true }
        press(RemoteAction.Up)
        focusedStartsWith("Today")
        press(RemoteAction.Right)
        focusedStartsWith("All sports")
        press(RemoteAction.Select)
        eventually("the sports") { focusedText()?.startsWith("All sports") == true && shows("NHL") }
        press(RemoteAction.Down)
        focusedStartsWith("NFL")
        press(RemoteAction.Select)
        press(RemoteAction.Down)
        focusedStartsWith("NBA")
        press(RemoteAction.Select)
        press(RemoteAction.Back)
        focusedStartsWith("NFL, NBA")
        eventually("only those two") { shows("Knicks vs. Celtics") && !shows("Rangers vs. Bruins") }
        assertEquals(setOf(Sport.NFL, Sport.NBA), app.sports.filters().chosen)

        // Away and back: still set.
        ui.runOnIdle { app.navigator.go(Route.Search) }
        route(Route.Search)
        ui.runOnIdle { app.navigator.go(Route.LiveSports) }
        route(Route.LiveSports)
        eventually("the filter still set") { shows("NFL, NBA") && shows("Jets vs. Chiefs") && !shows("Rangers vs. Bruins") }

        // It's a device setting, so it outlives the page.
        assertTrue(app.settings.raw(Settings.Key.SPORTS_FILTERS)!!.contains("NBA"))
    }

    /** live-sports.md → Round 2: Left from a game goes back to the filter used last, When until one is. */
    @Test fun leftGoesBackToTheFilters() = keyMapTest {
        games()
        open()
        eventually("a game focused") { focusedText()?.contains(" vs. ") == true }
        press(RemoteAction.Left)
        focusedStartsWith("Today")
        press(RemoteAction.Right)
        focusedStartsWith("All sports")
        press(RemoteAction.Select)
        eventually("the sports") { shows("NHL") && focusedText()?.startsWith("All sports") == true }
        press(RemoteAction.Back)
        focusedStartsWith("All sports")
        press(RemoteAction.Down)
        eventually("a game focused") { focusedText()?.contains(" vs. ") == true }
        press(RemoteAction.Down)
        eventually("the next game") { focusedText()?.contains(" vs. ") == true }
        press(RemoteAction.Left)
        focusedStartsWith("All sports")
    }

    @Test fun worldwideAddsInternationalChannels() = keyMapTest {
        games()
        app.sports.save(app.sports.filters().copy(region = SportsRegion.WORLDWIDE, whenOn = SportsWhen.LIVE))
        open()
        eventually("the UK game") { shows("Arsenal vs. Spurs") }
        assertTrue(shows("Live now") && shows("Worldwide"), "the saved filters are what the page opens with")
    }

    @Test fun warnsBeforeCuttingOffARecording() {
        var streamId = ""
        keyMapTest(nas = {
            when (it) {
                NasPaths.HEALTH -> health
                NasRecordingPaths.LIST -> """{"recordings":[{"id":"r","title":"The Big Game","channelName":"ESPN","startMs":1,"stopMs":9000000000000,"state":"RECORDING","streamId":"$streamId"}]}"""
                else -> null
            }
        }) {
            streamId = addGame(901, "US| NFL PPV", "LIVE | Jets vs. Chiefs | all | 8K EXCLUSIVE | US: NFL PPV 1")
            app.settings.put(Settings.Key.NAS_HOME_ADDRESS, "nas.test:3013")
            app.settings.put(Settings.Key.NAS_API_KEY, "key")
            open()
            eventually("a game focused") { focusedText()?.contains(" vs. ") == true }
            press(RemoteAction.Select)
            eventually("the warning") { shows("Stop the recording?") }
            assertTrue(app.navigator.current == Route.LiveSports, "nothing has played yet")
            focusedStartsWith("Cancel")
            press(RemoteAction.Down)
            focusedStartsWith("Watch anyway")
            press(RemoteAction.Select)
            eventually("full screen") { app.navigator.current is Route.FullScreen }
        }
    }
}

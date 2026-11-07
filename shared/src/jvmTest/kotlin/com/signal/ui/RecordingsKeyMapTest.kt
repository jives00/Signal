package com.signal.ui

import com.signal.app.RecordingText
import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.nas.NasPaths
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordingPaths
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * dvr.md → TV. The rows load after the page has asked for focus, so nothing would be focused on
 * arrival and OK would do nothing: found on the emulator, where the first press of OK was lost.
 */
class RecordingsKeyMapTest {
    private val two = """{"recordings":[
        {"id":"a","title":"Newest","channelName":"ESPN","startMs":2000,"stopMs":3000,"state":"DONE","durationMs":1000,"expiresAt":9000000000000},
        {"id":"b","title":"Oldest","channelName":"ESPN","startMs":1000,"stopMs":2000,"state":"DONE","durationMs":1000}
    ]}"""
    private val oldestOnly = """{"recordings":[
        {"id":"b","title":"Oldest","channelName":"ESPN","startMs":1000,"stopMs":2000,"state":"DONE","durationMs":1000,"expiresAt":9000000000000}
    ]}"""
    private val health = """{"status":"ok","version":"test","database":"ok"}"""
    private val nas: (String) -> String? = {
        when (it) {
            NasPaths.HEALTH -> health
            NasRecordingPaths.LIST -> two
            else -> null
        }
    }

    private fun KeyMapScope.open() {
        app.settings.put(Settings.Key.NAS_HOME_ADDRESS, "nas.test:3013")
        app.settings.put(Settings.Key.NAS_API_KEY, "key")
        ui.runOnIdle { app.navigator.go(Route.Recordings) }
    }

    @Test fun theTopRowHasFocusOnArrival() = keyMapTest(nas = nas) {
        open()
        focusedStartsWith("Newest")
    }

    @Test fun backFromPlayingReturnsToThatRow() = keyMapTest(nas = nas) {
        open()
        focusedStartsWith("Newest")
        press(RemoteAction.Down)
        focusedStartsWith("Oldest")
        press(RemoteAction.Select)
        eventually("the player") { app.navigator.current == Route.VodPlayer }
        // Last, not only: Home may have tuned its preview first.
        assertEquals("ESPN", platform.player.played.last())
        press(RemoteAction.Back)
        eventually("the list") { app.navigator.current == Route.Recordings }
        focusedStartsWith("Oldest")
    }

    @Test fun deletingAtTheTopMovesFocusToTheRowBelow() {
        var deleted = false
        keyMapTest(nas = { path ->
            when (path) {
                NasPaths.HEALTH -> health
                "DELETE ${NasRecordingPaths.one("a")}" -> { deleted = true; "{}" }
                NasRecordingPaths.LIST -> if (deleted) oldestOnly else two
                else -> null
            }
        }) {
            open()
            focusedStartsWith("Newest")
            press(RemoteAction.Options)
            focusedStartsWith("Keep 30 more days")
            press(RemoteAction.Down)
            focusedStartsWith("Delete")
            press(RemoteAction.Select)
            focusedStartsWith("Cancel")
            press(RemoteAction.Down)
            focusedStartsWith("Delete")
            press(RemoteAction.Select)
            eventually("the row gone") { deleted && !shows("Newest") }
            focusedStartsWith("Oldest")
        }
    }

    @Test fun homesRowPlaysARecording() = keyMapTest(nas = nas) {
        app.settings.put(Settings.Key.NAS_HOME_ADDRESS, "nas.test:3013")
        app.settings.put(Settings.Key.NAS_API_KEY, "key")
        heroFocused()
        ui.runOnIdle { app.scope.launch { app.recordings.refresh() } }
        eventually("the row") { shows("Recordings") && shows("Newest") }
        press(RemoteAction.Down)
        focusedStartsWith("Add favorites")
        press(RemoteAction.Down)
        focusedStartsWith("Newest")
        press(RemoteAction.Select)
        eventually("the player") { app.navigator.current == Route.VodPlayer }
    }

    private val show = """{"recordings":[
        {"id":"j2","title":"Jeopardy!","channelName":"ABC 7","startMs":5000,"stopMs":6000,"state":"DONE","durationMs":1000,"details":{"subTitle":"Teachers Tournament"}},
        {"id":"j1","title":"Jeopardy!","channelName":"ABC 7","startMs":4000,"stopMs":5000,"state":"DONE","durationMs":1000,"watchedAt":4500,"details":{"season":42,"episode":7}},
        {"id":"m","title":"A Movie","channelName":"FX","startMs":1000,"stopMs":2000,"state":"DONE","durationMs":1000,"expiresAt":9000000000000},
        {"id":"s","title":"Later Show","channelName":"FX","startMs":9000000000000,"stopMs":9000003600000,"state":"SCHEDULED","programStart":9000000000000}
    ]}"""
    private val showNas: (String) -> String? = {
        when (it) {
            NasPaths.HEALTH -> health
            NasRecordingPaths.LIST -> show
            else -> null
        }
    }

    @Test fun aShowOpensToItsEpisodesAndBackClosesIt() = keyMapTest(nas = showNas) {
        open()
        focusedStartsWith("Later Show")
        press(RemoteAction.Down)
        focusedStartsWith("Jeopardy! · 2 recordings · 1 new")
        press(RemoteAction.Select)
        // Unwatched first: the one with no position comes before the watched one.
        focusedStartsWith("Teachers Tournament")
        press(RemoteAction.Down)
        focusedStartsWith("S42 E7")
        press(RemoteAction.Back)
        focusedStartsWith("Jeopardy! · 2 recordings")
    }

    @Test fun rightGoesToTheFocusedRecordingsButtonsAndLeftComesBack() = keyMapTest(nas = showNas) {
        open()
        focusedStartsWith("Later Show")
        press(RemoteAction.Down)
        press(RemoteAction.Down)
        focusedStartsWith("A Movie")
        press(RemoteAction.Right)
        focusedStartsWith("Play")
        press(RemoteAction.Down)
        focusedStartsWith("Keep 30 more days")
        press(RemoteAction.Down)
        focusedStartsWith("Delete")
        press(RemoteAction.Left)
        focusedStartsWith("A Movie")
    }

    @Test fun theFilterIsKeptForTheTv() = keyMapTest(nas = showNas) {
        open()
        focusedStartsWith("Later Show")
        press(RemoteAction.Up)
        focusedStartsWith("All")
        press(RemoteAction.Right)
        press(RemoteAction.Right)
        focusedStartsWith("Scheduled (1)")
        press(RemoteAction.Select)
        eventually("only what's scheduled") { !shows("A Movie") && shows("Later Show") }
        assertEquals("SCHEDULED", app.settings.raw(Settings.Key.RECORDINGS_FILTER))
    }

    @Test fun outOfReachSaysSoAndOffersTryAgain() = keyMapTest {
        open()
        focusedStartsWith("Try again")
        eventually("the warning") { shows(RecordingText.OUT_OF_REACH) }
    }
}

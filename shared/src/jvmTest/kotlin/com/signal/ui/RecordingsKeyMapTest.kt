package com.signal.ui

import com.signal.app.RecordingText
import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.nas.NasPaths
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordingPaths
import com.signal.ui.screens.recordingListIndex
import com.signal.ui.screens.recordingSections
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
        assertEquals(1, platform.player.played.size)
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

    @Test fun outOfReachSaysSoAndOffersTryAgain() = keyMapTest {
        open()
        focusedStartsWith("Try again")
        eventually("the warning") { shows(RecordingText.OUT_OF_REACH) }
    }

    @Test fun rowsAreFoundPastEachSectionTitle() {
        fun rec(id: String, state: String, at: Long) = NasRecording(id, "Show", "Channel", at, at + 1, state, durationMs = 1)
        val sections = recordingSections(listOf(rec("on", "RECORDING", 3), rec("soon", "SCHEDULED", 5), rec("new", "DONE", 2), rec("old", "DONE", 1)))
        assertEquals(1, recordingListIndex(sections, "on"))
        assertEquals(3, recordingListIndex(sections, "soon"))
        assertEquals(6, recordingListIndex(sections, "old"))
        assertEquals(0, recordingListIndex(sections, "gone"))
        assertEquals(0, recordingListIndex(sections, null))
    }
}

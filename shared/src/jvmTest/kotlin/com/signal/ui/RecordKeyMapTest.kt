package com.signal.ui

import com.signal.app.RecordingText
import com.signal.app.Route
import com.signal.app.findRecording
import com.signal.app.recordNote
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.model.Program
import com.signal.nas.NasPaths
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordingPaths
import com.signal.nas.RecordingAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dvr.md → TV: Record in program details, from a long press in the guide. */
class RecordKeyMapTest {
    private val health = """{"status":"ok","version":"test","database":"ok"}"""

    private fun KeyMapScope.pair() {
        app.settings.put(Settings.Key.NAS_HOME_ADDRESS, "nas.test:3013")
        app.settings.put(Settings.Key.NAS_API_KEY, "key")
    }

    /** Program details open on a program still to come; focus on its first button. */
    private suspend fun KeyMapScope.detailsOfAFutureProgram() {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        liveTvReady()
        eventually("programs loaded") { focusedText()?.endsWith("No information") == false }
        repeat(2) {
            val before = focusedText()
            press(RemoteAction.Right)
            eventually("the next program") { focusedText() != before }
        }
        press(RemoteAction.Options)
    }

    @Test fun aTvNeverPairedHasNoRecordButton() = keyMapTest {
        detailsOfAFutureProgram()
        focusedStartsWith("Remind me")
        assertFalse(shows("Record"))
    }

    @Test fun outOfReachSaysSoOnThePress() = keyMapTest {
        pair()
        detailsOfAFutureProgram()
        focusedStartsWith("Record")
        press(RemoteAction.Select)
        eventually("the warning") { shows(RecordingText.OUT_OF_REACH) }
        focusedStartsWith("Record")
    }

    @Test fun aRecordingThatWontFitSaysSo() {
        var posted = false
        keyMapTest(nas = { path ->
            when (path) {
                NasPaths.HEALTH -> health
                "POST ${NasRecordingPaths.LIST}" -> {
                    posted = true
                    """{"recording":{"id":"r","title":"Show","channelName":"C","startMs":1,"stopMs":2,"state":"SCHEDULED"},"conflict":true}"""
                }
                NasRecordingPaths.LIST -> """{"recordings":[]}"""
                else -> null
            }
        }) {
            pair()
            detailsOfAFutureProgram()
            focusedStartsWith("Record")
            press(RemoteAction.Select)
            eventually("the note") { posted && shows(RecordingText.CONFLICT) }
        }
    }

    @Test fun findsOnlyWhatsStillToComeOnThatStream() {
        val p = Program(0, "", "", 1_000, 2_000, "Game")
        fun rec(state: String = "SCHEDULED", stream: String = "s", start: Long = 1_000, title: String = "Game") =
            NasRecording("r", title, "C", 1, 2, state, programStart = start, streamId = stream)
        assertEquals("r", findRecording(listOf(rec()), "s", p)?.id)
        assertEquals("r", findRecording(listOf(rec("RECORDING")), "s", p)?.id)
        assertEquals("r", findRecording(listOf(rec("WAITING")), "s", p)?.id)
        assertNull(findRecording(listOf(rec("DONE")), "s", p), "stopped early, it can be set again")
        assertNull(findRecording(listOf(rec(stream = "other")), "s", p))
        assertNull(findRecording(listOf(rec(start = 900)), "s", p))
        assertNull(findRecording(listOf(rec(title = "Other game")), "s", p), "two programs can list the same time")
    }

    @Test fun theNoteSaysWhatHappened() {
        assertNull(recordNote(RecordingAnswer.Set(conflict = false)))
        assertEquals(RecordingText.CONFLICT, recordNote(RecordingAnswer.Set(conflict = true)))
        assertEquals(RecordingText.OUT_OF_REACH, recordNote(RecordingAnswer.OutOfReach))
        assertEquals("That program has ended.", recordNote(RecordingAnswer.Refused("That program has ended.")))
        assertTrue(recordNote(RecordingAnswer.Refused(""))!!.isNotBlank())
    }
}

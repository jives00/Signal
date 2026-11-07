package com.signal.ui

import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.nas.NasPaths
import com.signal.nas.NasRecordingPaths
import kotlin.test.Test

/**
 * Renders the Recordings page at 1080p for a look (dvr.md → TV, the redesign); the images land in
 * shared/build/screenshots. Logos and pictures don't load without a network, so tiles show initials.
 */
class RecordingsScreenshotTest {
    @Test fun page() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val h = 3_600_000L
        val list = """{"freeBytes":2200000000000,"conflicts":["s2"],"recordings":[
            {"id":"on","title":"Monday Night Football","channelName":"ESPN","startMs":${now - h},"stopMs":${now + 2 * h},"state":"RECORDING","durationMs":${h},"bytes":3000000000,"programStart":${now - h},"programStop":${now + 2 * h},
             "details":{"subTitle":"Chiefs at Jets","description":"Kansas City visits New York in a battle of division leaders.","isNew":true,"categories":["Sports","Football"]}},
            {"id":"s1","title":"Jeopardy!","channelName":"ABC 7","startMs":${now + 5 * h},"stopMs":${now + 5 * h + 1_800_000},"state":"SCHEDULED","programStart":${now + 5 * h},"programStop":${now + 5 * h + 1_800_000}},
            {"id":"s2","title":"The Late Show","channelName":"CBS 2","startMs":${now + 29 * h},"stopMs":${now + 30 * h},"state":"SCHEDULED","programStart":${now + 29 * h},"programStop":${now + 30 * h}},
            {"id":"j2","title":"Jeopardy!","channelName":"ABC 7","startMs":${now - 20 * h},"stopMs":${now - 19 * h},"state":"DONE","durationMs":1800000,"bytes":1500000000,"expiresAt":${now + 20 * 24 * h},"programStart":${now - 20 * h},
             "details":{"season":42,"episode":51,"subTitle":"Teachers Tournament Final","description":"Three teachers play for the championship and a spot in the Tournament of Champions.","rating":"TV-G","isNew":true}},
            {"id":"j1","title":"Jeopardy!","channelName":"ABC 7","startMs":${now - 44 * h},"stopMs":${now - 43 * h},"state":"DONE","durationMs":1800000,"bytes":1500000000,"expiresAt":${now + 3 * 24 * h},"watchedAt":${now - 40 * h},"programStart":${now - 44 * h},
             "details":{"season":42,"episode":50}},
            {"id":"m","title":"The Martian","channelName":"FX","startMs":${now - 70 * h},"stopMs":${now - 67 * h},"state":"PARTIAL","durationMs":9000000,"gapMs":240000,"bytes":5000000000,"positionMs":3600000,"expiresAt":${now + 25 * 24 * h},"programStart":${now - 70 * h},
             "details":{"description":"An astronaut is stranded on Mars and has to survive until a rescue can reach him.","year":2015,"rating":"PG-13","categories":["Movie","Science fiction"]}},
            {"id":"f","title":"Evening News","channelName":"NBC 5","startMs":${now - 90 * h},"stopMs":${now - 89 * h},"state":"FAILED","reason":"No source was free."}
        ]}"""
        keyMapTest(nas = { path ->
            when (path) {
                NasPaths.HEALTH -> """{"status":"ok","version":"test","database":"ok"}"""
                NasRecordingPaths.LIST -> list
                else -> null
            }
        }) {
            app.settings.put(Settings.Key.NAS_HOME_ADDRESS, "nas.test:3013")
            app.settings.put(Settings.Key.NAS_API_KEY, "key")
            ui.runOnIdle { app.navigator.go(Route.Recordings) }
            route(Route.Recordings)
            focusedStartsWith("Monday Night Football")
            settle()
            screenshot("recordings")
            repeat(3) { press(RemoteAction.Down) }
            focusedStartsWith("Jeopardy! · 2 recordings")
            settle()
            screenshot("recordings-show")
            press(RemoteAction.Select)
            focusedStartsWith("S42 E51")
            settle()
            screenshot("recordings-show-open")
            press(RemoteAction.Back)
            press(RemoteAction.Down)
            focusedStartsWith("The Martian")
            press(RemoteAction.Right)
            settle()
            screenshot("recordings-buttons")
        }
    }
}

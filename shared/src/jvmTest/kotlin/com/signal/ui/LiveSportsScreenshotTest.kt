package com.signal.ui

import com.signal.app.Route
import com.signal.db.user.Stream
import com.signal.input.RemoteAction
import kotlin.test.Test

/**
 * Renders Live Sports at 1080p for a look (live-sports.md → Round 2); the images land in
 * shared/build/screenshots. ESPN is answered by the fake network, so the rows get its names and the
 * league badges; logos don't load without a network, so they're left out of the picture.
 */
class LiveSportsScreenshotTest {
    private fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms - ms % 60_000).toString().replace(":00Z", "Z")

    private fun scoreboard(start: Long) = """
        {"events":[{"competitions":[{"date":"${iso(start)}","venue":{"fullName":"Arrowhead Stadium"},"competitors":[
          {"homeAway":"home","team":{"displayName":"Kansas City Chiefs","shortDisplayName":"Chiefs","abbreviation":"KC","location":"Kansas City","name":"Chiefs","logo":"https://e.test/kc.png"},"records":[{"type":"total","summary":"6-2"}]},
          {"homeAway":"away","team":{"displayName":"New York Jets","shortDisplayName":"Jets","abbreviation":"NYJ","location":"New York","name":"Jets","logo":"https://e.test/nyj.png"},"records":[{"type":"total","summary":"2-6"}]}
        ]}]}]}
    """.trimIndent()

    @Test fun page() {
        val start = kotlin.time.Clock.System.now().toEpochMilliseconds()
        keyMapTest(nas = { path -> if (path.endsWith("/football/nfl/scoreboard")) scoreboard(start) else null }) {
            val source = app.channels.groupsNow().first { it.group is com.signal.model.ChannelGroup.Provider }.group as com.signal.model.ChannelGroup.Provider
            fun add(id: Int, group: String, name: String) =
                app.db.streamQueries.insert(Stream("${source.sourceId}:x:$id", source.sourceId, "$id", name, name, group, null, null, null, 100_000L + id, "http://demo.test/$id.ts", null))
            add(901, "US| NFL PPV", "LIVE | Jets vs. Chiefs | all | 8K EXCLUSIVE | US: NFL PPV 1")
            add(902, "US| ESPN+ PPV", "LIVE | NCAA Men`s Basketball: MORE vs. #11 LOU • UND vs. WIN | all | 8K EXCLUSIVE | US: ESPN+ PPV 3")
            add(903, "US| PEACOCK PPV VIP", "LIVE | Celtics vs. Wizards | all | 8K EXCLUSIVE | US: PEACOCK 1")
            add(904, "US| ESPN+ PPV", "LIVE | Lehigh vs. Colgate | all | 8K EXCLUSIVE | US: ESPN+ PPV 2")
            add(905, "US| BTN+ PPV", "LIVE | Volleyball (W): Illinois at Washington | all | 8K EXCLUSIVE | US: BTN+ 1")
            ui.runOnIdle { app.navigator.go(Route.LiveSports) }
            route(Route.LiveSports)
            eventually("the games", 20_000) { shows("Celtics vs. Wizards") }
            eventually("ESPN's names", 20_000) { shows("New York Jets") }
            settle()
            screenshot("live-sports")
            press(RemoteAction.Options)
            eventually("the game's panel") { app.panel.isOpen }
            settle()
            screenshot("live-sports-panel")
        }
    }
}

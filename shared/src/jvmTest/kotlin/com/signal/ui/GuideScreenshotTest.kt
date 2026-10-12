package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import androidx.compose.ui.test.performTextReplacement
import kotlin.test.Test

/** Renders the guide at 1080p for a look; the images land in shared/build/screenshots. */
class GuideScreenshotTest {
    @Test fun guide() = keyMapTest {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        eventually("guide focused") { focusedText()?.contains(" · ") == true }
        settle()
        screenshot("guide")
        press(RemoteAction.Down, times = 2)
        press(RemoteAction.Right, times = 2)
        screenshot("guide-future")
        press(RemoteAction.Options)
        eventually("details") { app.panel.isOpen }
        screenshot("guide-details")
    }
}

class GuideElsewhereScreenshotTest {
    @Test fun homeAndInfoBar() = keyMapTest(favorites = 6) {
        guideReady()
        ui.runOnIdle { app.navigator.resetTo(Route.Home) }
        heroFocused()
        eventually("hero program") { !shows("No guide information") }
        screenshot("home")
        press(RemoteAction.Select)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
        press(RemoteAction.Select)
        eventually("info bar") { shows("Aspect ratio") && shows("Next · ") }
        screenshot("info-bar")
    }
}

class ReminderScreenshotTest {
    @Test fun promptAndRow() = keyMapTest {
        guideReady()
        val ch = kotlinx.coroutines.runBlocking { app.channels.channelsNow(app.channels.groupsNow().first { it.group is com.signal.model.ChannelGroup.Provider }.group) }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val later = kotlinx.coroutines.runBlocking { app.guide.programs(ch[1], now + 3_600_000, now + 86_400_000) }.first { it.start > now + 3_600_000 }
        app.reminders.add(ch[1], later)
        app.reminders.add(ch[0], com.signal.model.Program(0, "", "", now + 30_000, now + 1_800_000, "Championship Final"))
        heroFocused()
        eventually("prompt and row") { shows("Starting now") && shows("Reminders") }
        screenshot("reminders")
    }
}

class SearchScreenshotTest {
    @Test fun results() = keyMapTest {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.Search) }
        settle()
        ui.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextReplacement("science")
        ui.mainClock.advanceTimeBy(400)
        eventually("results") { shows("Channels · ") && shows("Programs · ") }
        settle()
        screenshot("search")
    }
}

package com.signal.ui

import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.model.ChannelGroup
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLAN §4.3.15: EPG sources, Live TV & guide, Guide data; and the channel menu's Guide source (§6). */
class GuideSettingsTest {
    private suspend fun KeyMapScope.toSection(title: String) {
        focusedStartsWith("Sources")
        var guard = 0
        while (focusedText()?.startsWith(title) != true && guard++ < 8) press(RemoteAction.Down)
        focusedStartsWith(title)
        press(RemoteAction.Right)
    }

    @Test fun epgSourceTimeShiftAppliesAtOnce() = keyMapTest(start = Route.Settings) {
        guideReady()
        eventually("the Demo guide listed as default") { shows("Demo guide") && shows("Default") }
        press(RemoteAction.Right)
        var guard = 0
        while (focusedText()?.startsWith("Demo guide") != true && guard++ < 6) press(RemoteAction.Down)
        focusedStartsWith("Demo guide")
        press(RemoteAction.Select)
        eventually("its actions") { panelTitle == "Demo guide" }
        focusedStartsWith("Time shift")
        press(RemoteAction.Select)
        eventually("the shift picker") { panelTitle == "Time shift: Demo guide" }
        // −12h … +12h: "None" is the 25th; +1h two below it.
        repeat(26) { press(RemoteAction.Down) }
        focusedStartsWith("+1h")
        press(RemoteAction.Select)
        eventually("+1h shown") { shows("Default · +1h") }
        assertEquals(60, app.epg.all().single().timeShiftMinutes)
    }

    @Test fun reminderSettings() = keyMapTest(start = Route.Settings) {
        toSection("Live TV & guide")
        focusedStartsWith("Reminder time")
        press(RemoteAction.Select)
        eventually("picker") { panelTitle == "Reminder time" }
        press(RemoteAction.Down, times = 3)
        focusedStartsWith("5 min before")
        press(RemoteAction.Select)
        eventually("saved") { app.settings.raw(Settings.Key.REMINDER_LEAD_MINUTES) == "5" }
        eventually("back on the row") { focusedText()?.contains("5 min before") == true }
        press(RemoteAction.Down)
        focusedStartsWith("Switch automatically")
        press(RemoteAction.Select)
        eventually("on") { app.settings.bool(Settings.Key.REMINDER_AUTO_SWITCH, false) }
    }

    @Test fun updateEpgNowRebuilds() = keyMapTest(start = Route.Settings) {
        guideReady()
        val before = app.guideStore.builtAt()!!
        toSection("Guide data")
        eventually("counts") { shows("programs on") }
        press(RemoteAction.Down, times = 2)
        focusedStartsWith("Update EPG now")
        Thread.sleep(5) // A build in the same millisecond would keep the same time.
        press(RemoteAction.Select)
        eventually("rebuilt", timeoutMs = 60_000) { !app.guideBuilding && (app.guideStore.builtAt() ?: 0) > before }
    }

    @Test fun channelMenuOverridesTheGuideSource() = keyMapTest(start = Route.LiveTv) {
        guideReady()
        liveTvReady()
        press(RemoteAction.Left)
        eventually("channel column") { focusedText()?.endsWith(" · channel") == true }
        press(RemoteAction.Options)
        eventually("channel menu with the guide source") { shows("Guide source") && shows("Demo guide") }
        var guard = 0
        while (focusedText()?.startsWith("Guide source") != true && guard++ < 8) press(RemoteAction.Down)
        press(RemoteAction.Select)
        eventually("the source picker") { panelTitle == "Guide source" }
        press(RemoteAction.Down) // Demo guide
        press(RemoteAction.Select)
        val ch = runBlocking { app.channels.channelsNow(app.channels.groupsNow().first { it.group is ChannelGroup.Provider }.group).first() }
        eventually("an override stored") { ch.let { runBlocking { app.channels.userChannelIdFor(it) } }?.let { it in app.guide.state.value.mappings } == true }
        assertTrue(app.guide.state.value.mappings.values.single().guideChannelId == null)
    }
}

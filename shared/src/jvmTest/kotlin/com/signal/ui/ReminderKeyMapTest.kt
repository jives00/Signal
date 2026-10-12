package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.model.ChannelGroup
import com.signal.model.Program
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** PLAN §4.3.4 → Reminders, and §12.1's Reminder prompt row: OK = Watch, Back = Dismiss, no focus taken. */
class ReminderKeyMapTest {
    private fun KeyMapScope.firstChannel() = runBlocking {
        app.channels.channelsNow(app.channels.groupsNow().first { it.group is ChannelGroup.Provider }.group).first()
    }

    /** A reminder for a program starting in 30 seconds: inside the 1-minute lead, so it fires at once. */
    private suspend fun KeyMapScope.remindNow(title: String) {
        val ch = firstChannel()
        val now = Clock.System.now().toEpochMilliseconds()
        app.reminders.add(ch, Program(0, "", "", now + 30_000, now + 30 * 60_000, title))
        eventually("the prompt") { shows("Starting now") && shows(title) }
    }

    @Test fun remindMeFromProgramDetailsTogglesAndShowsOnHome() = keyMapTest {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        liveTvReady()
        eventually("programs loaded") { focusedText()?.endsWith("No information") == false }
        val live = focusedText()!!.substringAfter(" · ")
        press(RemoteAction.Right)
        eventually("a future program") { focusedText()!!.substringAfter(" · ") != live }
        val future = focusedText()!!.substringAfter(" · ")
        press(RemoteAction.Select)
        focusedStartsWith("Remind me")
        press(RemoteAction.Select)
        focusedStartsWith("Remove reminder")
        assertTrue(shows("You'll be reminded while the app is open."))
        assertEquals(future, app.reminderList.value.single().title)

        press(RemoteAction.Back)
        ui.runOnIdle { app.navigator.go(Route.Home) }
        eventually("the Reminders row") { shows("Reminders") && shows(future) }

        // Remove reminder from Home: long-press the card for program details.
        heroFocused()
        press(RemoteAction.Down) // Favorites (the "Add favorites" tile)
        press(RemoteAction.Down) // Reminders
        eventually("on the reminder card") { focusedText()?.contains(future) == true }
        press(RemoteAction.Options)
        focusedStartsWith("Remove reminder")
        press(RemoteAction.Select)
        eventually("removed") { app.reminderList.value.isEmpty() }
    }

    @Test fun promptOkWatchesWithoutTakingFocus() = keyMapTest {
        heroFocused()
        remindNow("Prompt test")
        // The prompt never takes focus: the hero keeps it, and D-pad keys work as usual.
        assertEquals("", focusedText())
        press(RemoteAction.Down)
        eventually("focus moved under the prompt") { focusedText() != "" }
        assertTrue(shows("Starting now"))
        press(RemoteAction.Select)
        eventually("watching full screen") { app.navigator.current is Route.FullScreen && app.activeReminder == null }
        assertEquals(firstChannel().stream.id, current?.stream?.id)
    }

    @Test fun promptBackDismissesAndNothingElse() = keyMapTest {
        heroFocused()
        remindNow("Dismiss test")
        press(RemoteAction.Back)
        eventually("dismissed") { app.activeReminder == null && !shows("Starting now") }
        assertEquals("", focusedText(), "Back went to the prompt, not the page")
        assertEquals(Route.Home, app.navigator.current)
    }

    @Test fun aReminderFollowsItsProgramWhenANewGuideMovesIt() = keyMapTest {
        guideReady()
        val ch = firstChannel()
        val now = Clock.System.now().toEpochMilliseconds()
        val upcoming = runBlocking { app.guide.programs(ch, now + 60 * 60_000, now + 24 * 60 * 60_000) }.first { it.start > now + 60 * 60_000 }
        // Stored a minute off, as if an older guide had it then. (Demo titles repeat, but never within 15 minutes.)
        app.reminders.add(ch, upcoming.copy(start = upcoming.start + 60_000, stop = upcoming.stop + 60_000))
        eventually("stored") { app.reminderList.value.size == 1 }
        runBlocking { app.rebuildGuide() }
        eventually("moved to the guide's time") { app.reminderList.value.single().start == upcoming.start }
        assertNull(app.activeReminder)
    }
}

package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PLAN §12.1, the Live TV guide and Group panel rows. The grid is one focus target that describes
 * its focus as "channel · program" (or "channel · channel" on the channel column).
 */
class LiveTvKeyMapTest {
    private suspend fun KeyMapScope.guide() {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        liveTvReady()
        eventually("programs loaded") { focusedText()?.endsWith("No information") == false }
    }

    private fun KeyMapScope.focusedChannel() = focusedText()!!.substringBefore(" · ")
    private fun KeyMapScope.focusedProgram() = focusedText()!!.split(" · ")[1]

    @Test fun okOnALiveProgramPreviewsThenGoesFullScreen() = keyMapTest {
        guide()
        val first = current!!
        press(RemoteAction.Down)
        eventually("next row") { focusedChannel() != first.displayName }
        press(RemoteAction.Select)
        eventually("preview tuned") { current?.key != first.key }
        assertEquals(Route.LiveTv, app.navigator.current)
        press(RemoteAction.Select)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
    }

    @Test fun okOnAFutureProgramOpensDetails() = keyMapTest {
        guide()
        val live = focusedText()
        press(RemoteAction.Right)
        eventually("a later program") { focusedText() != live }
        val future = focusedProgram()
        press(RemoteAction.Select)
        eventually("program details") { panelTitle == future }
        assertEquals(Route.LiveTv, app.navigator.current)
        press(RemoteAction.Back)
        eventually("closed, back on the same cell") { !app.panel.isOpen && focusedProgram() == future }
    }

    @Test fun longPressOnAProgramOpensDetailsAndOnTheChannelColumnTheChannelMenu() = keyMapTest {
        guide()
        val live = focusedProgram()
        press(RemoteAction.Options)
        eventually("details of the live program") { panelTitle == live }
        press(RemoteAction.Back)
        press(RemoteAction.Left)
        eventually("channel column") { focusedProgram() == "channel" }
        val channel = focusedChannel()
        press(RemoteAction.Options)
        eventually("channel menu") { panelTitle?.contains(channel) == true }
    }

    @Test fun leftReachesTheChannelColumnThenTheGroupPanel() = keyMapTest {
        guide()
        val group = current!!.groupName
        press(RemoteAction.Right)
        press(RemoteAction.Left)
        eventually("back on the live program") { focusedProgram() != "channel" }
        press(RemoteAction.Left)
        eventually("channel column") { focusedProgram() == "channel" }
        press(RemoteAction.Left)
        focusedStartsWith(group)
        press(RemoteAction.Right)
        eventually("panel closed") { focusedProgram() == "channel" }
    }

    @Test fun upDownChangeRowAndUpFromTheTopRevealsTheNavBar() = keyMapTest {
        guide()
        val first = focusedChannel()
        press(RemoteAction.Down)
        eventually("next row") { focusedChannel() != first }
        press(RemoteAction.Up)
        eventually("first row") { focusedChannel() == first }
        press(RemoteAction.Up)
        focusedStartsWith("Live TV")
        press(RemoteAction.Down)
        eventually("back in the grid") { focusedChannel() == first }
        press(RemoteAction.Back)
        focusedStartsWith("Live TV")
    }

    @Test fun playPauseJumpsToNowAndFastForwardRewindMoveByADay() = keyMapTest {
        guide()
        val live = focusedProgram()
        press(RemoteAction.FastForward)
        eventually("a day ahead") { shows("Tomorrow · ") }
        press(RemoteAction.Rewind)
        eventually("back to today") { shows("Today · ") }
        press(RemoteAction.Rewind)
        assertTrue(shows("Today · "), "never before now")
        press(RemoteAction.Right, times = 3)
        press(RemoteAction.PlayPause)
        eventually("now again") { focusedProgram() == live }
    }

    @Test fun groupPanelOkAppliesTheGroupAndBackCloses() = keyMapTest {
        guide()
        val before = current!!.groupName
        val oldChannel = focusedChannel()
        press(RemoteAction.Left)
        press(RemoteAction.Left)
        focusedStartsWith(before)
        press(RemoteAction.Down)
        val next = focusedText()!!
        assertNotEquals(before, next)
        val nextTitle = app.groups.value.map { it.group.title }.filter { next.startsWith(it) }.maxBy { it.length }
        press(RemoteAction.Select)
        eventually("the new group in the grid") { shows("Today · $nextTitle") && focusedText()?.contains(" · ") == true && focusedChannel() != oldChannel && focusedChannel() != "No channels" }
        assertEquals(before, current!!.groupName, "the preview keeps playing")
        press(RemoteAction.Left)
        press(RemoteAction.Left)
        focusedStartsWith(nextTitle)
        press(RemoteAction.Back)
        eventually("closed") { focusedProgram() == "channel" }
    }

    @Test fun groupPanelLongPressOpensTheGroupMenu() = keyMapTest {
        guide()
        val group = current!!.groupName
        press(RemoteAction.Left)
        press(RemoteAction.Left)
        focusedStartsWith(group)
        press(RemoteAction.Options)
        eventually("group menu") { panelTitle == "Group: $group" }
    }

    @Test fun groupPanelReachesAGroupScrolledOutOfView() = keyMapTest {
        guideReady()
        val last = app.groups.value.last().group
        ui.runOnIdle {
            app.liveTvGroup = last
            app.navigator.go(Route.LiveTv)
        }
        eventually("Live TV on the last group, loaded") { shows("Today · ${last.title}") && focusedText()?.let { it.contains(" · ") && !it.startsWith("No channels") } == true }
        press(RemoteAction.Left)
        press(RemoteAction.Left)
        focusedStartsWith(last.title)
    }
}

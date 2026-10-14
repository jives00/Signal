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

    // 0.2.7 on the Shield: OK's release after the long press goes unhandled, and the platform's
    // focus recovery pulled focus out of the panel, so Up/Down moved the guide's rows instead.
    @Test fun theLongPressPanelKeepsFocusThroughAnUnhandledRelease() = keyMapTest {
        guide()
        val row = focusedChannel()
        press(RemoteAction.Left)
        eventually("channel column") { focusedProgram() == "channel" }
        press(RemoteAction.Options)
        eventually("channel menu") { panelTitle?.contains(row) == true }
        val first = focusedText()
        ui.runOnIdle { assertEquals(false, app.dispatcher.recoverFocus()) }
        press(RemoteAction.Down)
        eventually("the next menu item") { app.panel.isOpen && focusedText() != first && focusedText() != null }
        press(RemoteAction.Back)
        eventually("closed, on the same row") { !app.panel.isOpen && focusedChannel() == row }
    }

    // Seen on the emulator (2026-10-14): full screen from the guide, and OK did nothing visible. The
    // hidden guide kept focus, so each OK went to it ("watch this channel") instead of the info bar.
    @Test fun okInFullScreenFromTheGuideOpensTheInfoBar() = keyMapTest {
        guide()
        // OK previews the channel, or goes full screen when it's the one already playing.
        press(RemoteAction.Select)
        if (app.navigator.current !is Route.FullScreen) press(RemoteAction.Select)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
        press(RemoteAction.Select)
        eventually("the info bar") { shows("Aspect ratio") }
        focusedStartsWith("Audio")
        press(RemoteAction.Right)
        focusedStartsWith("Subtitles")
        press(RemoteAction.Select)
        eventually("the subtitles picker") { panelTitle == "Subtitles" }
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

    @Test fun upDownChangeRowAndWrapAtTheEnds() = keyMapTest {
        guide()
        val first = focusedChannel()
        press(RemoteAction.Down)
        eventually("next row") { focusedChannel() != first }
        press(RemoteAction.Up)
        eventually("first row") { focusedChannel() == first }
        press(RemoteAction.Up)
        eventually("wrapped to the last row") { focusedChannel() != first && focusedChannel() != "" }
        val last = focusedChannel()
        press(RemoteAction.Down)
        eventually("wrapped to the first row") { focusedChannel() == first }
        press(RemoteAction.Up)
        eventually("the last row again") { focusedChannel() == last }
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

    @Test fun reopeningStartsOverOnTheFirstGroupAndThePlayingChannel() = keyMapTest {
        guide()
        val header = focusedChannel()
        val firstGroup = current!!.groupName
        press(RemoteAction.Left)
        press(RemoteAction.Left)
        press(RemoteAction.Down)
        val next = focusedText()!!
        val nextTitle = app.groups.value.map { it.group.title }.filter { next.startsWith(it) }.maxBy { it.length }
        press(RemoteAction.Select)
        eventually("the other group") { shows("Today · $nextTitle") }
        // The page is kept alive between visits (PLAN §8 → milestone 5), but each opening starts over (§4.3.2).
        ui.runOnIdle { app.navigator.go(Route.Home) }
        heroFocused()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        eventually("the first group again") { shows("Today · $firstGroup") && !shows("Today · $nextTitle") }
        eventually("on the playing channel") { focusedText()?.contains(" · ") == true && focusedChannel() == header }
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

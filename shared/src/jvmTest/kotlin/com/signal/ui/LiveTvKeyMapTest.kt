package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * PLAN §12.1, Live TV guide and Group panel rows, against milestone 1's stand-in: a group column
 * and a channel list (PLAN §8). OK previews then goes full screen; the group column is the panel.
 */
class LiveTvKeyMapTest {
    @Test fun okPreviewsThenGoesFullScreen() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        val first = current!!
        press(RemoteAction.Down)
        eventually("next row") { focusedText()?.contains(first.displayName) == false }
        press(RemoteAction.Select)
        eventually("preview tuned") { current?.key != first.key }
        assertEquals(Route.LiveTv, app.navigator.current)
        press(RemoteAction.Select)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
    }

    @Test fun longPressOpensTheChannelMenu() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        press(RemoteAction.Options)
        eventually("channel menu") { panelTitle?.contains(current!!.displayName) == true }
    }

    @Test fun backAndUpFromTheTopRowRevealTheNavBar() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        press(RemoteAction.Back)
        focusedStartsWith("Live TV")
        press(RemoteAction.Down)
        liveTvReady()
        press(RemoteAction.Up)
        focusedStartsWith("Live TV")
    }

    @Test fun leftReachesTheGroupsAndRightReturns() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        val group = current!!.groupName
        press(RemoteAction.Left)
        focusedStartsWith(group)
        press(RemoteAction.Right)
        liveTvReady()
    }

    @Test fun leftReachesAGroupScrolledOutOfView() = keyMapTest {
        heroFocused()
        val last = app.groups.value.last().group
        ui.runOnIdle {
            app.liveTvGroup = last
            app.navigator.go(Route.LiveTv)
        }
        eventually("Live TV on the last group") { shows("${last.title} · ") && focusedText()?.first()?.isDigit() == true }
        press(RemoteAction.Left)
        focusedStartsWith(last.title)
    }

    @Test fun groupPanelOkAppliesTheGroup() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        val before = current!!.groupName
        press(RemoteAction.Left)
        focusedStartsWith(before)
        press(RemoteAction.Down)
        val next = focusedText()!!
        assertNotEquals(before, next)
        val nextTitle = app.groups.value.map { it.group.title }.filter { next.startsWith(it) }.maxBy { it.length }
        press(RemoteAction.Select)
        // The list shows the new group and takes focus (rows start with the channel number); the preview keeps playing.
        eventually("the new group's channels") { shows("$nextTitle · ") && focusedText()?.first()?.isDigit() == true }
        assertEquals(before, current!!.groupName)
    }

    @Test fun groupPanelLongPressOpensTheGroupMenu() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        val group = current!!.groupName
        press(RemoteAction.Left)
        focusedStartsWith(group)
        press(RemoteAction.Options)
        eventually("group menu") { panelTitle == "Group: $group" }
    }
}

package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * PLAN §12.1, the full-screen live rows: Full screen, Info bar, Zap banner and Channel list.
 * "Aspect ratio" is an info bar option, so it marks the bar as open.
 */
class FullScreenKeyMapTest {
    @Test fun upDownZapInTheCurrentGroup() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Down)
        eventually("zapped down") { current?.key != start.key }
        press(RemoteAction.Up)
        eventually("zapped back") { current?.key == start.key }
        press(RemoteAction.ChannelDown)
        eventually("channel down zaps too") { current?.key != start.key }
    }

    @Test fun okOpensTheInfoBarAndLongPressFocusesItsOptions() = keyMapTest {
        toFullScreen()
        press(RemoteAction.Select)
        eventually("info bar") { shows("Aspect ratio") }
        focusedStartsWith("Audio")
        press(RemoteAction.Back)
        eventually("info bar closed") { !shows("Aspect ratio") }
        press(RemoteAction.Options)
        eventually("info bar") { shows("Aspect ratio") }
        focusedStartsWith("Audio")
    }

    @Test fun leftOpensTheChannelListAndRightIsUnassigned() = keyMapTest {
        toFullScreen()
        press(RemoteAction.Right)
        assertFalse(shows("Groups"))
        // The info bar shown on tuning takes no keys: Left still opens the channel list.
        press(RemoteAction.Left)
        eventually("channel list") { shows("Groups") }
    }

    @Test fun backGoesToLiveTvOnThePlayingChannel() = keyMapTest {
        toFullScreen()
        press(RemoteAction.Back)
        route(Route.LiveTv)
        liveTvReady()
    }

    @Test fun playPauseRecallsTheLastChannel() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Down)
        eventually("zapped") { current?.key != start.key }
        val second = current!!
        press(RemoteAction.PlayPause)
        eventually("recalled") { current?.key == start.key }
        press(RemoteAction.PlayPause)
        eventually("recalled back") { current?.key == second.key }
    }

    // ---- Info bar ----------------------------------------------------------------------

    @Test fun infoBarUpDownStillZap() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Select)
        eventually("info bar") { shows("Aspect ratio") }
        press(RemoteAction.Down)
        eventually("zapped") { current?.key != start.key }
        assertEquals(true, shows("Aspect ratio"))
    }

    @Test fun infoBarLeftRightMoveBetweenOptionsAndOkActivates() = keyMapTest {
        toFullScreen()
        press(RemoteAction.Select)
        focusedStartsWith("Audio")
        press(RemoteAction.Right)
        focusedStartsWith("Subtitles")
        press(RemoteAction.Left)
        focusedStartsWith("Audio")
        press(RemoteAction.Select)
        eventually("audio picker") { panelTitle == "Audio" }
    }

    // Up/Down in a picker used to fall through to the info bar and zap (user, 2026-10-13).
    @Test fun aPickerOwnsUpAndDown() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Options)
        focusedStartsWith("Audio")
        press(RemoteAction.Right, 2)
        focusedStartsWith("Aspect ratio")
        press(RemoteAction.Select)
        eventually("aspect picker") { panelTitle == "Aspect ratio" }
        focusedStartsWith("Fit")
        press(RemoteAction.Down)
        focusedStartsWith("Fill")
        press(RemoteAction.Down)
        focusedStartsWith("Stretch")
        press(RemoteAction.Up)
        focusedStartsWith("Fill")
        assertEquals(start.key, current?.key, "no zap underneath")
        assertEquals("Aspect ratio", panelTitle)
    }

    // Back from a picker put focus on Audio, not the button that opened it (user, 2026-10-15).
    @Test fun backFromAPickerReturnsToItsButton() = keyMapTest {
        toFullScreen()
        press(RemoteAction.Select)
        focusedStartsWith("Audio")
        press(RemoteAction.Right)
        focusedStartsWith("Subtitles")
        press(RemoteAction.Select)
        eventually("subtitle picker") { panelTitle == "Subtitles" }
        press(RemoteAction.Back)
        eventually("picker closed") { !app.panel.isOpen }
        focusedStartsWith("Subtitles")
    }

    @Test fun infoBarPlayPauseRecalls() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Down)
        eventually("zapped") { current?.key != start.key }
        press(RemoteAction.Select)
        eventually("info bar") { shows("Aspect ratio") }
        press(RemoteAction.PlayPause)
        eventually("recalled") { current?.key == start.key }
    }

    // ---- The info bar on a channel change (user, 2026-10-16; it was a zap banner) ----------

    @Test fun aChannelChangeShowsTheInfoBarWithoutTakingKeys() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Down)
        eventually("the info bar for the new channel") { current?.key != start.key && shows("Aspect ratio") && shows(current!!.displayName) }
        // It takes no focus: Down changes channel again, rather than moving within it.
        val second = current!!
        press(RemoteAction.Down)
        eventually("the next channel") { current?.key != second.key }
        // OK makes it the interactive one, on its first option.
        press(RemoteAction.Select)
        eventually("the info bar's options") { focusedText() == "Audio" }
        // It hides after 5 seconds.
        ui.mainClock.advanceTimeBy(6_000)
        eventually("hidden") { !shows("Aspect ratio") }
    }

    // ---- Channel list ------------------------------------------------------------------

    @Test fun channelListUpDownMoveWithoutTuningAndOkTunes() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Left)
        eventually("on the playing channel") { focusedText()?.contains(start.displayName) == true }
        press(RemoteAction.Down)
        eventually("moved") { focusedText()?.contains(start.displayName) == false }
        assertEquals(start.key, current?.key)
        press(RemoteAction.Select)
        eventually("tuned and closed") { current?.key != start.key && !shows("Groups") }
    }

    @Test fun channelListLeftReachesGroupsAndRightCloses() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Left)
        eventually("on the playing channel") { focusedText()?.contains(start.displayName) == true }
        press(RemoteAction.Left)
        focusedStartsWith(start.groupName)
        press(RemoteAction.Right)
        eventually("back on the channels") { focusedText()?.contains(start.displayName) == true }
        press(RemoteAction.Right)
        eventually("closed") { !shows("Groups") }
    }

    /** Seen on the Shield: after picking a group, nothing had focus. */
    @Test fun channelListPickingAGroupFocusesItsChannels() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Left)
        eventually("on the playing channel") { focusedText()?.contains(start.displayName) == true }
        press(RemoteAction.Left)
        focusedStartsWith(start.groupName)
        press(RemoteAction.Down)
        val next = focusedText()!!
        val nextTitle = app.groups.value.map { it.group.title }.filter { next.startsWith(it) }.maxBy { it.length }
        press(RemoteAction.Select)
        eventually("the new group's first channel") { shows("$nextTitle · ") && focusedText()?.first()?.isDigit() == true }
        press(RemoteAction.Down)
        eventually("Down moves through it") { focusedText()?.first()?.isDigit() == true }
    }

    /** The list stays built once opened (milestone 5): closed, it takes no keys, and it reopens on the playing channel. */
    @Test fun channelListKeptClosedTakesNoKeysAndReopensOnThePlayingChannel() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Left)
        eventually("on the playing channel") { focusedText()?.contains(start.displayName) == true }
        press(RemoteAction.Down)
        eventually("moved") { focusedText()?.contains(start.displayName) == false }
        press(RemoteAction.Back)
        eventually("list closed") { !shows("Groups") }
        // OK opens the info bar, not the row that was focused in the hidden list.
        press(RemoteAction.Select)
        eventually("info bar") { shows("Aspect ratio") }
        assertEquals(start.key, current?.key, "nothing tuned")
        press(RemoteAction.Back)
        eventually("info bar closed") { !shows("Aspect ratio") }
        // Up/Down zap again, and the list reopens on the new channel.
        press(RemoteAction.Down)
        eventually("zapped") { current?.key != start.key }
        val now = current!!
        press(RemoteAction.Left)
        eventually("reopened on the new channel") { focusedText()?.contains(now.displayName) == true }
    }

    /** Built ahead after 2 s without a key, it stays out of the way until Left. */
    @Test fun channelListBuiltInIdleTimeTakesNothing() = keyMapTest {
        toFullScreen()
        val start = current!!
        ui.mainClock.advanceTimeBy(6_000)
        settle()
        assertFalse(shows("Groups"))
        press(RemoteAction.Down)
        eventually("Down still zaps") { current?.key != start.key }
        val now = current!!
        press(RemoteAction.Left)
        eventually("opens on the playing channel") { focusedText()?.contains(now.displayName) == true }
    }

    @Test fun channelListBackClosesAndLongPressOpensTheMenu() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Left)
        eventually("on the playing channel") { focusedText()?.contains(start.displayName) == true }
        press(RemoteAction.Options)
        eventually("channel menu") { panelTitle?.contains(start.displayName) == true }
        press(RemoteAction.Back)
        eventually("menu closed") { !app.panel.isOpen && shows("Groups") }
        press(RemoteAction.Back)
        eventually("list closed") { !shows("Groups") }
        assertEquals(true, app.navigator.current is Route.FullScreen)
    }
}

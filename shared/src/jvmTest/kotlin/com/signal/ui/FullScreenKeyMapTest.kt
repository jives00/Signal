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
        assertFalse(shows("Aspect ratio"))
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

    // ---- Zap banner --------------------------------------------------------------------

    @Test fun zapBannerOkOpensTheInfoBar() = keyMapTest {
        toFullScreen()
        val start = current!!
        press(RemoteAction.Down)
        eventually("banner") { current?.key != start.key && shows(current!!.displayName) }
        assertFalse(shows("Aspect ratio"))
        press(RemoteAction.Select)
        eventually("info bar") { shows("Aspect ratio") }
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

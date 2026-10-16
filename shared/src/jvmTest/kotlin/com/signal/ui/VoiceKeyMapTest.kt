package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Voice commands acting on the app (PLAN §4.3.14 → Voice commands, phase 2), as the assistant hands them over. */
class VoiceKeyMapTest {
    @Test fun oneClearChannelIsWatchedAtOnce() = keyMapTest {
        val r = app.voice(listOf("watch golf channel"))
        assertEquals("Golf Channel", r.shown, r.message)
        eventually("full screen on Golf Channel") { app.navigator.current is Route.FullScreen && current?.displayName == "Golf Channel" }
        assertEquals(null, panelTitle)
    }

    @Test fun aSharedNameAsksWhichChannel() = keyMapTest {
        val r = app.voice(listOf("fox"))
        assertEquals("Choose a channel", r.shown, r.message)
        eventually("the choice") { panelTitle == "Which channel?" }
        // Rows are labeled as everywhere: number, name, then the group.
        eventually("the first choice focused") { focusedText()?.contains("FOX") == true }
        val first = focusedText()
        press(RemoteAction.Down)
        eventually("the second choice") { focusedText() != first && focusedText()?.contains("FOX") == true }
        val second = focusedText()!!
        press(RemoteAction.Select)
        eventually("full screen on the second choice") { app.navigator.current is Route.FullScreen && current != null && second.contains(current!!.displayName) }
        assertEquals(null, panelTitle)
    }

    @Test fun backClosesTheChoice() = keyMapTest {
        app.voice(listOf("fox"))
        eventually("the choice") { panelTitle == "Which channel?" }
        press(RemoteAction.Back)
        eventually("closed") { panelTitle == null }
        assertEquals(Route.Home, app.navigator.current)
    }

    @Test fun pagesByName() = keyMapTest {
        app.voice(listOf("go to the guide"))
        route(Route.LiveTv)
        app.voice(listOf("open settings"))
        route(Route.Settings)
        app.voice(listOf("go home"))
        route(Route.Home)
    }

    // The recognizer's top guess was a command: it's taken even when a later guess names a channel.
    @Test fun aCommandGuessComesFirst() = keyMapTest {
        app.voice(listOf("guide", "golf channel"))
        route(Route.LiveTv)
    }

    @Test fun liveTvCantSeekButStopsAndPlays() = keyMapTest {
        toFullScreen()
        val playing = current!!
        val seek = app.voice(listOf("back thirty seconds"))
        assertFalse(seek.acted)
        assertEquals("Live TV can't skip back", seek.shown)
        app.voice(listOf("pause"))
        eventually("stopped") { player.state.value.request == null }
        app.voice(listOf("resume"))
        eventually("playing again") { player.state.value.request?.label == playing.displayName }
    }

    @Test fun channelUpAndLastChannel() = keyMapTest(favorites = 3) {
        toFullScreen()
        val first = current!!
        app.voice(listOf("channel down"))
        eventually("the next channel") { current?.key != first.key }
        app.voice(listOf("last channel"))
        eventually("back to the first") { current?.key == first.key }
    }

    @Test fun whatsOnOpensProgramDetails() = keyMapTest {
        guideReady()
        val r = app.voice(listOf("what's on golf channel"))
        assertTrue(r.acted, r.message)
        eventually("program details") { panelTitle != null && panelTitle != "Which channel?" }
        // Nothing was tuned.
        assertEquals(Route.Home, app.navigator.current)
    }

    @Test fun nothingPlayingSaysSo() = keyMapTest(start = Route.Settings) {
        val r = app.voice(listOf("pause"))
        assertFalse(r.acted)
        assertEquals("Nothing is playing", r.shown)
    }
}

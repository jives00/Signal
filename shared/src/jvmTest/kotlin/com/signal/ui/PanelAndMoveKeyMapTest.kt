package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.model.ChannelGroup
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLAN §12.1, Side panel and Move mode rows. */
class PanelAndMoveKeyMapTest {
    private fun KeyMapScope.favorites() = runBlocking { app.channels.channelsNow(ChannelGroup.Favorites) }

    @Test fun sidePanelMovesClosesAndIsModal() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        toChannelColumn()
        val row = focusedText()
        press(RemoteAction.Options)
        focusedStartsWith("Add to Favorites")
        press(RemoteAction.Down)
        focusedStartsWith("Add to playlist")
        // Left/Right and playback keys never leak to the screen beneath.
        press(RemoteAction.Left)
        press(RemoteAction.Right)
        press(RemoteAction.PlayPause)
        focusedStartsWith("Add to playlist")
        press(RemoteAction.Back)
        eventually("closed, focus back on the row") { !app.panel.isOpen && focusedText() == row }
    }

    @Test fun sidePanelOkActivates() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        toChannelColumn()
        press(RemoteAction.Options)
        focusedStartsWith("Add to Favorites")
        press(RemoteAction.Select)
        eventually("favorited") { favorites().size == 1 }
        assertTrue(!app.panel.isOpen)
    }

    @Test fun sidePanelBackPopsOnePageAtATime() = keyMapTest(start = Route.LiveTv) {
        liveTvReady()
        toChannelColumn()
        press(RemoteAction.Options)
        focusedStartsWith("Add to Favorites")
        press(RemoteAction.Down)
        press(RemoteAction.Select)
        eventually("second page") { panelTitle == "Add to playlist" }
        press(RemoteAction.Back)
        eventually("first page") { panelTitle?.contains(current!!.displayName) == true }
        press(RemoteAction.Back)
        eventually("closed") { !app.panel.isOpen }
    }

    // In the guide, the channel menu is on the channel column (PLAN §12.1).
    private suspend fun KeyMapScope.toChannelColumn() {
        press(RemoteAction.Left)
        eventually("channel column") { focusedText()?.endsWith(" · channel") == true }
    }

    /** Live TV opens on Favorites when there are some; the channel menu there has Move, third. */
    private suspend fun KeyMapScope.startMovingFirstFavorite(): List<String> {
        val before = favorites()
        eventually("first favorite focused") { focusedText()?.contains(before[0].displayName) == true }
        toChannelColumn()
        press(RemoteAction.Options)
        focusedStartsWith("Remove from Favorites")
        press(RemoteAction.Down, times = 2)
        focusedStartsWith("Move")
        press(RemoteAction.Select)
        eventually("moving") { shows("↕ ") }
        return before.map { it.key }
    }

    @Test fun moveModeCarriesAndDrops() = keyMapTest(start = Route.LiveTv, favorites = 3) {
        val before = startMovingFirstFavorite()
        press(RemoteAction.Down)
        press(RemoteAction.Select)
        eventually("dropped one place down") { favorites().map { it.key } == listOf(before[1], before[0], before[2]) }
        assertTrue(!shows("↕ "))
    }

    @Test fun moveModeBackCancels() = keyMapTest(start = Route.LiveTv, favorites = 3) {
        val before = startMovingFirstFavorite()
        press(RemoteAction.Down, times = 2)
        press(RemoteAction.Back)
        eventually("cancelled") { !shows("↕ ") }
        assertEquals(before, favorites().map { it.key })
        assertEquals(Route.LiveTv, app.navigator.current)
    }
}

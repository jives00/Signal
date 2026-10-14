package com.signal.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.player.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pages built in idle time (AppRoot's warm-up, PLAN §8 → milestone 5): building them takes nothing
 * from the page that's showing, and each still opens the way the spec says.
 */
class WarmUpKeyMapTest {
    /** Long enough for every kept page to be built: 2 s quiet, then a 3 s window, each. */
    private fun KeyMapScope.idleThroughWarmUp() {
        ui.mainClock.advanceTimeBy(25_000)
    }

    @Test fun warmingLeavesHomeAlone() = keyMapTest(favorites = 3) {
        heroFocused()
        eventually("the hero playing") { player.played.isNotEmpty() }
        val plays = player.played.size
        idleThroughWarmUp()
        settle()
        heroFocused()
        assertEquals(plays, player.played.size, "the hero's stream never restarted")
        assertEquals(PlaybackState.Status.PLAYING, player.state.value.status)
        // The remote still works on Home.
        press(RemoteAction.Up)
        focusedStartsWith("Home")
    }

    @Test fun warmedPagesOpenAsUsual() = keyMapTest(favorites = 3) {
        heroFocused()
        idleThroughWarmUp()
        settle()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        liveTvReady()
        ui.runOnIdle { app.navigator.go(Route.Movies) }
        focusedStartsWith("Recently added")
        ui.runOnIdle { app.navigator.go(Route.Search) }
        eventually("the search field focused") { ui.runOnUiThread { ui.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() } }
        press(RemoteAction.Back)
        focusedStartsWith("Search")
    }
}

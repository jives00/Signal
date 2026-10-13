package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLAN §12.1, Nav bar row: OK opens · Back Home, on Home exit · Up/Down into the page · Left/Right between items. */
class NavBarKeyMapTest {
    @Test fun backFromThePageFocusesTheBarThenExitsOnHome() = keyMapTest {
        heroFocused()
        press(RemoteAction.Back)
        focusedStartsWith("Home")
        assertEquals(0, platform.exits)
        press(RemoteAction.Back)
        eventually("exit") { platform.exits == 1 }
    }

    @Test fun backOnAnotherPagesBarGoesHome() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        press(RemoteAction.Back)
        focusedStartsWith("Settings")
        press(RemoteAction.Back)
        route(Route.Home)
        assertEquals(0, platform.exits)
    }

    @Test fun leftRightMoveBetweenItemsAndOkOpens() = keyMapTest {
        heroFocused()
        press(RemoteAction.Back)
        focusedStartsWith("Home")
        press(RemoteAction.Right)
        focusedStartsWith("Live TV")
        // The Demo source has movies and series, so both are on the bar (PLAN §4.1).
        press(RemoteAction.Right)
        focusedStartsWith("Movies")
        press(RemoteAction.Right)
        focusedStartsWith("Series")
        press(RemoteAction.Right)
        focusedStartsWith("Search")
        press(RemoteAction.Right)
        focusedStartsWith("Settings")
        repeat(4) { press(RemoteAction.Left) }
        focusedStartsWith("Live TV")
        press(RemoteAction.Right)
        press(RemoteAction.Select)
        route(Route.Movies)
    }

    @Test fun downGoesIntoThePage() = keyMapTest {
        heroFocused()
        press(RemoteAction.Back)
        focusedStartsWith("Home")
        press(RemoteAction.Right) // Down goes into the page wherever the bar's focus is.
        press(RemoteAction.Down)
        heroFocused()
    }
}

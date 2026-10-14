package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.model.ChannelGroup
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLAN §12.1, Home row (milestone 1: the hero and the Favorites row). */
class HomeKeyMapTest {
    @Test fun okOnTheHeroGoesFullScreen() = keyMapTest {
        toFullScreen()
    }

    @Test fun longPressOnTheHeroOpensTheChannelMenu() = keyMapTest {
        heroFocused()
        press(RemoteAction.Options)
        eventually("channel menu") { panelTitle?.contains(current!!.displayName) == true }
    }

    @Test fun backAndUpGoToTheNavBar() = keyMapTest {
        heroFocused()
        press(RemoteAction.Up)
        focusedStartsWith("Home")
        press(RemoteAction.Down)
        heroFocused()
        press(RemoteAction.Back)
        focusedStartsWith("Home")
    }

    @Test fun favoritesRowMovesWithinTheRowAndOpens() = keyMapTest(favorites = 3) {
        val favorites = runBlocking { app.channels.channelsNow(ChannelGroup.Favorites) }
        heroFocused()
        press(RemoteAction.Down)
        eventually("first favorite") { focusedText()?.contains(favorites[0].displayName) == true }
        press(RemoteAction.Right)
        eventually("second favorite") { focusedText()?.contains(favorites[1].displayName) == true }
        press(RemoteAction.Options)
        eventually("its channel menu") { panelTitle?.contains(favorites[1].displayName) == true }
        press(RemoteAction.Back)
        eventually("menu closed") { !app.panel.isOpen }
        press(RemoteAction.Select)
        route(Route.FullScreen(ChannelGroup.Favorites))
        assertEquals(favorites[1].key, current?.key)
    }

    @Test fun liveTvCarriesTheHomeStreamOver() = keyMapTest {
        heroFocused()
        eventually("the hero playing") { platform.player.played.isNotEmpty() }
        val plays = platform.player.played.size
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        liveTvReady()
        // The same stream, never stopped and started again: that rebuffers and jumps (user, 2026-10-13).
        assertEquals(plays, platform.player.played.size)
        assertTrue(platform.player.state.value.status == com.signal.player.PlaybackState.Status.PLAYING)
        ui.runOnIdle { app.navigator.go(Route.Home) }
        heroFocused()
        assertEquals(plays, platform.player.played.size, "and back again")
    }

    @Test fun withNoFavoritesTheRowOffersToAddThem() = keyMapTest {
        heroFocused()
        press(RemoteAction.Down)
        focusedStartsWith("Add favorites")
        press(RemoteAction.Select)
        route(Route.LiveTv)
        assertTrue(app.navigator.current == Route.LiveTv)
    }

    // The safety net: whatever deleted the focused element, a direction brings focus back to the page.
    @Test fun aDirectionWithNothingFocusedGoesBackToThePage() = keyMapTest {
        heroFocused()
        ui.runOnIdle { app.dispatcher.recoverFocus() }
        eventually("only the root focused") { focusedText() == null }
        press(RemoteAction.Down)
        heroFocused()
    }
}

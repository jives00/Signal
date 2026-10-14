package com.signal.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTextReplacement
import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.model.ChannelGroup
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLAN §12.1, the Search row: OK opens by type · long-press the menu by type · Back: results → field → nav bar. */
class SearchKeyMapTest {
    private suspend fun KeyMapScope.search(query: String) {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.Search) }
        typeInFocusedField(query)
        // Past the 300ms typing pause, on the test's virtual clock.
        ui.mainClock.advanceTimeBy(400)
        eventually("results") { shows("Channels · ") || shows("Programs · ") }
    }

    private fun KeyMapScope.fieldFocused() = textFieldFocused()

    @Test fun okOnAChannelGoesFullScreenAndBackWalksOut() = keyMapTest {
        search("sports center")
        press(RemoteAction.Down) // chips
        press(RemoteAction.Down) // the Channels row
        eventually("a channel card") { focusedText()?.contains("Sports Center") == true }
        press(RemoteAction.Back)
        eventually("back in the field") { fieldFocused() }
        press(RemoteAction.Back)
        focusedStartsWith("Search")
        press(RemoteAction.Down)
        eventually("into the page") { fieldFocused() }
        press(RemoteAction.Down)
        press(RemoteAction.Down)
        eventually("a channel card") { focusedText()?.contains("Sports Center") == true }
        press(RemoteAction.Select)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
        assertTrue(current!!.displayName.contains("Sports Center"))
    }

    @Test fun longPressOnAChannelOpensItsMenuAndAProgramItsDetails() = keyMapTest {
        search("sports center")
        press(RemoteAction.Down)
        press(RemoteAction.Down)
        eventually("a channel card") { focusedText()?.contains("Sports Center") == true }
        press(RemoteAction.Options)
        eventually("channel menu") { panelTitle?.contains("Sports Center") == true }
        press(RemoteAction.Back)
        eventually("closed") { !app.panel.isOpen }
    }

    @Test fun programsFindTitlesAndOpenByTime() = keyMapTest {
        val title = "Championship Final"
        search("champ fin")
        eventually("program results") { shows("Programs · ") }
        // Only Programs: a grid of them.
        press(RemoteAction.Down) // chips: All
        press(RemoteAction.Right)
        press(RemoteAction.Right) // Programs
        press(RemoteAction.Select)
        eventually("the programs grid") { shows("Programs · ") && !shows("Channels · ") }
        press(RemoteAction.Down)
        eventually("a program card") { focusedText()?.contains(title) == true }
        val onNow = focusedText()!!.contains("On now")
        press(RemoteAction.Options)
        eventually("program details") { panelTitle == title }
        press(RemoteAction.Back)
        press(RemoteAction.Select)
        if (onNow) eventually("full screen") { app.navigator.current is Route.FullScreen }
        else eventually("details") { panelTitle == title }
        // The filter is remembered on the device.
        assertEquals("PROGRAMS", app.settings.raw(com.signal.data.Settings.Key.SEARCH_FILTER))
    }

    @Test fun recentSearchesRunAndRemove() = keyMapTest {
        search("golf")
        ui.runOnIdle { app.navigator.go(Route.Home) } // leaving after typing saves the search
        ui.runOnIdle { app.navigator.go(Route.Search) }
        eventually("recent searches") { shows("Recent searches") && shows("golf") }
        press(RemoteAction.Down)
        focusedStartsWith("All")
        press(RemoteAction.Down)
        focusedStartsWith("golf")
        press(RemoteAction.Options)
        eventually("removed") { !shows("golf") }
        assertEquals(null, app.settings.raw(com.signal.data.Settings.Key.RECENT_SEARCHES)?.takeIf { it.contains("golf") })
    }

    @Test fun hiddenChannelsAreLeftOut() = keyMapTest {
        guideReady()
        val ch = runBlocking { app.channels.channelsNow(app.channels.groupsNow().first { it.group is ChannelGroup.Provider }.group).first() }
        assertTrue(runBlocking { app.search.channels("sports center") }.any { it.stream.id == ch.stream.id })
        runBlocking { app.channels.setHidden(ch, true) }
        eventually("hidden from search") { runBlocking { app.search.channels("sports center") }.none { it.stream.id == ch.stream.id } }
    }
}

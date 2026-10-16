package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.match.NameCleanup
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLAN §12.1, Settings and First-run setup rows. */
class SettingsAndSetupKeyMapTest {
    @Test fun settingsUpDownMoveAndLeftRightSwitchSectionsAndContent() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        press(RemoteAction.Down)
        focusedStartsWith("Channels & groups")
        press(RemoteAction.Up)
        focusedStartsWith("Sources")
        press(RemoteAction.Right)
        focusedStartsWith("Demo")
        press(RemoteAction.Left)
        focusedStartsWith("Sources")
    }

    @Test fun settingsOkActivates() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        press(RemoteAction.Select)
        focusedStartsWith("Demo")
        press(RemoteAction.Select)
        eventually("source actions") { panelTitle == "Demo" }
    }

    @Test fun settingsBackGoesUpALevelThenToTheNavBar() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        press(RemoteAction.Right)
        focusedStartsWith("Demo")
        press(RemoteAction.Back)
        focusedStartsWith("Sources")
        press(RemoteAction.Back)
        focusedStartsWith("Settings")
    }

    @Test fun setupUpDownMove() = keyMapTest(start = Route.Setup, withDemo = false) {
        focusedStartsWith("Connect to NAS")
        press(RemoteAction.Down)
        focusedStartsWith("Add a source")
        press(RemoteAction.Down)
        focusedStartsWith("Use the Demo source")
        press(RemoteAction.Up)
        focusedStartsWith("Add a source")
    }

    @Test fun setupOkOpensTheStepAndBackReturnsThenExits() = keyMapTest(start = Route.Setup, withDemo = false) {
        focusedStartsWith("Connect to NAS")
        press(RemoteAction.Down)
        press(RemoteAction.Select)
        eventually("the source form") { shows("Enter the M3U link") }
        press(RemoteAction.Back)
        eventually("the first step") { shows("Welcome to Signal") }
        focusedStartsWith("Connect to NAS")
        assertEquals(0, platform.exits)
        press(RemoteAction.Back)
        eventually("exit") { platform.exits == 1 }
    }

    @Test fun setupDemoSourceGoesHome() = keyMapTest(start = Route.Setup, withDemo = false) {
        focusedStartsWith("Connect to NAS")
        press(RemoteAction.Down)
        press(RemoteAction.Down)
        press(RemoteAction.Select)
        route(Route.Home)
    }

    @Test fun setupConnectToNasOpensTheFormAndBackReturns() = keyMapTest(start = Route.Setup, withDemo = false) {
        focusedStartsWith("Connect to NAS")
        press(RemoteAction.Select)
        eventually("the NAS form") { shows("Pair this TV") }
        press(RemoteAction.Back)
        eventually("the first step") { shows("Welcome to Signal") }
        focusedStartsWith("Connect to NAS")
    }

    @Test fun channelNumbersCanBeHidden() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        repeat(5) { press(RemoteAction.Down) }
        focusedStartsWith("Appearance")
        press(RemoteAction.Right)
        focusedStartsWith("Start page")
        press(RemoteAction.Down, times = 2)
        focusedStartsWith("Channel numbers")
        assertEquals(true, app.showNumbers.value)
        press(RemoteAction.Select)
        eventually("numbers hidden") { !app.showNumbers.value && focusedText()?.contains("Hidden") == true }
        val ch = app.playback.startingChannel()!!
        assertEquals(ch.displayName, app.channelLabel(ch))
        press(RemoteAction.Select)
        eventually("numbers shown") { app.showNumbers.value }
        assertEquals("${ch.number}  ${ch.displayName}", app.channelLabel(ch))
    }

    @Test fun playbackSettingsReachThePlayer() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        repeat(4) { press(RemoteAction.Down) }
        focusedStartsWith("Playback")
        press(RemoteAction.Right)
        focusedStartsWith("Audio language")
        // Audio language, Subtitles: pick Always.
        press(RemoteAction.Down)
        focusedStartsWith("Subtitles")
        press(RemoteAction.Select)
        eventually("the subtitles panel") { app.panel.top?.title == "Subtitles" }
        press(RemoteAction.Down, times = 2)
        focusedStartsWith("Always")
        press(RemoteAction.Select)
        eventually("subtitles always") { app.playerSettings.value.subtitles == com.signal.player.SubtitleMode.ALWAYS }
        assertEquals("ALWAYS", app.settings.raw(com.signal.data.Settings.Key.SUBTITLES))
    }

    @Test fun nameCleanupPresetsAndOwnRules() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        press(RemoteAction.Down)
        focusedStartsWith("Channels & groups")
        press(RemoteAction.Right)
        focusedStartsWith("Group order")
        press(RemoteAction.Down, times = 2)
        focusedStartsWith("Country codes at the start")
        press(RemoteAction.Select)
        eventually("the preset's panel") { panelTitle == "Country codes at the start" }
        focusedStartsWith("On")
        press(RemoteAction.Select)
        eventually("country codes on") {
            NameCleanup.Preset.COUNTRY in app.settings.nameCleanup().presets && focusedText()?.startsWith("Country codes at the start") == true
        }

        // Past the other presets to "Add text to remove": at the end, "hd".
        press(RemoteAction.Down, times = 4)
        focusedStartsWith("Add text to remove")
        press(RemoteAction.Select)
        eventually("where to remove it") { panelTitle == "Remove text from names" }
        press(RemoteAction.Down)
        focusedStartsWith("At the end")
        press(RemoteAction.Select)
        typeInFocusedField(" hd ")
        press(RemoteAction.Down)
        focusedStartsWith("Add")
        press(RemoteAction.Select)
        eventually("the rule saved") { app.settings.nameCleanup().custom == listOf(NameCleanup.Rule("hd", NameCleanup.At.END)) }
        eventually("the rule listed") { shows("Remove \"hd\" at the end") }
        assertEquals("ESPN", app.settings.nameCleanup().apply("US: ESPN HD"))
    }

    @Test fun theStartPageOpensWhereSet() = keyMapTest {
        suspend fun open(page: com.signal.app.StartPage?) {
            app.settings.put(com.signal.data.Settings.Key.START_PAGE, page?.name)
            ui.runOnIdle { app.navigator.resetTo(Route.Settings) }
            ui.runOnIdle { app.scope.launch { app.openStartPage() } }
        }
        open(com.signal.app.StartPage.LIVE_TV)
        route(Route.LiveTv)
        open(com.signal.app.StartPage.MOVIES)
        route(Route.Movies)
        open(null)
        route(Route.Home)
        // The last channel full screen, and Back goes to the guide like any full screen.
        open(com.signal.app.StartPage.LAST_CHANNEL)
        eventually("full screen") { app.navigator.current is Route.FullScreen }
        eventually("playing") { app.playback.current.value != null }
        press(RemoteAction.Back)
        route(Route.LiveTv)
    }

    @Test fun settingsNasSectionOffersPairing() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        // Sources, Channels & groups, Live TV & guide, Movies & Series, Playback, Appearance, then NAS & sync.
        repeat(6) { press(RemoteAction.Down) }
        focusedStartsWith("NAS & sync")
        press(RemoteAction.Right)
        focusedStartsWith("Pair with NAS")
        press(RemoteAction.Select)
        eventually("the NAS form") { shows("Home network address") }
        press(RemoteAction.Back)
        eventually("back to the section") { shows("Pair with NAS") }
    }
}

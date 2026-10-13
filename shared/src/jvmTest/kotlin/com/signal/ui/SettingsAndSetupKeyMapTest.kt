package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
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

    @Test fun settingsNasSectionOffersPairing() = keyMapTest(start = Route.Settings) {
        focusedStartsWith("Sources")
        // Sources, Channels & groups, Live TV & guide, Movies & Series, Appearance, then NAS & sync.
        repeat(5) { press(RemoteAction.Down) }
        focusedStartsWith("NAS & sync")
        press(RemoteAction.Right)
        focusedStartsWith("Pair with NAS")
        press(RemoteAction.Select)
        eventually("the NAS form") { shows("Home network address") }
        press(RemoteAction.Back)
        eventually("back to the section") { shows("Pair with NAS") }
    }
}

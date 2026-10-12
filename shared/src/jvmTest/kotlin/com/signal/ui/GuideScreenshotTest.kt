package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test

/** Renders the guide at 1080p for a look; the images land in shared/build/screenshots. */
class GuideScreenshotTest {
    @Test fun guide() = keyMapTest {
        guideReady()
        ui.runOnIdle { app.navigator.go(Route.LiveTv) }
        eventually("guide focused") { focusedText()?.contains(" · ") == true }
        settle()
        screenshot("guide")
        press(RemoteAction.Down, times = 2)
        press(RemoteAction.Right, times = 2)
        screenshot("guide-future")
        press(RemoteAction.Options)
        eventually("details") { app.panel.isOpen }
        screenshot("guide-details")
    }
}

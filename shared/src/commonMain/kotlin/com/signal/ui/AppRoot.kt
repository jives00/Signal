package com.signal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.input.LocalRemoteDispatcher
import com.signal.input.RemoteAction
import com.signal.input.RemoteInput
import com.signal.ui.components.LocalVideoLayer
import com.signal.ui.components.VideoLayer
import com.signal.ui.components.VideoLayerHost
import com.signal.ui.panel.SidePanelHost
import com.signal.ui.screens.FullScreenPlayback
import com.signal.ui.screens.GuideSpike
import com.signal.ui.screens.HomePage
import com.signal.ui.screens.LiveTvPage
import com.signal.ui.screens.SettingsPage
import com.signal.ui.screens.SetupScreen
import com.signal.ui.theme.SignalTheme

val LocalApp = staticCompositionLocalOf<AppController> { error("No AppController") }

/** Root of the app on every platform: theme, key layer, routes and the shared side panel. */
@Composable
fun SignalApp(app: AppController) {
    SignalTheme {
        RemoteInput(app.dispatcher) {
            val videoLayer = remember { VideoLayer() }
            CompositionLocalProvider(LocalApp provides app, LocalRemoteDispatcher provides app.dispatcher, LocalVideoLayer provides videoLayer) {
                val focusManager = LocalFocusManager.current
                SideEffect {
                    // What nobody else handled: directions move focus, Back pops a screen (PLAN §12.3).
                    app.dispatcher.onDefault = { action ->
                        when (action) {
                            RemoteAction.Up -> focusManager.moveFocus(FocusDirection.Up)
                            RemoteAction.Down -> focusManager.moveFocus(FocusDirection.Down)
                            RemoteAction.Left -> focusManager.moveFocus(FocusDirection.Left)
                            RemoteAction.Right -> focusManager.moveFocus(FocusDirection.Right)
                            RemoteAction.Back -> app.navigator.back()
                            else -> false
                        }
                    }
                }
                Box(Modifier.fillMaxSize().background(SignalTheme.colors.bg)) {
                    // The one video view, under every screen (see VideoLayer).
                    VideoLayerHost(videoLayer, app.platform.player)
                    if (app.ready) {
                        when (val route = app.navigator.current) {
                            Route.Setup -> SetupScreen()
                            Route.Home -> HomePage()
                            Route.LiveTv -> LiveTvPage()
                            Route.Settings -> SettingsPage()
                            is Route.FullScreen -> FullScreenPlayback(route)
                            Route.GuideSpike -> GuideSpike(onClose = { app.navigator.back() })
                        }
                        SidePanelHost(app.panel)
                    }
                }
            }
        }
    }
}

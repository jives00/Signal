package com.signal.dev

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.signal.ui.screens.SignalApp

fun main() = application {
    // 1280×720 by default; the design-unit scaling makes any size look like the TV (PLAN §7.1).
    Window(
        onCloseRequest = ::exitApplication,
        title = "Signal dev harness",
        state = rememberWindowState(width = 1280.dp, height = 720.dp),
    ) {
        SignalApp(versionLabel = "Dev harness")
    }
}

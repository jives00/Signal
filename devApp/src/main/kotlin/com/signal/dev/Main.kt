package com.signal.dev

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.signal.app.AppController
import com.signal.data.Log
import com.signal.ui.SignalApp

/**
 * The desktop dev harness (PLAN §7.1). Keyboard as remote: arrows = D-pad, Enter = OK (hold for
 * long press), Esc or Backspace = Back, Space = Play/Pause, PgUp/PgDn = channel up/down,
 * comma/period = rewind/fast-forward, digits = number keys.
 *
 * `./gradlew :devApp:run` plays real streams through VLC; add `-Psignal.player=fake` to use the
 * fake player, which never touches a provider connection (PLAN §7.4).
 */
fun main() {
    val player = if (System.getProperty("signal.player") == "fake") FakePlayer() else runCatching { VlcjPlayer() }.getOrElse {
        System.err.println("VLC not available (${it.message}); using the fake player.")
        FakePlayer()
    }
    val controller = AppController(DesktopPlatform(player), Log { level, message -> println("$level $message") })
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Signal dev harness",
            // 1280×720 by default; the design-unit scaling makes any size look like the TV.
            state = rememberWindowState(width = 1280.dp, height = 720.dp),
        ) {
            SignalApp(controller)
        }
    }
}

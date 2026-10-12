package com.signal.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.ui.LocalApp
import com.signal.ui.components.ListRow
import com.signal.ui.components.SText
import com.signal.ui.components.Wordmark
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch

/**
 * First run, with no sources yet (PLAN §4.3.1 → First run). Home and the nav bar don't open until
 * a source loads. "Connect to NAS" arrives with the companion in milestone 3.
 */
@Composable
fun SetupScreen() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val scope = rememberCoroutineScope()
    var addingSource by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    RemoteHandler { action ->
        // Back steps out of the form; on the first step it exits, like Back on Home.
        if (action != RemoteAction.Back) return@RemoteHandler false
        if (addingSource) addingSource = false else app.platform.exitApp()
        true
    }

    Column(Modifier.fillMaxSize().padding(SignalSizes.EDGE_MARGIN.dp)) {
        Wordmark()
        SText(if (addingSource) "Add a source" else "Welcome to Signal", 64, Modifier.padding(top = 64.dp), weight = FontWeight.ExtraBold)
        if (addingSource) {
            SText("Enter the M3U link or the Xtream Codes login from your provider.", 26, Modifier.padding(top = 8.dp, bottom = 32.dp), color = c.muted)
            SourceForm(existing = null, onDone = { app.navigator.resetTo(Route.Home) }, onCancel = { addingSource = false })
            return@Column
        }
        SText("Signal plays the channels from your own IPTV provider. Start by adding one.", 26, Modifier.padding(top = 8.dp, bottom = 48.dp), color = c.muted)
        Column(Modifier.width(900.dp)) {
            ListRow("Add a source", detail = "M3U or Xtream Codes", onSelect = { addingSource = true }, focusRequester = first)
            ListRow("Connect to NAS", detail = "Arrives with the NAS companion", enabled = false, onSelect = null)
            if (app.platform.appInfo.isDebug) {
                ListRow("Use the Demo source", detail = "Debug builds only", onSelect = {
                    status = "Loading the Demo source…"
                    scope.launch {
                        app.addDemoSource()
                            .onSuccess { app.navigator.resetTo(Route.Home) }
                            .onFailure { status = "Couldn't load it: ${it.message}" }
                    }
                })
            }
        }
        status?.let { SText(it, 24, Modifier.padding(top = 32.dp), color = c.secondaryText) }
    }
    LaunchedEffect(addingSource) { if (!addingSource) first.requestFocusWhenReady() }
}

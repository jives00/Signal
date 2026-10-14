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

private enum class SetupStep { CHOOSE, NAS, PAIRED, SOURCE }

/** After pairing: the first sync running, sources arrived and loading, none on the NAS, or no answer. */
private enum class FirstSync { RUNNING, LOADING, EMPTY, FAILED }

/**
 * First run, with no sources yet (PLAN §4.3.1 → First run). Home and the nav bar don't open until
 * a source loads. Connect to NAS pairs the device and downloads the NAS's settings; a source is
 * added here only when the NAS has none.
 */
@Composable
fun SetupScreen() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SetupStep.CHOOSE) }
    var pairedAs by remember { mutableStateOf("") }
    var firstSync by remember { mutableStateOf(FirstSync.RUNNING) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    RemoteHandler { action ->
        // Back steps out of a step; on the first step it exits, like Back on Home.
        if (action != RemoteAction.Back) return@RemoteHandler false
        when (step) {
            SetupStep.CHOOSE -> app.platform.exitApp()
            SetupStep.SOURCE -> step = if (app.nas.isPaired) SetupStep.PAIRED else SetupStep.CHOOSE
            else -> step = SetupStep.CHOOSE
        }
        true
    }

    Column(Modifier.fillMaxSize().padding(SignalSizes.EDGE_MARGIN.dp)) {
        Wordmark()
        val title = when (step) {
            SetupStep.CHOOSE -> "Welcome to Signal"
            SetupStep.NAS -> "Connect to NAS"
            SetupStep.PAIRED -> "Paired with the NAS"
            SetupStep.SOURCE -> "Add a source"
        }
        SText(title, 64, Modifier.padding(top = 64.dp), weight = FontWeight.ExtraBold)
        when (step) {
            SetupStep.SOURCE -> {
                SText("Enter the M3U link or the Xtream Codes login from your provider.", 26, Modifier.padding(top = 8.dp, bottom = 32.dp), color = c.muted)
                SourceForm(existing = null, onDone = { app.navigator.resetTo(Route.Home) }, onCancel = { step = SetupStep.CHOOSE })
            }
            SetupStep.NAS -> {
                SText("Pair this TV with Signal's NAS companion.", 26, Modifier.padding(top = 8.dp, bottom = 32.dp), color = c.muted)
                NasForm(pair = true, onPaired = {
                    pairedAs = it
                    step = SetupStep.PAIRED
                    firstSync = FirstSync.RUNNING
                    // First run on a paired device: the NAS's settings download, and Home opens once a source loads (PLAN §4.3.1).
                    // On the app's scope: Home replaces this screen mid-sync, and the sync must carry on.
                    app.scope.launch {
                        firstSync = when {
                            !app.syncNow() -> FirstSync.FAILED
                            // Connecting starts a sync too, and whichever runs first brings the sources and
                            // loads them; the other finds nothing new. So ask the database, not this sync.
                            app.sources.count() > 0 -> FirstSync.LOADING
                            else -> FirstSync.EMPTY
                        }
                    }
                }, onCancel = { step = SetupStep.CHOOSE })
            }
            SetupStep.PAIRED -> {
                val text = when (firstSync) {
                    FirstSync.RUNNING -> "This TV is paired as $pairedAs. Getting your settings from the NAS…"
                    FirstSync.LOADING -> "This TV is paired as $pairedAs. Loading your channels; Home opens when they're ready."
                    FirstSync.EMPTY -> "This TV is paired as $pairedAs. The NAS has no sources yet: add one here, and your other TVs get it too."
                    FirstSync.FAILED -> "This TV is paired as $pairedAs, but the NAS didn't answer. Add a source here; it syncs once the NAS is back."
                }
                SText(text, 26, Modifier.padding(top = 8.dp, bottom = 48.dp), color = c.muted, maxLines = 2)
                Column(Modifier.width(900.dp)) {
                    ListRow("Add a source", detail = "M3U or Xtream Codes", onSelect = { step = SetupStep.SOURCE }, focusRequester = first)
                }
            }
            SetupStep.CHOOSE -> {
                SText("Signal plays the channels from your own IPTV provider.", 26, Modifier.padding(top = 8.dp, bottom = 48.dp), color = c.muted)
                Column(Modifier.width(900.dp)) {
                    ListRow("Connect to NAS", detail = "Recommended", onSelect = { step = SetupStep.NAS }, focusRequester = first)
                    ListRow("Add a source", detail = "M3U or Xtream Codes", onSelect = { step = SetupStep.SOURCE })
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
        }
    }
    LaunchedEffect(step) { if (step == SetupStep.CHOOSE || step == SetupStep.PAIRED) first.requestFocusWhenReady() }
}

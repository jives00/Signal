package com.signal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.signal.nas.NasConnection.Pairing
import com.signal.ui.LocalApp
import com.signal.ui.components.SButton
import com.signal.ui.components.SField
import com.signal.ui.components.SText
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch

/**
 * The companion's two addresses and, when [pair] is set, pairing (PLAN §4.3.1 → First run, §4.3.15
 * → NAS & sync). Shared by the setup screen and Settings. With [pair] off it only saves the
 * addresses and re-checks the connection.
 */
@Composable
fun NasForm(pair: Boolean, onPaired: (deviceName: String) -> Unit, onCancel: () -> Unit) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val scope = rememberCoroutineScope()
    var home by remember { mutableStateOf(app.nas.homeAddress) }
    var tailscale by remember { mutableStateOf(app.nas.tailscaleAddress) }
    // Bumped by Connect: each value starts a fresh pairing, and leaving the form stops it.
    var attempt by remember { mutableStateOf(0) }
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val connect = remember { FocusRequester() }

    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        app.nas.pair().collect {
            pairing = it
            if (it is Pairing.Paired) onPaired(it.deviceName)
        }
    }

    Column(Modifier.width(1100.dp).verticalScroll(rememberScrollState())) {
        SField("Home network address", home, { home = it }, keyboard = KeyboardType.Uri, focusRequester = first)
        SField("Tailscale address", tailscale, { tailscale = it }, keyboard = KeyboardType.Uri, down = connect)
        SText("Signal uses whichever answers, so it works at home with Tailscale off and away from home over Tailscale.", 22, Modifier.padding(top = 4.dp), color = c.muted, maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val label = when {
                !pair -> "Save"
                pairing is Pairing.ShowCode || pairing == Pairing.Searching -> "New code"
                else -> "Connect"
            }
            SButton(label, focusRequester = connect, onSelect = {
                app.nas.setAddresses(home, tailscale)
                if (pair) {
                    attempt++
                } else {
                    status = "Checking…"
                    scope.launch {
                        app.nas.check()
                        status = null
                        onCancel()
                    }
                }
            })
            SButton("Cancel", onSelect = onCancel)
        }
        status?.let { SText(it, 24, Modifier.padding(top = 24.dp), color = c.secondaryText) }
        when (val p = pairing) {
            null, is Pairing.Paired -> Unit
            Pairing.Searching -> SText("Looking for the NAS…", 26, Modifier.padding(top = 32.dp), color = c.secondaryText)
            is Pairing.Failed -> SText(p.message, 26, Modifier.padding(top = 32.dp), color = c.secondaryText, maxLines = 3)
            is Pairing.ShowCode -> Column(Modifier.padding(top = 32.dp)) {
                SText("Found the NAS over the ${p.via.label}. To approve this TV, open the web admin and enter this code under Devices & profiles:", 26, color = c.secondaryText, maxLines = 2)
                SText(p.code.chunked(3).joinToString(" "), 120, Modifier.padding(vertical = 16.dp), weight = FontWeight.ExtraBold)
                SText("${p.address}  ·  waiting for approval…", 24, color = c.muted)
            }
        }
    }
}

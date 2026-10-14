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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.signal.data.newId
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.ui.LocalApp
import com.signal.ui.components.SButton
import com.signal.ui.components.SField
import com.signal.ui.components.SText
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch

/**
 * Add or edit a source (PLAN §4.3.15 → Sources): M3U URL, or Xtream server, username and password,
 * plus a display name. Test connection checks it without saving. Shared by first run and Settings.
 */
@Composable
fun SourceForm(existing: Source?, onDone: () -> Unit, onCancel: () -> Unit) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(existing?.kind?.takeIf { it != SourceKind.DEMO } ?: SourceKind.XTREAM) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var userAgent by remember { mutableStateOf(existing?.userAgent ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val save = remember { FocusRequester() }

    fun build() = Source(
        id = existing?.id ?: newId(),
        name = name.trim().ifEmpty { if (kind == SourceKind.XTREAM) "Xtream" else "M3U" },
        kind = kind,
        url = url.trim(),
        username = if (kind == SourceKind.XTREAM) username.trim() else "",
        password = if (kind == SourceKind.XTREAM) password else "",
        userAgent = userAgent.trim().ifEmpty { null },
        referrer = existing?.referrer,
        enabled = existing?.enabled ?: true,
        liveFormat = existing?.liveFormat ?: com.signal.model.LiveFormat.TS,
        connectionLimit = existing?.connectionLimit ?: 2,
    )

    LaunchedEffect(Unit) { first.requestFocusWhenReady() }

    Column(Modifier.width(1100.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SButton("Xtream Codes", onSelect = { kind = SourceKind.XTREAM }, selected = kind == SourceKind.XTREAM, focusRequester = first)
            SButton("M3U link", onSelect = { kind = SourceKind.M3U }, selected = kind == SourceKind.M3U)
        }
        SField("Name", name, { name = it }, Modifier.padding(top = 16.dp))
        if (kind == SourceKind.XTREAM) {
            SField("Server URL (for example http://provider.example:8080)", url, { url = it }, keyboard = KeyboardType.Uri)
            SField("Username", username, { username = it })
            SField("Password", password, { password = it }, password = true)
        } else {
            SField("M3U URL", url, { url = it }, keyboard = KeyboardType.Uri)
        }
        // Down from the last field goes to Save, the likely next step, not the nearest button (Cancel).
        SField("User-agent (optional)", userAgent, { userAgent = it }, down = save)
        status?.let { SText(it, 24, Modifier.padding(top = 16.dp), color = c.secondaryText, maxLines = 3) }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SButton(if (busy) "Working…" else "Save", focusRequester = save, onSelect = {
                if (busy || url.isBlank()) { status = "Enter the URL first."; return@SButton }
                busy = true
                status = "Loading channels…"
                scope.launch {
                    val source = build()
                    val result = if (existing == null) app.addSource(source) else { app.sources.save(source); app.refresh(source.id) }
                    busy = false
                    result.onSuccess { onDone() }.onFailure { status = "Couldn't load the source: ${it.message}" }
                }
            })
            SButton("Test connection", onSelect = {
                if (busy) return@SButton
                busy = true
                status = "Testing…"
                scope.launch {
                    status = app.sources.test(build()).fold({ "Connected: $it" }, { "Failed: ${it.message}" })
                    busy = false
                }
            })
            SButton("Cancel", onSelect = onCancel)
        }
    }
}

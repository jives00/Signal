package com.signal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.signal.app.AppController
import com.signal.app.RecordingText
import com.signal.app.Route
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordings
import com.signal.ui.LocalApp
import com.signal.ui.components.ListRow
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SButton
import com.signal.ui.components.SText
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** How often the page looks again, for what's recording now. */
private const val REFRESH_MS = 15_000L

/** Recordings, in the order they matter: what's on now, what's coming, then what's saved, newest first. */
internal fun recordingSections(all: List<NasRecording>): List<Pair<String, List<NasRecording>>> = listOfNotNull(
    all.filter { it.running }.sortedBy { it.at }.takeIf { it.isNotEmpty() }?.let { "Recording now" to it },
    all.filter { it.upcoming }.sortedBy { it.at }.takeIf { it.isNotEmpty() }?.let { "Scheduled" to it },
    all.filter { !it.running && !it.upcoming }.sortedByDescending { it.at }.takeIf { it.isNotEmpty() }?.let { "Recorded" to it },
)

private fun Long.ago(now: Long, app: AppController): String {
    val h24 = use24(app)
    return "${dayLabel(this, now)} ${localTime(this, h24)}"
}

/**
 * The TV's recordings (dvr.md → TV), which live on the NAS. A paired TV that can't reach the
 * companion keeps this page and says so, rather than hiding it or showing an empty list.
 */
@Composable
fun RecordingsPage() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val chrome = remember { PageChrome() }
    val scope = rememberCoroutineScope()
    // Null until the first answer; a null answer after that means the companion is out of reach.
    var loaded by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<NasRecordings?>(null) }
    val first = chrome.contentFocus

    suspend fun load() {
        view = app.nas.recordings()
        loaded = true
    }

    fun play(r: NasRecording, fromStart: Boolean) {
        val target = app.nas.recordingPlayback(r.id) ?: return
        app.vodPlayback.playRecording(r.id, r.title, r.channelName, target.first, target.second, if (fromStart) 0 else r.resumeMs)
        app.navigator.go(Route.VodPlayer)
    }

    fun menu(r: NasRecording, returnTo: FocusRequester) {
        val actions = buildList {
            if (r.playable && r.resumeMs > 0) add(PanelAction("Play from the start") { play(r, fromStart = true); null })
            if (r.expiresAt != null) add(PanelAction("Keep 30 more days") { scope.launch { app.nas.keepRecording(r.id); load() }; null })
            if (!r.running) {
                val label = if (r.upcoming) "Cancel recording" else "Delete"
                add(PanelAction(label) {
                    PanelPage.Confirm(label, r.title, label) {
                        scope.launch {
                            app.nas.deleteRecording(r.id)
                            load()
                            // The focused row went: put focus back on the list.
                            first.requestFocusWhenReady()
                        }
                    }
                })
            }
        }
        if (actions.isNotEmpty()) app.panel.open(PanelPage.Actions(r.title, actions), returnTo)
    }

    LaunchedEffect(Unit) {
        while (true) {
            load()
            delay(REFRESH_MS)
        }
    }

    TopLevelPage(Route.Recordings, chrome) {
        val current = view
        val sections = recordingSections(current?.recordings.orEmpty())
        Box(Modifier.fillMaxSize().padding(horizontal = SignalSizes.EDGE_MARGIN.dp)) {
            when {
                !loaded -> {}
                current == null -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SText(RecordingText.OUT_OF_REACH, 28, maxLines = 3)
                    SButton("Try again", { scope.launch { load() } }, focusRequester = first)
                }
                sections.isEmpty() -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SText(current.problem ?: "No recordings yet.", 28, maxLines = 3)
                    SButton("Refresh", { scope.launch { load() } }, focusRequester = first)
                }
                else -> {
                    val now = Clock.System.now().toEpochMilliseconds()
                    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sections.forEachIndexed { sectionIndex, (title, rows) ->
                            item(key = title) { SText(title, 24, Modifier.padding(top = 24.dp, bottom = 8.dp), weight = FontWeight.SemiBold, color = c.muted) }
                            items(rows, key = { it.id }) { r ->
                                val row = if (sectionIndex == 0 && r === rows.first()) first else remember { FocusRequester() }
                                ListRow(
                                    r.title,
                                    detail = "${r.channelName} · ${r.at.ago(now, app)} · ${RecordingText.status(r, now)}",
                                    focusRequester = row,
                                    onSelect = { if (r.playable) play(r, fromStart = false) else menu(r, row) },
                                    onOptions = { menu(r, row) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

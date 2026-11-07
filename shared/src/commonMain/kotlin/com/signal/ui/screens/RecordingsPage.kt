package com.signal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
private const val HOME_ROW = 12

/** Recordings, in the order they matter: what's on now, what's coming, then what's saved, newest first. */
internal fun recordingSections(all: List<NasRecording>): List<Pair<String, List<NasRecording>>> = listOfNotNull(
    all.filter { it.running }.sortedBy { it.at }.takeIf { it.isNotEmpty() }?.let { "Recording now" to it },
    all.filter { it.upcoming }.sortedBy { it.at }.takeIf { it.isNotEmpty() }?.let { "Scheduled" to it },
    all.filter { !it.running && !it.upcoming }.sortedByDescending { it.at }.takeIf { it.isNotEmpty() }?.let { "Recorded" to it },
)

/** Home's row: what's on, what's coming, then what's still to watch, newest first. Watched ones stay on the page. */
internal fun homeRecordings(all: List<NasRecording>): List<NasRecording> = (
    all.filter { it.running }.sortedBy { it.at } +
        all.filter { it.upcoming }.sortedBy { it.at } +
        all.filter { it.playable && !it.running && it.watchedAt == null }.sortedByDescending { it.at }
    ).take(HOME_ROW)

/** Plays a recording, from where it left off unless [fromStart]. False when no address has answered. */
internal fun playRecording(app: AppController, r: NasRecording, fromStart: Boolean = false): Boolean {
    val target = app.nas.recordingPlayback(r.id) ?: return false
    app.vodPlayback.playRecording(r.id, r.title, r.channelName, target.first, target.second, if (fromStart) 0 else r.resumeMs)
    app.navigator.go(Route.VodPlayer)
    return true
}

/** Where [id] sits in the list, counting each section's title; the top if it's gone. */
internal fun recordingListIndex(sections: List<Pair<String, List<NasRecording>>>, id: String?): Int {
    var index = 0
    sections.forEach { (_, rows) ->
        index++
        val at = rows.indexOfFirst { it.id == id }
        if (at >= 0) return index + at
        index += rows.size
    }
    return 0
}

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
    val list = rememberLazyListState()

    /** The row focus belongs on: the one played last, or else the top one. */
    fun focusRow(rows: List<NasRecording>): String? =
        (rows.firstOrNull { it.id == app.vodPlayback.lastRecordingId } ?: rows.firstOrNull())?.id

    suspend fun load() {
        view = app.recordings.refresh()
        loaded = true
    }

    /**
     * The rows arrive after the page has asked for focus, so ask again once they're there: two frames
     * on, for the list to lay out, or a row just deleted is still there to take it with it.
     */
    suspend fun focusList() {
        repeat(2) { withFrameNanos { } }
        if (chrome.navHasFocus) return
        val sections = recordingSections(view?.recordings.orEmpty())
        val index = recordingListIndex(sections, focusRow(sections.flatMap { it.second }))
        // One up, so a section's first row shows its title.
        list.requestScrollToItem((index - 1).coerceAtLeast(0))
        first.requestFocusWhenReady()
    }

    fun menu(r: NasRecording, returnTo: FocusRequester) {
        val actions = buildList {
            if (r.playable && r.resumeMs > 0) add(PanelAction("Play from the start") { playRecording(app, r, fromStart = true); null })
            if (r.expiresAt != null) add(PanelAction("Keep 30 more days") { scope.launch { app.nas.keepRecording(r.id); load() }; null })
            if (r.running || r.upcoming) add(PanelAction(RecordingText.KEEP_LONGER) { scope.launch { app.recordings.keepLonger(r); load() }; null })
            if (r.running) {
                add(PanelAction("Stop recording") {
                    PanelPage.Confirm("Stop recording", r.title, "Stop recording") { scope.launch { app.recordings.stop(r); load() } }
                })
            } else {
                val label = if (r.upcoming) "Cancel recording" else "Delete"
                add(PanelAction(label) {
                    PanelPage.Confirm(label, "${r.title} · ${r.at.ago(Clock.System.now().toEpochMilliseconds(), app)}", label) {
                        scope.launch {
                            app.nas.deleteRecording(r.id)
                            load()
                            // The focused row went: put focus back on the list.
                            focusList()
                        }
                    }
                })
            }
        }
        if (actions.isNotEmpty()) app.panel.open(PanelPage.Actions(r.title, actions), returnTo)
    }

    LaunchedEffect(Unit) {
        load()
        focusList()
        while (true) {
            delay(REFRESH_MS)
            load()
        }
    }

    TopLevelPage(Route.Recordings, chrome) {
        val current = view
        val sections = recordingSections(current?.recordings.orEmpty())
        val focused = focusRow(sections.flatMap { it.second })
        Box(Modifier.fillMaxSize().padding(horizontal = SignalSizes.EDGE_MARGIN.dp)) {
            when {
                !loaded -> {}
                current == null -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SText(RecordingText.OUT_OF_REACH, 28, weight = FontWeight.Bold, color = c.warning, maxLines = 3)
                    SButton("Try again", { scope.launch { load(); focusList() } }, focusRequester = first)
                }
                sections.isEmpty() -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SText(current.problem ?: "No recordings yet.", 28, maxLines = 3)
                    SButton("Refresh", { scope.launch { load(); focusList() } }, focusRequester = first)
                }
                else -> {
                    val now = Clock.System.now().toEpochMilliseconds()
                    LazyColumn(Modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sections.forEach { (title, rows) ->
                            item(key = title) { SText(title, 24, Modifier.padding(top = 24.dp, bottom = 8.dp), weight = FontWeight.SemiBold, color = c.muted) }
                            items(rows, key = { it.id }) { r ->
                                val own = remember { FocusRequester() }
                                val row = if (r.id == focused) first else own
                                ListRow(
                                    r.title,
                                    detail = "${r.channelName} · ${r.at.ago(now, app)} · ${RecordingText.status(r, now, r.id in current?.conflicts.orEmpty())}",
                                    focusRequester = row,
                                    onSelect = { if (r.playable) playRecording(app, r) else menu(r, row) },
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

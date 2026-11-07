package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.RecordingText
import com.signal.app.Route
import com.signal.app.findRecording
import com.signal.app.recordNote
import com.signal.model.Channel
import com.signal.model.Program
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.ui.components.LogoTile
import com.signal.ui.components.SButton
import com.signal.ui.components.SText
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.panel.PanelPage
import com.signal.ui.components.labelOf
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Program details (PLAN §4.3.4): a side panel with everything the guide has about one program, in
 * full, and only the actions that apply.
 */
private const val RECORDING_LOOK_MS = 15_000L
private const val RECORD_SETTLE_MS = 1_500L

internal fun programDetails(app: AppController, ch: Channel, p: Program, now: Long, watching: Boolean = false): PanelPage =
    PanelPage.Custom(p.title) { ProgramDetailsContent(app, ch, p, now, watching) }

@Composable
private fun ProgramDetailsContent(app: AppController, ch: Channel, p: Program, now: Long, watching: Boolean) {
    val c = SignalTheme.colors
    val first = remember { FocusRequester() }
    val onNow = p.isOn(now)
    val reminders by app.reminderList.collectAsState()
    // Looked up fresh: setting the first reminder creates the user channel this [ch] predates.
    val userChannelId by produceState(ch.userChannelId, reminders) { value = app.channels.userChannelIdFor(ch) }
    val reminder = app.reminders.find(reminders, userChannelId, p)
    // Find in Movies / Find in Series (PLAN §4.3.4), looked up once the panel is open.
    val find by produceState<VodFind?>(null, p.id) { value = findInVod(app, p) }
    val set by app.recordings.list.collectAsState()
    val recording = findRecording(set, ch.stream.id, p)
    // What came of the last press: the panel stays open, so it's said here.
    var recordNote by remember(p.id) { mutableStateOf<String?>(null) }
    // A recording set for now starts within seconds, and Cancel becomes Stop.
    val watchingId = recording?.id
    LaunchedEffect(watchingId) {
        while (watchingId != null) {
            delay(RECORDING_LOOK_MS)
            runCatching { app.recordings.refresh() }
        }
    }
    // One at a time: the NAS can take seconds, and a press has to show at once or it gets pressed again.
    var recordBusy by remember(p.id) { mutableStateOf(false) }
    fun recordAction(working: String, action: suspend () -> String?) {
        if (recordBusy) return
        recordBusy = true
        recordNote = working
        app.scope.launch {
            try {
                recordNote = action()
                // The button under the finger has just changed: a press meant for Record mustn't land on Cancel.
                delay(RECORD_SETTLE_MS)
            } finally {
                recordBusy = false
            }
        }
    }
    val actions = buildList<Pair<String, () -> Unit>> {
        if (onNow && !watching) add("Watch" to {
            app.panel.close()
            val group = groupFor(ch)
            app.playback.tune(ch, fullScreen = true, group = group)
            app.navigator.go(Route.FullScreen(group))
        })
        // Only with a companion, which does the recording; one out of reach keeps the button and says so (dvr.md).
        if (app.nas.isPaired && p.stop > now) {
            when {
                recording == null -> add((if (onNow) "Record from now" else "Record") to { recordAction(RecordingText.SETTING) { recordNote(app.recordings.record(ch, p)) } })
                recording.running -> add("Stop recording" to {
                    app.panel.push(PanelPage.Confirm("Stop recording", p.title, "Stop recording") {
                        app.scope.launch { app.recordings.stop(recording) }
                    })
                })
                else -> add("Cancel recording" to {
                    recordAction(RecordingText.WORKING) { if (app.recordings.cancel(recording)) null else app.recordings.failure() }
                })
            }
            if (recording != null) add(RecordingText.KEEP_LONGER to {
                recordAction(RecordingText.WORKING) { if (app.recordings.keepLonger(recording)) RecordingText.KEPT_LONGER else app.recordings.failure() }
            })
        }
        // Remind me / Remove reminder, only for a future program (PLAN §4.3.4).
        if (p.start > now) {
            if (reminder != null) add("Remove reminder" to { app.scope.launch { app.reminders.remove(reminder.id) } })
            else add("Remind me" to { app.scope.launch { app.reminders.add(ch, p) } })
        }
        find?.let { f -> add(f.label to { openFind(app, p, f) }) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
        item {
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoTile(ch.displayName, ch.logoUrl, 80, 48)
                SText(labelOf(ch), 24, Modifier.padding(start = 20.dp), weight = FontWeight.SemiBold, color = c.accentText, maxLines = 2)
            }
            listOfNotNull(p.episodeText, p.subTitle).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                SText(it, 26, Modifier.padding(top = 20.dp), weight = FontWeight.SemiBold, color = c.secondaryText, maxLines = 3)
            }
            SText("${programTime(app, p, now)} · ${p.durationMs / 60_000} min", 24, Modifier.padding(top = 12.dp), color = c.muted)
            if (onNow) {
                val left = (p.stop - now) / 60_000
                SText("$left min left", 22, Modifier.padding(top = 4.dp), color = c.muted)
                val progress = ((now - p.start).toFloat() / p.durationMs).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).background(c.neutral300)) {
                    Box(Modifier.fillMaxWidth(progress).height(6.dp).background(c.accent))
                }
            }
            ProgramTags(p, Modifier.padding(top = 16.dp))
        }
        if (actions.isNotEmpty()) item {
            // Stacked, not a row: the panel moves with Up/Down, and keeps Left/Right from the screen beneath.
            Column(Modifier.padding(top = 32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                actions.forEachIndexed { i, (label, action) ->
                    SButton(label, onSelect = action, modifier = Modifier.fillMaxWidth(), focusRequester = if (i == 0) first else null, fillWidth = true)
                }
            }
            // A platform limit, said plainly (PLAN §4.3.4): Android TV can't show reminders while the app is closed.
            if (reminder != null) SText("You'll be reminded while the app is open.", 22, Modifier.padding(top = 16.dp), color = c.muted)
            recordNote?.let {
                // Being out of reach is something the user has to fix, so it stands out.
                val outOfReach = it == RecordingText.OUT_OF_REACH
                SText(
                    it, 22, Modifier.padding(top = 16.dp),
                    weight = if (outOfReach) FontWeight.Bold else FontWeight.Normal,
                    color = if (outOfReach) c.warning else c.text, maxLines = 3,
                )
            }
        }
        item {
            // The full description, never truncated (PLAN §4.3.4).
            SText(p.description ?: "No description.", 24, Modifier.padding(top = 32.dp), color = c.secondaryText, maxLines = 40)
            if (p.credits.isNotEmpty()) SText(p.credits.joinToString(", "), 22, Modifier.padding(top = 16.dp), color = c.muted, maxLines = 6)
            Spacer(Modifier.height(48.dp))
        }
    }
    LaunchedEffect(Unit) { if (actions.isNotEmpty()) first.requestFocusWhenReady() }
}

/** What Find in Movies/Series found for a program: the confident match, or none for a program of a known kind. */
internal data class VodFind(val kind: VodKind, val item: VodItem?) {
    val label: String get() = when {
        item == null -> "Search Movies & Series"
        kind == VodKind.MOVIE -> "Watch in Movies"
        else -> "Watch in Series"
    }
}

/** A movie from the guide's categories, an episode from its numbering; null when the guide doesn't say. */
internal fun programKind(p: Program): VodKind? = when {
    p.categories.any { c -> c.contains("movie", ignoreCase = true) || c.contains("film", ignoreCase = true) } -> VodKind.MOVIE
    p.season != null || p.episode != null || p.episodeLabel != null -> VodKind.SERIES
    else -> null
}

/**
 * Find in Movies/Series (PLAN §4.3.4): only when a source has VOD, and the program is a movie or an
 * episode, or simply has a match. The type comes from the guide when it says, else from finding one.
 */
internal suspend fun findInVod(app: AppController, p: Program): VodFind? {
    val counts = mapOf(VodKind.MOVIE to app.movieCount.value, VodKind.SERIES to app.seriesCount.value)
    if (counts.values.all { it == 0L }) return null
    val kind = programKind(p)
    for (k in kind?.let { listOf(it) } ?: listOf(VodKind.MOVIE, VodKind.SERIES)) {
        if ((counts[k] ?: 0L) == 0L) continue
        app.vod.find(p.title, if (k == VodKind.MOVIE) p.year else null, k)?.let { return VodFind(k, it) }
    }
    return kind?.let { VodFind(it, null) }
}

/** A match opens its page, a series at the program's episode; no match opens Search on Movies and Series. */
private fun openFind(app: AppController, p: Program, f: VodFind) {
    app.panel.close()
    val item = f.item
    if (item == null) {
        app.searchRequest.value = AppController.SearchRequest(p.title, setOf("MOVIES", "SERIES"))
        app.navigator.go(Route.Search)
    } else if (item.kind == VodKind.MOVIE) {
        app.navigator.go(Route.MovieDetail(item.id))
    } else {
        app.navigator.go(Route.SeriesDetail(item.id, p.season, p.episode))
    }
}

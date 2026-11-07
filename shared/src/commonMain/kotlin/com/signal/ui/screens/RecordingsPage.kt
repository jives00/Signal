package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.RecordingText
import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordings
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.ImageOrPlaceholder
import com.signal.ui.components.LogoTile
import com.signal.ui.components.PageChrome
import com.signal.ui.components.ProgressBar
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
private const val PANE_WIDTH = 600

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/** Home's row: what's on, what's coming, then what's still to watch, newest first. Watched ones stay on the page. */
internal fun homeRecordings(all: List<NasRecording>): List<NasRecording> = (
    all.filter { it.running }.sortedBy { it.at } +
        all.filter { it.upcoming }.sortedBy { it.at } +
        all.filter { it.playable && !it.running && it.watchedAt == null }.sortedByDescending { it.at }
    ).take(HOME_ROW)

/** Plays a recording, from where it left off unless [fromStart]. False when no address has answered. */
internal fun playRecording(app: AppController, r: NasRecording, fromStart: Boolean = false): Boolean {
    val target = app.nas.recordingPlayback(r.id) ?: return false
    app.recordings.lastFocus = r.id
    app.vodPlayback.playRecording(r.id, r.title, r.channelName, target.first, target.second, if (fromStart) 0 else r.resumeMs)
    app.navigator.go(Route.VodPlayer)
    return true
}

private fun Long.ago(now: Long, app: AppController): String = "${dayLabel(this, now)} ${localTime(this, use24(app))}"

private fun gigabytes(bytes: Long): String {
    val gb = bytes / (1L shl 30).toDouble()
    return if (gb >= 1000) "${((gb / 1024) * 10).toLong() / 10.0} TB" else if (gb >= 10) "${gb.toLong()} GB" else "${(gb * 10).toLong() / 10.0} GB"
}

/** The episode line: "S3 E12 · The One Where…", from the guide's details. */
private fun episodeLine(r: NasRecording): String? =
    listOfNotNull(r.details?.episodeText, r.details?.subTitle).joinToString(" · ").takeIf { it.isNotEmpty() }

/** A row's line under its title. */
private fun rowDetail(r: NasRecording, inShow: Boolean, now: Long, app: AppController): String {
    val h24 = use24(app)
    return when {
        r.running -> listOf("Until ${localTime(r.programEnd, h24)}", r.channelName).joinToString(" · ")
        r.upcoming -> "${localTime(r.at, h24)} – ${localTime(r.programEnd, h24)} · ${r.channelName}"
        r.failed -> listOfNotNull(r.at.ago(now, app), r.channelName, r.reason).joinToString(" · ")
        else -> listOfNotNull(
            episodeLine(r).takeIf { !inShow },
            r.at.ago(now, app),
            RecordingText.length(r.durationMs),
            r.channelName.takeIf { !inShow },
        ).joinToString(" · ")
    }
}

/** What a row says it is: the show, or inside an open show, the episode. */
private fun rowTitle(r: NasRecording, inShow: Boolean, now: Long, app: AppController): String =
    if (inShow) episodeLine(r) ?: r.at.ago(now, app) else r.title

private enum class BadgeKind { RECORDING, WAITING, NEW, PARTIAL, FAILED, CONFLICT }

/** Marks in their own colors, never the accent, which is for focus (CLAUDE.md → Only focus uses the accent). */
@Composable
private fun Badge(kind: BadgeKind, focused: Boolean) {
    val c = SignalTheme.colors
    val (text, color) = when (kind) {
        BadgeKind.RECORDING -> "REC" to c.record
        BadgeKind.WAITING -> "WAITING" to c.warning
        BadgeKind.NEW -> "NEW" to c.tagText
        BadgeKind.PARTIAL -> "PARTIAL" to c.warning
        BadgeKind.FAILED -> "FAILED" to c.warning
        BadgeKind.CONFLICT -> "MAY MISS" to c.warning
    }
    val shown = if (focused) c.onAccent else color
    Row(
        Modifier.padding(start = 16.dp).let {
            if (kind == BadgeKind.NEW) it.background(if (focused) Color.White.copy(alpha = 0.16f) else c.tagBg) else it.border(2.dp, shown)
        }.padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == BadgeKind.RECORDING) {
            Box(Modifier.size(12.dp).background(shown, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        SText(text, 18, weight = FontWeight.ExtraBold, color = shown)
    }
}

private fun badgesOf(r: NasRecording, conflict: Boolean): List<BadgeKind> = listOfNotNull(
    BadgeKind.RECORDING.takeIf { r.state == "RECORDING" },
    BadgeKind.WAITING.takeIf { r.state == "WAITING" },
    BadgeKind.CONFLICT.takeIf { r.upcoming && conflict },
    BadgeKind.PARTIAL.takeIf { r.state == "PARTIAL" },
    BadgeKind.FAILED.takeIf { r.failed },
    BadgeKind.NEW.takeIf { r.isNew },
)

/** How far along its bar is: recorded so far while recording, else watched so far; null for none. */
private fun progressOf(r: NasRecording, now: Long): Float? = when {
    r.running -> ((now - r.startMs).toFloat() / (r.stopMs - r.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    r.resumeMs > 0 && r.durationMs > 0 -> (r.resumeMs.toFloat() / r.durationMs).coerceIn(0f, 1f)
    else -> null
}

/**
 * The TV's recordings (dvr.md → TV), which live on the NAS: a list on the left, grouped by show and
 * filtered, and the focused line's details and buttons on the right. A paired TV that can't reach
 * the companion keeps this page and says so, rather than hiding it or showing an empty list.
 */
@Composable
fun RecordingsPage() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val chrome = remember { PageChrome() }
    val scope = rememberCoroutineScope()
    val state = app.recordings
    // Null until the first answer; a null answer after that means the companion is out of reach.
    var loaded by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<NasRecordings?>(null) }
    var now by remember { mutableLongStateOf(nowMs()) }
    var filter by remember { mutableStateOf(RecordingsFilter.parse(app.settings.raw(Settings.Key.RECORDINGS_FILTER))) }
    var openShow by remember { mutableStateOf(state.openShow) }
    var failedOpen by remember { mutableStateOf(state.failedOpen) }
    // The line focus belongs on. State, so the line it names takes up rowFocus before focus is asked for.
    var focusKey by remember { mutableStateOf(state.lastFocus) }
    val list = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    val paneFirst = remember { FocusRequester() }
    // All is where TopLevelPage puts focus back; the rest have their own.
    val filterFocus = remember { RecordingsFilter.entries.associateWith { if (it == RecordingsFilter.ALL) chrome.contentFocus else FocusRequester() } }

    fun entries(): List<RecordingsEntry> = recordingEntries(view?.recordings.orEmpty(), filter, now, openShow, failedOpen)

    suspend fun load() {
        view = state.refresh()
        now = nowMs()
        loaded = true
    }

    /**
     * The rows arrive after the page has asked for focus, so ask again once they're there: two frames
     * on, for the list to lay out, or a row just deleted is still there to take it with it.
     */
    suspend fun focusList() {
        repeat(2) { withFrameNanos { } }
        if (chrome.navHasFocus) return
        val shown = entries()
        val index = entryIndex(shown, focusKey)
        if (index < 0) {
            chrome.contentFocus.requestFocusWhenReady()
            return
        }
        focusKey = shown[index].key
        state.lastFocus = focusKey
        repeat(2) { withFrameNanos { } }
        // One up, so a section's first row shows its heading.
        list.requestScrollToItem((index - 1).coerceAtLeast(0))
        rowFocus.requestFocusWhenReady()
    }

    fun setFilter(value: RecordingsFilter) {
        filter = value
        openShow = null
        state.openShow = null
        app.settings.put(Settings.Key.RECORDINGS_FILTER, value.name)
    }

    fun open(show: RecordingsEntry.Show) {
        openShow = show.showKey
        state.openShow = show.showKey
        focusKey = show.next.id
        scope.launch { focusList() }
    }

    fun closeShow() {
        val show = openShow ?: return
        openShow = null
        state.openShow = null
        focusKey = RecordingsEntry.showKey(show)
        scope.launch { focusList() }
    }

    /** Deletes, or cancels, then puts focus on the line next to it. */
    fun remove(r: NasRecording) {
        val neighbor = neighborKey(entries(), r.id)
        scope.launch {
            if (r.upcoming) state.cancel(r) else app.nas.deleteRecording(r.id)
            focusKey = neighbor
            load()
            // The focused row went: put focus back on the list.
            focusList()
        }
    }

    fun confirmRemove(r: NasRecording, returnTo: FocusRequester) {
        val label = if (r.upcoming) "Cancel recording" else "Delete"
        app.panel.open(PanelPage.Confirm(label, "${r.title} · ${r.at.ago(nowMs(), app)}", label) { remove(r) }, returnTo)
    }

    fun confirmStop(r: NasRecording, returnTo: FocusRequester) {
        app.panel.open(PanelPage.Confirm("Stop recording", r.title, "Stop recording") { scope.launch { state.stop(r); load() } }, returnTo)
    }

    fun keepLonger(r: NasRecording) {
        scope.launch {
            if (r.running || r.upcoming) state.keepLonger(r) else app.nas.keepRecording(r.id)
            load()
        }
    }

    /** Long press on a recording: everything that applies, as before the redesign. */
    fun menu(r: NasRecording, returnTo: FocusRequester) {
        val actions = buildList {
            if (r.playable && r.resumeMs > 0) add(PanelAction("Play from the start") { playRecording(app, r, fromStart = true); null })
            if (r.expiresAt != null) add(PanelAction("Keep 30 more days") { keepLonger(r); null })
            if (r.running || r.upcoming) add(PanelAction(RecordingText.KEEP_LONGER) { keepLonger(r); null })
            if (r.running) {
                add(PanelAction("Stop recording") { PanelPage.Confirm("Stop recording", r.title, "Stop recording") { scope.launch { state.stop(r); load() } } })
            } else {
                val label = if (r.upcoming) "Cancel recording" else "Delete"
                add(PanelAction(label) { PanelPage.Confirm(label, "${r.title} · ${r.at.ago(nowMs(), app)}", label) { remove(r) } })
            }
        }
        if (actions.isNotEmpty()) app.panel.open(PanelPage.Actions(r.title, actions), returnTo)
    }

    fun deleteWatched(show: RecordingsEntry.Show, returnTo: FocusRequester) {
        val watched = show.recordings.filter { it.watchedAt != null }
        if (watched.isEmpty()) return
        val message = "${show.title}: ${watched.size} watched ${if (watched.size == 1) "recording" else "recordings"}"
        app.panel.open(
            PanelPage.Confirm("Delete watched", message, "Delete") {
                val neighbor = if (watched.size == show.recordings.size) neighborKey(entries(), show.key) else show.key
                scope.launch {
                    watched.forEach { app.nas.deleteRecording(it.id) }
                    focusKey = neighbor
                    load()
                    focusList()
                }
            },
            returnTo,
        )
    }

    /** OK on a line. */
    fun select(e: RecordingsEntry, returnTo: FocusRequester) {
        when (e) {
            is RecordingsEntry.Heading -> {}
            is RecordingsEntry.Show -> open(e)
            is RecordingsEntry.Failed -> {
                failedOpen = !failedOpen
                state.failedOpen = failedOpen
            }
            is RecordingsEntry.One -> if (e.recording.playable) playRecording(app, e.recording) else menu(e.recording, returnTo)
        }
    }

    /** The detail pane's buttons for a line, stacked (PLAN §4.3.4). */
    fun buttons(e: RecordingsEntry?): List<Pair<String, () -> Unit>> = when (e) {
        is RecordingsEntry.One -> {
            val r = e.recording
            buildList {
                if (r.playable) add((if (r.resumeMs > 0) "Resume" else "Play") to { playRecording(app, r); Unit })
                if (r.playable && r.resumeMs > 0) add("Play from the start" to { playRecording(app, r, fromStart = true); Unit })
                if (r.running) add("Stop recording" to { confirmStop(r, paneFirst) })
                if (r.running || r.upcoming) add(RecordingText.KEEP_LONGER to { keepLonger(r) })
                if (r.expiresAt != null) add("Keep 30 more days" to { keepLonger(r) })
                if (!r.running) add((if (r.upcoming) "Cancel recording" else "Delete") to { confirmRemove(r, rowFocus) })
            }
        }
        is RecordingsEntry.Show -> buildList {
            val next = e.next
            add((if (next.resumeMs > 0) "Resume" else "Play") to { playRecording(app, next); Unit })
            add("See all ${e.recordings.size}" to { open(e) })
            val watched = e.recordings.count { it.watchedAt != null }
            if (watched > 0) add("Delete watched ($watched)" to { deleteWatched(e, rowFocus) })
        }
        else -> emptyList()
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
        Box(Modifier.fillMaxSize().padding(horizontal = SignalSizes.EDGE_MARGIN.dp)) {
            when {
                !loaded -> {}
                current == null -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SText(RecordingText.OUT_OF_REACH, 28, weight = FontWeight.Bold, color = c.warning, maxLines = 3)
                    SButton("Try again", { scope.launch { load(); focusList() } }, focusRequester = chrome.contentFocus)
                }
                current.recordings.isEmpty() -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SText(current.problem ?: "No recordings yet. Press and hold OK on a program in the guide to record it.", 28, maxLines = 3)
                    SButton("Refresh", { scope.launch { load(); focusList() } }, focusRequester = chrome.contentFocus)
                }
                else -> {
                    val shown = entries()
                    val selected = shown.firstOrNull { it.key == focusKey }
                    val paneButtons = buttons(selected)
                    Column(Modifier.fillMaxSize()) {
                        FilterRow(current, filter, filterFocus) { setFilter(it); scope.launch { focusList() } }
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (shown.isEmpty()) {
                                    SText(if (filter == RecordingsFilter.SCHEDULED) "Nothing scheduled." else "Nothing here.", 28, Modifier.padding(top = 24.dp), color = c.muted)
                                } else {
                                    LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        itemsIndexed(shown, key = { _, e -> e.key }) { index, e ->
                                            if (e is RecordingsEntry.Heading) {
                                                SText(e.text, 24, Modifier.padding(top = if (index == 0) 0.dp else 24.dp, bottom = 8.dp), weight = FontWeight.SemiBold, color = c.muted)
                                            } else {
                                                val own = remember { FocusRequester() }
                                                val row = if (e.key == focusKey) rowFocus else own
                                                EntryRow(
                                                    e, now, app, current.conflicts, row,
                                                    up = if (shown.take(index).all { it is RecordingsEntry.Heading }) filterFocus[filter] else null,
                                                    right = if (paneButtons.isNotEmpty() && e.key == focusKey) paneFirst else null,
                                                    onFocused = {
                                                        focusKey = e.key
                                                        state.lastFocus = e.key
                                                    },
                                                    onSelect = { select(e, row) },
                                                    onOptions = { (e as? RecordingsEntry.One)?.let { menu(it.recording, row) } },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(48.dp))
                            DetailPane(selected, now, app, current.conflicts, paneButtons, paneFirst, rowFocus, Modifier.width(PANE_WIDTH.dp).fillMaxHeight())
                        }
                    }
                }
            }
        }
        // Registered after TopLevelPage's handler, so it's asked first: Back leaves an open show before the nav bar.
        RemoteHandler { action ->
            if (action == RemoteAction.Back && openShow != null && !chrome.navHasFocus && !app.panel.isOpen) {
                closeShow()
                true
            } else {
                false
            }
        }
    }
}

/** The filters, the current one marked, and space used and free on the right. */
@Composable
private fun FilterRow(
    view: NasRecordings,
    filter: RecordingsFilter,
    focus: Map<RecordingsFilter, FocusRequester>,
    choose: (RecordingsFilter) -> Unit,
) {
    val c = SignalTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        RecordingsFilter.entries.forEach { f ->
            val count = filterCount(view.recordings, f)
            val label = if (f == RecordingsFilter.ALL || count == 0) f.label else "${f.label} ($count)"
            SButton(label, { choose(f) }, selected = f == filter, focusRequester = focus[f])
        }
        Spacer(Modifier.weight(1f))
        val used = view.recordings.sumOf { it.bytes }
        if (used > 0 || view.freeBytes > 0) {
            SText(listOfNotNull("${gigabytes(used)} recorded", view.freeBytes.takeIf { it > 0 }?.let { "${gigabytes(it)} free" }).joinToString(" · "), 22, color = c.muted)
        }
    }
}

@Composable
private fun EntryRow(
    e: RecordingsEntry, now: Long, app: AppController, conflicts: List<String>, focus: FocusRequester,
    up: FocusRequester?, right: FocusRequester?, onFocused: () -> Unit, onSelect: () -> Unit, onOptions: () -> Unit,
) {
    val c = SignalTheme.colors
    val (title, detail, logoOf) = when (e) {
        is RecordingsEntry.One -> Triple(rowTitle(e.recording, e.inShow, now, app), rowDetail(e.recording, e.inShow, now, app), e.recording)
        is RecordingsEntry.Show -> {
            val count = "${e.recordings.size} recordings"
            Triple(e.title, listOfNotNull(count, e.unwatched.takeIf { it > 0 }?.let { "$it new" }, e.newest.channelName).joinToString(" · "), e.newest)
        }
        is RecordingsEntry.Failed -> Triple("Failed (${e.recordings.size})", if (e.open) "Hide" else "Show", null)
        is RecordingsEntry.Heading -> return
    }
    Focusable(
        Modifier.fillMaxWidth()
            .semantics { contentDescription = "$title · $detail" }
            .focusProperties {
                if (up != null) this.up = up
                if (right != null) this.right = right
            },
        onSelect = onSelect, onOptions = onOptions, focusRequester = focus, onFocused = onFocused,
    ) { focused ->
        val text = if (focused) c.onAccent else c.text
        val muted = if (focused) c.onAccent else c.muted
        Row(
            Modifier.fillMaxWidth().background(if (focused) c.accent else Color.Transparent).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (logoOf != null) LogoTile(logoOf.channelName, logoOf.channelLogo, 96, 56, onAccent = focused)
            else Box(Modifier.size(96.dp, 56.dp))
            Column(Modifier.weight(1f).padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText(title, 28, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, color = text)
                    when (e) {
                        is RecordingsEntry.One -> badgesOf(e.recording, e.recording.id in conflicts).forEach { Badge(it, focused) }
                        is RecordingsEntry.Show -> if (e.unwatched > 0) Badge(BadgeKind.NEW, focused)
                        else -> {}
                    }
                }
                SText(detail, 22, color = muted)
                val progress = (e as? RecordingsEntry.One)?.recording?.let { progressOf(it, now) }
                if (progress != null) ProgressBar(progress, Modifier.fillMaxWidth().padding(top = 6.dp), onAccent = focused)
            }
        }
    }
}

/** The focused line in full: the guide's picture, episode, description and status, and its buttons, stacked. */
@Composable
private fun DetailPane(
    e: RecordingsEntry?, now: Long, app: AppController, conflicts: List<String>,
    buttons: List<Pair<String, () -> Unit>>, first: FocusRequester, back: FocusRequester, modifier: Modifier,
) {
    val c = SignalTheme.colors
    val r = when (e) {
        is RecordingsEntry.One -> e.recording
        is RecordingsEntry.Show -> e.next
        else -> null
    }
    Column(modifier.clipToBounds(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (r == null) {
            if (e is RecordingsEntry.Failed) {
                SText("Failed recordings", 34, weight = FontWeight.Bold)
                SText("Recordings that saved nothing, and why. They're removed when they expire.", 24, color = c.secondaryText, maxLines = 4)
            }
            return@Column
        }
        val d = r.details
        val picture = d?.icon
        if (picture != null) {
            Box(Modifier.width(PANE_WIDTH.dp).padding(bottom = 8.dp)) {
                ImageOrPlaceholder(picture, null, ContentScale.Crop, Modifier.size(PANE_WIDTH.dp, (PANE_WIDTH * 9 / 16).dp)) {
                    LogoTile(r.channelName, r.channelLogo, PANE_WIDTH, PANE_WIDTH * 9 / 16)
                }
            }
        } else {
            LogoTile(r.channelName, r.channelLogo, 160, 96, Modifier.padding(bottom = 8.dp))
        }
        SText(if (e is RecordingsEntry.Show) e.title else r.title, 34, weight = FontWeight.Bold, maxLines = 2)
        if (e is RecordingsEntry.Show) {
            SText(listOfNotNull("${e.recordings.size} recordings", e.unwatched.takeIf { it > 0 }?.let { "$it new" }).joinToString(" · "), 24, color = c.secondaryText)
            SText("Up next: ${episodeLine(r) ?: r.at.ago(now, app)}", 22, color = c.muted)
        } else {
            episodeLine(r)?.let { SText(it, 26, weight = FontWeight.SemiBold, color = c.secondaryText, maxLines = 2) }
            val h24 = use24(app)
            val time = if (r.upcoming || r.running) "${dayLabel(r.at, now)} ${localTime(r.at, h24)} – ${localTime(r.programEnd, h24)}" else r.at.ago(now, app)
            SText(listOfNotNull(r.channelName, time, RecordingText.length(r.durationMs).takeIf { r.playable && !r.running }).joinToString(" · "), 22, color = c.muted)
            statusLines(r, now, app, r.id in conflicts).forEach { (text, color) -> SText(text, 22, weight = FontWeight.SemiBold, color = color, maxLines = 3) }
        }
        val tags = listOfNotNull(
            d?.rating, d?.year?.toString(), d?.categories?.firstOrNull { it != "Sports" } ?: d?.categories?.firstOrNull(),
        )
        if (tags.isNotEmpty()) {
            Row {
                tags.forEach { t ->
                    SText(t, 20, Modifier.padding(end = 12.dp).border(2.dp, c.divider).padding(horizontal = 12.dp, vertical = 4.dp), weight = FontWeight.SemiBold, color = c.secondaryText)
                }
            }
        }
        if (buttons.isNotEmpty()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                buttons.forEachIndexed { i, (label, action) ->
                    SButton(
                        label, action,
                        Modifier.fillMaxWidth().focusProperties { left = back },
                        focusRequester = if (i == 0) first else null, fillWidth = true,
                    )
                }
            }
        }
        d?.description?.let { SText(it, 24, Modifier.padding(top = 12.dp), color = c.secondaryText, maxLines = 6) }
    }
}

/** The pane's status: what's wrong in the warning color on its own line, recording in the record red, the rest muted. */
@Composable
private fun statusLines(r: NasRecording, now: Long, app: AppController, conflict: Boolean): List<Pair<String, Color>> {
    val c = SignalTheme.colors
    val h24 = use24(app)
    return when {
        r.state == "RECORDING" -> listOf("● Recording until ${localTime(r.programEnd, h24)}" to c.record)
        r.state == "WAITING" -> listOf("Waiting for a free source" to c.warning)
        r.upcoming && conflict -> listOf(RecordingText.CONFLICT to c.warning)
        r.upcoming -> listOf((r.reason ?: "Scheduled") to c.muted)
        r.failed -> listOf((r.reason ?: "Failed") to c.warning)
        else -> listOfNotNull(
            ("Missing ${RecordingText.length(r.gapMs)} of the program" to c.warning).takeIf { r.state == "PARTIAL" },
            listOfNotNull(
                "Watched".takeIf { r.watchedAt != null },
                r.resumeMs.takeIf { it > 0 }?.let { "${RecordingText.length(r.durationMs - it)} left" },
                r.expiresAt?.let { RecordingText.expiry(it, now) },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { it to c.muted },
        )
    }
}

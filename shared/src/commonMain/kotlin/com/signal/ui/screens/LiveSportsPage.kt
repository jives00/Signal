package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.sports.EventChannel
import com.signal.sports.Sport
import com.signal.sports.SportEvent
import com.signal.sports.SportsFilters
import com.signal.sports.SportsList
import com.signal.sports.SportsRegion
import com.signal.sports.SportsWhen
import com.signal.sports.programOn
import com.signal.sports.recordingsAtRisk
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.ListRow
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** How often the list looks again: progress bars move and games start and end. */
private const val REFRESH_MS = 60_000L

/** How long a tune waits to hear what's recording before going ahead. */
private const val RECORDING_LOOK_MS = 1_500L

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

private suspend fun channelOf(app: AppController, c: EventChannel): Channel? = app.channels.channelByStream(c.streamId)

/** Plays [c] full screen, in its own group. */
private fun watch(app: AppController, ch: Channel) {
    val group = ChannelGroup.Provider(ch.stream.sourceId, ch.stream.groupName, ch.stream.groupName)
    app.panel.close()
    app.playback.tune(ch, fullScreen = true, group = group)
    // Back comes here, not to a Live TV group the channel may be hidden in.
    app.navigator.go(Route.FullScreen(group, backTo = Route.LiveSports))
}

/**
 * Plays a game's channel, first asking if that would cut off a recording (live-sports.md). What's
 * recording comes fresh from the companion, if it answers quickly; a TV never waits on it for long.
 */
private suspend fun play(app: AppController, c: EventChannel, returnTo: FocusRequester?) {
    val ch = channelOf(app, c) ?: return
    val logins = app.sources.byId(c.sourceId)?.logins?.size ?: 1
    val recordings = withTimeoutOrNull(RECORDING_LOOK_MS) { app.recordings.refresh()?.recordings } ?: app.recordings.list.value
    val risk = recordingsAtRisk(recordings, c.sourceId, logins)
    if (risk.isEmpty()) {
        watch(app, ch)
        return
    }
    val what = risk.joinToString(", ") { it.title }
    val warning = PanelPage.Confirm("Stop the recording?", "Watching this uses the same source as a recording, which will stop: $what", "Watch anyway") { watch(app, ch) }
    app.panel.open(warning, returnTo)
}

private fun whenText(e: SportEvent, now: Long, use24: Boolean): String = when {
    e.isOn(now) -> "LIVE"
    dayLabel(e.start, now).let { it == "Today" || it.isEmpty() } -> localTime(e.start, use24)
    else -> "${dayLabel(e.start, now)} ${localTime(e.start, use24)}"
}

/** What a row says under its heading. */
internal fun sportLine(e: SportEvent): String {
    val more = e.channels.size - 1
    return listOfNotNull(
        e.sport.label,
        e.channels.firstOrNull()?.let { if (it.program != null) it.name else it.group.substringAfter('|').trim() },
        if (more > 0) "$more more" else null,
        e.detail?.takeIf { e.teams == null },
    ).joinToString(" · ")
}

/**
 * Live Sports (live-sports.md): games now and coming, one row each, from every channel in reach
 * whether hidden or not. Filters for when, sport and region; OK plays the best channel and a long
 * press offers the others.
 */
@Composable
fun LiveSportsPage() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val chrome = remember { PageChrome() }
    val scope = rememberCoroutineScope()
    val sports = app.sports
    var all by remember { mutableStateOf(sports.lastEvents) }
    var now by remember { mutableStateOf(nowMs()) }
    // Saved per TV as they change, so they're still set on the next visit and after a restart.
    var filters by remember { mutableStateOf(sports.filters()) }
    // The row focus goes back to. State, so the row it names takes up rowFocus before focus is asked for.
    var focusKey by remember { mutableStateOf(sports.lastEvent) }
    val list = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    val sportButton = remember { FocusRequester() }
    val regionButton = remember { FocusRequester() }
    val moreButton = remember { FocusRequester() }
    val use24 = use24(app)

    suspend fun load() {
        now = nowMs()
        all = sports.events(now).also { sports.lastEvents = it }
    }

    fun rows(): List<SportEvent> = SportsList.filter(all.orEmpty(), filters.whenOn, filters.chosen, now)

    fun setFilters(value: SportsFilters) {
        filters = value
        sports.save(value)
    }

    /** Back to the row played last, else the top one, unless the user has gone to the filters or the bar. */
    suspend fun focusList() {
        repeat(2) { withFrameNanos { } }
        if (chrome.navHasFocus) return
        val shown = rows()
        if (shown.isEmpty()) {
            chrome.contentFocus.requestFocusWhenReady()
            return
        }
        val index = shown.indexOfFirst { it.key == sports.lastEvent }.coerceAtLeast(0)
        focusKey = shown[index].key
        sports.lastEvent = focusKey
        repeat(2) { withFrameNanos { } }
        list.requestScrollToItem(index)
        rowFocus.requestFocusWhenReady()
    }

    fun pick(title: String, returnTo: FocusRequester, choices: List<PanelAction>) = app.panel.open(PanelPage.Actions(title, choices), returnTo)

    fun chooseWhen(returnTo: FocusRequester) = pick(
        "When", returnTo,
        SportsWhen.entries.map { w ->
            PanelAction(w.label, selected = w == filters.whenOn) {
                setFilters(filters.copy(whenOn = w))
                null
            }
        },
    )

    /** Stays open, so several can be ticked. A chosen sport with no games right now is still listed, to take it off. */
    fun chooseSport(returnTo: FocusRequester) {
        val present = SportsList.sports(all.orEmpty(), filters.whenOn, now).toSet() + filters.chosen
        val page = PanelPage.Custom("Sports") {
            SportFilter(Sport.entries.filter { it in present }, filters) { setFilters(it) }
        }
        app.panel.open(page, returnTo)
    }

    fun chooseRegion(returnTo: FocusRequester) = pick(
        "Region", returnTo,
        SportsRegion.entries.map { r ->
            PanelAction(r.label, detail = if (r == SportsRegion.US) "US channels only" else "International channels too", selected = r == filters.region) {
                setFilters(filters.copy(region = r))
                scope.launch { load() }
                null
            }
        },
    )

    fun details(e: SportEvent, ec: EventChannel, returnTo: FocusRequester) {
        scope.launch {
            val ch = channelOf(app, ec) ?: return@launch
            // Marked as watching, so its own Watch doesn't go around the recording warning.
            app.panel.open(programDetails(app, ch, e.programOn(ec), nowMs(), watching = true), returnTo)
        }
    }

    fun menu(e: SportEvent, returnTo: FocusRequester) {
        val first = e.channels.firstOrNull() ?: return
        val actions = buildList {
            add(PanelAction("Watch", detail = first.name) { scope.launch { play(app, first, returnTo) }; null })
            if (e.channels.size > 1) {
                add(
                    PanelAction("Other channels", detail = "${e.channels.size - 1}") {
                        PanelPage.Actions(
                            "Other channels",
                            e.channels.drop(1).map { o -> PanelAction(o.name, detail = o.group) { scope.launch { play(app, o, returnTo) }; null } },
                        )
                    },
                )
            }
            if (e.stop > nowMs()) add(PanelAction("Record or remind me") { details(e, first, returnTo); null })
        }
        app.panel.open(PanelPage.Actions(e.heading, actions), returnTo)
    }

    LaunchedEffect(Unit) {
        // On the filters until there are rows, so a key does something while it looks.
        if (all != null) focusList() else chrome.contentFocus.requestFocusWhenReady()
        load()
        focusList()
        while (true) {
            delay(REFRESH_MS)
            load()
        }
    }

    TopLevelPage(Route.LiveSports, chrome) {
        Column(Modifier.fillMaxSize().padding(horizontal = SignalSizes.EDGE_MARGIN.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val top = chrome.contentFocus
                SButton(filters.whenOn.label, { chooseWhen(top) }, focusRequester = top)
                SButton(filters.sportLabel, { chooseSport(sportButton) }, focusRequester = sportButton)
                SButton(filters.region.label, { chooseRegion(regionButton) }, focusRequester = regionButton)
                SButton("Sports and groups", { app.panel.open(sportsChoices(app) { scope.launch { load() } }, moreButton) }, focusRequester = moreButton)
            }
            val shown = rows()
            when {
                all == null -> SText("Finding games…", 28, color = c.muted)
                shown.isEmpty() -> SText(if (filters.whenOn == SportsWhen.LIVE) "No games on right now." else "No games found.", 28, color = c.muted)
                else -> LazyColumn(Modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(shown, key = { _, e -> e.key }) { index, e ->
                        val own = remember { FocusRequester() }
                        val row = if (e.key == focusKey) rowFocus else own
                        GameRow(
                            e, now, use24, row,
                            // Up leaves the list at the first filter, not the one nearest the middle of the screen.
                            up = if (index == 0) chrome.contentFocus else null,
                            onFocused = { sports.lastEvent = e.key },
                            onSelect = { e.channels.firstOrNull()?.let { first -> scope.launch { play(app, first, row) } } },
                            onOptions = { menu(e, row) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(e: SportEvent, now: Long, use24: Boolean, focus: FocusRequester, up: FocusRequester?, onFocused: () -> Unit, onSelect: () -> Unit, onOptions: () -> Unit) {
    val c = SignalTheme.colors
    Focusable(
        Modifier.fillMaxWidth().let { m -> if (up != null) m.focusProperties { this.up = up } else m },
        onSelect = onSelect, onOptions = onOptions, focusRequester = focus, onFocused = onFocused,
    ) { focused ->
        val on = e.isOn(now)
        val text = if (focused) c.onAccent else c.text
        val muted = if (focused) c.onAccent else c.muted
        Row(
            Modifier.fillMaxWidth().background(if (focused) c.accent else Color.Transparent).padding(horizontal = 32.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(220.dp)) {
                SText(whenText(e, now, use24), 24, weight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (on) text else muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SText(e.heading, 28, weight = FontWeight.SemiBold, color = text)
                SText(sportLine(e), 22, color = muted)
                if (on) ProgressBar(e.progress(now), Modifier.fillMaxWidth().padding(top = 6.dp), onAccent = focused)
            }
        }
    }
}

/** The Sport filter: All sports clears it, and each sport turns on or off. */
@Composable
private fun SportFilter(present: List<Sport>, start: SportsFilters, change: (SportsFilters) -> Unit) {
    var current by remember { mutableStateOf(start) }
    val first = remember { FocusRequester() }
    fun set(value: SportsFilters) {
        current = value
        change(value)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "all") {
            ListRow("All sports", selected = current.sports.isEmpty(), focusRequester = first, onSelect = { set(current.copy(sports = emptySet())) })
        }
        items(present, key = { it.name }) { s ->
            val on = s in current.chosen
            ListRow(s.label, detail = if (on) "On" else null, selected = on, onSelect = { set(current.toggled(s)) })
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}

/** The profile's sports and groups (live-sports.md): each row turns one on or off. */
private fun sportsChoices(app: AppController, changed: () -> Unit): PanelPage = PanelPage.Actions(
    "Sports and groups",
    listOf(
        PanelAction("Sports") { PanelPage.Custom("Sports") { SportChoices(app, changed) } },
        PanelAction("Groups", detail = "Channels read for games") { PanelPage.Custom("Groups") { GroupChoices(app, changed) } },
    ),
)

@Composable
private fun SportChoices(app: AppController, changed: () -> Unit) {
    val hidden = remember { mutableStateListOf<String>().apply { addAll(app.sports.settings().hiddenSports) } }
    val first = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(Sport.entries.toList(), key = { it.name }) { s ->
            ListRow(
                s.label,
                detail = if (s.name in hidden) "Off" else "On",
                focusRequester = if (s == Sport.entries.first()) first else null,
                onSelect = {
                    if (s.name in hidden) hidden.remove(s.name) else hidden.add(s.name)
                    app.sports.save(app.sports.settings().copy(hiddenSports = hidden.toSet()))
                    changed()
                },
            )
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}

@Composable
private fun GroupChoices(app: AppController, changed: () -> Unit) {
    var groups by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        groups = app.sports.groups()
        first.requestFocusWhenReady()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        val rows = groups.orEmpty()
        items(rows, key = { it.first }) { (name, on) ->
            ListRow(
                name,
                detail = if (on) "On" else "Off",
                focusRequester = if (name == rows.first().first) first else null,
                size = 24,
                onSelect = {
                    val s = app.sports.settings()
                    app.sports.save(if (on) s.copy(groupsOn = s.groupsOn - name, groupsOff = s.groupsOff + name) else s.copy(groupsOn = s.groupsOn + name, groupsOff = s.groupsOff - name))
                    groups = rows.map { if (it.first == name) name to !on else it }
                    changed()
                },
            )
        }
    }
}

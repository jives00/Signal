package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.input.RemoteDispatcher
import com.signal.input.RemoteHandler
import com.signal.input.remoteItem
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.model.Program
import com.signal.ui.LocalApp
import com.signal.ui.components.HDivider
import com.signal.ui.components.Icon
import com.signal.ui.components.SignalIcons
import com.signal.ui.components.LogoTile
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SText
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.VDivider
import com.signal.ui.components.VideoBox
import com.signal.ui.components.formatTime
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** The handoff's guide geometry (screen 2), in design units. */
private object GuideGeometry {
    const val STRIP_HEIGHT = 360
    const val HEADER_HEIGHT = 64
    const val ROW_HEIGHT = 88
    const val CHANNEL_COLUMN = 400
    const val PROGRAM_AREA = 1920 - CHANNEL_COLUMN
    const val UNITS_PER_MINUTE = 10.4f
    const val NOW_LINE = 4
    const val GROUP_PANEL = 400
    val windowMs: Long = (PROGRAM_AREA / UNITS_PER_MINUTE * 60_000).toLong()
}

private const val MINUTE = 60_000L
private const val HALF_HOUR = 30 * MINUTE
private const val DAY = 24 * 60 * MINUTE
/** Program cells narrower than this (design units) show no text. */
private const val MIN_TEXT_WIDTH = 72f

/**
 * Live TV, the guide (PLAN §4.3.2, handoff screen 2). One screen-level focus position (a row and a
 * moment in time, or the channel column), not a focusable per cell, and only the programs inside
 * the time window are composed: the structure the milestone 1 spike measured at about 60fps on the
 * Shield (PLAN §10). The grid starts at now and never shows ended programs.
 */
@Composable
fun LiveTvPage() {
    val app = LocalApp.current
    val chrome = remember { PageChrome() }
    val scope = rememberCoroutineScope()
    val groups by app.groups.collectAsState()
    val current by app.playback.current.collectAsState()
    val guideState by app.guide.state.collectAsState()
    var group by remember { mutableStateOf<ChannelGroup?>(null) }
    val move = remember { MoveState() }
    val groupCol = remember { ListFocus() }
    var panelOpen by remember { mutableStateOf(false) }
    val gridFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val rows = remember(guideState) { GuideRows(app, scope) }
    val reminderList by app.reminderList.collectAsState()
    val reminderKeys = remember(reminderList) { reminderList.mapTo(HashSet()) { "${it.userChannelId}|${it.title}|${it.start}" } }

    // The clock the grid runs on: ended programs drop off and the now line moves.
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }

    // Focus: a row, and a moment inside the focused program; or the row's channel column.
    var focusRow by remember { mutableStateOf(0) }
    var focusTime by remember { mutableLongStateOf(now) }
    var onChannelColumn by remember { mutableStateOf(false) }
    // The program area's left edge. It starts at now (PLAN §4.3.2 → Time window).
    var windowStart by remember { mutableLongStateOf(now) }

    // Opens on Favorites every time; with no favorites, the first visible group (PLAN §4.3.2).
    LaunchedEffect(groups.isNotEmpty()) {
        if (group != null || groups.isEmpty()) return@LaunchedEffect
        val favoriteCount = groups.firstOrNull { it.group == ChannelGroup.Favorites }?.count ?: 0
        group = app.liveTvGroup
            ?: if (favoriteCount > 0) ChannelGroup.Favorites
            else groups.firstOrNull { it.group is ChannelGroup.Provider }?.group ?: ChannelGroup.Favorites
        app.liveTvGroup = null
    }
    val loaded = channelsOf(app, group)
    val channels = move.arrange(MoveState.Kind.CHANNEL, loaded.orEmpty()) { it.key }
    // The key handlers below run later, from the dispatcher: they read these, never a composition's copy.
    val channelsNow = rememberUpdatedState(channels)
    val loadedNow = rememberUpdatedState(loaded)
    val rowsNow = rememberUpdatedState(rows)

    LaunchedEffect(Unit) {
        // The preview plays the last played channel, the same as the Home hero (PLAN §4.3.2).
        if (app.playback.current.value == null) app.playback.startingChannel()?.let { app.playback.tune(it, fullScreen = false) }
        else app.playback.resumeIfStopped()
    }
    DisposableEffect(Unit) {
        onDispose { if (app.navigator.current !is Route.FullScreen) app.playback.stop() }
    }

    fun scrollToRow(row: Int) {
        val first = list.firstVisibleItemIndex
        val visible = (list.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
        if (row < first) list.requestScrollToItem(row)
        else if (row >= first + visible) list.requestScrollToItem(row - visible + 1)
    }

    fun focusGrid() {
        scope.launch { gridFocus.requestFocusWhenReady() }
    }

    // On a group change: the channel that was playing full screen, else the playing channel, else the first row; at now.
    LaunchedEffect(loaded != null, group) {
        val l = loaded ?: return@LaunchedEffect
        val target = app.liveTvFocusKey ?: current?.key
        app.liveTvFocusKey = null
        focusRow = l.indexOfFirst { it.key == target || it.stream.id == current?.stream?.id }.coerceAtLeast(0)
        focusTime = now
        windowStart = now
        onChannelColumn = false
        list.requestScrollToItem((focusRow - 2).coerceAtLeast(0))
        if (!panelOpen) focusGrid()
    }

    fun channelAt(row: Int): Channel? = channelsNow.value.getOrNull(row)
    fun programsAt(row: Int): List<Program>? = channelAt(row)?.let { rowsNow.value.get(it) }

    /** The focused program, or null on a row without guide data (or still loading). */
    fun focusedProgram(): Program? = programsAt(focusRow)?.firstOrNull { focusTime in it.start until it.stop && it.stop > now }

    fun moveRow(delta: Int) {
        val all = channelsNow.value
        if (all.isEmpty()) return
        val target = (focusRow + delta).coerceIn(0, all.lastIndex)
        if (target == focusRow) {
            if (delta < 0) chrome.focusNav() // Up from the top row reveals the nav bar (PLAN §4.1).
            return
        }
        focusRow = target
        scrollToRow(target)
    }

    /** Keeps [p] inside the window, scrolling time as little as possible, never before now. */
    fun showInWindow(p: Program) {
        val windowEnd = windowStart + GuideGeometry.windowMs
        if (p.start < windowStart) windowStart = maxOf(now, p.start)
        else if (p.start >= windowEnd - HALF_HOUR) windowStart = maxOf(now, p.start - HALF_HOUR)
    }

    fun moveProgram(delta: Int) {
        val progs = programsAt(focusRow)?.filter { it.stop > now }.orEmpty()
        if (onChannelColumn) {
            if (delta > 0) {
                onChannelColumn = false
                focusTime = maxOf(now, windowStart)
            } else {
                panelOpen = true // Left on the channel column opens the group panel (PLAN §4.3.2).
            }
            return
        }
        val i = progs.indexOfFirst { focusTime in it.start until it.stop }
        if (delta < 0 && (i <= 0 || progs[i].start <= now)) {
            // Left on a live program (or no data) moves to the channel column.
            onChannelColumn = true
            return
        }
        val next = progs.getOrNull(if (i < 0) 0 else i + delta) ?: return
        focusTime = maxOf(next.start, now)
        showInWindow(next)
    }

    fun jumpTime(to: Long) {
        windowStart = maxOf(now, to)
        focusTime = windowStart
        onChannelColumn = false
    }

    /** OK on a live program, a no-data row or the channel column: preview first, then full screen (PLAN §4.3.2). */
    fun watch(ch: Channel) {
        val g = group ?: return
        if (current?.stream?.id == ch.stream.id) {
            app.playback.tune(ch, fullScreen = true, group = g)
            app.navigator.go(Route.FullScreen(g))
        } else {
            app.playback.tune(ch, fullScreen = false, group = g)
        }
    }

    fun select() {
        val ch = channelAt(focusRow) ?: return
        if (move.active) { move.drop(); return }
        val p = focusedProgram()
        when {
            onChannelColumn || p == null || p.start <= now -> watch(ch)
            else -> app.panel.open(programDetails(app, ch, p, now), gridFocus)
        }
    }

    fun options() {
        val ch = channelAt(focusRow) ?: return
        if (move.active) return
        val p = focusedProgram()
        if (onChannelColumn || p == null) {
            val g = group
            val canMove = g == ChannelGroup.Favorites || g is ChannelGroup.Playlist
            val onMove = if (canMove && g != null) ({ startChannelMove(app, move, g, ch.key, loadedNow.value) }) else null
            app.panel.open(app.menus.channelMenu(ch, g, onMove), gridFocus)
        } else {
            app.panel.open(programDetails(app, ch, p, now), gridFocus)
        }
    }

    // While moving a channel, focus rides with it.
    if (move.active && move.kind == MoveState.Kind.CHANNEL) {
        val i = channels.indexOfFirst { it.key == move.movingKey }
        if (i >= 0 && i != focusRow) { focusRow = i; scrollToRow(i) }
    }

    RemoteHandler { action ->
        if (!gridHasFocus || move.active) return@RemoteHandler false
        when (action) {
            RemoteAction.Up -> moveRow(-1)
            RemoteAction.Down -> moveRow(1)
            RemoteAction.ChannelUp -> moveRow(-7)
            RemoteAction.ChannelDown -> moveRow(7)
            RemoteAction.Left -> moveProgram(-1)
            RemoteAction.Right -> moveProgram(1)
            RemoteAction.PlayPause -> jumpTime(now) // Jump to now.
            RemoteAction.FastForward -> jumpTime(windowStart + DAY)
            RemoteAction.Rewind -> jumpTime(windowStart - DAY)
            else -> return@RemoteHandler false
        }
        true
    }
    MoveHandler(move)

    NumberJump(channels) { ch ->
        val i = channelsNow.value.indexOfFirst { it.key == ch.key }
        if (i >= 0) { focusRow = i; onChannelColumn = true; list.requestScrollToItem((i - 2).coerceAtLeast(0)) }
    }

    // Opening the group panel focuses the current group; closing it returns to the grid.
    LaunchedEffect(panelOpen) {
        if (panelOpen) {
            group?.let { groupCol.focus(it.key) }
        } else {
            focusGrid()
        }
    }
    RemoteHandler(enabled = panelOpen && !move.active, priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        when (action) {
            RemoteAction.Back, RemoteAction.Right -> { panelOpen = false; true }
            else -> false
        }
    }

    val focusedChannel = channelAt(focusRow)
    val focused = focusedProgram()
    TopLevelPage(Route.LiveTv, chrome, hideNav = true) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(GuideGeometry.STRIP_HEIGHT.dp)) {
                VideoBox(Modifier.width(640.dp).fillMaxHeight(), compact = true, emptyText = "Pick a channel")
                VDivider()
                DetailsStrip(app, focusedChannel ?: current, if (onChannelColumn) programsAt(focusRow)?.firstOrNull { it.isOn(now) } else focused, now)
            }
            HDivider()
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(chrome.contentFocus)
                        .focusRequester(gridFocus)
                        .onFocusChanged { gridHasFocus = it.isFocused }
                        .remoteItem(onSelect = ::select, onOptions = ::options)
                        .focusable()
                        .semantics {
                            // What's focused, for accessibility and the key-map tests.
                            contentDescription = buildString {
                                append(focusedChannel?.displayName ?: "No channels")
                                append(" · ")
                                append(if (onChannelColumn) "channel" else focused?.title ?: "No information")
                            }
                        },
                ) {
                    TimeHeader(app, group, windowStart)
                    HDivider()
                    LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = false) {
                        itemsIndexed(channels, key = { _, ch -> ch.key }) { i, ch ->
                            LaunchedEffect(ch.stream.id, rows) { rows.request(ch, now) }
                            GuideRow(
                                ch,
                                programs = rows.get(ch),
                                windowStart = windowStart,
                                now = now,
                                rowFocused = gridHasFocus && i == focusRow,
                                channelFocused = gridHasFocus && i == focusRow && onChannelColumn,
                                focusTime = focusTime,
                                playing = current?.stream?.id == ch.stream.id,
                                moving = move.movingKey == ch.key,
                                hasReminder = { p -> ch.userChannelId != null && "${ch.userChannelId}|${p.title}|${p.start}" in reminderKeys },
                            )
                            HDivider()
                        }
                    }
                }
                NowLine(now, windowStart)
                if (loaded != null && loaded.isEmpty()) {
                    SText(
                        if (group == ChannelGroup.Favorites) "No favorites yet. Long-press OK on a channel to add one." else "No channels in this group.",
                        24, Modifier.padding(top = 104.dp, start = 40.dp), color = SignalTheme.colors.muted, maxLines = 2,
                    )
                }
                if (panelOpen) {
                    // Created with the panel, already at the current group: it may be far down the list.
                    val groupList = rememberLazyListState(initialFirstVisibleItemIndex = (groups.indexOfFirst { it.group == group } - 3).coerceAtLeast(0))
                    Box(Modifier.width(GuideGeometry.GROUP_PANEL.dp).fillMaxHeight().background(SignalTheme.colors.bg)) {
                        GroupColumn(app, groups, group, groupCol, groupList, move) { g ->
                            group = g
                            panelOpen = false
                        }
                    }
                }
            }
        }
    }
}

/** Each row's programs from now to 7 days out, loaded as rows appear. A bounded cache: the grid can be 2,000 rows deep. */
@Stable
internal class GuideRows(private val app: AppController, private val scope: CoroutineScope) {
    private val cache = mutableStateMapOf<String, List<Program>>()
    private val order = ArrayDeque<String>()
    private val loading = HashSet<String>()

    fun get(ch: Channel): List<Program>? = cache[ch.stream.id]

    fun request(ch: Channel, now: Long) {
        val key = ch.stream.id
        if (key in cache || !loading.add(key)) return
        scope.launch {
            val programs = runCatching { app.guide.programs(ch, now, now + 7 * DAY) }.getOrDefault(emptyList())
            loading.remove(key)
            cache[key] = programs
            order.addLast(key)
            while (order.size > MAX_ROWS) cache.remove(order.removeFirst())
        }
    }

    private companion object {
        const val MAX_ROWS = 300
    }
}

private fun localTime(ms: Long, use24: Boolean): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return formatTime(t.hour, t.minute, use24)
}

/** "Today", "Tomorrow", else a short weekday and date. */
internal fun dayLabel(ms: Long, now: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val day = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    return when (day.toEpochDays() - today.toEpochDays()) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + day.day
    }
}

internal fun use24(app: AppController) = app.settings.bool(Settings.Key.CLOCK_24H, app.platform.systemUses24HourClock())

/** "6:00 – 8:00 PM", with the day when it isn't today. */
internal fun programTime(app: AppController, p: Program, now: Long): String {
    val h24 = use24(app)
    val day = dayLabel(p.start, now).takeIf { it != "Today" }?.let { "$it " }.orEmpty()
    return "$day${localTime(p.start, h24)} – ${localTime(p.stop, h24)}"
}

@Composable
private fun TimeHeader(app: AppController, group: ChannelGroup?, windowStart: Long) {
    val c = SignalTheme.colors
    val h24 = use24(app)
    Row(Modifier.fillMaxWidth().height(GuideGeometry.HEADER_HEIGHT.dp)) {
        SText(
            "${dayLabel(windowStart, Clock.System.now().toEpochMilliseconds())} · ${group?.title.orEmpty()}",
            22, Modifier.width(GuideGeometry.CHANNEL_COLUMN.dp).padding(start = 24.dp, top = 18.dp, end = 16.dp),
            weight = FontWeight.SemiBold, color = c.muted,
        )
        Box(Modifier.fillMaxSize()) {
            // Half-hour labels, each with a 2-unit left rule, from the first boundary in the window.
            var t = windowStart - windowStart % HALF_HOUR + if (windowStart % HALF_HOUR == 0L) 0 else HALF_HOUR
            while (t < windowStart + GuideGeometry.windowMs) {
                val x = (t - windowStart) / MINUTE.toFloat() * GuideGeometry.UNITS_PER_MINUTE
                Box(Modifier.offset(x = x.dp).width(2.dp).fillMaxHeight().background(c.divider))
                SText(localTime(t, h24), 22, Modifier.offset(x = x.dp + 12.dp, y = 18.dp), weight = FontWeight.SemiBold, color = c.secondaryText)
                t += HALF_HOUR
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: Channel,
    programs: List<Program>?,
    windowStart: Long,
    now: Long,
    rowFocused: Boolean,
    channelFocused: Boolean,
    focusTime: Long,
    playing: Boolean,
    moving: Boolean,
    hasReminder: (Program) -> Boolean,
) {
    val c = SignalTheme.colors
    val windowEnd = windowStart + GuideGeometry.windowMs
    Row(Modifier.fillMaxWidth().height(GuideGeometry.ROW_HEIGHT.dp)) {
        // The channel column: number, logo and name.
        Row(
            Modifier
                .width(GuideGeometry.CHANNEL_COLUMN.dp)
                .fillMaxHeight()
                .background(if (channelFocused || moving) c.accent else if (rowFocused) c.surface else Color.Transparent)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (channelFocused || moving) c.onAccent else c.text
            SText(ch.number.toString(), 20, Modifier.width(56.dp), color = if (channelFocused || moving) c.onAccent else c.muted)
            LogoTile(ch.displayName, ch.logoUrl, 96, 56, onAccent = channelFocused || moving)
            SText((if (moving) "↕ " else "") + ch.displayName, 24, Modifier.padding(start = 16.dp).weight(1f), weight = FontWeight.SemiBold, color = ink)
            if (playing) Box(Modifier.width(8.dp).height(8.dp).background(if (channelFocused) c.onAccent else c.accent))
        }
        VDivider()
        Box(Modifier.fillMaxSize()) {
            val visible = programs?.filter { it.stop > maxOf(now, windowStart) && it.start < windowEnd }
            if (visible.isNullOrEmpty()) {
                // No guide data: one cell across the window, counting as on now (PLAN §4.3.2).
                val focused = rowFocused && !channelFocused
                Box(
                    Modifier.fillMaxSize().background(if (focused) c.accent else if (programs != null) c.surface else Color.Transparent)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (programs != null) SText("No information", 24, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.muted)
                }
            } else {
                for (p in visible) ProgramCell(p, windowStart, windowEnd, now, focused = rowFocused && !channelFocused && focusTime in p.start until p.stop, reminder = hasReminder(p))
            }
        }
    }
}

@Composable
private fun ProgramCell(p: Program, windowStart: Long, windowEnd: Long, now: Long, focused: Boolean, reminder: Boolean) {
    val c = SignalTheme.colors
    val app = LocalApp.current
    val from = maxOf(p.start, windowStart, now)
    val to = minOf(p.stop, windowEnd)
    val left = (from - windowStart) / MINUTE.toFloat() * GuideGeometry.UNITS_PER_MINUTE
    val width = (to - from) / MINUTE.toFloat() * GuideGeometry.UNITS_PER_MINUTE
    Row(Modifier.offset(x = left.dp).width(width.coerceAtLeast(2f).dp).fillMaxHeight()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (focused) c.accent else if (p.isOn(now)) c.surface else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // A sliver at the window's edge has no room for text, only for "…".
            if (width >= MIN_TEXT_WIDTH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A bell marks a program with a reminder (PLAN §4.3.4).
                    if (reminder) Icon(SignalIcons.bell, 20, if (focused) c.onAccent else c.accentText, Modifier.padding(end = 8.dp))
                    SText(p.title, 24, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
                }
                // The time range only on cells of 30 minutes or more (handoff screen 2).
                if (to - from >= HALF_HOUR) {
                    SText(programTime(app, p, now), 20, color = if (focused) c.onAccent else c.muted)
                }
            }
        }
        // 2-unit right rule between cells.
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.divider))
    }
}

/** A 4-unit accent bar at now, through the header and every row. */
@Composable
private fun NowLine(now: Long, windowStart: Long) {
    if (now < windowStart || now >= windowStart + GuideGeometry.windowMs) return
    val x = GuideGeometry.CHANNEL_COLUMN + 2 + (now - windowStart) / MINUTE.toFloat() * GuideGeometry.UNITS_PER_MINUTE
    Box(Modifier.offset(x = x.dp).width(GuideGeometry.NOW_LINE.dp).fillMaxHeight().background(SignalTheme.colors.accent))
}

/** The focused program's details, above the grid (PLAN §4.3.2: the preview doesn't follow focus; this does). */
@Composable
private fun DetailsStrip(app: AppController, ch: Channel?, p: Program?, now: Long) {
    val c = SignalTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
        if (ch == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(ch.displayName, ch.logoUrl, 80, 48)
            SText("${ch.number}  ${ch.displayName}", 26, Modifier.padding(start = 24.dp), weight = FontWeight.SemiBold, color = c.accentText)
            if (p != null) {
                val status = if (p.isOn(now)) " · On now" else ""
                SText("   ${programTime(app, p, now)}$status", 24, color = c.muted)
            }
        }
        if (p == null) {
            SText(ch.displayName, 56, Modifier.padding(top = 20.dp), weight = FontWeight.ExtraBold)
            SText("No guide information", 26, Modifier.padding(top = 8.dp), color = c.muted)
            return@Column
        }
        SText(p.title, 56, Modifier.padding(top = 16.dp), weight = FontWeight.ExtraBold)
        val subtitle = listOfNotNull(p.episodeText, p.subTitle).joinToString(" · ")
        if (subtitle.isNotEmpty()) SText(subtitle, 26, color = c.secondaryText)
        p.description?.let { SText(it, 24, Modifier.padding(top = 8.dp).width(1000.dp), color = c.muted, maxLines = 2) }
        ProgramTags(p, Modifier.padding(top = 12.dp))
    }
}

/** Outline tags: New, Live, the rating and the first category (handoff screen 2). */
@Composable
internal fun ProgramTags(p: Program, modifier: Modifier = Modifier) {
    val c = SignalTheme.colors
    val tags = listOfNotNull(if (p.isNew) "New" else null, if (p.isLive) "Live" else null, p.rating, p.categories.firstOrNull())
    if (tags.isEmpty()) return
    Row(modifier) {
        tags.forEach { t ->
            SText(t, 20, Modifier.padding(end = 12.dp).border(2.dp, c.divider).padding(horizontal = 12.dp, vertical = 4.dp), weight = FontWeight.SemiBold, color = c.secondaryText)
        }
    }
}

/** The guide's "now" for screens outside the grid, ticking each minute. */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - Clock.System.now().toEpochMilliseconds() % 60_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/**
 * [ch]'s program on now and the next one, for Home, the info bar, the zap banner and the channel
 * list. Reloads when the channel or the guide changes, and when the program on now ends.
 */
@Composable
internal fun rememberNowAndNext(ch: Channel?, now: Long = rememberNow()): Pair<Program?, Program?> {
    val app = LocalApp.current
    val state by app.guide.state.collectAsState()
    var result by remember(ch?.stream?.id, state) { mutableStateOf<Pair<Program?, Program?>>(null to null) }
    val stale = result.first?.let { now >= it.stop } ?: true
    LaunchedEffect(ch?.stream?.id, state, stale) {
        result = if (ch == null) null to null else app.guide.nowAndNext(ch, now)
    }
    return result
}

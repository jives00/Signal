package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.data.GroupEntry
import com.signal.input.RemoteAction
import com.signal.input.RemoteDispatcher
import com.signal.input.RemoteHandler
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.HDivider
import com.signal.ui.components.LogoTile
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SText
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.VDivider
import com.signal.ui.components.VideoBox
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalSizes
import com.signal.ui.components.showNumbers
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Live TV's shared pieces: the group column (the guide's group panel), move mode, number jump and
// channel loading. The guide itself is in GuidePage.kt.

/** Focus bookkeeping for one list column. */
@Stable
internal class ListFocus {
    /** On the LazyColumn, with focusRestorer: requesting it returns to the last focused row. */
    val column = FocusRequester()
    var targetKey by mutableStateOf<String?>(null)
        private set
    var tick by mutableStateOf(0)
        private set
    var focusedIndex by mutableStateOf(0)
    var hasFocus by mutableStateOf(false)

    /** Focuses the row with [key] once it's composed (scroll it into view first). */
    fun focus(key: String) {
        targetKey = key
        tick++
    }
}

/** Move mode (PLAN §2.3): Up/Down carries the item, OK drops it, Back cancels. */
@Stable
internal class MoveState {
    enum class Kind { CHANNEL, GROUP }

    var kind by mutableStateOf<Kind?>(null)
        private set
    var movingKey by mutableStateOf<String?>(null)
        private set
    /** The working order while moving; committed on drop. */
    val order = mutableStateListOf<String>()
    private var onDrop: ((order: List<String>) -> Unit)? = null

    val active: Boolean get() = movingKey != null

    fun start(kind: Kind, key: String, keys: List<String>, onDrop: (List<String>) -> Unit) {
        order.clear()
        order.addAll(keys)
        this.kind = kind
        movingKey = key
        this.onDrop = onDrop
    }

    fun step(delta: Int) {
        val key = movingKey ?: return
        val i = order.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order.removeAt(i)
        order.add(j, key)
    }

    fun drop() {
        onDrop?.invoke(order.toList())
        cancel()
    }

    fun cancel() {
        movingKey = null
        kind = null
        onDrop = null
        order.clear()
    }

    fun <T> arrange(kind: Kind, items: List<T>, keyOf: (T) -> String): List<T> {
        if (this.kind != kind || order.isEmpty()) return items
        val byKey = items.associateBy(keyOf)
        return order.mapNotNull(byKey::get) + items.filter { keyOf(it) !in order }
    }
}

@Composable
internal fun MoveHandler(move: MoveState) {
    RemoteHandler(enabled = move.active, priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        when (action) {
            RemoteAction.Up -> { move.step(-1); true }
            RemoteAction.Down -> { move.step(1); true }
            RemoteAction.Back -> { move.cancel(); true }
            RemoteAction.Left, RemoteAction.Right -> true
            else -> false
        }
    }
}

@Composable
internal fun GroupColumn(
    app: AppController,
    groups: List<GroupEntry>,
    selected: ChannelGroup?,
    focus: ListFocus,
    state: LazyListState,
    move: MoveState,
    onApply: (ChannelGroup) -> Unit,
) {
    val c = SignalTheme.colors
    val shown = move.arrange(MoveState.Kind.GROUP, groups) { it.group.key }
    Column(Modifier.width(380.dp).fillMaxHeight()) {
        SText("Groups", 24, Modifier.padding(start = 40.dp, top = 20.dp, bottom = 8.dp), weight = FontWeight.SemiBold, color = c.muted)
        LazyColumn(Modifier.fillMaxSize().onFocusChanged { focus.hasFocus = it.hasFocus }.focusRequester(focus.column).focusRestorer(), state = state) {
            itemsIndexed(shown, key = { _, g -> g.group.key }) { i, entry ->
                val requester = remember { FocusRequester() }
                LaunchedEffect(focus.tick) { if (focus.targetKey == entry.group.key) requester.requestFocusWhenReady() }
                val moving = move.movingKey == entry.group.key
                Focusable(
                    Modifier.fillMaxWidth(),
                    focusRequester = requester,
                    onFocused = { focus.focusedIndex = i },
                    onSelect = { if (move.active) move.drop() else onApply(entry.group) },
                    onOptions = { if (!move.active) openGroupMenu(app, entry, groups, move, focus.column, onApply) },
                ) { focused ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    focused || moving -> c.accent
                                    entry.group == selected -> c.surface
                                    else -> Color.Transparent
                                },
                            )
                            .padding(horizontal = 40.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val ink = if (focused || moving) c.onAccent else c.text
                        Column(Modifier.weight(1f)) {
                            SText((if (moving) "↕ " else "") + entry.group.title, 26, weight = FontWeight.SemiBold, color = ink)
                            entry.sourceName?.let { SText(it, 20, color = if (focused || moving) c.onAccent else c.muted) }
                        }
                        if (entry.count > 0) SText(entry.count.toString(), 22, Modifier.padding(start = 16.dp), color = if (focused || moving) c.onAccent else c.muted)
                    }
                }
            }
        }
    }
}

private fun openGroupMenu(
    app: AppController,
    entry: GroupEntry,
    groups: List<GroupEntry>,
    move: MoveState,
    returnTo: FocusRequester,
    onApply: (ChannelGroup) -> Unit,
) {
    val page = when (val g = entry.group) {
        ChannelGroup.Favorites -> app.menus.favoritesMenu { onApply(g); startChannelMove(app, move, g, null) }
        is ChannelGroup.Playlist -> app.menus.playlistMenu(
            g,
            onReorder = { onApply(g); startChannelMove(app, move, g, null) },
            onMovePlaylist = {
                val playlists = groups.filter { it.group is ChannelGroup.Playlist }.map { it.group.key }
                move.start(MoveState.Kind.GROUP, g.key, groups.map { it.group.key }) { order ->
                    val index = order.filter { it in playlists }.indexOf(g.key)
                    app.scope.launch { app.channels.movePlaylist(g.id, index) }
                }
            },
        )
        is ChannelGroup.Provider -> app.menus.groupMenu(entry, onMoveGroup = {
            val providers = groups.mapNotNull { it.group as? ChannelGroup.Provider }
            move.start(MoveState.Kind.GROUP, g.key, groups.map { it.group.key }) { order ->
                // Provider groups move among themselves; Favorites and playlists stay on top.
                val index = order.filter { k -> providers.any { it.key == k } }.indexOf(g.key)
                app.scope.launch { app.channels.moveGroup(providers, g, index) }
            }
        })
    }
    app.panel.open(page, returnTo)
}

@Composable
internal fun ChannelRow(ch: Channel, focused: Boolean, playing: Boolean, moving: Boolean = false) {
    val c = SignalTheme.colors
    Row(
        Modifier.fillMaxWidth().background(if (focused) c.accent else Color.Transparent).padding(horizontal = 40.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumbers()) SText(ch.number.toString(), 22, Modifier.width(80.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.muted)
        LogoTile(ch.displayName, ch.logoUrl, 80, 48, onAccent = focused)
        SText((if (moving) "↕ " else "") + ch.displayName, 26, Modifier.padding(start = 24.dp).weight(1f), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
        if (playing) SText("Playing", 20, Modifier.padding(start = 16.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.accentText)
        if (ch.isFavorite) SText("★", 22, Modifier.padding(start = 16.dp), color = if (focused) c.onAccent else c.accentText)
    }
}

internal fun startChannelMove(app: AppController, move: MoveState, group: ChannelGroup, key: String?, channels: List<Channel>? = null) {
    app.scope.launch {
        val list = channels ?: app.channels.channelsNow(group)
        val start = key ?: list.firstOrNull()?.key ?: return@launch
        move.start(MoveState.Kind.CHANNEL, start, list.map { it.key }) { order ->
            val to = order.indexOf(start)
            app.scope.launch {
                when (group) {
                    ChannelGroup.Favorites -> app.channels.moveFavorite(start, to)
                    is ChannelGroup.Playlist -> app.channels.movePlaylistItem(group.id, start, to)
                    is ChannelGroup.Provider -> {}
                }
            }
        }
    }
}

/**
 * Number keys jump to a channel number in the current group (PLAN §4.3.2 → Other keys). Digits
 * collect for 1.5 seconds, so "1", "0", "3" jumps to 103.
 */
@Composable
internal fun NumberJump(channels: List<Channel>, onJump: (Channel) -> Unit) {
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(1500)
        typed.toIntOrNull()?.let { n -> channels.firstOrNull { it.number == n }?.let(onJump) }
        typed = ""
    }
    RemoteHandler { action ->
        if (action is RemoteAction.Digit) { typed = (typed + action.value).takeLast(5); true } else false
    }
    if (typed.isNotEmpty()) {
        val c = SignalTheme.colors
        Box(Modifier.fillMaxSize().padding(SignalSizes.EDGE_MARGIN.dp), contentAlignment = Alignment.TopEnd) {
            SText(typed, 72, Modifier.background(c.bg).padding(horizontal = 32.dp, vertical = 16.dp), weight = FontWeight.ExtraBold)
        }
    }
}

/**
 * A group's channels, or null until that group's list has loaded. Right after the group changes,
 * the old list is still in state for a composition; returning it would let effects keyed on the list
 * act on the previous group's rows, so each list carries the group it belongs to.
 */
@Composable
internal fun channelsOf(app: AppController, group: ChannelGroup?): List<Channel>? {
    val loaded by produceState<Pair<ChannelGroup, List<Channel>>?>(null, group) {
        group?.let { g -> app.channels.channels(g).collect { value = g to it } }
    }
    return loaded?.takeIf { it.first == group }?.second
}

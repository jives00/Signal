package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.Route
import com.signal.input.RemoteAction
import com.signal.input.RemoteDispatcher
import com.signal.input.RemoteHandler
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.player.AspectMode
import com.signal.player.PlaybackState
import com.signal.player.summary
import com.signal.ui.LocalApp
import com.signal.ui.components.ClockText
import com.signal.ui.components.Focusable
import com.signal.ui.components.LogoTile
import com.signal.ui.components.SText
import com.signal.ui.components.VDivider
import com.signal.ui.components.VideoBox
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalSizes
import com.signal.ui.components.showNumbers
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Overlay { NONE, INFO, CHANNEL_LIST }

/**
 * Full-screen live playback (PLAN §4.3.3). Video only by default; UI appears on OK (info bar), Left
 * (channel list) or briefly after a channel change (zap banner).
 */
@Composable
fun FullScreenPlayback(route: Route.FullScreen) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val current by app.playback.current.collectAsState()
    val playerState by app.platform.player.state.collectAsState()
    val aspect by app.playback.aspect.collectAsState()
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var infoFocusOptions by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableStateOf(0) }
    var lastInput by remember { mutableStateOf(0) }

    fun zap(step: Int) {
        scope.launch { app.playback.zap(step)?.let { bannerTick++ } }
    }

    fun backToGuide() {
        // Back goes to Live TV on the current group, focused on the playing channel (PLAN §4.3.3).
        app.liveTvGroup = app.playback.currentGroup
        app.liveTvFocusKey = current?.key
        app.navigator.go(Route.LiveTv)
    }

    // Any key keeps the info bar up; it hides after 5 s without input (PLAN §4.3.3 → Auto-hide).
    RemoteHandler(priority = RemoteDispatcher.Priority.PROMPT) { lastInput++; false }

    RemoteHandler { action ->
        when (action) {
            RemoteAction.Up, RemoteAction.ChannelUp -> { zap(-1); true }
            RemoteAction.Down, RemoteAction.ChannelDown -> { zap(1); true }
            RemoteAction.Select -> {
                if (playerState.status == PlaybackState.Status.ERROR && overlay == Overlay.NONE) app.playback.retry()
                else { infoFocusOptions = false; overlay = Overlay.INFO }
                true
            }
            RemoteAction.Options -> { infoFocusOptions = true; overlay = Overlay.INFO; true }
            RemoteAction.Left -> { overlay = Overlay.CHANNEL_LIST; true }
            RemoteAction.Right -> true // Reserved (PLAN §4.3.3).
            RemoteAction.PlayPause -> { if (app.playback.recall() != null) bannerTick++; true }
            RemoteAction.Back -> { backToGuide(); true }
            else -> false
        }
    }

    val groupChannels by remember(app.playback.currentGroup, current) { app.channels.channels(app.playback.currentGroup) }.collectAsState(emptyList())
    NumberJump(groupChannels) { ch -> app.playback.tune(ch, fullScreen = true); bannerTick++ }

    // The channel list stays built once opened, and is built ahead after 2 s without a key: opening it
    // built both lists from scratch, 50–100 ms frames on the Shield (PLAN §8 → milestone 5).
    var listKept by remember { mutableStateOf(false) }
    var listWarm by remember { mutableStateOf(false) }
    LaunchedEffect(overlay) { if (overlay == Overlay.CHANNEL_LIST) listKept = true }
    LaunchedEffect(Unit) {
        app.dispatcher.awaitQuiet(2_000)
        if (listKept) return@LaunchedEffect
        listKept = true
        listWarm = true
        val inputs = app.dispatcher.inputs.value
        withTimeoutOrNull(3_000) { app.dispatcher.inputs.first { it != inputs } }
        listWarm = false
    }

    Box(Modifier.fillMaxSize()) {
        VideoBox(Modifier.fillMaxSize(), aspect = aspect)
        val ch = current
        if (ch != null) {
            if (listKept || overlay == Overlay.CHANNEL_LIST) {
                com.signal.ui.components.KeptPage(active = overlay == Overlay.CHANNEL_LIST, warm = listWarm) {
                    ChannelListOverlay(onClose = { overlay = Overlay.NONE }, onTuned = { bannerTick++ })
                }
            }
            when (overlay) {
                Overlay.INFO -> InfoBar(
                    channel = ch,
                    state = playerState,
                    focusOptions = infoFocusOptions,
                    lastInput = lastInput,
                    onClose = { overlay = Overlay.NONE },
                    onZap = ::zap,
                    onRecall = { app.playback.recall() },
                )
                Overlay.CHANNEL_LIST -> {} // Kept, above.
                Overlay.NONE -> if (bannerTick > 0) ZapBanner(ch, bannerTick, onOpenInfo = { overlay = Overlay.INFO })
            }
        }
    }
}

/** The bar's shared look: full width, a scrim token over the video, a short fade at its top edge, no blur. */
@Composable
internal fun OverlayBackground(
    modifier: Modifier = Modifier,
    scrim: Color = SignalTheme.colors.overlayScrim,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(Color.Transparent, scrim))))
        Box(Modifier.fillMaxWidth().background(scrim)) { content() }
    }
}

/** A slim strip after a channel change, about 3 seconds, no focus (PLAN §4.3.3 → Zap banner). */
@Composable
private fun ZapBanner(channel: Channel, tick: Int, onOpenInfo: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(tick) {
        visible = true
        delay(3000)
        visible = false
    }
    if (!visible) return
    RemoteHandler(priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        if (action == RemoteAction.Select) { onOpenInfo(); true } else false
    }
    val c = SignalTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        OverlayBackground {
            Row(Modifier.padding(horizontal = SignalSizes.EDGE_MARGIN.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                val numbers = showNumbers()
                if (numbers) SText(channel.number.toString(), 48, weight = FontWeight.ExtraBold)
                LogoTile(channel.displayName, channel.logoUrl, 96, 56, if (numbers) Modifier.padding(start = 32.dp) else Modifier)
                Column(Modifier.padding(start = 32.dp)) {
                    val app = LocalApp.current
                    val now = rememberNow()
                    val on = rememberNowAndNext(channel, now).first
                    SText(channel.displayName, 32, weight = FontWeight.ExtraBold)
                    SText(on?.let { "${it.title} · ${programTime(app, it, now)}" } ?: "No guide information", 24, color = c.muted)
                }
            }
        }
    }
}

/** The info bar (PLAN §4.3.3): full width along the bottom, about 40% of the height. */
@Composable
private fun InfoBar(
    channel: Channel,
    state: PlaybackState,
    focusOptions: Boolean,
    lastInput: Int,
    onClose: () -> Unit,
    onZap: (Int) -> Unit,
    onRecall: () -> Unit,
) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val firstOption = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val optionsRow = remember { FocusRequester() }

    LaunchedEffect(lastInput, app.panel.isOpen) {
        // A picker open in the side panel pauses the timer until it closes.
        if (app.panel.isOpen) return@LaunchedEffect
        delay(5000)
        onClose()
    }
    LaunchedEffect(Unit) { firstOption.requestFocusWhenReady() }
    LaunchedEffect(focusOptions) { if (focusOptions) optionsRow.requestFocusWhenReady() }

    RemoteHandler(priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        when (action) {
            RemoteAction.Back -> { onClose(); true }
            // Up/Down still change channel, and the bar updates for the new channel.
            RemoteAction.Up, RemoteAction.ChannelUp -> { onZap(-1); true }
            RemoteAction.Down, RemoteAction.ChannelDown -> { onZap(1); true }
            RemoteAction.PlayPause -> { onRecall(); true }
            // Left/Right move between options (PLAN §12.1); otherwise full screen would take them.
            RemoteAction.Left -> { focusManager.moveFocus(FocusDirection.Left); true }
            RemoteAction.Right -> { focusManager.moveFocus(FocusDirection.Right); true }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        OverlayBackground(scrim = SignalTheme.colors.infoBarScrim) {
            Column(Modifier.fillMaxWidth().height(400.dp).padding(horizontal = SignalSizes.EDGE_MARGIN.dp, vertical = 32.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.width(300.dp)) {
                        if (showNumbers()) SText(channel.number.toString(), 80, weight = FontWeight.ExtraBold)
                        LogoTile(channel.displayName, channel.logoUrl, 128, 72, Modifier.padding(top = 8.dp), onAccent = true)
                        SText(channel.displayName, 26, Modifier.padding(top = 12.dp), weight = FontWeight.SemiBold)
                    }
                    VDivider(Modifier.padding(horizontal = 40.dp))
                    Column(Modifier.weight(1f)) {
                        // Now and next from the guide (PLAN §4.3.3 → Info bar).
                        val now = rememberNow()
                        val (on, next) = rememberNowAndNext(channel, now)
                        SText(on?.let { "Now · ${programTime(app, it, now)}" } ?: "Now", 24, weight = FontWeight.SemiBold, color = c.accentText)
                        SText(on?.title ?: channel.displayName, 56, weight = FontWeight.ExtraBold)
                        if (on == null) {
                            SText("No information", 26, Modifier.padding(top = 8.dp), color = c.muted)
                        } else {
                            listOfNotNull(on.episodeText, on.subTitle).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { SText(it, 24, color = c.secondaryText) }
                            on.description?.let { SText(it, 24, Modifier.padding(top = 8.dp), color = c.muted, maxLines = 2) }
                            ProgressBar(((now - on.start).toFloat() / on.durationMs).coerceIn(0f, 1f), Modifier.padding(top = 12.dp).width(800.dp))
                        }
                        next?.let { SText("Next · ${programTime(app, it, now)} · ${it.title}", 22, Modifier.padding(top = 12.dp), color = c.muted) }
                    }
                    VDivider(Modifier.padding(horizontal = 40.dp))
                    Column(Modifier.width(520.dp)) {
                        ClockText(28, withDate = true)
                        Spacer(Modifier.height(16.dp))
                        // The decoded stream's actual size and codecs, not a provider label (PLAN §4.3.3).
                        state.video?.summary()?.forEach { SText(it, 24, color = c.secondaryText) }
                        state.selectedSubtitleTrackId?.let { id ->
                            state.subtitleTracks.firstOrNull { it.id == id }?.let { SText("Subtitles: ${it.label}", 24, color = c.secondaryText) }
                        }
                        if (channel.isFavorite) {
                            SText("Favorite", 22, Modifier.padding(top = 12.dp).background(c.tagBg).padding(horizontal = 12.dp, vertical = 4.dp), weight = FontWeight.SemiBold, color = c.tagText)
                        }
                    }
                }
                OptionsRow(channel, state, firstOption, optionsRow, onRecall)
            }
        }
    }
}

@Composable
private fun OptionsRow(channel: Channel, state: PlaybackState, first: FocusRequester, row: FocusRequester, onRecall: () -> Unit) {
    val app = LocalApp.current
    // A picker returns focus to the button that opened it: the row's focusRestorer doesn't restore on a
    // direct requestFocus(), so handing the panel the row put focus back on the first button.
    val options = buildList<Pair<String, (FocusRequester) -> Unit>> {
        add("Audio" to { back -> app.panel.open(audioPicker(app, channel, state), back) })
        add("Subtitles" to { back -> app.panel.open(subtitlePicker(app, channel, state), back) })
        add("Aspect ratio" to { back -> app.panel.open(aspectPicker(app, channel), back) })
        if (app.menus.linkedStreams(channel) != null) add("Stream" to { back -> app.panel.open(app.menus.streamPage(channel), back) })
        add((if (channel.isFavorite) "Remove favorite" else "Favorite") to { _ -> app.scope.launch { app.channels.setFavorite(channel, !channel.isFavorite) } })
        add("Add to playlist…" to { back -> app.panel.open(app.menus.addToPlaylistPage { listOf(channel) }, back) })
        add("Previous channel" to { _ -> onRecall() })
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp).focusRequester(row).focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        options.forEachIndexed { i, (label, action) ->
            val requester = if (i == 0) first else remember(label) { FocusRequester() }
            com.signal.ui.components.SButton(label, onSelect = { action(requester) }, focusRequester = requester)
        }
    }
}

private fun audioPicker(app: com.signal.app.AppController, channel: Channel, state: PlaybackState): PanelPage {
    val tracks = state.audioTracks
    return PanelPage.Actions(
        "Audio",
        if (tracks.isEmpty()) listOf(PanelAction("No other audio tracks") { null })
        else tracks.map { t -> PanelAction(t.label, detail = t.codec, selected = t.id == state.selectedAudioTrackId) { app.playback.selectAudio(channel, t.id); null } },
    )
}

private fun subtitlePicker(app: com.signal.app.AppController, channel: Channel, state: PlaybackState): PanelPage {
    return PanelPage.Actions(
        "Subtitles",
        listOf(PanelAction("Off", selected = state.selectedSubtitleTrackId == null) { app.playback.selectSubtitle(channel, null); null }) +
            state.subtitleTracks.map { t -> PanelAction(t.label, selected = t.id == state.selectedSubtitleTrackId) { app.playback.selectSubtitle(channel, t.id); null } },
    )
}

private fun aspectPicker(app: com.signal.app.AppController, channel: Channel): PanelPage {
    return PanelPage.Actions("Aspect ratio", AspectMode.entries.map { m ->
        PanelAction(m.label, selected = m == app.playback.aspect.value) { app.playback.setAspect(channel, m); null }
    })
}

/** The channel list's first focus: up to this many tries, each a few frames apart (about a second in all). */
private const val FOCUS_TRIES = 10
private const val FRAMES_PER_TRY = 6

/**
 * The channel list over playback (PLAN §4.3.3 → Channel list): the current group's channels first,
 * Left again for the groups. Up/Down move without tuning; OK tunes and makes that group current.
 */
@Composable
private fun ChannelListOverlay(onClose: () -> Unit, onTuned: () -> Unit) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val current by app.playback.current.collectAsState()
    val groups by app.groups.collectAsState()
    var shownGroup by remember { mutableStateOf(app.playback.currentGroup) }
    val channels = channelsOf(app, shownGroup)
    val channelFocus = remember { ListFocus() }
    val groupFocus = remember { ListFocus() }
    val channelState = rememberLazyListState()
    val groupState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Kept built while hidden (KeptPage): each opening starts on the playing channel's group, and only a
    // shown list takes focus.
    var opened by remember { mutableStateOf(0) }
    com.signal.ui.components.OnPageShown {
        shownGroup = app.playback.currentGroup
        opened++
    }
    // Closing only hides it: its focused row would keep focus, and OK would tune it. Give focus back to
    // the key layer, as when the list was taken away.
    com.signal.ui.components.OnPageHidden { focusManager.clearFocus(force = true) }
    val active = com.signal.ui.components.LocalPageActive.current
    LaunchedEffect(channels != null, shownGroup, opened, active) {
        if (!active) return@LaunchedEffect
        val list = channels ?: return@LaunchedEffect
        val i = list.indexOfFirst { it.stream.id == current?.stream?.id }.coerceAtLeast(0)
        if (list.isNotEmpty()) {
            channelState.requestScrollToItem(i)
            // A list built ahead and shown again can take longer than a row's own few frames to be
            // placed when the device (or a test run) is busy, and the row then gave up, leaving nothing
            // focused. Ask again until a row has it.
            repeat(FOCUS_TRIES) {
                if (channelFocus.hasFocus) return@LaunchedEffect
                channelFocus.focus(list[i].key)
                repeat(FRAMES_PER_TRY) { androidx.compose.runtime.withFrameNanos { } }
            }
        }
    }

    RemoteHandler(priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        when (action) {
            RemoteAction.Back -> { onClose(); true }
            // Up/Down move without tuning (PLAN §12.1): handled here, or full screen's handler would zap.
            RemoteAction.Up -> { focusManager.moveFocus(FocusDirection.Up); true }
            RemoteAction.Down -> { focusManager.moveFocus(FocusDirection.Down); true }
            RemoteAction.Right -> if (groupFocus.hasFocus) { runCatching { channelFocus.column.requestFocus() }; true } else { onClose(); true }
            RemoteAction.Left -> if (channelFocus.hasFocus) {
                scope.launch {
                    val i = groups.indexOfFirst { it.group == shownGroup }.coerceAtLeast(0)
                    groupState.requestScrollToItem(i)
                    groupFocus.focus(shownGroup.key)
                }
                true
            } else true
            RemoteAction.PlayPause, RemoteAction.ChannelUp, RemoteAction.ChannelDown, is RemoteAction.Digit -> true
            else -> false
        }
    }

    Row(Modifier.width(1240.dp).fillMaxHeight().background(c.bg)) {
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            SText("Groups", 24, Modifier.padding(start = 40.dp, top = 40.dp, bottom = 12.dp), weight = FontWeight.SemiBold, color = c.muted)
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { groupFocus.hasFocus = it.hasFocus }.focusRequester(groupFocus.column).focusRestorer(), state = groupState) {
                itemsIndexed(groups, key = { _, g -> g.group.key }) { _, entry ->
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(groupFocus.tick) { if (groupFocus.targetKey == entry.group.key) requester.requestFocusWhenReady() }
                    com.signal.ui.components.ListRow(
                        text = entry.group.title,
                        detail = entry.count.takeIf { it > 0 }?.toString(),
                        selected = entry.group == shownGroup,
                        focusRequester = requester,
                        // Picking a group swaps the list but doesn't change channel.
                        onSelect = {
                            shownGroup = entry.group
                        },
                    )
                }
            }
        }
        VDivider()
        Column(Modifier.fillMaxSize()) {
            SText("${shownGroup.title} · ${channels?.size ?: 0} channels", 24, Modifier.padding(start = 40.dp, top = 40.dp, bottom = 12.dp), weight = FontWeight.SemiBold, color = c.muted)
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { channelFocus.hasFocus = it.hasFocus }.focusRequester(channelFocus.column).focusRestorer(), state = channelState) {
                itemsIndexed(channels.orEmpty(), key = { _, ch -> ch.key }) { _, ch ->
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(channelFocus.tick) { if (channelFocus.targetKey == ch.key) requester.requestFocusWhenReady() }
                    val playing = ch.stream.id == current?.stream?.id
                    Focusable(
                        Modifier.fillMaxWidth(),
                        focusRequester = requester,
                        onSelect = {
                            app.playback.tune(ch, fullScreen = true, group = shownGroup)
                            onTuned()
                            onClose()
                        },
                        onOptions = { app.panel.open(app.menus.channelMenu(ch, shownGroup, null), channelFocus.column) },
                    ) { focused -> ChannelRow(ch, focused = focused, playing = playing) }
                }
            }
        }
    }
}

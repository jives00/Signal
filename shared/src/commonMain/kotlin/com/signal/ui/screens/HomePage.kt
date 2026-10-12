package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.Route
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.player.PlaybackState
import com.signal.ui.LocalApp
import com.signal.ui.components.Focusable
import com.signal.ui.components.HDivider
import com.signal.ui.components.LogoTile
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SText
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.VDivider
import com.signal.ui.components.VideoBox
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme

/**
 * Home (PLAN §4.3.1): the hero (the last played channel, playing with sound, plus its details) and
 * the Favorites row. Continue watching arrives with VOD (milestone 4), Reminders with the guide
 * (milestone 2).
 */
@Composable
fun HomePage() {
    val app = LocalApp.current
    val chrome = remember { PageChrome() }
    val current by app.playback.current.collectAsState()
    val playerState by app.platform.player.state.collectAsState()
    val favorites by remember { app.channels.channels(ChannelGroup.Favorites) }.collectAsState(null)

    LaunchedEffect(app.navigator.generation) {
        // The hero has focus and autoplays as soon as Home opens (PLAN §4.3.1 → Focus). Focus first:
        // finding the channel reads the database.
        chrome.focusContent()
        app.playback.startingChannel()?.let { app.playback.tune(it, fullScreen = false, group = groupFor(it)) }
    }
    DisposableEffect(Unit) {
        // Leaving Home stops the preview, except into full screen, which carries the player over.
        onDispose { if (app.navigator.current !is Route.FullScreen) app.playback.stop() }
    }

    TopLevelPage(Route.Home, chrome) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(400.dp)) {
                Focusable(
                    Modifier.width(900.dp).fillMaxHeight(),
                    focusRequester = chrome.contentFocus,
                    onSelect = {
                        val ch = current ?: return@Focusable
                        if (playerState.status == PlaybackState.Status.ERROR) app.playback.retry()
                        else app.navigator.go(Route.FullScreen(groupFor(ch)))
                    },
                    onOptions = { current?.let { ch -> app.panel.open(app.menus.channelMenu(ch, null, null), chrome.contentFocus) } },
                ) { focused ->
                    Box(Modifier.fillMaxSize().border(if (focused) SignalSizes.FOCUS_OUTLINE.dp else 0.dp, if (focused) SignalTheme.colors.accent else Color.Transparent)) {
                        VideoBox(Modifier.fillMaxSize().padding(if (focused) SignalSizes.FOCUS_OUTLINE.dp else 0.dp), compact = true, emptyText = "No channel yet")
                    }
                }
                VDivider()
                HeroDetails(current)
            }
            HDivider()
            FavoritesRow(favorites)
        }
    }
}

/** Full screen zaps within the group the channel was tuned from (PLAN §4.3.3 → Current group). */
internal fun groupFor(channel: Channel): ChannelGroup =
    if (channel.isFavorite) ChannelGroup.Favorites
    else ChannelGroup.Provider(channel.stream.sourceId, channel.stream.groupName, channel.stream.groupName)

@Composable
private fun HeroDetails(channel: Channel?) {
    val c = SignalTheme.colors
    Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 48.dp, end = SignalSizes.EDGE_MARGIN.dp, bottom = 48.dp)) {
        if (channel == null) {
            SText("Nothing playing", 48, weight = FontWeight.ExtraBold)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(channel.displayName, channel.logoUrl, 96, 56)
            SText("${channel.number}  ${channel.displayName}", 26, Modifier.padding(start = 24.dp), weight = FontWeight.SemiBold, color = c.accentText)
        }
        SText(channel.displayName, 64, Modifier.padding(top = 24.dp), weight = FontWeight.ExtraBold)
        // Program details arrive with the guide in milestone 2 (PLAN §4.3.1 → Right, the details panel).
        SText("No guide information", 26, Modifier.padding(top = 8.dp), color = c.muted)
    }
}

@Composable
private fun FavoritesRow(favorites: List<Channel>?) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val tileWidth = 268 // Six tiles across the 1920 canvas inside the 96 margins, 24 apart.
    Column(Modifier.fillMaxWidth()) {
        SText("Favorites", 28, Modifier.padding(start = SignalSizes.EDGE_MARGIN.dp, top = 32.dp, bottom = 16.dp), weight = FontWeight.ExtraBold)
        if (favorites == null) return@Column
        val firstTile = remember { FocusRequester() }
        LazyRow(
            // Entering the row lands on the first tile, then on the last one focused.
            Modifier.fillMaxWidth().focusRestorer(firstTile),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SignalSizes.EDGE_MARGIN.dp),
        ) {
            if (favorites.isEmpty()) {
                item {
                    Focusable(Modifier.width((tileWidth * 3).dp), onSelect = { app.navigator.go(Route.LiveTv) }) { focused ->
                        Column(Modifier.background(if (focused) c.accent else c.surface).padding(24.dp)) {
                            SText("Add favorites", 26, weight = FontWeight.ExtraBold, color = if (focused) c.onAccent else c.text)
                            SText("Long-press OK on any channel in Live TV and choose Add to Favorites.", 22, color = if (focused) c.onAccent else c.muted, maxLines = 2)
                        }
                    }
                }
            }
            itemsIndexed(favorites, key = { _, ch -> ch.key }) { i, ch ->
                val own = remember { FocusRequester() }
                val tile = if (i == 0) firstTile else own
                Focusable(
                    Modifier.width(tileWidth.dp),
                    focusRequester = tile,
                    onSelect = { app.playback.tune(ch, fullScreen = true, group = ChannelGroup.Favorites); app.navigator.go(Route.FullScreen(ChannelGroup.Favorites)) },
                    onOptions = { app.panel.open(app.menus.channelMenu(ch, ChannelGroup.Favorites, null), tile) },
                ) { focused ->
                    Column(Modifier.fillMaxWidth().background(if (focused) c.accent else c.surface).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LogoTile(ch.displayName, ch.logoUrl, 72, 48, onAccent = focused)
                            SText(ch.number.toString(), 20, Modifier.padding(start = 16.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.secondaryText)
                        }
                        SText(ch.displayName, 22, Modifier.padding(top = 12.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
                    }
                }
            }
            if (favorites.isNotEmpty()) {
                item {
                    Focusable(Modifier.width(tileWidth.dp), onSelect = {
                        app.liveTvGroup = ChannelGroup.Favorites
                        app.navigator.go(Route.LiveTv)
                    }) { focused ->
                        Box(Modifier.fillMaxWidth().height(124.dp).background(if (focused) c.accent else c.surface).padding(16.dp), contentAlignment = Alignment.CenterStart) {
                            SText("All favorites →", 22, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
                        }
                    }
                }
            }
        }
    }
}

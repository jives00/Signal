package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.Route
import com.signal.model.Channel
import kotlinx.datetime.toLocalDateTime
import com.signal.model.Program
import androidx.compose.runtime.produceState
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
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.theme.SignalSizes
import com.signal.ui.components.labelOf
import com.signal.ui.components.showNumbers
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch

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
        // The last played channel already playing (from Live TV or full screen) carries on: retuning
        // rebuffers and jumps. Anything else playing is a guide preview, which doesn't count (PLAN §4.3.1).
        val start = app.playback.startingChannel()
        val now = app.playback.current.value
        val playing = now != null && app.platform.player.state.value.status != com.signal.player.PlaybackState.Status.IDLE
        val carriesOn = playing && (start == null || now!!.isSameChannel(start))
        if (!carriesOn && start != null) app.playback.tune(start, fullScreen = false, group = groupFor(start))
    }
    DisposableEffect(Unit) {
        // Leaving Home stops the preview, except into a screen that shows video (Live TV, full screen),
        // which carries the player over. Continue watching starts a movie before going to the VOD player,
        // so that's left alone too.
        onDispose { if (!app.navigator.current.showsVideo && app.navigator.current != Route.VodPlayer) app.playback.stop() }
    }

    // Favorites' first tile: where focus goes when Continue watching empties under it.
    val favoritesFirst = remember { FocusRequester() }

    TopLevelPage(Route.Home, chrome) {
        // Scrolls: with Continue watching and Reminders, the rows can run past the screen.
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
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
            FavoritesRow(favorites, favoritesFirst)
            ContinueRow(onEmptied = { favoritesFirst.requestFocusWhenReady() })
            RemindersRow()
        }
    }
}

/** Full screen zaps within the group the channel was tuned from (PLAN §4.3.3 → Current group). */
/** The same stream, or another stream of the same user channel (after a failover). */
private fun Channel.isSameChannel(other: Channel): Boolean =
    stream.id == other.stream.id || (userChannelId != null && userChannelId == other.userChannelId)

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
            SText(labelOf(channel), 26, Modifier.padding(start = 24.dp), weight = FontWeight.SemiBold, color = c.accentText)
        }
        // The program on now, its time and what's next, and a progress bar on the bottom edge (handoff screen 1).
        val app = LocalApp.current
        val now = rememberNow()
        val (on, next) = rememberNowAndNext(channel, now)
        SText(on?.title ?: channel.displayName, 64, Modifier.padding(top = 24.dp), weight = FontWeight.ExtraBold)
        // "S2 E3 · Episode title" when the guide has them (milestone 6).
        on?.let { listOfNotNull(it.episodeText, it.subTitle).joinToString(" · ") }?.takeIf { it.isNotEmpty() }?.let {
            SText(it, 28, Modifier.padding(top = 4.dp), weight = FontWeight.SemiBold, color = c.secondaryText)
        }
        val line = listOfNotNull(on?.let { programTime(app, it, now) }, next?.let { "Next: ${it.title}" }).joinToString(" · ")
        SText(line.ifEmpty { "No guide information" }, 26, Modifier.padding(top = 8.dp), color = c.muted)
        if (on != null) {
            Spacer(Modifier.weight(1f))
            ProgressBar(((now - on.start).toFloat() / on.durationMs).coerceIn(0f, 1f))
        }
    }
}

/** A 6-unit progress bar: a neutral track with an accent fill (handoff global conventions). */
@Composable
internal fun ProgressBar(progress: Float, modifier: Modifier = Modifier, onAccent: Boolean = false) {
    val c = SignalTheme.colors
    Box(modifier.fillMaxWidth().height(6.dp).background(if (onAccent) c.onAccent.copy(alpha = 0.35f) else c.neutral300)) {
        Box(Modifier.fillMaxWidth(progress).height(6.dp).background(if (onAccent) c.onAccent else c.accent))
    }
}

@Composable
private fun FavoritesRow(favorites: List<Channel>?, firstTile: FocusRequester) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val tileWidth = 268 // Six tiles across the 1920 canvas inside the 96 margins, 24 apart.
    Column(Modifier.fillMaxWidth()) {
        SText("Favorites", 28, Modifier.padding(start = SignalSizes.EDGE_MARGIN.dp, top = 32.dp, bottom = 16.dp), weight = FontWeight.ExtraBold)
        if (favorites == null) return@Column
        LazyRow(
            // Entering the row lands on the first tile, then on the last one focused.
            Modifier.fillMaxWidth().focusRestorer(firstTile),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SignalSizes.EDGE_MARGIN.dp),
        ) {
            if (favorites.isEmpty()) {
                item {
                    Focusable(Modifier.width((tileWidth * 3).dp), focusRequester = firstTile, onSelect = { app.navigator.go(Route.LiveTv) }) { focused ->
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
                            if (showNumbers()) SText(ch.number.toString(), 20, Modifier.padding(start = 16.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.secondaryText)
                        }
                        SText(ch.displayName, 22, Modifier.padding(top = 12.dp), weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
                        // The program on now (handoff screen 1: "Favourites — on now").
                        val on = rememberNowAndNext(ch).first
                        SText(on?.title ?: "No guide information", 20, color = if (focused) c.onAccent else c.muted)
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

/**
 * The Reminders row (PLAN §4.3.1): soonest first, with programs airing now first. Hidden when there
 * are none. OK on an "On now" card tunes full screen; on a future card, and long-press on any,
 * it opens program details.
 */
@Composable
private fun RemindersRow() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val reminders by app.reminderList.collectAsState()
    val now = rememberNow()
    val shown = reminders.filter { it.stop > now }.sortedWith(compareBy({ !(it.start <= now) }, { it.start }))
    if (shown.isEmpty()) return
    val tileWidth = 420
    Column(Modifier.fillMaxWidth()) {
        SText("Reminders", 28, Modifier.padding(start = SignalSizes.EDGE_MARGIN.dp, top = 32.dp, bottom = 16.dp), weight = FontWeight.ExtraBold)
        val first = remember { FocusRequester() }
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(first),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SignalSizes.EDGE_MARGIN.dp),
        ) {
            itemsIndexed(shown, key = { _, r -> r.id }) { i, r ->
                val own = remember { FocusRequester() }
                val tile = if (i == 0) first else own
                // The channel, and the program as the guide has it now; null when a new guide dropped it.
                val channel by produceState<Channel?>(null, r.userChannelId) { value = app.channels.channelByKey(r.userChannelId) }
                val program by produceState<Program?>(null, channel, r.start) {
                    // The window also catches the program before, which can share the title (a series back to back).
                    value = channel?.let { ch -> app.guide.programs(ch, r.start - 60_000, r.stop).filter { it.title == r.title }.minByOrNull { kotlin.math.abs(it.start - r.start) } }
                }
                val onNow = r.start <= now
                fun details() {
                    val ch = channel ?: return
                    val p = program ?: Program(0, "", "", r.start, r.stop, r.title)
                    app.panel.open(programDetails(app, ch, p, now), tile)
                }
                Focusable(
                    Modifier.width(tileWidth.dp),
                    focusRequester = tile,
                    onSelect = {
                        val ch = channel
                        if (onNow && ch != null) app.watchReminder(com.signal.app.AppController.ActiveReminder(r, ch)) else details()
                    },
                    onOptions = ::details,
                ) { focused ->
                    val ink = if (focused) c.onAccent else c.text
                    val muted = if (focused) c.onAccent else c.muted
                    Column(Modifier.fillMaxWidth().background(if (focused) c.accent else c.surface).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            channel?.let { LogoTile(it.displayName, it.logoUrl, 72, 48, onAccent = focused) }
                            SText(channel?.displayName.orEmpty(), 20, Modifier.padding(start = 16.dp), weight = FontWeight.SemiBold, color = muted)
                        }
                        SText(r.title, 24, Modifier.padding(top = 12.dp), weight = FontWeight.SemiBold, color = ink)
                        val whenText = when {
                            channel != null && program == null && !onNow -> "Not in guide any more"
                            onNow -> "On now"
                            r.start - now < 60 * 60_000L -> "Starts in ${(r.start - now + 59_999) / 60_000} min"
                            else -> "${reminderDay(r.start, now)} ${localStart(app, r.start)}"
                        }
                        SText(whenText, 20, color = muted)
                        if (onNow) ProgressBar(((now - r.start).toFloat() / (r.stop - r.start)).coerceIn(0f, 1f), Modifier.padding(top = 8.dp), onAccent = focused)
                    }
                }
            }
        }
    }
}

private fun localStart(app: com.signal.app.AppController, ms: Long): String {
    val t = kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    return com.signal.ui.components.formatTime(t.hour, t.minute, use24(app))
}

/** "Today", "Tonight" (from 5 PM), "Tomorrow" or a weekday, as the Reminders row says it (PLAN §4.3.1). */
private fun reminderDay(ms: Long, now: Long): String {
    val day = dayLabel(ms, now)
    val hour = kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).hour
    return if (day == "Today" && hour >= 17) "Tonight" else day
}

/**
 * Continue watching (PLAN §4.3.1): started movies and episodes, and next up. OK plays at once from
 * the saved position (next up from the start); long-press opens details, Mark as watched and Remove.
 * Hidden when empty.
 *
 * Removing a card, or marking it watched, deletes the focused element. Nothing would hold focus
 * then, and the remote went dead (user, 2026-10-13), so focus moves to the card now in its place,
 * or [onEmptied] when the row goes.
 */
@Composable
private fun ContinueRow(onEmptied: suspend () -> Unit) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val cards by app.continueWatching.collectAsState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    // The card a menu action is taking away, and where it was.
    var leaving by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(cards, leaving) {
        val (key, index) = leaving ?: return@LaunchedEffect
        if (cards.any { it.key == key }) return@LaunchedEffect
        leaving = null
        if (cards.isEmpty()) return@LaunchedEffect onEmptied()
        // Wait for the row's next layout, which drops the removed card and swaps the card now in
        // first place to the row's first requester. Focus asked for before that was lost with it.
        val target = cards[index.coerceAtMost(cards.lastIndex)].key
        repeat(10) {
            withFrameNanos { }
            if (requesters[target]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return@LaunchedEffect
        }
    }
    if (cards.isEmpty()) return
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth()) {
        SText("Continue watching", 28, Modifier.padding(start = SignalSizes.EDGE_MARGIN.dp, top = 32.dp, bottom = 16.dp), weight = FontWeight.ExtraBold)
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(first),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SignalSizes.EDGE_MARGIN.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.key }) { i, card ->
                val own = remember { FocusRequester() }
                val tile = if (i == 0) first else own
                requesters[card.key] = tile
                fun play() {
                    app.scope.launch {
                        val e = card.episode
                        if (e != null) {
                            val detail = app.vodDetails.series(card.item) ?: return@launch
                            app.vodPlayback.unattendedEpisodes = 0
                            app.vodPlayback.playEpisode(card.item, detail, e)
                        } else {
                            app.vodPlayback.playMovie(card.item)
                        }
                        app.navigator.go(Route.VodPlayer)
                    }
                }
                Focusable(
                    Modifier.width(326.dp),
                    focusRequester = tile,
                    onSelect = ::play,
                    onOptions = { app.panel.open(continueMenu(app, card) { leaving = card.key to i }, tile) },
                ) { focused ->
                    Column(Modifier.fillMaxWidth().background(if (focused) c.accent else Color.Transparent)) {
                        Box(Modifier.fillMaxWidth().height(150.dp).background(c.neutral300), contentAlignment = Alignment.BottomStart) {
                            val image = card.episode?.stillUrl ?: card.item.posterUrl
                            if (image != null) coil3.compose.AsyncImage(model = image, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else SText(card.title, 24, Modifier.align(Alignment.Center).padding(16.dp), weight = FontWeight.ExtraBold, color = c.secondaryText, maxLines = 2)
                            if (card.fraction != null) com.signal.ui.components.ProgressBar(card.fraction, height = 6, onAccent = focused)
                            else SText("Next up", 20, Modifier.background(c.tagBg).padding(horizontal = 10.dp, vertical = 2.dp), weight = FontWeight.SemiBold, color = c.tagText)
                        }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            SText(card.title, 22, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text, maxLines = 1)
                            card.subtitle?.let { SText(it, 20, color = if (focused) c.onAccent else c.muted, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

/** Long-press on a Continue watching card (PLAN §4.3.1). */
private fun continueMenu(app: com.signal.app.AppController, card: com.signal.data.ContinueWatching.Card, onLeaving: () -> Unit): com.signal.ui.panel.PanelPage =
    com.signal.ui.panel.PanelPage.Actions(card.title, listOf(
        com.signal.ui.panel.PanelAction("Go to details") {
            app.navigator.go(if (card.episode != null) Route.SeriesDetail(card.item.id) else Route.MovieDetail(card.item.id))
            null
        },
        com.signal.ui.panel.PanelAction("Mark as watched") {
            onLeaving()
            app.scope.launch {
                val e = card.episode
                app.watch.markWatched(card.key, if (e != null) "EPISODE" else "MOVIE", card.item.id, card.seriesKey, e?.season, e?.episode)
            }
            null
        },
        com.signal.ui.panel.PanelAction("Remove from Continue watching") {
            onLeaving()
            app.scope.launch { card.seriesKey?.let { app.watch.hideSeries(it) } ?: app.watch.clearPosition(card.key) }
            null
        },
        // Reset (milestone 6): a movie unwatched from the start, or the whole series.
        com.signal.ui.panel.PanelAction(if (card.seriesKey != null) "Reset series" else "Reset", detail = if (card.seriesKey != null) "Every episode unwatched" else "Unwatched, from the start") {
            onLeaving()
            app.scope.launch { card.seriesKey?.let { app.watch.resetSeries(it) } ?: app.watch.markUnwatched(card.key) }
            null
        },
    ))

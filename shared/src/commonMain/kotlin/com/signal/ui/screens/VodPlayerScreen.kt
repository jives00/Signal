package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.Route
import com.signal.app.VodPlayback
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.input.RemoteDispatcher
import com.signal.input.RemoteHandler
import com.signal.player.AspectMode
import com.signal.player.PlaybackPosition
import com.signal.ui.LocalApp
import com.signal.ui.components.ProgressBar
import com.signal.ui.components.SButton
import com.signal.ui.components.SText
import com.signal.ui.components.VideoBox
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.menus.clockTime
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Seconds of the end at which the autoplay card appears, without a credits marker (PLAN §4.3.9). */
private const val NEXT_CARD_BEFORE_END_MS = 30_000L
private const val NEXT_COUNTDOWN_S = 10
/** Autoplay asks "Still watching?" after this many episodes with no key pressed. */
private const val STILL_WATCHING_AFTER = 3

/**
 * Movie and episode playback (PLAN §4.3.10): video only by default; OK shows the controls (title,
 * progress, Audio · Subtitles · Aspect ratio), which hide after 5 seconds. Left/Right seek 10
 * seconds, faster while held; Rewind/Fast-forward 30. Back saves the position and returns. Near an
 * episode's end the autoplay card offers the next one (PLAN §4.3.9).
 */
@Composable
fun VodPlayerScreen() {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val now by app.vodPlayback.current.collectAsState()
    val state by app.platform.player.state.collectAsState()
    var position by remember { mutableStateOf(PlaybackPosition(0, 0)) }
    var controls by remember { mutableStateOf(false) }
    var focusOptions by remember { mutableStateOf(false) }
    // The controls shown on their own as a video starts: the first key isn't theirs, so Back still leaves.
    var peek by remember { mutableStateOf(false) }
    var lastInput by remember { mutableStateOf(0L) }
    var aspect by remember { mutableStateOf(AspectMode.FIT) }
    // Holding Left/Right: presses arriving close together seek further each time (PLAN §4.3.10 → scrub).
    var lastSeekAt by remember { mutableStateOf(0L) }
    var seekRun by remember { mutableStateOf(0) }
    // The autoplay card: null hidden, else seconds left, or -1 for "Still watching?".
    var nextCard by remember { mutableStateOf<Int?>(null) }
    var nextDismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val autoplay = app.settings.bool(Settings.Key.AUTOPLAY_NEXT, true)

    fun input() {
        lastInput = Clock.System.now().toEpochMilliseconds()
        app.vodPlayback.unattendedEpisodes = 0
    }

    fun back() {
        app.vodPlayback.stop()
        app.navigator.back()
    }

    fun playNext() {
        val n = now ?: return
        val next = n.next ?: return
        val series = n.series ?: return
        nextCard = null
        nextDismissed = false
        app.vodPlayback.finished()
        scope.launch { app.vodPlayback.playEpisode(n.item, series, next) }
    }

    fun seek(byMs: Long) {
        val t = Clock.System.now().toEpochMilliseconds()
        seekRun = if (t - lastSeekAt < 400) seekRun + 1 else 0
        lastSeekAt = t
        val step = byMs * when {
            seekRun < 3 -> 1
            seekRun < 6 -> 3
            seekRun < 10 -> 6
            else -> 18
        }
        val pos = app.platform.player.position()
        val target = (pos.positionMs + step).coerceIn(0, (pos.durationMs - 1_000).coerceAtLeast(0))
        app.platform.player.seekTo(target)
        position = pos.copy(positionMs = target)
        controls = true
        input()
    }

    // The position, for the progress bar and the autoplay card.
    LaunchedEffect(Unit) {
        while (true) {
            position = app.platform.player.position()
            delay(500)
        }
    }
    LaunchedEffect(controls, lastInput, state.paused, app.panel.isOpen) {
        // Paused, or a picker open: the controls stay (PLAN §4.3.10).
        if (!controls || state.paused || app.panel.isOpen) return@LaunchedEffect
        delay(5_000)
        controls = false
        focusOptions = false
    }

    // Autoplay: the card near the end of an episode with a next one (PLAN §4.3.9).
    val n = now
    // A new episode starts fresh: the last one's near-the-end position mustn't start its countdown.
    LaunchedEffect(n?.key) {
        position = PlaybackPosition(0, 0)
        nextCard = null
        nextDismissed = false
        // A movie or episode starting shows the controls (title, progress) for 5 seconds (user, 2026-10-16).
        if (n != null) {
            controls = true
            focusOptions = false
            peek = true
            input()
        }
    }
    val remaining = position.durationMs - position.positionMs
    val nearEnd = position.durationMs > 0 && position.positionMs > 0 && remaining in 1..NEXT_CARD_BEFORE_END_MS
    LaunchedEffect(n?.key, nearEnd) {
        if (!nearEnd) {
            // Seeked back out of the last 30 seconds: the countdown goes (a "Still watching?" stays).
            if (nextCard != null && nextCard != -1) nextCard = null
            return@LaunchedEffect
        }
        if (n?.next == null || !autoplay || nextDismissed) return@LaunchedEffect
        if (app.vodPlayback.unattendedEpisodes >= STILL_WATCHING_AFTER) {
            nextCard = -1
            return@LaunchedEffect
        }
        // The card takes the corner; the controls step aside so it never covers them.
        controls = false
        focusOptions = false
        for (s in NEXT_COUNTDOWN_S downTo 1) {
            nextCard = s
            delay(1_000)
            if (nextCard == null) return@LaunchedEffect
        }
        app.vodPlayback.unattendedEpisodes++
        playNext()
    }

    // The end: an episode with autoplay goes on; otherwise it's marked watched and Back to the page.
    LaunchedEffect(state.ended) {
        if (!state.ended) return@LaunchedEffect
        val cur = app.vodPlayback.current.value ?: return@LaunchedEffect
        if (cur.next != null && autoplay && !nextDismissed && nextCard != -1) {
            app.vodPlayback.unattendedEpisodes++
            playNext()
        } else {
            app.vodPlayback.finished()
            app.vodPlayback.stop()
            app.navigator.back()
        }
    }

    // No background here: the video draws in the app-wide layer underneath (VideoLayer).
    Box(Modifier.fillMaxSize()) {
        VideoBox(Modifier.fillMaxSize(), aspect = aspect, errorTitle = "Can't play this", retryHint = "Press OK to retry · Back to return")

        if (controls && n != null) Controls(n, position, state.paused, focusOptions, onAspect = { aspect = it }, onInput = ::input)

        nextCard?.let { s ->
            NextCard(
                text = if (s < 0) "Still watching?" else "Next episode in ${s}s",
                detail = n?.next?.let { "${it.code} · ${it.title}" }.orEmpty(),
                stillWatching = s < 0,
                onPlay = { input(); playNext() },
                onCancel = { input(); nextCard = null; nextDismissed = true },
            )
        }
    }

    RemoteHandler { action ->
        if (nextCard != null) return@RemoteHandler false
        val peeked = peek
        peek = false
        when (action) {
            RemoteAction.PlayPause -> {
                val paused = !state.paused
                app.vodPlayback.setPaused(paused)
                controls = true
                input()
                true
            }
            RemoteAction.Left -> if (controls && focusOptions) false else { seek(-10_000); true }
            RemoteAction.Right -> if (controls && focusOptions) false else { seek(10_000); true }
            RemoteAction.Rewind -> { seek(-30_000); true }
            RemoteAction.FastForward -> { seek(30_000); true }
            RemoteAction.Select -> {
                if (state.status == com.signal.player.PlaybackState.Status.ERROR) { app.platform.player.retry(); true }
                else if (!controls) { controls = true; focusOptions = false; input(); true } else false
            }
            RemoteAction.Options -> { controls = true; focusOptions = true; input(); true }
            RemoteAction.Up, RemoteAction.Down -> {
                // Nothing while hidden (PLAN §4.3.10: no accidental jumps); between progress and options when shown.
                if (controls) { focusOptions = action == RemoteAction.Down; input() }
                true
            }
            RemoteAction.Back -> {
                if (controls && !peeked) { controls = false; focusOptions = false } else back()
                true
            }
            else -> false
        }
    }
}

@Composable
private fun Controls(n: VodPlayback.Now, position: PlaybackPosition, paused: Boolean, focusOptions: Boolean, onAspect: (AspectMode) -> Unit, onInput: () -> Unit) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    val firstOption = remember { FocusRequester() }
    val returnTo = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusOptions) { if (focusOptions) firstOption.requestFocusWhenReady() }

    RemoteHandler(priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        if (!focusOptions) return@RemoteHandler false
        when (action) {
            RemoteAction.Left -> { focusManager.moveFocus(FocusDirection.Left); onInput(); true }
            RemoteAction.Right -> { focusManager.moveFocus(FocusDirection.Right); onInput(); true }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        OverlayBackground {
            Column(Modifier.fillMaxWidth().padding(horizontal = SignalSizes.EDGE_MARGIN.dp, vertical = 32.dp)) {
                SText(n.title, 48, weight = FontWeight.ExtraBold, maxLines = 1)
                n.subtitle?.let { SText(it, 26, Modifier.padding(top = 4.dp), color = c.secondaryText, maxLines = 1) }
                if (paused) SText("Paused", 24, Modifier.padding(top = 8.dp), color = c.accentText, weight = FontWeight.SemiBold)
                ProgressBar(if (position.durationMs > 0) position.positionMs.toFloat() / position.durationMs else 0f, Modifier.padding(top = 20.dp), height = 8)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    SText(clockTime(position.positionMs), 24, weight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    SText("${clockTime(position.durationMs)} · ${clockTime((position.durationMs - position.positionMs).coerceAtLeast(0))} left", 24, color = c.muted)
                }
                if (focusOptions) {
                    val player = app.platform.player
                    val s = player.state.value
                    Row(Modifier.padding(top = 24.dp).focusRequester(returnTo).focusRestorer(firstOption), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SButton("Audio", {
                            app.panel.open(PanelPage.Actions("Audio",
                                if (s.audioTracks.isEmpty()) listOf(PanelAction("No other audio tracks") { null })
                                else s.audioTracks.map { t -> PanelAction(t.label, detail = t.codec, selected = t.id == s.selectedAudioTrackId) { player.selectAudioTrack(t.id); null } }), returnTo)
                        }, focusRequester = firstOption)
                        SButton("Subtitles", {
                            app.panel.open(PanelPage.Actions("Subtitles",
                                listOf(PanelAction("Off", selected = s.selectedSubtitleTrackId == null) { player.selectSubtitleTrack(null); null }) +
                                    s.subtitleTracks.map { t -> PanelAction(t.label, selected = t.id == s.selectedSubtitleTrackId) { player.selectSubtitleTrack(t.id); null } }), returnTo)
                        })
                        SButton("Aspect ratio", {
                            app.panel.open(PanelPage.Actions("Aspect ratio", AspectMode.entries.map { m -> PanelAction(m.label) { onAspect(m); null } }), returnTo)
                        })
                    }
                } else {
                    SText("Long-press OK for Audio, Subtitles and Aspect ratio", 22, Modifier.padding(top = 16.dp), color = c.muted)
                }
            }
        }
    }
}

/** The autoplay card (PLAN §4.3.9), in the corner: OK plays now, Back cancels. */
@Composable
private fun NextCard(text: String, detail: String, stillWatching: Boolean, onPlay: () -> Unit, onCancel: () -> Unit) {
    val c = SignalTheme.colors
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { play.requestFocusWhenReady() }
    RemoteHandler(priority = RemoteDispatcher.Priority.OVERLAY) { action ->
        when (action) {
            RemoteAction.Back -> { onCancel(); true }
            RemoteAction.Left, RemoteAction.Right, RemoteAction.Select -> false
            else -> true
        }
    }
    Box(Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.width(640.dp).background(c.bg.copy(alpha = 0.92f)).padding(32.dp)) {
            SText(text, 32, weight = FontWeight.ExtraBold)
            if (detail.isNotEmpty()) SText(detail, 24, Modifier.padding(top = 8.dp), color = c.secondaryText, maxLines = 1)
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SButton(if (stillWatching) "Keep watching" else "Play now", onPlay, focusRequester = play)
                SButton(if (stillWatching) "Stop" else "Cancel", onCancel)
            }
        }
    }
}

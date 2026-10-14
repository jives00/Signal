package com.signal.ui.components

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.ui.LocalApp
import com.signal.ui.screens.updatePanel
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import com.signal.ui.theme.SignalType
import com.signal.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Focus state shared between a top-level page and its nav bar. */
@Stable
class PageChrome {
    val navFocus = FocusRequester()
    val contentFocus = FocusRequester()
    var navHasFocus by mutableStateOf(false)
        internal set
    /** Live TV: set to show the hidden nav bar and move focus to it. */
    var revealNav by mutableStateOf(false)

    fun focusNav() {
        revealNav = true
        runCatching { navFocus.requestFocus() }
    }

    fun focusContent() {
        runCatching { contentFocus.requestFocus() }
    }
}

/**
 * A page on the nav bar (PLAN §4.1) and the Back rule for it (PLAN §4.2): Back in the content goes
 * to the nav bar; on the nav bar it goes Home; on Home's nav bar it exits straight away.
 *
 * @param hideNav Live TV hides the bar so the guide gets the full height (PLAN §4.1). The page
 * reveals it with [PageChrome.focusNav] (Up from the top row); Back reveals it too.
 */
@Composable
fun TopLevelPage(
    route: Route,
    chrome: PageChrome,
    hideNav: Boolean = false,
    content: @Composable () -> Unit,
) {
    val app = LocalApp.current
    val showNav = !hideNav || chrome.revealNav || chrome.navHasFocus
    val focusManager = LocalFocusManager.current

    RemoteHandler { action ->
        when (action) {
            RemoteAction.Back -> {
                when {
                    !chrome.navHasFocus -> chrome.focusNav()
                    route != Route.Home -> app.navigator.go(Route.Home)
                    else -> app.platform.exitApp()
                }
                true
            }
            // Down from the bar goes into the page, wherever the bar's focus is.
            RemoteAction.Down -> if (chrome.navHasFocus) { chrome.focusContent(); true } else false
            // Up into the bar lands on the current page's item, not whichever item is nearest.
            RemoteAction.Up -> if (!chrome.navHasFocus && focusManager.moveFocus(FocusDirection.Up)) {
                if (chrome.navHasFocus) runCatching { chrome.navFocus.requestFocus() }
                true
            } else false
            else -> false
        }
    }

    // Revealing the bar composes it first; focus it once it exists.
    LaunchedEffect(chrome.revealNav, showNav) {
        if (chrome.revealNav && showNav) runCatching { chrome.navFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        UpdateBanner()
        if (showNav) {
            NavBar(route, chrome)
            HDivider()
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun NavBar(current: Route, chrome: PageChrome) {
    val app = LocalApp.current
    val c = SignalTheme.colors
    // Movies and Series only when a source has some (PLAN §4.1).
    val movies by app.movieCount.collectAsState()
    val series by app.seriesCount.collectAsState()
    val items = buildList {
        add("Home" to Route.Home)
        add("Live TV" to Route.LiveTv)
        if (movies > 0) add("Movies" to Route.Movies)
        if (series > 0) add("Series" to Route.Series)
        add("Search" to Route.Search)
        add("Settings" to Route.Settings)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(SignalSizes.NAV_BAR_HEIGHT.dp)
            .padding(horizontal = SignalSizes.EDGE_MARGIN.dp)
            .onFocusChanged { state ->
                // Only a real loss of focus hides a revealed bar; the first report on appearing is "unfocused".
                if (chrome.navHasFocus && !state.hasFocus) chrome.revealNav = false
                chrome.navHasFocus = state.hasFocus
            }
            .focusRestorer(chrome.navFocus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        Spacer(Modifier.width(48.dp))
        items.forEach { (label, route) ->
            Focusable(
                Modifier.padding(end = 12.dp),
                onSelect = { if (route != current) app.navigator.go(route) else chrome.focusContent() },
                focusRequester = if (route == current) chrome.navFocus else null,
            ) { focused ->
                Box(Modifier.background(if (focused) c.accent else Color.Transparent).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    SText(
                        label, SignalType.NAV, weight = FontWeight.SemiBold,
                        color = when {
                            focused -> c.onAccent
                            route == current -> c.text
                            else -> c.muted
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ClockText(SignalType.NAV)
    }
}

@Composable
fun Wordmark() {
    val c = SignalTheme.colors
    BasicText(
        buildAnnotatedString {
            append("SIGNAL")
            withStyle(SpanStyle(color = c.accent)) { append(".") }
        },
        style = TextStyle(color = c.text, fontSize = SignalType.WORDMARK.sp, fontWeight = FontWeight.ExtraBold, fontFamily = SignalTheme.font),
    )
}

/** The clock, in the 12/24-hour setting; defaults to the device's own format (PLAN §4.1). */
@Composable
fun ClockText(size: Int, modifier: Modifier = Modifier, withDate: Boolean = false, color: Color = SignalTheme.colors.text) {
    val app = LocalApp.current
    // To the minute, so it recomposes once a minute; and not at all on a hidden kept-alive page,
    // where each page's clock was ticking every second (PLAN §8 → milestone 5).
    var now by remember { mutableStateOf(nowMinute()) }
    val active = LocalPageActive.current
    LaunchedEffect(active) {
        while (active) {
            now = nowMinute()
            delay(60_000L - Clock.System.now().toEpochMilliseconds() % 60_000L)
        }
    }
    val use24 = app.settings.bool(Settings.Key.CLOCK_24H, app.platform.systemUses24HourClock())
    val date = if (!withDate) "" else {
        val day = now.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val month = now.date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        " · $day ${now.date.day} $month"
    }
    SText(formatTime(now.hour, now.minute, use24) + date, size, modifier, weight = FontWeight.SemiBold, color = color)
}

fun nowLocal(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

private fun nowMinute(): LocalDateTime = nowLocal().let { LocalDateTime(it.year, it.month, it.day, it.hour, it.minute) }

fun formatTime(hour: Int, minute: Int, use24: Boolean): String = if (use24) {
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
} else {
    val h = if (hour % 12 == 0) 12 else hour % 12
    "$h:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

/**
 * "Update available" (PLAN §4.3.15 → Auto-updater): at the top of the nav bar pages, never over
 * playback, never taking focus by itself. Up from the nav bar reaches it.
 */
@Composable
fun UpdateBanner() {
    val app = LocalApp.current
    val updater = app.platform.updater ?: return
    val state by updater.state.collectAsState()
    val c = SignalTheme.colors
    // The update panel hands focus back here when it closes.
    val banner = remember { FocusRequester() }
    val text = when (val s = state) {
        is UpdateState.Available -> "Update available: ${s.tag.removePrefix("apk-")}. Press OK to see what's new."
        is UpdateState.Downloading -> "Downloading ${s.tag.removePrefix("apk-")}… ${(s.progress * 100).toInt()}%"
        is UpdateState.Failed -> "Update failed: ${s.message}. Press OK to try again."
        UpdateState.None -> return
    }
    Focusable(Modifier.fillMaxWidth(), focusRequester = banner, onSelect = {
        when (val s = state) {
            is UpdateState.Available -> app.panel.open(updatePanel(app, s), returnTo = banner)
            is UpdateState.Failed -> updater.install()
            else -> {}
        }
    }) { focused ->
        Box(Modifier.fillMaxWidth().background(if (focused) c.accent600 else c.accent).padding(horizontal = SignalSizes.EDGE_MARGIN.dp, vertical = 14.dp)) {
            SText(text, 24, weight = FontWeight.SemiBold, color = c.onAccent)
        }
    }
}

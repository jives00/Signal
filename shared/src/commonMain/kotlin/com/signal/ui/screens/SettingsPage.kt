package com.signal.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.model.ChannelGroup
import com.signal.model.ChannelSort
import com.signal.model.GroupSort
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.ui.LocalApp
import com.signal.ui.components.ListRow
import com.signal.ui.components.PageChrome
import com.signal.ui.components.SText
import com.signal.ui.components.SectionTitle
import com.signal.ui.components.TopLevelPage
import com.signal.ui.components.VDivider
import com.signal.ui.components.formatTime
import com.signal.ui.components.requestFocusWhenReady
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.theme.SignalTheme
import com.signal.update.UpdateState
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private enum class Section(val title: String) {
    SOURCES("Sources"),
    CHANNELS("Channels & groups"),
    APPEARANCE("Appearance"),
    ABOUT("About & updates"),
}

/**
 * Settings on the Shield (PLAN §4.3.15): section names on the left, that section's settings on
 * the right. Milestone 1 has the sections the app can already use; the rest join with their
 * features (Live TV & guide, Movies & Series, Playback, NAS & sync, Guide data).
 */
@Composable
fun SettingsPage() {
    val app = LocalApp.current
    val chrome = remember { PageChrome() }
    var section by remember { mutableStateOf(Section.SOURCES) }
    var editing by remember { mutableStateOf<Source?>(null) }
    var adding by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val firstSection = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var sectionsHaveFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The section's rows may still be composing when focus moves in.
    fun focusSection() { scope.launch { contentFocus.requestFocusWhenReady() } }

    LaunchedEffect(Unit) { chrome.focusContent() }

    TopLevelPage(Route.Settings, chrome) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(400.dp).fillMaxHeight().onFocusChanged { sectionsHaveFocus = it.hasFocus }.focusRequester(chrome.contentFocus).focusRestorer(firstSection)) {
                Box(Modifier.padding(top = 24.dp))
                Section.entries.forEach { s ->
                    ListRow(s.title, selected = s == section, onSelect = { section = s; adding = false; editing = null; focusSection() }, onFocused = { section = s; adding = false; editing = null }, focusRequester = if (s == Section.entries.first()) firstSection else null)
                }
            }
            VDivider()
            Box(Modifier.fillMaxSize().padding(start = 48.dp, top = 24.dp).onFocusChanged { contentHasFocus = it.hasFocus }.focusRequester(contentFocus).focusRestorer()) {
                when {
                    adding || editing != null -> SourceForm(editing, onDone = { adding = false; editing = null }, onCancel = { adding = false; editing = null })
                    section == Section.SOURCES -> SourcesSection(app, onAdd = { adding = true }, onEdit = { editing = it }, returnTo = contentFocus)
                    section == Section.CHANNELS -> ChannelsSection(app, contentFocus)
                    section == Section.APPEARANCE -> AppearanceSection(app, contentFocus)
                    section == Section.ABOUT -> AboutSection(app)
                }
            }
        }
    }

    // Sections ↔ content, and Back goes up a level before the nav bar (PLAN §12.1). Registered after
    // TopLevelPage's handler, so it's asked first.
    val inForm = adding || editing != null
    RemoteHandler { action ->
        when {
            action == RemoteAction.Right && sectionsHaveFocus -> { focusSection(); true }
            action == RemoteAction.Left && contentHasFocus && !inForm -> { chrome.focusContent(); true }
            action == RemoteAction.Back && inForm -> {
                adding = false
                editing = null
                focusSection()
                true
            }
            action == RemoteAction.Back && contentHasFocus -> { chrome.focusContent(); true }
            else -> false
        }
    }
}

@Composable
private fun SourcesSection(app: AppController, onAdd: () -> Unit, onEdit: (Source) -> Unit, returnTo: FocusRequester) {
    val sources by app.sourceList.collectAsState()
    val c = SignalTheme.colors
    LazyColumn(Modifier.width(1100.dp)) {
        item { SectionTitle("Sources") }
        items(sources, key = { it.id }) { s ->
            val count by produceState(0L, s.lastRefreshedAt) { value = app.db.streamQueries.countForSource(s.id).executeAsOne() }
            val detail = when {
                app.refreshing == s.id -> "Refreshing…"
                s.lastError != null -> "Error: ${s.lastError}"
                !s.enabled -> "Disabled"
                else -> "$count channels · ${refreshedLabel(app, s.lastRefreshedAt)}"
            }
            ListRow("${s.name} · ${kindLabel(s.kind)}", detail = detail, onSelect = { app.panel.open(sourceActions(app, s, onEdit), returnTo) })
        }
        item { ListRow("Add source", detail = "M3U or Xtream Codes", onSelect = onAdd) }
        item { SText("EPG sources arrive with the guide in milestone 2.", 22, Modifier.padding(40.dp), color = c.muted) }
    }
}

private fun kindLabel(kind: SourceKind) = when (kind) {
    SourceKind.M3U -> "M3U"
    SourceKind.XTREAM -> "Xtream"
    SourceKind.DEMO -> "Demo"
}

private fun refreshedLabel(app: AppController, at: Long): String {
    if (at == 0L) return "never refreshed"
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault())
    return "refreshed ${t.date} ${formatTime(t.hour, t.minute, app.settings.bool(Settings.Key.CLOCK_24H, app.platform.systemUses24HourClock()))}"
}

private fun sourceActions(app: AppController, s: Source, onEdit: (Source) -> Unit): PanelPage = PanelPage.Actions(s.name, buildList {
    add(PanelAction("Refresh now") { app.scope.launch { app.refresh(s.id) }; null })
    if (s.kind != SourceKind.DEMO) add(PanelAction("Edit") { onEdit(s); null })
    add(PanelAction(if (s.enabled) "Disable" else "Enable") { app.scope.launch { app.sources.save(s.copy(enabled = !s.enabled)) }; null })
    add(PanelAction("Remove") {
        PanelPage.Confirm("Remove ${s.name}?", "Its channels leave every list, including Favorites and playlists.", "Remove") {
            app.scope.launch { app.removeSource(s.id) }
        }
    })
})

@Composable
private fun ChannelsSection(app: AppController, returnTo: FocusRequester) {
    val hiddenChannels by app.channels.hiddenChannels().collectAsState(emptyList())
    val allGroups by remember { app.channels.groups(includeHidden = true) }.collectAsState(emptyList())
    val hiddenGroups = allGroups.filter { it.hidden }
    var tick by remember { mutableStateOf(0) }
    LazyColumn(Modifier.width(1100.dp)) {
        item { SectionTitle("Order") }
        item {
            val sort = remember(tick) { app.settings.groupSort() }
            ListRow("Group order", detail = groupSortLabel(sort), onSelect = {
                app.panel.open(PanelPage.Actions("Group order", GroupSort.entries.map { g ->
                    PanelAction(groupSortLabel(g), selected = g == sort) { app.settings.put(Settings.Key.GROUP_SORT, g.name); tick++; null }
                }), returnTo)
            })
        }
        item {
            val sort = remember(tick) { app.settings.channelSort() }
            ListRow("Channel order within groups", detail = channelSortLabel(sort), onSelect = {
                app.panel.open(PanelPage.Actions("Channel order", ChannelSort.entries.map { s ->
                    PanelAction(channelSortLabel(s), selected = s == sort) { app.settings.put(Settings.Key.CHANNEL_SORT, s.name); tick++; null }
                }, subtitle = "Favorites and playlists always keep their manual order."), returnTo)
            })
        }
        item { SectionTitle("Hidden channels") }
        if (hiddenChannels.isEmpty()) item { ListRow("None", enabled = false, onSelect = null) }
        items(hiddenChannels, key = { "c:" + it.key }) { ch ->
            ListRow(ch.displayName, detail = "Restore", onSelect = { app.scope.launch { app.channels.setHidden(ch, false) } })
        }
        item { SectionTitle("Hidden groups") }
        if (hiddenGroups.isEmpty()) item { ListRow("None", enabled = false, onSelect = null) }
        items(hiddenGroups, key = { "g:" + it.group.key }) { g ->
            ListRow(g.group.title, detail = "Restore", onSelect = { app.scope.launch { app.channels.setGroupHidden(g.group as ChannelGroup.Provider, false) } })
        }
    }
}

private fun groupSortLabel(s: GroupSort) = when (s) {
    GroupSort.PROVIDER -> "Provider order"
    GroupSort.A_Z -> "A–Z"
    GroupSort.Z_A -> "Z–A"
    GroupSort.MANUAL -> "Manual"
}

private fun channelSortLabel(s: ChannelSort) = when (s) {
    ChannelSort.PROVIDER -> "Provider order"
    ChannelSort.NUMBER -> "Channel number"
    ChannelSort.A_Z -> "A–Z"
}

@Composable
private fun AppearanceSection(app: AppController, returnTo: FocusRequester) {
    var tick by remember { mutableStateOf(0) }
    val raw = remember(tick) { app.settings.raw(Settings.Key.CLOCK_24H) }
    val label = when (raw) {
        "true" -> "24-hour"
        "false" -> "12-hour"
        else -> "System (${if (app.platform.systemUses24HourClock()) "24-hour" else "12-hour"})"
    }
    Column(Modifier.width(1100.dp)) {
        SectionTitle("Clock")
        ListRow("Time format", detail = label, onSelect = {
            app.panel.open(PanelPage.Actions("Time format", listOf(
                PanelAction("System", selected = raw == null) { app.settings.put(Settings.Key.CLOCK_24H, null); tick++; null },
                PanelAction("12-hour", selected = raw == "false") { app.settings.put(Settings.Key.CLOCK_24H, "false"); tick++; null },
                PanelAction("24-hour", selected = raw == "true") { app.settings.put(Settings.Key.CLOCK_24H, "true"); tick++; null },
            )), returnTo)
        })
    }
}

@Composable
private fun AboutSection(app: AppController) {
    val c = SignalTheme.colors
    val info = app.platform.appInfo
    val updater = app.platform.updater
    val state = updater?.state?.collectAsState()?.value
    Column(Modifier.width(1100.dp)) {
        SectionTitle("Signal")
        SText("Version ${info.versionName} (${info.buildTag})", 26, Modifier.padding(horizontal = 40.dp))
        SText(info.deviceModel, 22, Modifier.padding(horizontal = 40.dp, vertical = 8.dp), color = c.muted)
        SectionTitle("Updates")
        if (updater == null) {
            SText(if (info.isDebug) "Debug builds don't update themselves." else "Updates aren't available here.", 24, Modifier.padding(horizontal = 40.dp), color = c.muted)
        } else {
            val status = when (state) {
                is UpdateState.Available -> "Version ${state.tag.removePrefix("apk-")} is available"
                is UpdateState.Downloading -> "Downloading… ${(state.progress * 100).toInt()}%"
                is UpdateState.Failed -> "Failed: ${state.message}"
                else -> "Up to date"
            }
            ListRow("Check for updates", detail = status, onSelect = {
                if (state is UpdateState.Available) app.panel.open(updatePanel(app, state), null)
                else app.scope.launch { updater.check() }
            })
        }
        if (info.isDebug) {
            SectionTitle("Developer")
            ListRow("Guide grid spike", detail = "2,000 channels × 7 days, frame timing", onSelect = { app.navigator.go(Route.GuideSpike) })
        }
        SectionTitle("Credits")
        SText("Uses the Archivo typeface (SIL Open Font License).", 22, Modifier.padding(horizontal = 40.dp), color = c.muted)
    }
}

/** The changelog with Install and Later (PLAN §4.3.15 → Auto-updater). */
fun updatePanel(app: AppController, state: UpdateState.Available): PanelPage {
    val updater = app.platform.updater
    return PanelPage.Actions(
        "Signal ${state.tag.removePrefix("apk-")}",
        listOf(
            PanelAction("Install") { updater?.install(); null },
            PanelAction("Later") { updater?.later(); null },
        ),
        subtitle = state.changelog.take(600).ifEmpty { "No changelog for this release." },
    )
}

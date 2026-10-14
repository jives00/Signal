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
import com.signal.app.StartPage
import com.signal.data.Settings
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.model.ChannelGroup
import com.signal.model.ChannelSort
import com.signal.model.GroupSort
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.nas.NasConnection
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
    LIVE_TV("Live TV & guide"),
    MOVIES("Movies & Series"),
    APPEARANCE("Appearance"),
    NAS("NAS & sync"),
    GUIDE_DATA("Guide data"),
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
    // NAS & sync's form: null when closed, else whether it pairs (true) or only edits the addresses.
    var nasForm by remember { mutableStateOf<Boolean?>(null) }
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
                    ListRow(s.title, selected = s == section, onSelect = { section = s; adding = false; editing = null; nasForm = null; focusSection() }, onFocused = { section = s; adding = false; editing = null; nasForm = null }, focusRequester = if (s == Section.entries.first()) firstSection else null)
                }
            }
            VDivider()
            Box(Modifier.fillMaxSize().padding(start = 48.dp, top = 24.dp).onFocusChanged { contentHasFocus = it.hasFocus }.focusRequester(contentFocus).focusRestorer()) {
                when {
                    adding || editing != null -> SourceForm(editing, onDone = { adding = false; editing = null }, onCancel = { adding = false; editing = null })
                    nasForm != null -> NasForm(pair = nasForm == true, onPaired = { nasForm = null; focusSection() }, onCancel = { nasForm = null; focusSection() })
                    section == Section.SOURCES -> SourcesSection(app, onAdd = { adding = true }, onEdit = { editing = it }, returnTo = contentFocus)
                    section == Section.CHANNELS -> ChannelsSection(app, contentFocus)
                    section == Section.LIVE_TV -> LiveTvGuideSection(app, contentFocus)
                    section == Section.MOVIES -> MoviesSection(app)
                    section == Section.APPEARANCE -> AppearanceSection(app, contentFocus)
                    section == Section.NAS -> NasSection(app, onForm = { nasForm = it }, returnTo = contentFocus)
                    section == Section.GUIDE_DATA -> GuideDataSection(app)
                    section == Section.ABOUT -> AboutSection(app)
                }
            }
        }
    }

    // Sections ↔ content, and Back goes up a level before the nav bar (PLAN §12.1). Registered after
    // TopLevelPage's handler, so it's asked first.
    val inForm = adding || editing != null || nasForm != null
    RemoteHandler { action ->
        when {
            action == RemoteAction.Right && sectionsHaveFocus -> { focusSection(); true }
            action == RemoteAction.Left && contentHasFocus && !inForm -> { chrome.focusContent(); true }
            action == RemoteAction.Back && inForm -> {
                adding = false
                editing = null
                nasForm = null
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
    val guideState by app.guide.state.collectAsState()
    val epgCounts by produceState(emptyMap<String, Pair<Long, Long>>(), guideState) { value = app.guide.counts() }
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
            ListRow("${s.name} · ${kindLabel(s.kind)}", detail = detail, onSelect = { app.panel.open(sourceActions(app, s, onEdit, returnTo), returnTo) })
        }
        item { ListRow("Add source", detail = "M3U or Xtream Codes", onSelect = onAdd) }
        epgSourceItems(app, guideState.sources, guideState.defaultId, epgCounts, returnTo)
    }
}

private fun kindLabel(kind: SourceKind) = when (kind) {
    SourceKind.M3U -> "M3U"
    SourceKind.XTREAM -> "Xtream"
    SourceKind.DEMO -> "Demo"
}

internal fun refreshedLabel(app: AppController, at: Long): String {
    if (at == 0L) return "never refreshed"
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault())
    return "refreshed ${t.date} ${formatTime(t.hour, t.minute, app.settings.bool(Settings.Key.CLOCK_24H, app.platform.systemUses24HourClock()))}"
}

private fun sourceActions(app: AppController, s: Source, onEdit: (Source) -> Unit, returnTo: FocusRequester): PanelPage = PanelPage.Actions(s.name, buildList {
    add(PanelAction("Refresh now") { app.scope.launch { app.refresh(s.id) }; null })
    if (s.kind != SourceKind.DEMO) add(PanelAction("Edit") { onEdit(s); null })
    // With another source beside this one: the provider switch review (PLAN §4.5).
    if (app.sourceList.value.size > 1) add(PanelAction("Match channels to this source", detail = "Link your channels to its streams before switching") {
        com.signal.ui.menus.SwitchReview.open(app, s, returnTo); null
    })
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

/** Settings → Movies & Series (PLAN §4.3.15, section 4). VOD category tools and default sorts come later. */
@Composable
private fun MoviesSection(app: AppController) {
    var tick by remember { mutableStateOf(0) }
    val autoplay = remember(tick) { app.settings.bool(Settings.Key.AUTOPLAY_NEXT, true) }
    Column(Modifier.width(1100.dp)) {
        SectionTitle("Playback")
        ListRow("Autoplay next episode", detail = if (autoplay) "On · asks \"Still watching?\" after 3 in a row" else "Off", onSelect = {
            app.settings.put(Settings.Key.AUTOPLAY_NEXT, (!autoplay).toString())
            tick++
        })
    }
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
        SectionTitle("Start")
        val start = remember(tick) { StartPage.from(app.settings.raw(Settings.Key.START_PAGE)) }
        ListRow("Start page", detail = start.label, onSelect = {
            app.panel.open(PanelPage.Actions("Start page", StartPage.entries.map { p ->
                PanelAction(p.label, selected = p == start) {
                    app.settings.put(Settings.Key.START_PAGE, if (p == StartPage.HOME) null else p.name); tick++; null
                }
            }, subtitle = "Where Signal opens, and where it returns after 4 hours away."), returnTo)
        })
        SectionTitle("Clock")
        ListRow("Time format", detail = label, onSelect = {
            app.panel.open(PanelPage.Actions("Time format", listOf(
                PanelAction("System", selected = raw == null) { app.settings.put(Settings.Key.CLOCK_24H, null); tick++; null },
                PanelAction("12-hour", selected = raw == "false") { app.settings.put(Settings.Key.CLOCK_24H, "false"); tick++; null },
                PanelAction("24-hour", selected = raw == "true") { app.settings.put(Settings.Key.CLOCK_24H, "true"); tick++; null },
            )), returnTo)
        })
        SectionTitle("Channels")
        val numbers by app.showNumbers.collectAsState()
        ListRow("Channel numbers", detail = if (numbers) "Shown" else "Hidden", onSelect = {
            app.settings.put(Settings.Key.SHOW_NUMBERS, if (numbers) "false" else null) // Unset is Shown, as the web admin has it.
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
        notes = state.changelog.ifEmpty { "No changelog for this release." },
    )
}

/** NAS & sync (PLAN §4.3.15 → 7): the connection, sync, pairing, the addresses and the settings file. */
@Composable
private fun NasSection(app: AppController, onForm: (pair: Boolean) -> Unit, returnTo: FocusRequester) {
    val c = SignalTheme.colors
    val status by app.nas.status.collectAsState()
    val sync by app.sync.state.collectAsState()
    val events by app.eventsConnected.collectAsState()
    var fileMessage by remember { mutableStateOf<String?>(null) }
    val paired = app.nas.isPaired
    val name = app.settings.raw(Settings.Key.NAS_DEVICE_NAME)
    val summary = when (val s = status) {
        NasConnection.Status.NotPaired -> "Not paired. The app works on its own; pairing adds the NAS guide and settings sync."
        NasConnection.Status.Checking -> "Checking…"
        is NasConnection.Status.Connected -> "Connected over the ${s.via.label} (${s.address.removePrefix("http://")}) as ${s.deviceName}."
        NasConnection.Status.Unreachable -> "Paired as ${name ?: "this device"}, but the NAS isn't answering at either address. Changes wait here until it's back."
        NasConnection.Status.Removed -> "This device was removed in the web admin. Pair again to reconnect."
    }
    LazyColumn(Modifier.width(1100.dp)) {
        item { SectionTitle("Status") }
        item { SText(summary, 24, Modifier.padding(horizontal = 40.dp).padding(bottom = 16.dp), color = c.secondaryText, maxLines = 2) }
        if (paired) {
            item {
                val last = sync.lastSyncAt?.let { refreshedLabel(app, it).replace("refreshed", "last synced") } ?: "not synced yet"
                val detail = when {
                    sync.running -> "Syncing…"
                    sync.error != null -> "${sync.error} · $last"
                    else -> last.replaceFirstChar { it.uppercase() } + if (events) " · live updates on" else " · live updates off"
                }
                ListRow("Sync now", detail = detail, onSelect = { app.scope.launch { app.syncNow() } })
            }
            item { ListRow("Profile", detail = sync.profileName ?: "Main", onSelect = null) }
            item { ListRow("Check connection", onSelect = { app.scope.launch { app.nas.check() } }) }
        }
        item { SectionTitle("Pairing") }
        item { ListRow(if (paired) "Pair again" else "Pair with NAS", detail = "Shows a code to approve in the web admin", onSelect = { onForm(true) }) }
        if (paired) item {
            ListRow("Unpair this device", onSelect = {
                app.panel.open(PanelPage.Confirm("Unpair this device?", "It stops using the NAS until it's paired again, and keeps its settings. Forget it in the web admin's Devices too.", "Unpair") {
                    app.scope.launch { app.unpairNas() }
                }, returnTo)
            })
        }
        item { SectionTitle("Connection") }
        item { ListRow("Home network address", detail = app.nas.homeAddress, onSelect = { onForm(false) }) }
        item { ListRow("Tailscale address", detail = app.nas.tailscaleAddress, onSelect = { onForm(false) }) }
        if (app.platform.settingsFiles != null) {
            item { SectionTitle("Settings file") }
            item {
                ListRow("Export settings", detail = "A file with every setting, for a backup or another TV. It includes source passwords.", onSelect = {
                    fileMessage = "Exporting…"
                    app.scope.launch { fileMessage = app.exportSettings().fold({ "Saved to $it" }, { "Not exported: ${it.message}" }) }
                })
            }
            item {
                ListRow("Import settings", detail = "Adds a file's settings here. Its values win; nothing here is removed.", onSelect = {
                    fileMessage = "Importing…"
                    app.scope.launch { fileMessage = app.importSettings().fold({ "Imported $it settings" }, { "Not imported: ${it.message}" }) }
                })
            }
            fileMessage?.let { m -> item { SText(m, 22, Modifier.padding(horizontal = 40.dp, vertical = 12.dp), color = c.secondaryText, maxLines = 2) } }
        }
    }
}

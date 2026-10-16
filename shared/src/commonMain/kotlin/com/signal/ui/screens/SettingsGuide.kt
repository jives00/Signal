package com.signal.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.signal.app.AppController
import com.signal.data.Settings
import com.signal.model.EpgKind
import com.signal.model.EpgSource
import com.signal.ui.components.ListRow
import com.signal.ui.components.SText
import com.signal.ui.components.SectionTitle
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

// The guide's Settings (PLAN §4.3.15): EPG sources under Sources, Live TV & guide, and Guide data.

/** EPG sources, below the IPTV sources: the default first, then the fallback order. */
internal fun LazyListScope.epgSourceItems(app: AppController, sources: List<EpgSource>, defaultId: String?, counts: Map<String, Pair<Long, Long>>, returnTo: FocusRequester) {
    item { SectionTitle("EPG sources") }
    items(sources, key = { "epg:" + it.id }) { s ->
        val role = when {
            !s.enabled -> "Disabled"
            s.id == defaultId -> "Default"
            else -> "Fallback ${sources.filter { it.enabled && it.id != defaultId }.indexOf(s) + 1}"
        }
        val count = counts[s.id]?.let { (ch, pr) -> "$ch channels · $pr programs" } ?: "Not built yet"
        val detail = listOfNotNull(role, shiftLabel(s.timeShiftMinutes), if (s.lastError != null) "Error: ${s.lastError}" else count).joinToString(" · ")
        ListRow(s.name, detail = detail, onSelect = { app.panel.open(epgSourceActions(app, s, sources, defaultId), returnTo) })
    }
    item {
        ListRow("Add EPG source", detail = "An XMLTV URL, plain or .gz", onSelect = {
            app.panel.open(PanelPage.TextInput("EPG source URL", "http://", confirmLabel = "Add") { url ->
                if (url.length > 8) app.scope.launch {
                    val name = url.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "Guide" }
                    app.epg.addXmltv(name, url)
                    app.rebuildGuide()
                }
            }, returnTo)
        })
    }
}

/** "+1h", "−2.5h", or null at 0. */
private fun shiftLabel(minutes: Int): String? {
    if (minutes == 0) return null
    val hours = abs(minutes) / 60.0
    val text = if (hours % 1.0 == 0.0) hours.toInt().toString() else hours.toString()
    return (if (minutes > 0) "+" else "−") + text + "h"
}

private fun epgSourceActions(app: AppController, s: EpgSource, sources: List<EpgSource>, defaultId: String?): PanelPage = PanelPage.Actions(s.name, buildList {
    fun launch(block: suspend () -> Unit) { app.scope.launch { block() } }
    if (s.enabled && s.id != defaultId) add(PanelAction("Set as default") { app.epg.setDefault(s.id); null })
    // −12 to +12 hours in 30-minute steps; applied when the guide is read, so it shows at once (PLAN §6).
    add(PanelAction("Time shift", detail = shiftLabel(s.timeShiftMinutes) ?: "None") {
        PanelPage.Actions("Time shift: ${s.name}", (-24..24).map { step ->
            val minutes = step * 30
            PanelAction(shiftLabel(minutes) ?: "None", selected = minutes == s.timeShiftMinutes) {
                launch { app.epg.update(s.copy(timeShiftMinutes = minutes)) }
                null
            }
        })
    })
    val index = sources.indexOfFirst { it.id == s.id }
    if (index > 0) add(PanelAction("Move up") { launch { app.epg.move(s.id, index - 1) }; null })
    if (index < sources.lastIndex) add(PanelAction("Move down") { launch { app.epg.move(s.id, index + 1) }; null })
    if (s.kind == EpgKind.XMLTV) {
        add(PanelAction("Edit URL") {
            PanelPage.TextInput("EPG source URL", s.url) { url -> launch { app.epg.update(s.copy(url = url.trim())); app.rebuildGuide() } }
        })
        add(PanelAction("Rename") { PanelPage.TextInput("Rename ${s.name}", s.name) { name -> launch { app.epg.update(s.copy(name = name.trim().ifEmpty { s.name })) } } })
    }
    add(PanelAction(if (s.enabled) "Disable" else "Enable") {
        launch {
            app.epg.update(s.copy(enabled = !s.enabled))
            // A source that wasn't in the last build needs one.
            if (!s.enabled) app.rebuildGuide()
        }
        null
    })
    // An IPTV source's own guide comes back with its source; it can be disabled, not removed (PLAN §4.3.15).
    if (s.kind == EpgKind.XMLTV) add(PanelAction("Remove") {
        PanelPage.Confirm("Remove ${s.name}?", "Channels using it switch to the default or the fallback order.", "Remove") { launch { app.epg.delete(s.id) } }
    })
})

/** Settings → Live TV & guide (PLAN §4.3.15, section 3): reminders. */
@Composable
internal fun LiveTvGuideSection(app: AppController, returnTo: FocusRequester) {
    val changes by app.settings.changes().collectAsState(emptyMap())
    @Suppress("UNUSED_EXPRESSION") changes // Re-read the values below on every change.
    val lead = app.settings.raw(Settings.Key.REMINDER_LEAD_MINUTES)?.toIntOrNull() ?: 1
    val autoSwitch = app.settings.bool(Settings.Key.REMINDER_AUTO_SWITCH, false)
    LazyColumn(Modifier.width(1100.dp)) {
        item { SectionTitle("Reminders") }
        item {
            ListRow("Reminder time", detail = if (lead == 0) "When it starts" else "$lead min before", onSelect = {
                app.panel.open(PanelPage.Actions("Reminder time", listOf(0, 1, 2, 5).map { m ->
                    PanelAction(if (m == 0) "When it starts" else "$m min before", selected = m == lead) {
                        app.settings.put(Settings.Key.REMINDER_LEAD_MINUTES, m.toString()); null
                    }
                }), returnTo)
            })
        }
        item {
            ListRow("Switch automatically", detail = if (autoSwitch) "On: tunes the channel when a reminder fires" else "Off: shows a prompt", onSelect = {
                app.settings.put(Settings.Key.REMINDER_AUTO_SWITCH, (!autoSwitch).toString())
            })
        }
        item { SText("Reminders work while Signal is open. Android TV can't show them while it's closed.", 22, Modifier.padding(40.dp), color = SignalTheme.colors.muted, maxLines = 2) }
    }
}

/** Settings → Guide data (PLAN §4.3.15, section 8): the guide's age and where it came from, and Update EPG now. */
@Composable
internal fun GuideDataSection(app: AppController) {
    val state by app.guide.state.collectAsState()
    val counts by produceState(emptyMap<String, Pair<Long, Long>>(), state) { value = app.guide.counts() }
    // Off the main thread: a guide install holds guide.db's connection, and reading it here during
    // composition froze the Chromecast until Android killed the app (ANR, 2026-10-16).
    val meta by produceState<Pair<Long?, String?>>(null to null, state) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.guideStore.builtAt() to app.guideStore.origin() }
    }
    val built = meta.first
    LazyColumn(Modifier.width(1100.dp)) {
        item { SectionTitle("Guide data") }
        item {
            val from = if (meta.second == com.signal.guide.GuideStore.ORIGIN_NAS) "From the NAS" else "Built on this device"
            val age = built?.let { "$from, ${refreshedLabel(app, it).removePrefix("refreshed ")}" } ?: "No guide yet"
            ListRow("Guide", detail = age, onSelect = null)
        }
        item {
            ListRow("Programs", detail = "${counts.values.sumOf { it.second }} programs on ${counts.values.sumOf { it.first }} guide channels", onSelect = null)
        }
        item {
            ListRow(
                "Update EPG now",
                detail = if (app.guideBuilding) "Updating…" else if (app.nas.isPaired) "Gets the NAS's latest build, or builds here if the NAS has none" else "Rebuilds the guide from every enabled EPG source",
                onSelect = { if (!app.guideBuilding) app.scope.launch { app.refreshGuide() } },
            )
        }
        if (app.nas.isPaired) item {
            ListRow("Rebuild on this device", detail = "For troubleshooting: skips the NAS", onSelect = { if (!app.guideBuilding) app.scope.launch { app.rebuildGuide() } })
        }
        item { SText(if (app.nas.isPaired) "The NAS builds the guide nightly, and Signal checks for a new build every 30 minutes." else "Pair with the NAS (NAS & sync) to get its nightly guide build instead of building here.", 22, Modifier.padding(40.dp), color = SignalTheme.colors.muted, maxLines = 2) }
    }
}

package com.signal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.signal.data.ChannelRepository
import com.signal.data.GroupEntry
import com.signal.data.Log
import com.signal.model.ChannelGroup
import com.signal.ui.menus.ItemMenus
import com.signal.ui.panel.SidePanelState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.signal.data.Settings
import com.signal.data.SourceRepository
import com.signal.guide.EpgRepository
import com.signal.guide.GuideBuilder
import com.signal.guide.GuideRepository
import com.signal.guide.GuideStore
import com.signal.guide.ReminderRepository
import com.signal.search.SearchRepository
import com.signal.model.Channel
import com.signal.model.Reminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.signal.db.user.UserDatabase
import com.signal.input.RemoteDispatcher
import com.signal.model.Source
import com.signal.model.SourceKind
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.cash.sqldelight.coroutines.asFlow
import com.signal.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The app's object graph, built once per process. Plain constructor wiring: there are few enough
 * objects that a DI framework would add more than it saves.
 */
class AppController(val platform: Platform, val log: Log) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val db = UserDatabase(platform.createUserDatabaseDriver())
    val settings = Settings(db, platform.io)
    val http = platform.createHttpClient()
    val sources = SourceRepository(db, http, platform.secretBox, platform.io, log)
    val channels = ChannelRepository(db, settings, platform.io)
    val playback = PlaybackController(platform.player, channels, sources, settings, scope, log)
    // The NAS companion (PLAN §5.7): optional; the app works the same without it.
    val nas = com.signal.nas.NasConnection(settings, http, platform.secretBox, platform.appInfo, log)
    // Settings sync through the companion (PLAN §9).
    val sync = com.signal.sync.SyncEngine(db, settings, com.signal.sync.SyncTables(db, settings, platform.secretBox), platform.io, log)

    // The guide (PLAN §6). It reads and writes only guide.db, so it gets its own IO threads: on the
    // desktop user.db runs one thread at a time, and a guide build must never queue behind or block it.
    private val guideIo = Dispatchers.IO
    val guideStore = GuideStore(platform, guideIo, scope, log)
    val epg = EpgRepository(db, settings, platform.io)
    val guide = GuideRepository(guideStore, epg, settings, guideIo, scope)
    private val guideBuilder = GuideBuilder(http, { sources.byId(it) }, guideIo, log)
    val reminders = ReminderRepository(db, settings, channels, platform.io)
    val search = SearchRepository(db, settings, channels, guide, platform.io, scope)
    val reminderList: StateFlow<List<Reminder>> = reminders.reminders().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val dispatcher = RemoteDispatcher()
    val navigator = Navigator(Route.Home)
    val panel = SidePanelState()
    val menus = ItemMenus(this)

    init {
        navigator.onNavigate = { log.info("Navigate to ${it::class.simpleName}") }
        scope.launch { platform.player.state.collect { log.info("Player ${it.status} ${it.error?.kind ?: ""}") } }
    }

    /** Live TV's group list, kept live for menus and screens. */
    val groups: StateFlow<List<GroupEntry>> = channels.groups().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The sources, kept live, so Settings never shows an empty list while it reloads. */
    val sourceList: StateFlow<List<Source>> = sources.sources().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Live TV opens on this group; full screen's Back sets it to the current group (PLAN §4.3.3). */
    var liveTvGroup by mutableStateOf<ChannelGroup?>(null)

    /** The channel Live TV focuses when it opens, e.g. the one that was playing full screen. */
    var liveTvFocusKey by mutableStateOf<String?>(null)

    /** False until the first source check finishes, so first run never flashes Home. */
    var ready by mutableStateOf(false)
        private set

    /** Shown by the setup screen and Settings while a refresh runs. */
    var refreshing by mutableStateOf<String?>(null)
        private set

    /** The reminder prompt showing now, if any (PLAN §4.3.4 → Reminders). */
    var activeReminder by mutableStateOf<ActiveReminder?>(null)
        private set

    data class ActiveReminder(val reminder: Reminder, val channel: Channel)

    /** True while the guide builds on this device. */
    var guideBuilding by mutableStateOf(false)
        private set

    init {
        scope.launch {
            withContext(guideIo) { guideStore.open() }
            if (sources.count() == 0L) navigator.resetTo(Route.Setup)
            ready = true
            refreshStaleSources()
            // Sources from before milestone 2 get their guides here too.
            sources.all().forEach { epg.ensureForSource(it) }
            // The NAS build first when paired; the device builds its own only when that gives nothing (PLAN §6).
            if (installNasGuide() == null && guideIsStale()) rebuildGuide()
        }
        scope.launch { runReminders() }
        // A new guide can move a program: its reminder follows (PLAN §4.3.4 → Guide changes).
        scope.launch { guideStore.version.collect { if (it > 0) followMovedPrograms() } }
        scope.launch {
            sync.load()
            nas.check()
        }
        // Settings sync (PLAN §9.2): on connecting (launch, foreground, just paired)...
        scope.launch {
            nas.status.map { it is com.signal.nas.NasConnection.Status.Connected }.distinctUntilChanged()
                .collect { if (it) syncNow() }
        }
        // ...after a change here...
        scope.launch {
            db.syncQueries.changeTick().asFlow().drop(1).debounce(SYNC_DEBOUNCE_MS)
                .collect { if (nas.status.value is com.signal.nas.NasConnection.Status.Connected && sync.hasLocalChanges()) syncNow() }
        }
        // ...and whenever the NAS says something changed.
        scope.launch { followEvents() }
        // A NAS build that lands while the app is open (the nightly run, or new inputs) is picked up.
        scope.launch {
            while (true) {
                delay(NAS_GUIDE_CHECK_MS)
                if (nas.isPaired && !guideBuilding) installNasGuide()
            }
        }
        scope.launch { platform.updater?.check() }
    }

    /** Sources not refreshed in 24 hours refresh in the background on launch (PLAN §6). */
    private suspend fun refreshStaleSources() {
        val day = 24 * 60 * 60_000L
        val now = Clock.System.now().toEpochMilliseconds()
        sources.all().filter { it.enabled && now - it.lastRefreshedAt > day }.forEach { refresh(it.id) }
    }

    /** No guide yet, or one more than 24 hours old (PLAN §6: "the app refreshes from the sources itself"). */
    private fun guideIsStale(): Boolean {
        val built = guideStore.builtAt() ?: return true
        return Clock.System.now().toEpochMilliseconds() - built > 24 * 60 * 60_000L
    }

    /**
     * Builds the guide on this device from every enabled EPG source, and swaps it in (PLAN §6). The
     * NAS build arrives in milestone 3. Returns false when nothing could be built and the old guide stays.
     */
    suspend fun rebuildGuide(): Boolean {
        if (guideBuilding) return false
        val epgSources = epg.all().filter { it.enabled }
        if (epgSources.isEmpty()) return false
        guideBuilding = true
        return try {
            val now = Clock.System.now().toEpochMilliseconds()
            var outcomes: Map<String, GuideBuilder.Outcome> = emptyMap()
            val swapped = guideStore.build(now) { fresh, previous ->
                outcomes = guideBuilder.build(epgSources, fresh, previous, now)
                outcomes.values.any { it.programs > 0 }
            }
            withContext(platform.io) {
                outcomes.forEach { (id, o) -> if (o.error == null) epg.markBuilt(id, now) else epg.markFailed(id, o.error) }
            }
            if (swapped) scope.launch { logGuideCoverage() }
            swapped
        } catch (e: Throwable) {
            log.error("Guide build failed", e)
            false
        } finally {
            guideBuilding = false
        }
    }

    // ---- The NAS guide (PLAN §5.2, §6) ---------------------------------------------------------

    // ---- Settings sync (PLAN §5.5, §9.2) ------------------------------------------------------

    /** True while the NAS's event stream is connected; Settings shows it. */
    val eventsConnected = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Push, pull, and act on what arrived: new sources load, a removed last source returns to setup,
     * and a guide change picks up the NAS's build. Returns false when the NAS couldn't be reached.
     */
    suspend fun syncNow(): Boolean {
        if (!nas.isPaired) return false
        val report = sync.sync(nas) ?: return false
        for (id in report.sourcesToRefresh) {
            refresh(id)
            sources.byId(id)?.let { epg.ensureForSource(it) }
        }
        if (report.sourcesRemoved && sources.count() == 0L) {
            playback.stop()
            navigator.resetTo(Route.Setup)
        } else if (report.sourcesToRefresh.isNotEmpty() && navigator.current == Route.Setup) {
            // First run on a paired device: the NAS's settings arrived (PLAN §4.3.1 → First run).
            navigator.resetTo(Route.Home)
        }
        if (report.profileChanged && navigator.current != Route.Setup) navigator.resetTo(Route.Home)
        if (report.epgChanged || report.sourcesToRefresh.isNotEmpty()) scope.launch {
            if (installNasGuide() == null && guideIsStale()) rebuildGuide()
        }
        return true
    }

    /**
     * The NAS's event stream while paired (PLAN §9.2): a settings change pulls, a new guide build
     * installs. On a drop it reconnects with backoff and syncs on reconnect; while it's down a 5-minute
     * poll stands in.
     */
    private suspend fun followEvents() {
        var backoff = 2_000L
        while (true) {
            if (nas.status.value !is com.signal.nas.NasConnection.Status.Connected) {
                nas.status.first { it is com.signal.nas.NasConnection.Status.Connected }
            }
            runCatching {
                nas.events().collect { event ->
                    eventsConnected.value = true
                    backoff = 2_000L
                    when (event.kind) {
                        com.signal.sync.SyncEvent.HELLO -> if (event.rev != sync.state.value.rev) syncNow()
                        com.signal.sync.SyncEvent.SETTINGS -> if (event.rev != sync.state.value.rev) syncNow()
                        com.signal.sync.SyncEvent.GUIDE -> if (!guideBuilding) installNasGuide()
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            eventsConnected.value = false
            // Down: poll as a safety net, and try the stream again with backoff.
            val lastSync = sync.state.value.lastSyncAt ?: 0
            if (Clock.System.now().toEpochMilliseconds() - lastSync > SYNC_POLL_MS && nas.isPaired) syncNow()
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** Unpairing: the key goes, and the next pairing is a first sync again. */
    suspend fun unpairNas() {
        nas.unpair()
        sync.reset()
    }

    /** Settings → NAS & sync → Export settings: a file of every setting, passwords included (PLAN §9.2 → Fallback). */
    suspend fun exportSettings(): Result<String> = runCatching {
        val files = platform.settingsFiles ?: error("Not available on this device")
        files.save("signal-settings-${Clock.System.now().toEpochMilliseconds() / 1000}.json", sync.exportFile()) ?: error("Not saved")
    }

    suspend fun importSettings(): Result<Int> {
        val files = platform.settingsFiles ?: return Result.failure(IllegalStateException("Not available on this device"))
        val text = files.open() ?: return Result.failure(IllegalStateException("No file chosen"))
        return sync.importFile(text).onSuccess {
            sources.all().filter { it.enabled && it.lastRefreshedAt == 0L }.forEach { s ->
                refresh(s.id)
                epg.ensureForSource(s)
            }
            if (navigator.current == Route.Setup && sources.count() > 0) navigator.resetTo(Route.Home)
            if (nas.status.value is com.signal.nas.NasConnection.Status.Connected) syncNow()
        }
    }

    /** Update EPG now (PLAN §4.3.15 → Guide data): the NAS build when there is one, else a build on this device. */
    suspend fun refreshGuide(): Boolean = installNasGuide() ?: rebuildGuide()

    /**
     * Installs the NAS's guide when it's newer than the live one. True when the guide is now the
     * NAS's (or something fresher), false when the download failed, and null when the NAS has nothing
     * usable: not paired, unreachable, no build yet, a build too old or from another schema.
     */
    private suspend fun installNasGuide(): Boolean? {
        val meta = nas.guideMeta() ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        if (meta.schema != GuideStore.SCHEMA || now - meta.builtAt > NAS_GUIDE_MAX_AGE_MS) return null
        val liveBuiltAt = guideStore.builtAt() ?: 0
        // Already this build, or a newer one made here (EPG sources edited since the NAS ran).
        if (liveBuiltAt >= meta.builtAt) return true
        if (guideBuilding) return false
        guideBuilding = true
        return try {
            val part = com.signal.guide.GuideFiles.join(platform.guideDirectory, "nas-${meta.builtAt}.db.part")
            val ok = nas.downloadGuide(meta, part) && guideStore.install(part)
            if (ok) {
                log.info("Guide from the NAS: ${meta.programs} programs, built ${meta.builtAt}")
                withContext(platform.io) {
                    meta.outcomes.forEach { (id, o) -> if (o.error == null) epg.markBuilt(id, meta.builtAt) else epg.markFailed(id, o.error) }
                }
                scope.launch { logGuideCoverage() }
            } else {
                com.signal.guide.GuideFiles.delete(part)
            }
            ok
        } finally {
            guideBuilding = false
        }
    }

    suspend fun refresh(sourceId: String): Result<Int> {
        refreshing = sourceId
        return sources.refresh(sourceId).also { refreshing = null }
    }

    /** Adds a source and loads it; on success the app goes to Home (PLAN §4.3.1 → First run). */
    suspend fun addSource(source: Source): Result<Int> {
        sources.save(source)
        val result = refresh(source.id)
        if (result.isFailure && sources.byId(source.id)?.lastRefreshedAt == 0L) {
            // Never loaded at all: don't leave a dead source behind on first run.
            sources.delete(source.id)
        } else if (result.isSuccess && sources.byId(source.id)?.let { epg.ensureForSource(it) } == true) {
            // An Xtream or Demo source brings its own guide: build it now, in the background.
            scope.launch { rebuildGuide() }
        }
        return result
    }

    suspend fun addDemoSource(): Result<Int> =
        addSource(Source(id = "demo", name = "Demo", kind = SourceKind.DEMO, url = "demo:"))

    suspend fun removeSource(id: String) {
        sources.delete(id)
        epg.removeForSource(id)
        // With every source removed, the app returns to setup (PLAN §4.3.1 → First run).
        if (sources.count() == 0L) {
            playback.stop()
            navigator.resetTo(Route.Setup)
        }
    }

    /** Diagnostics for mapping coverage (PLAN §6): how many channels get a guide, and why some don't. */
    private suspend fun logGuideCoverage() {
        val (mapped, total, unmatched) = search.guideCoverage()
        log.info("Guide mapping: $mapped of $total channels have guide data")
        for ((name, tvgId) in unmatched) {
            val why = tvgId?.takeIf { it.isNotBlank() }?.let { "tvg-id $it, ${guide.describeTvgId(it)}" } ?: "no tvg-id"
            log.info("No guide: $name ($why)")
        }
    }

    // ---- Reminders (PLAN §4.3.4 → Reminders) ----------------------------------------------

    private fun reminderLeadMs(): Long = (settings.raw(Settings.Key.REMINDER_LEAD_MINUTES)?.toLongOrNull() ?: 1L) * 60_000L

    private suspend fun runReminders() {
        // Due while the app was closed: they show as "On now" on Home, never as a late prompt.
        catchUpReminders()
        // Also on every change, so a reminder set just before its program fires at once.
        scope.launch { reminderList.collect { checkReminders() } }
        while (true) {
            checkReminders()
            delay(15_000)
        }
    }

    private val reminderCheck = kotlinx.coroutines.sync.Mutex()

    private suspend fun checkReminders() = reminderCheck.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        reminders.removeEnded(now)
        val due = reminders.all().firstOrNull { !it.fired && it.start - reminderLeadMs() <= now && now < it.stop }
        if (due != null) fireReminder(due)
    }

    private suspend fun catchUpReminders() {
        val now = Clock.System.now().toEpochMilliseconds()
        reminders.all().filter { !it.fired && it.start - reminderLeadMs() <= now }.forEach { reminders.markFired(it.id) }
    }

    private suspend fun fireReminder(r: Reminder) {
        reminders.markFired(r.id)
        val channel = channels.channelByKey(r.userChannelId) ?: return
        if (settings.bool(Settings.Key.REMINDER_AUTO_SWITCH, false)) {
            watchReminder(ActiveReminder(r, channel))
            return
        }
        val shown = ActiveReminder(r, channel)
        activeReminder = shown
        // It auto-dismisses after 15 seconds and never switches on its own.
        scope.launch {
            delay(15_000)
            if (activeReminder == shown) activeReminder = null
        }
    }

    /** The prompt's Watch: the channel full screen, in its own group. */
    fun watchReminder(active: ActiveReminder) {
        activeReminder = null
        val ch = active.channel
        val group = if (ch.isFavorite) ChannelGroup.Favorites else ChannelGroup.Provider(ch.stream.sourceId, ch.stream.groupName, ch.stream.groupName)
        panel.close()
        playback.tune(ch, fullScreen = true, group = group)
        navigator.go(Route.FullScreen(group))
    }

    fun dismissReminder() {
        activeReminder = null
    }

    /** Within ±2 hours, the same title on the same channel is the same program, moved. */
    private suspend fun followMovedPrograms() {
        val now = Clock.System.now().toEpochMilliseconds()
        for (r in reminders.all().filter { !it.fired && it.start > now }) {
            val channel = channels.channelByKey(r.userChannelId) ?: continue
            val window = 2 * 60 * 60_000L
            val match = guide.programs(channel, r.start - window, r.start + window)
                .filter { it.title == r.title }
                .minByOrNull { kotlin.math.abs(it.start - r.start) } ?: continue
            if (match.start != r.start || match.stop != r.stop) reminders.move(r.id, match.start, match.stop)
        }
    }

    // ---- Lifecycle (PLAN §4.2 → Sleep, the Home button and interruptions) ---------------------

    fun onBackground() {
        playback.release()
        settings.put(Settings.Key.BACKGROUNDED_AT, Clock.System.now().toEpochMilliseconds().toString())
    }

    fun onForeground() {
        val away = settings.raw(Settings.Key.BACKGROUNDED_AT)?.toLongOrNull()?.let { Clock.System.now().toEpochMilliseconds() - it }
        settings.put(Settings.Key.BACKGROUNDED_AT, null)
        scope.launch { catchUpReminders() }
        if (away != null && away > AWAY_RESETS_TO_HOME_MS && navigator.current != Route.Setup) {
            navigator.resetTo(Route.Home)
        } else if (away != null && navigator.current.showsVideo) {
            playback.resume()
        }
        scope.launch {
            val wasConnected = nas.status.value is com.signal.nas.NasConnection.Status.Connected
            nas.check()
            // Still connected, so the status collector won't fire: sync on resume here (PLAN §9.2).
            if (wasConnected && nas.status.value is com.signal.nas.NasConnection.Status.Connected) syncNow()
            if (nas.isPaired && !guideBuilding) installNasGuide()
        }
        scope.launch { platform.updater?.check() }
    }

    companion object {
        /** Away longer than this, the app opens on Home instead of resuming (PLAN §4.2). */
        const val AWAY_RESETS_TO_HOME_MS = 4 * 60 * 60_000L
        /** A NAS guide older than this is ignored, and the device builds its own: the nightly run must have stopped. */
        const val NAS_GUIDE_MAX_AGE_MS = 36 * 60 * 60_000L
        const val NAS_GUIDE_CHECK_MS = 30 * 60_000L
        /** A burst of edits (a move, a rename) goes as one sync. */
        const val SYNC_DEBOUNCE_MS = 2_000L
        /** While the event stream is down (PLAN §9.2). */
        const val SYNC_POLL_MS = 5 * 60_000L
    }
}

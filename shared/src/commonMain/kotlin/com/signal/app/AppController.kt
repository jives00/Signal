package com.signal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.signal.data.ChannelRepository
import com.signal.data.GroupEntry
import com.signal.data.Log
import com.signal.model.ChannelGroup
import com.signal.ui.menus.ItemMenus
import com.signal.ui.panel.PanelAction
import com.signal.ui.panel.PanelPage
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    val sources = SourceRepository(db, http, platform.secretBox, platform.io, log, vodFilter = { settings.vodCategoryFilter() })
    val channels = ChannelRepository(db, settings, platform.io)
    val switches = com.signal.data.SwitchRepository(db, settings, platform.io)
    // Posters the companion matched come from its image cache, or TMDB's CDN when it's out of reach (PLAN §4.3.12).
    val vod = com.signal.data.VodRepository(
        db, platform.io,
        providerPoster = { id -> nas.providerImageUrl(id) },
        tmdbPoster = { path -> nas.imageUrl(com.signal.nas.TmdbImages.POSTER, path) },
        cleanup = { settings.vodNameCleanup() },
        cleanupChanges = settings.changes().map { settings.raw(Settings.Key.VOD_NAME_CLEANUP) to settings.vodCategoryFilter().let { Triple(it.names, it.sort, it.order) } }.distinctUntilChanged(),
        categoryFilter = { settings.vodCategoryFilter() },
    )
    val vodDetails = com.signal.data.VodDetailRepository(db, sources, platform.io, log)
    val watch = com.signal.data.WatchRepository(db, settings, platform.io)
    val vodPlayback = VodPlayback(platform.player, sources, vodDetails, watch, scope, log)
    val playback = PlaybackController(platform.player, channels, sources, settings, scope, log, linkedStreams = { linkedStreams.value })
    // The NAS companion (PLAN §5.7): optional; the app works the same without it.
    val nas = com.signal.nas.NasConnection(settings, http, platform.secretBox, platform.appInfo, log)
    // VOD enrichment from the companion (PLAN §4.3.12): lists, genres, posters and metadata.
    val vodEnrichment = com.signal.data.VodEnrichment(db, settings, vod, { nas }, platform.io, log)
    /** Bumped when a new VOD index lands, so the browse pages reload their posters and shelves. */
    val vodVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    // Settings sync through the companion (PLAN §9).
    val sync = com.signal.sync.SyncEngine(db, settings, com.signal.sync.SyncTables(db, settings, platform.secretBox), platform.io, log)

    // The guide (PLAN §6). It reads and writes only guide.db, so it gets its own IO threads: on the
    // desktop user.db runs one thread at a time, and a guide build must never queue behind or block it.
    private val guideIo = Dispatchers.IO
    val guideStore = GuideStore(platform, guideIo, scope, log)
    val epg = EpgRepository(db, settings, platform.io)
    val guide = GuideRepository(guideStore, epg, settings, guideIo, scope) { log.info(it) }
    private val guideBuilder = GuideBuilder(http, { sources.byId(it) }, guideIo, log)
    val reminders = ReminderRepository(db, settings, channels, platform.io)
    val search = SearchRepository(db, settings, channels, guide, platform.io, scope) { log.info(it) }
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

    /** Titles per kind on enabled sources: Movies and Series show in the nav bar only when there are some (PLAN §4.1). */
    val movieCount: StateFlow<Long> = vod.count(com.signal.model.VodKind.MOVIE).stateIn(scope, SharingStarted.Eagerly, 0L)
    val seriesCount: StateFlow<Long> = vod.count(com.signal.model.VodKind.SERIES).stateIn(scope, SharingStarted.Eagerly, 0L)

    /** The Library, resume positions and watched state for this profile, kept live for posters and Home (PLAN §4.3.8–4.3.11). */
    val watchState: StateFlow<com.signal.data.WatchRepository.State> = watch.state().stateIn(scope, SharingStarted.Eagerly, com.signal.data.WatchRepository.State())

    /** Bumped when the catalog, the enabled sources or the TMDB matches change: saved titles may resolve elsewhere now. */
    val catalogVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** What Continue watching and the badges depend on besides the watch state: detail and the catalog. */
    private val vodInputs = combine(vodDetails.updates, catalogVersion) { a, b -> a to b }

    /** Home's Continue watching row (PLAN §4.3.1), rebuilt whenever the watch state, detail or the catalog changes. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<List<com.signal.data.ContinueWatching.Card>> = combine(watch.state(), vodInputs) { s, _ -> s }
        .mapLatest { com.signal.data.ContinueWatching.build(it, vod, vodDetails, Clock.System.now().toEpochMilliseconds()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Series keys with the "New episode" badge (PLAN §4.3.7). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val newEpisodes: StateFlow<Set<String>> = combine(watch.state(), vodInputs) { s, _ -> s }
        .mapLatest { com.signal.data.NewEpisodes.build(it, vod, vodDetails) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** User channels with more than one playable stream, for the Stream picker (PLAN §4.5). */
    val linkedStreams: StateFlow<Map<String, List<ChannelRepository.LinkedStream>>> = channels.multiLinked().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The sources, kept live, so Settings never shows an empty list while it reloads. */
    val sourceList: StateFlow<List<Source>> = sources.sources().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Settings → Appearance → Channel numbers (PLAN §4.3.2): off hides them wherever a channel is named. */
    val showNumbers: StateFlow<Boolean> = settings.changes().map { settings.bool(Settings.Key.SHOW_NUMBERS, true) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, settings.bool(Settings.Key.SHOW_NUMBERS, true))

    /** A channel's name as shown in titles: with its number in front, unless numbers are hidden. */
    fun channelLabel(channel: com.signal.model.Channel): String =
        if (showNumbers.value) "${channel.number}  ${channel.displayName}" else channel.displayName

    /** Live TV opens on this group; full screen's Back sets it to the current group (PLAN §4.3.3). */
    var liveTvGroup by mutableStateOf<ChannelGroup?>(null)

    /** The channel Live TV focuses when it opens, e.g. the one that was playing full screen. */
    var liveTvFocusKey by mutableStateOf<String?>(null)

    /** False until the first source check finishes, so first run never flashes Home. */
    var ready by mutableStateOf(false)
        private set

    /**
     * What Search opens with, set just before going there: Find in Movies/Series with no confident
     * match fills in the title, limited to Movies and Series (PLAN §4.3.4). Taken once by Search.
     */
    val searchRequest = kotlinx.coroutines.flow.MutableStateFlow<SearchRequest?>(null)

    data class SearchRequest(val query: String, val types: Set<String>)

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

    // Declared before the init blocks below: the coroutines they start can run before the constructor
    // reaches a later line, and would find these still null (seen on CI as an NPE in Mutex.withLock).

    /** True while the NAS's event stream is connected; Settings shows it. */
    val eventsConnected = kotlinx.coroutines.flow.MutableStateFlow(false)

    private var fullSyncDone = false

    private val reminderCheck = kotlinx.coroutines.sync.Mutex()

    init {
        scope.launch {
            val opening = kotlin.time.TimeSource.Monotonic.markNow()
            withContext(guideIo) { guideStore.open() }
            log.info("Guide: opened in ${opening.elapsedNow().inWholeMilliseconds} ms")
            if (sources.count() == 0L) navigator.resetTo(Route.Setup) else openStartPage()
            ready = true
            // Titles cached before Search and matching keys existed (user.db 7 → 8) get them now.
            runCatching { vod.fillMissingKeys() }.onSuccess { if (it > 0) log.info("VOD: keyed $it cached titles") }
            refreshStaleSources()
            sources.all().forEach { sources.refreshVodIfMissing(it.id) }
            refreshFollowedSeries()
            // Sources from before milestone 2 get their guides here too.
            sources.all().forEach { epg.ensureForSource(it) }
            // The NAS build first when paired; the device builds its own only when that gives nothing (PLAN §6).
            if (installNasGuide() == null && guideIsStale()) rebuildGuide()
        }
        // Hidden movie and series categories (PLAN §4.3.6 → Categories): titles cached before the filter
        // arrived, or before it changed, are dropped now. A category it shows again needs its source
        // refreshed, since its titles were never kept.
        scope.launch {
            var applied: com.signal.match.VodCategoryFilter? = null
            settings.changes().map { settings.vodCategoryFilter().visibility }.distinctUntilChanged().collect { filter ->
                val old = applied
                applied = filter
                runCatching { vod.dropHidden(filter) }.onSuccess { if (it > 0) log.info("VOD: dropped $it titles in hidden categories") }
                if (old != null) vod.sourcesUnhidden(old, filter).forEach { sources.refresh(it) }
            }
        }
        // New title cleanup rules: the grids reload, so the titles show them.
        scope.launch {
            settings.changes().map { settings.raw(Settings.Key.VOD_NAME_CLEANUP) }.distinctUntilChanged().drop(1).collect { vodVersion.value++ }
        }
        // The catalog changed (a refresh, a source enabled or removed, new TMDB matches): saved titles
        // find their copy in today's sources (PLAN §4.3.11 → Across providers).
        scope.launch {
            vod.catalogChanges().collect { catalogVersion.value++ }
        }
        scope.launch {
            catalogVersion.drop(1).debounce(CARRY_OVER_DEBOUNCE_MS).collect { carryOver() }
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
        // The profile's release channel (PLAN §4.3.15 → Release channels): stable only unless set to all.
        // A change (from the web admin, by sync) checks again, so the TV sees what it now may take.
        platform.updater?.stableOnly = { settings.raw(Settings.Key.UPDATE_CHANNEL) != UPDATE_ALL }
        scope.launch {
            settings.changes().map { settings.raw(Settings.Key.UPDATE_CHANNEL) }.distinctUntilChanged().collect { platform.updater?.check() }
        }
    }

    /** Library, resume and watched rows move to the copy of their title that today's sources have. */
    private suspend fun carryOver() {
        runCatching { watch.carryOver(vod) }
            .onSuccess { if (it > 0) log.info("Carry-over: $it titles moved to another source") }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log.warn("Carry-over failed: ${it.message}") }
    }

    /**
     * Followed series whose provider detail is over a day old are fetched again (PLAN §4.3.13), so the
     * New episode badge and Next up see new episodes. Done by the device with or without the NAS.
     */
    private suspend fun refreshFollowedSeries() {
        runCatching { com.signal.data.NewEpisodes.refresh(watch.current(), vod, vodDetails) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log.warn("Series refresh failed: ${it.message}") }
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

    /**
     * Push, pull, and act on what arrived: new sources load, a removed last source returns to setup,
     * and a guide change picks up the NAS's build. Returns false when the NAS couldn't be reached.
     */
    suspend fun syncNow(): Boolean {
        if (!nas.isPaired) return false
        // The first sync after launch is a full one (see SyncEngine.sync).
        val report = sync.sync(nas, full = !fullSyncDone) ?: return false
        fullSyncDone = true
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
        // Connected, so the VOD index too (PLAN §4.3.12); an unchanged one costs a few bytes.
        scope.launch { syncVod() }
        return true
    }

    /** Fetches the companion's VOD index if it changed. */
    suspend fun syncVod() {
        runCatching { vodEnrichment.sync() }
            .onSuccess { if (it) vodVersion.value++ }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log.warn("VOD index failed: ${it.message}") }
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
                        com.signal.sync.SyncEvent.VOD -> syncVod()
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

    /**
     * Settings → General → Start page (milestone 6): where launch, and coming back after a long time
     * away, open. Home unless set; Movies and Series fall back to Home with nothing in them, and the
     * last channel to Home when there isn't one. Back from it works as from anywhere else.
     */
    suspend fun openStartPage() {
        when (StartPage.from(settings.raw(Settings.Key.START_PAGE))) {
            StartPage.HOME -> navigator.resetTo(Route.Home)
            StartPage.LIVE_TV -> navigator.resetTo(Route.LiveTv)
            StartPage.MOVIES -> navigator.resetTo(if (vod.count(com.signal.model.VodKind.MOVIE).first() > 0) Route.Movies else Route.Home)
            StartPage.SERIES -> navigator.resetTo(if (vod.count(com.signal.model.VodKind.SERIES).first() > 0) Route.Series else Route.Home)
            StartPage.LAST_CHANNEL -> {
                val ch = playback.startingChannel()
                if (ch == null) { navigator.resetTo(Route.Home); return }
                val group = if (ch.isFavorite) ChannelGroup.Favorites else ChannelGroup.Provider(ch.stream.sourceId, ch.stream.groupName, ch.stream.groupName)
                navigator.resetTo(Route.LiveTv)
                playback.tune(ch, fullScreen = true, group = group)
                navigator.go(Route.FullScreen(group))
            }
        }
    }

    /** The prompt's Watch: the channel full screen, in its own group. */
    fun watchReminder(active: ActiveReminder) {
        activeReminder = null
        watch(active.channel)
    }

    /** A channel full screen, in its own group, from wherever the app is. */
    private fun watch(ch: com.signal.model.Channel) {
        // During a movie, this saves the position first (PLAN §4.3.10).
        if (navigator.current == Route.VodPlayer) vodPlayback.stop()
        val group = if (ch.isFavorite) ChannelGroup.Favorites else ChannelGroup.Provider(ch.stream.sourceId, ch.stream.groupName, ch.stream.groupName)
        panel.close()
        playback.tune(ch, fullScreen = true, group = group)
        if (navigator.current !is Route.FullScreen) navigator.go(Route.FullScreen(group))
    }

    /**
     * Acts on what the assistant heard (PLAN §4.3.14 → Voice commands): the recognizer's guesses,
     * best first. A playback, page or player command acts on what's showing. Otherwise the first
     * guess that names a channel wins, so a top guess of "a b d" loses to a second of "a b c". One
     * clear channel is watched at once; several, or a weak match, open a choice of the top three.
     * Returns what it did, for the log and the listening overlay.
     */
    suspend fun voice(guesses: List<String>, free: String? = null): VoiceResult {
        val best = guesses.firstOrNull { it.isNotBlank() }.orEmpty()
        val heardFree = free?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = guesses.mapNotNull { heard -> com.signal.voice.VoiceCommand.parse(heard)?.let { heard to it } }
        fun result(acted: Boolean, heard: String, shown: String, detail: String = shown) =
            VoiceResult(acted, heard, best, "Voice: \"$heard\" → $detail (guesses: ${guesses.joinToString(" | ")}; free: ${heardFree ?: "-"})", shown).also { log.info(it.message) }
        // After startup's own start page, on the main thread with the screens.
        withContext(Dispatchers.Main) { androidx.compose.runtime.snapshotFlow { ready }.first { it } }

        // A game, or a reminder for one, as the grammar heard it: it knows the teams, and the free
        // recognizer doesn't ("the chicago bears game" came back as "the chicago bears team").
        parsed.firstOrNull()?.let { (heard, c) ->
            if ("[unk]" !in heard && (c is com.signal.voice.VoiceCommand.Game || (c is com.signal.voice.VoiceCommand.Remind && c.team != null))) {
                return voiceTitle(heard, c, ::result)
            }
        }
        // "Play ___" is Movies & Series, always; "watch ___" or a bare name is live TV, always (user,
        // 2026-10-16): "watch friends" played the series when Friends wasn't on, and "watch charlotte"
        // a movie by that name. "Continue ___" is Movies & Series, "search ___" all of Search.
        val plays = com.signal.voice.VoiceCommand.saysPlay(heardFree ?: best) || parsed.any { com.signal.voice.VoiceCommand.saysPlay(it.first) }

        // A title as the grammar heard it, whole and naming a title exactly: the likely titles are in
        // the grammar, and it hears them far better than the free recognizer ("play the office season
        // five episode two" came back from that as "wade the author he than five episode two", 0.2.28).
        parsed.firstOrNull()?.let { (heard, c) ->
            if ("[unk]" in heard) return@let
            when (c) {
                is com.signal.voice.VoiceCommand.PlayTitle ->
                    if (hasExactTitle(c.title, c.kind)) return voiceTitle(heard, c, ::result)
                is com.signal.voice.VoiceCommand.ContinueTitle ->
                    if (hasExactTitle(c.title, null)) return voiceTitle(heard, c, ::result)
                is com.signal.voice.VoiceCommand.Tune -> if (plays) {
                    for (name in c.names) if (hasExactTitle(name, null)) return voiceTitle(heard, com.signal.voice.VoiceCommand.PlayTitle(name), ::result)
                }
                else -> {}
            }
        }
        // A movie or series command, as the free recognizer heard it: the grammar knows few titles.
        val freeCommand = heardFree?.let { com.signal.voice.VoiceCommand.parse(it) }
        if (freeCommand != null && freeCommand.isTitleCommand) return voiceTitle(heardFree, freeCommand, ::result)
        val (freeTitle, _) = heardFree?.let { com.signal.voice.VoiceCommand.spokenTitle(it) } ?: ("" to false)

        val tunes = ArrayList<Pair<String, String>>()
        for ((heard, command) in parsed) {
            // Words outside the grammar make a guess a name, never a command: "play [unk]" was
            // "play inception", not "play".
            val whole = "[unk]" !in heard
            when (command) {
                is com.signal.voice.VoiceCommand.Tune -> {
                    // One letter is never enough to name a channel ("v" found VH1).
                    // A filler alone ("watch the", cut short) is no name: it found The Weather Channel.
                    if (command.channel.replace(" ", "").length < 2 || command.channel in com.signal.voice.VoiceCommand.FILLER_NAMES) continue
                    for (name in command.names) {
                        tunes += heard to name
                        // "Play ___" never tunes a channel: it's a title, looked for below.
                        if (plays) continue
                        val hits = search.channelHits(name, limit = VOICE_HITS, forVoice = true)
                        if (hits.isEmpty()) continue
                        // Not one clear channel: a program on now by that name ("watch friends").
                        val pick = com.signal.voice.VoiceChoice.choose(hits, { it.channel.displayName }, { it.exact }, { it.favorite })
                        if (!(pick is com.signal.voice.VoiceChoice.Pick.One && pick.hit.exact)) {
                            for (title in listOf(freeTitle, name).filter { it.isNotBlank() }.distinct()) {
                                onNow(title).takeIf { it.isNotEmpty() }?.let { return voiceOnNow(heard, it, ::result) }
                            }
                        }
                        return voiceChannel(heard, hits, "", ::result)
                    }
                }
                is com.signal.voice.VoiceCommand.WhatsOn -> return voiceWhatsOn(heard, command.channel, ::result)
                // A title command in the grammar's words, when the free recognizer heard it too: its
                // title, not the grammar's channel words, and that was handled above.
                else -> if (whole && !(command.isTitleCommand && heardFree != null)) {
                    // The first guess that's a command is taken as said.
                    return if (command.isTitleCommand) voiceTitle(heard, command, ::result)
                    else withContext(Dispatchers.Main) { voiceCommand(heard, command, ::result) }
                }
            }
        }
        val names = (listOf(freeTitle) + tunes.map { it.second }).filter { it.replace(" ", "").length >= 2 }.distinct()
        if (!plays) {
            // Initials that sound like a guess's letters ("e a b t" → ABC).
            val initials = if (tunes.isEmpty()) emptySet() else search.voiceNames().second
            for ((heard, name) in tunes) {
                for (like in com.signal.voice.VoiceCommand.soundsLike(name, initials)) {
                    val hits = search.channelHits(like, limit = VOICE_HITS, forVoice = true)
                    if (hits.isNotEmpty()) return voiceChannel(heard, hits, " by sound (\"$like\")", ::result)
                }
            }
            // No channel: a program on now by that name ("watch friends").
            for (title in names) onNow(title).takeIf { it.isNotEmpty() }?.let { return voiceOnNow(heardFree ?: best, it, ::result) }
        } else {
            // A movie or series by that name. Any match for what the free recognizer heard; only an
            // exact one for the grammar's guesses, which were "play me truckers" for "play matrix".
            if (heardFree != null && freeTitle.replace(" ", "").length >= 2 && titleHits(freeTitle, null).isNotEmpty()) {
                return voiceTitle(heardFree, com.signal.voice.VoiceCommand.PlayTitle(freeTitle), ::result)
            }
            for ((heard, title) in tunes) exactTitle(title)?.let { return voicePlay(heard, it, null, null, ::result) }
        }
        // A command only the free recognizer caught.
        when (freeCommand) {
            null, is com.signal.voice.VoiceCommand.Tune -> {}
            is com.signal.voice.VoiceCommand.WhatsOn -> return voiceWhatsOn(heardFree, freeCommand.channel, ::result)
            else -> return withContext(Dispatchers.Main) { voiceCommand(heardFree, freeCommand, ::result) }
        }
        val tried = names.firstOrNull() ?: return result(false, best, "Didn't catch that", "nothing")
        // "Play ___" that matched nothing: Search, with it filled in.
        if (plays) return voiceSearch(heardFree ?: best, tried, ::result)
        // "Watch ___" that isn't on now: when it's next on, if it's in the guide.
        nextAiring(tried)?.let { return result(false, heardFree ?: best, it, "not on now") }
        return result(false, heardFree ?: best, "No channel or show on now matches \"$tried\"", "no match")
    }

    /** "Friends is next on TBS at 2:30 PM": a program's next airing, on your channels first, for "watch ___" when it isn't on now. */
    private suspend fun nextAiring(title: String): String? {
        val wanted = com.signal.match.SearchText.fold(title)
        if (wanted.replace(" ", "").length < 2) return null
        val now = Clock.System.now().toEpochMilliseconds()
        val upcoming = search.programs(title, now, limit = 40).filter {
            it.program.start > now && com.signal.match.SearchText.fold(com.signal.guide.GameTitles.withoutMatchup(it.program.title)) == wanted
        }
        if (upcoming.isEmpty()) return null
        val mine = search.voiceStreamIds()
        val next = upcoming.minWith(compareBy<SearchRepository.ProgramHit>({ it.channel.stream.id !in mine }, { it.program.start }))
        val time = com.signal.ui.screens.programTime(this, next.program, now).substringBefore(" –")
        return "${next.program.title} isn't on now · next on ${next.channel.displayName}, $time"
    }

    private val com.signal.voice.VoiceCommand.isTitleCommand: Boolean get() =
        this is com.signal.voice.VoiceCommand.Search || this is com.signal.voice.VoiceCommand.PlayTitle || this is com.signal.voice.VoiceCommand.ContinueTitle ||
            this is com.signal.voice.VoiceCommand.Game || this is com.signal.voice.VoiceCommand.Remind

    /** A title search hit: [exact] when the whole title was said, [saved] when it's in the Library or Continue watching. */
    private class TitleHit(val item: com.signal.model.VodItem, val exact: Boolean, val saved: Boolean) {
        val name get() = "${item.kind}|${com.signal.data.VodRepository.displayName(item).lowercase()}|${item.year ?: ""}"
    }

    /**
     * Movies and series matching a spoken title, exact ones first. Spoken numbers are tried as
     * words, then as digits ("apollo thirteen", then "apollo 13").
     */
    private suspend fun titleHits(title: String, kind: com.signal.model.VodKind?): List<TitleHit> {
        val state = watchState.value
        val cards = continueWatching.value.map { it.item.id }.toSet()
        for (variant in listOf(title, com.signal.voice.SpokenNumbers.toDigits(title)).distinct()) {
            val key = com.signal.match.VodTitles.nameKey(variant)
            if (key.isEmpty()) continue
            val items = (kind?.let { listOf(it) } ?: com.signal.model.VodKind.entries).flatMap { k ->
                runCatching { vod.search(variant, k, VOICE_TITLE_HITS) }.getOrDefault(emptyList())
            }
            if (items.isEmpty()) continue
            return items.map { item ->
                val exact = item.titleKey == key || com.signal.match.VodTitles.nameKey(com.signal.data.VodRepository.displayName(item)) == key
                TitleHit(item, exact, item.id in cards || state.inLibrary(com.signal.data.ItemKeys.of(item)))
            }.sortedBy { !it.exact }
        }
        return emptyList()
    }

    /** Whether some title of [kind] (any, when null) is named exactly [title]: several may be ("The Office", US and UK). */
    private suspend fun hasExactTitle(title: String, kind: com.signal.model.VodKind?): Boolean =
        title.replace(" ", "").length >= 2 && titleHits(title, kind).any { it.exact }

    /** The one title named exactly [title], if there's exactly one. */
    private suspend fun exactTitle(title: String): com.signal.model.VodItem? {
        if (title.replace(" ", "").length < 2) return null
        val pick = titleChoice(titleHits(title, null))
        return (pick as? com.signal.voice.VoiceChoice.Pick.One)?.hit?.takeIf { it.exact }?.item
    }

    private fun titleChoice(hits: List<TitleHit>) = com.signal.voice.VoiceChoice.choose(hits, { it.name }, { it.exact }, { it.saved })

    /** "Search ___", "play the movie ___", "play ___ season 2 episode 5", "continue ___". */
    private suspend fun voiceTitle(heard: String, command: com.signal.voice.VoiceCommand, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        val (title, kind, season, episode) = when (command) {
            is com.signal.voice.VoiceCommand.Search -> return voiceSearch(heard, command.query, result)
            is com.signal.voice.VoiceCommand.Game -> return voiceGame(heard, command.team, result)
            is com.signal.voice.VoiceCommand.Remind -> return voiceRemind(heard, command, result)
            is com.signal.voice.VoiceCommand.PlayTitle -> listOf(command.title, command.kind, command.season, command.episode)
            is com.signal.voice.VoiceCommand.ContinueTitle -> {
                // Continue watching first: what's there is what "continue" means.
                val wanted = com.signal.match.VodTitles.nameKey(command.title)
                val card = continueWatching.value.firstOrNull { com.signal.match.VodTitles.nameKey(it.title) == wanted }
                    ?: continueWatching.value.firstOrNull { com.signal.match.VodTitles.nameKey(it.title).startsWith(wanted) && wanted.isNotEmpty() }
                if (card != null) return voicePlay(heard, card.item, card.episode?.season, card.episode?.episode, result)
                listOf(command.title, null, null, null)
            }
            else -> return result(false, heard, "Didn't catch that", "nothing")
        }
        title as String
        val hits = titleHits(title, kind as com.signal.model.VodKind?)
        return when (val pick = titleChoice(hits)) {
            null -> voiceSearch(heard, title, result)
            is com.signal.voice.VoiceChoice.Pick.One ->
                if (pick.hit.exact) voicePlay(heard, pick.hit.item, season as Int?, episode as Int?, result)
                else voiceAskTitle(heard, listOf(pick.hit), season as Int?, episode as Int?, result)
            is com.signal.voice.VoiceChoice.Pick.Several -> voiceAskTitle(heard, pick.hits, season as Int?, episode as Int?, result)
        }
    }

    // ---- Games and reminders (PLAN §4.3.14 → Voice commands, phase 2, step 4) ------------------

    /**
     * Games with [team] in their title's teams (PLAN §6 → Game titles), from now on, on visible
     * channels. "Steelers" matches "Pittsburgh Steelers" by its words, so does "Pittsburgh".
     */
    private suspend fun gameHits(team: String, now: Long): List<SearchRepository.ProgramHit> {
        val wanted = com.signal.match.SearchText.fold(team)
        if (wanted.isEmpty()) return emptyList()
        return search.programs(team, now, limit = 60).filter { h ->
            val m = com.signal.guide.GameTitles.matchup(h.program.title) ?: return@filter false
            listOf(m.away, m.home).any { " ${com.signal.match.SearchText.fold(it)} ".contains(" $wanted ") }
        }
    }

    /** Programs on now titled exactly [title] (teams aside), on visible channels: "watch friends" is the airing. */
    private suspend fun onNow(title: String): List<SearchRepository.ProgramHit> {
        val wanted = com.signal.match.SearchText.fold(title)
        if (wanted.replace(" ", "").length < 2) return emptyList()
        val now = Clock.System.now().toEpochMilliseconds()
        return search.programs(title, now, limit = 40).filter {
            it.program.isOn(now) && com.signal.match.SearchText.fold(com.signal.guide.GameTitles.withoutMatchup(it.program.title)) == wanted
        }
    }

    /** Tunes a program on now: one channel at once, a favorite when it's on several, else a choice. */
    private suspend fun voiceOnNow(heard: String, hits: List<SearchRepository.ProgramHit>, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult =
        withContext(Dispatchers.Main) {
            when (val pick = com.signal.voice.VoiceChoice.choose(hits, { it.channel.displayName }, { true }, { it.channel.isFavorite })) {
                null -> result(false, heard, "Nothing on now", "nothing on")
                is com.signal.voice.VoiceChoice.Pick.One -> {
                    val ch = pick.hit.channel
                    inFront { watch(ch) }
                    result(true, heard, "${pick.hit.program.title} on ${ch.displayName}", "on now on ${ch.displayName}")
                }
                is com.signal.voice.VoiceChoice.Pick.Several -> {
                    inFront { voiceAsk(heard, pick.hits.map { it.channel }) { ch -> watch(ch); null } }
                    result(true, heard, "Choose a channel", "on now: a choice")
                }
            }
        }

    /**
     * [team]'s games on the channels voice matches (favorites, playlists, voice names). A game only
     * elsewhere isn't taken: a reminder landed on NBC Sports Philadelphia, which isn't a favorite
     * (user, 2026-10-16). Then [elsewhere] gets a message naming where the next one is.
     */
    private suspend inline fun yourGames(team: String, now: Long, elsewhere: (String) -> Nothing): List<SearchRepository.ProgramHit> {
        val all = gameHits(team, now)
        val mine = search.voiceStreamIds()
        val yours = all.filter { it.channel.stream.id in mine }
        if (yours.isEmpty() && all.isNotEmpty()) {
            val next = all.minBy { it.program.start }
            val time = if (next.program.isOn(now)) "on now" else com.signal.ui.screens.programTime(this, next.program, now).substringBefore(" –")
            elsewhere("No ${team.replaceFirstChar { it.uppercase() }} game on your channels · next is on ${next.channel.displayName}, $time")
        }
        return yours
    }

    /** "Pittsburgh Steelers vs. Cleveland Browns", or the title when it names no teams. */
    private fun gameName(p: com.signal.model.Program) = com.signal.guide.GameTitles.matchup(p.title)?.text ?: p.title

    /** "Watch the Steelers game": the game on now. One tunes; several (or several channels) ask. */
    private suspend fun voiceGame(heard: String, team: String, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        val now = Clock.System.now().toEpochMilliseconds()
        val hits = yourGames(team, now) { return result(false, heard, it, "game elsewhere") }
        val on = hits.filter { it.program.isOn(now) }
        if (on.isEmpty()) {
            val next = hits.filter { it.program.start > now }.minByOrNull { it.program.start }
                ?: return result(false, heard, "No ${team.replaceFirstChar { it.uppercase() }} game in the guide", "no game")
            val time = com.signal.ui.screens.programTime(this, next.program, now).substringBefore(" –")
            return result(false, heard, "${gameName(next.program)} starts $time on ${next.channel.displayName}", "game later")
        }
        return withContext(Dispatchers.Main) {
            when (val pick = com.signal.voice.VoiceChoice.choose(on, { "${it.program.title}|${it.channel.displayName}" }, { true }, { it.channel.isFavorite })) {
                null -> result(false, heard, "No game on", "no game")
                is com.signal.voice.VoiceChoice.Pick.One -> {
                    val ch = pick.hit.channel
                    inFront { watch(ch) }
                    result(true, heard, "${gameName(pick.hit.program)} on ${ch.displayName}", "game on ${ch.displayName}")
                }
                is com.signal.voice.VoiceChoice.Pick.Several -> {
                    inFront {
                        panel.open(
                            PanelPage.Actions(
                                "Which game?",
                                pick.hits.map { h -> PanelAction(gameName(h.program), detail = channelLabel(h.channel)) { watch(h.channel); null } },
                                subtitle = "Heard \"$heard\"",
                            ),
                            null,
                        )
                    }
                    result(true, heard, "Choose a game", "a choice of games")
                }
            }
        }
    }

    /**
     * "Remind me about Jeopardy" or "… the Steelers game": a reminder on the next airing (PLAN
     * §4.3.4 → Reminders). An exact title is preferred; several titles ask, a game takes its next one.
     */
    private suspend fun voiceRemind(heard: String, command: com.signal.voice.VoiceCommand.Remind, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        val now = Clock.System.now().toEpochMilliseconds()
        val team = command.team
        val hits = if (team != null) yourGames(team, now) { return result(false, heard, it, "game elsewhere") } else search.programs(command.program, now, limit = 60)
        val upcoming = hits.filter { it.program.start > now }
        if (upcoming.isEmpty()) {
            val on = hits.firstOrNull { it.program.isOn(now) }
                ?: return result(false, heard, "Nothing called \"${command.program}\" in the guide", "no program")
            return result(false, heard, "${gameName(on.program)} is on now on ${on.channel.displayName}", "on now")
        }
        val wanted = com.signal.match.SearchText.fold(command.program)
        val exact = upcoming.filter { com.signal.match.SearchText.fold(it.program.title) == wanted }
        // Each title's next airing, a favorite channel first when it airs on several at once.
        val firsts = if (team != null) {
            // The team's next game, on a favorite channel when one carries it: the earliest airing
            // anywhere picked NBC Sports Philadelphia, which isn't one (0.2.28). Airings of that game
            // starting within a few hours count, since a regional feed can start earlier.
            val next = upcoming.minOf { it.program.start }
            val game = upcoming.filter { it.program.start - next <= GAME_WINDOW_MS }
            listOfNotNull(game.minWithOrNull(compareBy<SearchRepository.ProgramHit>({ !it.channel.isFavorite }, { it.program.start })))
        } else {
            exact.ifEmpty { upcoming }
                .sortedWith(compareBy<SearchRepository.ProgramHit>({ it.program.start }, { !it.channel.isFavorite }))
                .distinctBy { it.program.title }
        }
        val choices = if (team != null || exact.isNotEmpty()) firsts.take(1) else firsts.take(com.signal.voice.VoiceChoice.CHOICES)
        if (choices.size == 1) return remindOf(heard, choices[0], now, result)
        return withContext(Dispatchers.Main) {
            inFront {
                panel.open(
                    PanelPage.Actions(
                        "Remind me about…",
                        choices.map { h ->
                            PanelAction(gameName(h.program), detail = "${com.signal.ui.screens.programTime(this@AppController, h.program, now)} · ${h.channel.displayName}") {
                                scope.launch { reminders.add(h.channel, h.program) }
                                null
                            }
                        },
                        subtitle = "Heard \"$heard\"",
                    ),
                    null,
                )
            }
            result(true, heard, "Choose a program", "a choice of programs")
        }
    }

    private suspend fun remindOf(heard: String, hit: SearchRepository.ProgramHit, now: Long, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        val time = com.signal.ui.screens.programTime(this, hit.program, now).substringBefore(" –")
        val what = "${gameName(hit.program)}, $time on ${hit.channel.displayName}"
        val userChannel = channels.userChannelIdFor(hit.channel)
        if (reminders.find(reminderList.value, userChannel, hit.program) != null) return result(false, heard, "Already reminding you: $what", "already set")
        reminders.add(hit.channel, hit.program)
        return result(true, heard, "Reminder set: $what", "reminder")
    }

    /** Search, with [query] filled in and every type shown. */
    private suspend fun voiceSearch(heard: String, query: String, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult =
        withContext(Dispatchers.Main) {
            inFront {
                panel.close()
                if (navigator.current == Route.VodPlayer) vodPlayback.stop()
                if (navigator.current.showsVideo) playback.stop()
                searchRequest.value = SearchRequest(query, emptySet())
                navigator.go(Route.Search)
            }
            result(true, heard, "Searching \"$query\"", "search")
        }

    /** The choice of titles, like channels': each row plays its title. */
    private suspend fun voiceAskTitle(heard: String, hits: List<TitleHit>, season: Int?, episode: Int?, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult =
        withContext(Dispatchers.Main) {
            inFront {
                panel.open(
                    PanelPage.Actions(
                        "Which one?",
                        hits.map { h ->
                            val what = if (h.item.kind == com.signal.model.VodKind.MOVIE) "Movie" else "Series"
                            PanelAction(com.signal.data.VodRepository.displayName(h.item), detail = listOfNotNull(what, h.item.year?.toString()).joinToString(" · ")) {
                                scope.launch { startTitle(h.item, season, episode) }
                                null
                            }
                        },
                        subtitle = "Heard \"$heard\"",
                    ),
                    null,
                )
            }
            result(true, heard, "Choose a title", "a choice of ${hits.joinToString { com.signal.data.VodRepository.displayName(it.item) }}")
        }

    /** Plays a title now, or once Signal is in front. */
    private suspend fun voicePlay(heard: String, item: com.signal.model.VodItem, season: Int?, episode: Int?, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        val name = com.signal.data.VodRepository.displayName(item)
        if (inBackground) {
            voicePending = { scope.launch { startTitle(item, season, episode) } }
            return result(true, heard, name, name)
        }
        val error = startTitle(item, season, episode)
        return if (error == null) result(true, heard, name, name) else result(false, heard, error, error)
    }

    /**
     * Plays a movie (resuming it, from the preferred source) or a series' episode: the one asked
     * for, else where the series is up to (PLAN §4.3.9 → Continue). Returns why not, or null.
     */
    private suspend fun startTitle(item: com.signal.model.VodItem, season: Int?, episode: Int?): String? {
        if (item.kind == com.signal.model.VodKind.MOVIE) {
            val detail = vodDetails.movie(item)
            withContext(Dispatchers.Main) { leaveForVod() }
            vodPlayback.playMovie(vod.copies(item).first(), asKey = com.signal.data.ItemKeys.movie(item, detail?.tmdbId ?: item.tmdbId))
        } else {
            val detail = vodDetails.series(item) ?: return "Can't load ${com.signal.data.VodRepository.displayName(item)}"
            val seriesKey = com.signal.data.ItemKeys.series(item, detail.tmdbId ?: item.tmdbId)
            val e = if (season != null && episode != null) {
                detail.episodes.firstOrNull { it.season == season && it.episode == episode } ?: return "No season $season episode $episode"
            } else {
                com.signal.ui.screens.seriesNext(seriesKey, detail, watchState.value)?.episode ?: return "No episodes"
            }
            withContext(Dispatchers.Main) { leaveForVod() }
            vodPlayback.unattendedEpisodes = 0
            vodPlayback.playEpisode(item, detail, e)
        }
        withContext(Dispatchers.Main) { if (navigator.current != Route.VodPlayer) navigator.go(Route.VodPlayer) }
        return null
    }

    /** Before a movie plays from voice: the panel closes, and live video stops. A movie playing saves its place. */
    private fun leaveForVod() {
        panel.close()
        if (navigator.current == Route.VodPlayer) vodPlayback.saveNow()
        else if (navigator.current.showsVideo) playback.stop()
    }

    private suspend fun voiceChannel(
        heard: String,
        hits: List<SearchRepository.ChannelHit>,
        how: String,
        result: (Boolean, String, String, String) -> VoiceResult,
    ): VoiceResult = withContext(Dispatchers.Main) {
        when (val pick = com.signal.voice.VoiceChoice.choose(hits, { it.channel.displayName }, { it.exact }, { it.favorite })) {
            is com.signal.voice.VoiceChoice.Pick.One -> {
                val ch = pick.hit.channel
                inFront { watch(ch) }
                result(true, heard, ch.displayName, "${ch.displayName}$how")
            }
            is com.signal.voice.VoiceChoice.Pick.Several -> {
                inFront { voiceAsk(heard, pick.hits.map { it.channel }) { ch -> watch(ch); null } }
                result(true, heard, "Choose a channel", "a choice of ${pick.hits.joinToString { it.channel.displayName }}$how")
            }
            null -> result(false, heard, "No channel matches \"$heard\"", "no channel")
        }
    }

    /** "What's on ESPN?": the channel's program now, in program details, without tuning. */
    private suspend fun voiceWhatsOn(heard: String, name: String?, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        val now = Clock.System.now().toEpochMilliseconds()
        suspend fun details(ch: Channel): PanelPage? =
            guide.programs(ch, now, now + 1).firstOrNull { it.isOn(now) }?.let { com.signal.ui.screens.programDetails(this, ch, it, now) }
        if (name == null) {
            val ch = withContext(Dispatchers.Main) { playback.current.value?.takeIf { navigator.current.showsVideo && !inBackground } }
                ?: return result(false, heard, "No channel is playing", "nothing playing")
            val page = details(ch) ?: return result(false, heard, "No guide for ${ch.displayName}", "no guide")
            withContext(Dispatchers.Main) { panel.open(page, null) }
            return result(true, heard, ch.displayName, "what's on ${ch.displayName}")
        }
        val hits = com.signal.voice.VoiceCommand.Tune(name).names
            .firstNotNullOfOrNull { n -> search.channelHits(n, limit = VOICE_HITS, forVoice = true).ifEmpty { null } }.orEmpty()
        return withContext(Dispatchers.Main) {
            when (val pick = com.signal.voice.VoiceChoice.choose(hits, { it.channel.displayName }, { it.exact }, { it.favorite })) {
                null -> result(false, heard, "No channel matches \"$name\"", "no channel")
                is com.signal.voice.VoiceChoice.Pick.One -> {
                    val ch = pick.hit.channel
                    val page = details(ch) ?: return@withContext result(false, heard, "No guide for ${ch.displayName}", "no guide")
                    inFront { panel.open(page, null) }
                    result(true, heard, ch.displayName, "what's on ${ch.displayName}")
                }
                is com.signal.voice.VoiceChoice.Pick.Several -> {
                    // Looked up now, so a channel without a guide shows as such in the choice.
                    val pages = pick.hits.associate { it.channel.key to details(it.channel) }
                    inFront { voiceAsk(heard, pick.hits.map { it.channel }) { ch -> pages[ch.key] ?: PanelPage.Actions("No guide for ${ch.displayName}", emptyList()) } }
                    result(true, heard, "Choose a channel", "what's on: a choice")
                }
            }
        }
    }

    /** The choice of channels for a weak or shared match (PLAN §4.3.14 → Voice commands, phase 2). */
    private fun voiceAsk(heard: String, channels: List<Channel>, onPick: (Channel) -> PanelPage?) {
        panel.open(
            PanelPage.Actions(
                "Which channel?",
                channels.map { ch -> PanelAction(channelLabel(ch), detail = groupTitle(ch)) { onPick(ch) } },
                subtitle = "Heard \"$heard\"",
            ),
            null,
        )
    }

    /** A channel's group as Live TV shows it: renamed, or with name cleanup applied. */
    private fun groupTitle(ch: Channel): String = groups.value.firstNotNullOfOrNull { e ->
        (e.group as? ChannelGroup.Provider)?.takeIf { it.sourceId == ch.stream.sourceId && it.name == ch.stream.groupName }?.title
    } ?: ch.groupName

    /** Runs [block] now, or when Signal comes back to the front if a press reached it from another app. */
    private fun inFront(block: () -> Unit) {
        if (inBackground) voicePending = block else block()
    }

    /** A playback, page or player command, on the main thread. */
    private fun voiceCommand(heard: String, command: com.signal.voice.VoiceCommand, result: (Boolean, String, String, String) -> VoiceResult): VoiceResult {
        fun done(shown: String) = result(true, heard, shown, shown)
        fun fail(shown: String) = result(false, heard, shown, shown)
        val vod = vodPlayback.current.value?.takeIf { navigator.current == Route.VodPlayer && !inBackground }
        val live = playback.current.value?.takeIf { navigator.current.showsVideo && !inBackground }
        val nothing = "Nothing is playing"
        val player = platform.player
        return when (command) {
            com.signal.voice.VoiceCommand.Pause -> when {
                vod != null -> { vodPlayback.setPaused(true); done("Paused") }
                // Live TV has no timeshift (PLAN §1 Out of scope). Stopping it only left a grey
                // screen (user, 0.2.20), so it says so and carries on.
                live != null -> fail("Live TV can't pause")
                else -> fail(nothing)
            }
            com.signal.voice.VoiceCommand.Resume -> when {
                vod != null -> { vodPlayback.setPaused(false); done("Playing") }
                live != null -> { playback.resumeIfStopped(); done("Playing ${live.displayName}") }
                else -> fail(nothing)
            }
            com.signal.voice.VoiceCommand.Stop -> when {
                vod != null -> { vodPlayback.stop(); navigator.back(); done("Stopped") }
                // Like pause: stopping live TV only left a grey screen (user, 2026-10-15).
                live != null -> fail("Live TV can't stop")
                else -> fail(nothing)
            }
            com.signal.voice.VoiceCommand.StartOver -> when {
                vod != null -> { player.seekTo(0); vodPlayback.setPaused(false); done("From the beginning") }
                live != null -> fail("Live TV can't start over")
                else -> fail(nothing)
            }
            is com.signal.voice.VoiceCommand.Seek -> when {
                vod != null -> {
                    val pos = player.position()
                    player.seekTo((pos.positionMs + command.byMs).coerceIn(0, (pos.durationMs - 1_000).coerceAtLeast(0)))
                    done("${if (command.byMs < 0) "Back" else "Ahead"} ${spokenDuration(kotlin.math.abs(command.byMs))}")
                }
                live != null -> fail("Live TV can't skip ${if (command.byMs < 0) "back" else "ahead"}")
                else -> fail(nothing)
            }
            com.signal.voice.VoiceCommand.Next ->
                if (vod != null) voiceCommand(heard, com.signal.voice.VoiceCommand.NextEpisode, result)
                else voiceCommand(heard, com.signal.voice.VoiceCommand.ChannelStep(1), result)
            com.signal.voice.VoiceCommand.NextEpisode -> {
                val next = vod?.next
                val series = vod?.series
                when {
                    vod == null -> fail("No episode is playing")
                    next == null || series == null -> fail("No next episode")
                    else -> {
                        vodPlayback.finished()
                        scope.launch { vodPlayback.playEpisode(vod.item, series, next) }
                        done("${next.code} · ${next.title}")
                    }
                }
            }
            is com.signal.voice.VoiceCommand.ChannelStep -> {
                if (navigator.current == Route.VodPlayer) return fail("Not on live TV")
                if (playback.current.value == null) return fail("No channel is playing")
                inFront {
                    scope.launch {
                        playback.zap(command.step)
                        if (navigator.current !is Route.FullScreen) navigator.go(Route.FullScreen(playback.currentGroup))
                    }
                }
                done(if (command.step < 0) "Channel up" else "Channel down")
            }
            com.signal.voice.VoiceCommand.LastChannel -> {
                val ch = playback.previousChannel ?: return fail("No last channel")
                inFront {
                    if (navigator.current == Route.VodPlayer) vodPlayback.stop()
                    playback.recall()
                    if (navigator.current !is Route.FullScreen) navigator.go(Route.FullScreen(playback.currentGroup))
                }
                done(ch.displayName)
            }
            is com.signal.voice.VoiceCommand.Open -> {
                val page = command.page
                if (page == com.signal.voice.VoiceCommand.Page.MOVIES && movieCount.value == 0L) return fail("No movies")
                if (page == com.signal.voice.VoiceCommand.Page.SERIES && seriesCount.value == 0L) return fail("No series")
                inFront { voiceOpen(page) }
                done(page.name.lowercase().replaceFirstChar { it.uppercase() })
            }
            is com.signal.voice.VoiceCommand.Captions -> {
                if (vod == null && live == null) return fail(nothing)
                val state = player.state.value
                val on = command.on ?: (state.selectedSubtitleTrackId == null)
                if (!on) {
                    if (live != null) playback.selectSubtitle(live, null) else player.selectSubtitleTrack(null)
                    return done("Captions off")
                }
                val track = state.subtitleTracks.firstOrNull { com.signal.voice.VoiceCommand.Language.ENGLISH.matches(it.language, it.label) }
                    ?: state.subtitleTracks.firstOrNull()
                    ?: return fail("No captions on this ${if (live != null) "channel" else "video"}")
                if (live != null) playback.selectSubtitle(live, track.id) else player.selectSubtitleTrack(track.id)
                done("Captions on")
            }
            is com.signal.voice.VoiceCommand.Audio -> {
                if (vod == null && live == null) return fail(nothing)
                val name = command.language.spoken.replaceFirstChar { it.uppercase() }
                val track = player.state.value.audioTracks.firstOrNull { command.language.matches(it.language, it.label) }
                    ?: return fail("No $name audio")
                if (live != null) playback.selectAudio(live, track.id) else player.selectAudioTrack(track.id)
                done("$name audio")
            }
            // Handled before this, off the main thread.
            is com.signal.voice.VoiceCommand.Tune, is com.signal.voice.VoiceCommand.WhatsOn, is com.signal.voice.VoiceCommand.Search,
            is com.signal.voice.VoiceCommand.PlayTitle, is com.signal.voice.VoiceCommand.ContinueTitle,
            is com.signal.voice.VoiceCommand.Game, is com.signal.voice.VoiceCommand.Remind -> fail("Didn't catch that")
        }
    }

    /** A page by name. Leaving a movie saves its place first; leaving live video stops it. */
    private fun voiceOpen(page: com.signal.voice.VoiceCommand.Page) {
        panel.close()
        if (navigator.current == Route.VodPlayer) vodPlayback.stop()
        val route = when (page) {
            com.signal.voice.VoiceCommand.Page.HOME -> Route.Home
            com.signal.voice.VoiceCommand.Page.GUIDE -> Route.LiveTv
            com.signal.voice.VoiceCommand.Page.FAVORITES -> { liveTvGroup = ChannelGroup.Favorites; Route.LiveTv }
            com.signal.voice.VoiceCommand.Page.MOVIES -> Route.Movies
            com.signal.voice.VoiceCommand.Page.SERIES -> Route.Series
            com.signal.voice.VoiceCommand.Page.SEARCH -> Route.Search
            com.signal.voice.VoiceCommand.Page.SETTINGS -> Route.Settings
        }
        if (navigator.current.showsVideo && !route.showsVideo) playback.stop()
        navigator.go(route)
    }

    /** "30 seconds", "1 minute", "10 minutes". */
    private fun spokenDuration(ms: Long): String {
        val s = ms / 1000
        return if (s % 60 == 0L && s >= 60) "${s / 60} minute${if (s == 60L) "" else "s"}" else "$s seconds"
    }

    /** [heard] is the guess acted on (or the best), [best] the recognizer's first guess, and [shown] the outcome for the listening overlay. */
    data class VoiceResult(val acted: Boolean, val heard: String, val best: String, val message: String, val shown: String)

    /**
     * The phrases a voice command can be made of: the commands' own and the channels' names. Kept
     * until the channels change, since building them from a large list takes seconds.
     */
    suspend fun voicePhrases(): Set<String> {
        val titles = voiceTitles()
        val teams = voiceTeams()
        val version = listOf(search.version, titles.hashCode(), teams.hashCode(), settings.raw(Settings.Key.VOICE_CHANNELS))
        voicePhrasesCache?.takeIf { it.first == version }?.let { return it.second }
        val (names, initials) = search.voiceNames()
        return com.signal.voice.VoiceCommand.phrases(names, initials, titles, teams).also { voicePhrasesCache = version to it }
    }

    @kotlin.concurrent.Volatile private var voicePhrasesCache: Pair<List<Any?>, Set<String>>? = null

    /**
     * Teams in the next few days' games ([VOICE_TEAM_HOURS]), folded, each as it's said (PLAN §4.3.14 → Voice commands, phase
     * 2): full name, nickname and place. In the grammar, "watch the Steelers game" needs no free
     * recognizer.
     */
    suspend fun voiceTeams(): Set<String> {
        val titles = runCatching { guide.gameTitles(Clock.System.now().toEpochMilliseconds(), VOICE_TEAM_HOURS) }.getOrDefault(emptyList())
        return titles.mapNotNull(com.signal.guide.GameTitles::matchup)
            .flatMap { listOf(it.away, it.home) }
            .flatMap(com.signal.guide.GameTitles::spokenNames)
            .mapNotNullTo(LinkedHashSet()) { com.signal.match.SearchText.fold(it).ifEmpty { null } }
    }

    /**
     * The titles most likely to be said (PLAN §4.3.14 → Voice commands, phase 2), folded, for the
     * grammar: Continue watching, the Library, every series being watched (new episodes included), the
     * newest of each kind and the companion's TMDB lists.
     * A couple of thousand at most; the rest of the catalog is heard by the free recognizer.
     */
    suspend fun voiceTitles(): Set<String> {
        val state = watchState.value
        val out = LinkedHashSet<String>()
        continueWatching.value.forEach { out += it.title }
        state.library.forEach { out += it.title }
        // Every series being watched (an episode watched or started), not only those on Continue watching.
        state.followedSeries().values.forEach { f -> f.title?.let { out += it } }
        for (kind in com.signal.model.VodKind.entries) {
            runCatching { vod.recentlyAdded(kind, VOICE_RECENT_TITLES) }.getOrDefault(emptyList()).forEach { out += com.signal.data.VodRepository.displayName(it) }
            // The companion's TMDB lists (trending, popular, top rated, new or airing), which hold the
            // well-known titles people ask for by name: "the simpsons" wasn't in the grammar (0.2.28),
            // and the free recognizer couldn't hear it.
            for ((list, _) in com.signal.data.VodEnrichment.lists(kind)) {
                runCatching { vod.shelfItems(kind, "list:$list") }.getOrDefault(emptyList()).take(VOICE_LIST_TITLES)
                    .forEach { out += com.signal.data.VodRepository.displayName(it) }
            }
        }
        return out.mapNotNullTo(LinkedHashSet()) { com.signal.match.SearchText.fold(it).ifEmpty { null } }
    }

    /**
     * Changes whenever the grammar's inputs do: the channels and the likely titles. The recognizer
     * rebuilds on it (ReadyRecognizer.follow).
     */
    val voiceInputs: kotlinx.coroutines.flow.Flow<Any> get() =
        // The guide too: a new one brings the next day's games, whose teams the grammar knows.
        combine(
            listOf(search.versions, continueWatching.map { c -> c.map { it.title } }, watchState.map { s -> s.library.map { it.title } }, newEpisodes, vodVersion, guideStore.version, settings.changes().map { settings.raw(Settings.Key.VOICE_CHANNELS).orEmpty() }),
        ) { it.toList() }.distinctUntilChanged()

    /** From the activity's onStop to the next onStart, whichever activity instance that is. */
    var inBackground = false
        private set
    /** What a voice command asked for while the app was in the background: done on return. */
    private var voicePending: (() -> Unit)? = null

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
        // A movie or episode saves its position first (PLAN §4.3.10: "on pause, Back and app exit").
        if (navigator.current == Route.VodPlayer) vodPlayback.release()
        playback.release()
        settings.put(Settings.Key.BACKGROUNDED_AT, Clock.System.now().toEpochMilliseconds().toString())
        inBackground = true
    }

    fun onForeground() {
        val away = settings.raw(Settings.Key.BACKGROUNDED_AT)?.toLongOrNull()?.let { Clock.System.now().toEpochMilliseconds() - it }
        settings.put(Settings.Key.BACKGROUNDED_AT, null)
        inBackground = false
        scope.launch { catchUpReminders() }
        scope.launch { refreshFollowedSeries() }
        val voice = voicePending
        voicePending = null
        if (voice != null) {
            voice()
        } else if (away != null && away > AWAY_RESETS_TO_HOME_MS && navigator.current != Route.Setup) {
            scope.launch { openStartPage() }
        } else if (away != null && navigator.current == Route.VodPlayer) {
            scope.launch { vodPlayback.resume() }
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
        /** A refresh writes the catalog source by source: carry-over waits for it to settle. */
        const val CARRY_OVER_DEBOUNCE_MS = 5_000L
        /** Channels a voice command looks through: enough that an exact name behind favorites' prefix matches is still seen. */
        const val VOICE_HITS = 50
        /** [Settings.Key.UPDATE_CHANNEL]: every release, testing ones included. Unset is stable only. */
        const val UPDATE_ALL = "ALL"
        /** Newest movies and newest series in the voice grammar, each. */
        const val VOICE_RECENT_TITLES = 100
        /** Titles from each TMDB list in the voice grammar. */
        const val VOICE_LIST_TITLES = 200
        /** A team's airings starting within this of its next one are the same game, on other channels. */
        const val GAME_WINDOW_MS = 3 * 60 * 60_000L
        /** Titles a voice command looks through for an exact one. */
        const val VOICE_TITLE_HITS = 20
        /** Games whose teams the voice grammar knows: four days, so Sunday's games are known by Friday (a day missed the Bears). */
        const val VOICE_TEAM_HOURS = 96
        /** A burst of edits (a move, a rename) goes as one sync. */
        const val SYNC_DEBOUNCE_MS = 2_000L
        /** While the event stream is down (PLAN §9.2). */
        const val SYNC_POLL_MS = 5 * 60_000L
    }
}

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
    val guide = GuideRepository(guideStore, epg, settings, guideIo, scope)
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
            withContext(guideIo) { guideStore.open() }
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
        scope.launch { platform.updater?.check() }
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

        // A movie or series command, as the free recognizer heard it: the grammar knows few titles.
        val freeCommand = heardFree?.let { com.signal.voice.VoiceCommand.parse(it) }
        if (freeCommand != null && freeCommand.isTitleCommand) return voiceTitle(heardFree, freeCommand, ::result)
        val (freeTitle, freeLeadIn) = heardFree?.let { com.signal.voice.VoiceCommand.spokenTitle(it) } ?: ("" to false)

        val tunes = ArrayList<Pair<String, String>>()
        for ((heard, command) in parsed) {
            // Words outside the grammar make a guess a name, never a command: "play [unk]" was
            // "play inception", not "play".
            val whole = "[unk]" !in heard
            when (command) {
                is com.signal.voice.VoiceCommand.Tune -> {
                    // One letter is never enough to name a channel ("v" found VH1).
                    if (command.channel.replace(" ", "").length < 2) continue
                    for (name in command.names) {
                        tunes += heard to name
                        val hits = search.channelHits(name, limit = VOICE_HITS)
                        if (hits.isEmpty()) continue
                        // Not one clear channel: a title wins. The free recognizer's, when it ran (it
                        // only runs for "play …" and the like), with any match; the grammar's only
                        // exactly, since it forces any title into channel words ("the me tribeca").
                        val pick = com.signal.voice.VoiceChoice.choose(hits, { it.channel.displayName }, { it.exact }, { it.favorite })
                        if (!(pick is com.signal.voice.VoiceChoice.Pick.One && pick.hit.exact)) {
                            if (freeTitle.isNotBlank() && titleHits(freeTitle, null).isNotEmpty()) {
                                return voiceTitle(heardFree!!, com.signal.voice.VoiceCommand.PlayTitle(freeTitle), ::result)
                            }
                            exactTitle(name)?.let { return voicePlay(heard, it, null, null, ::result) }
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
        // Else initials that sound like a guess's letters ("e a b t" → ABC).
        val initials = if (tunes.isEmpty()) emptySet() else search.voiceNames().second
        for ((heard, name) in tunes) {
            for (like in com.signal.voice.VoiceCommand.soundsLike(name, initials)) {
                val hits = search.channelHits(like, limit = VOICE_HITS)
                if (hits.isNotEmpty()) return voiceChannel(heard, hits, " by sound (\"$like\")", ::result)
            }
        }
        // No channel: a movie or series by that name. Any match for what the free recognizer heard;
        // only an exact one for the grammar's guesses, which were "play me truckers" for "play matrix".
        if (heardFree != null && freeTitle.replace(" ", "").length >= 2 && titleHits(freeTitle, null).isNotEmpty()) {
            return voiceTitle(heardFree, com.signal.voice.VoiceCommand.PlayTitle(freeTitle), ::result)
        }
        for ((heard, title) in tunes) exactTitle(title)?.let { return voicePlay(heard, it, null, null, ::result) }
        // A command only the free recognizer caught.
        when (freeCommand) {
            null, is com.signal.voice.VoiceCommand.Tune -> {}
            is com.signal.voice.VoiceCommand.WhatsOn -> return voiceWhatsOn(heardFree, freeCommand.channel, ::result)
            else -> return withContext(Dispatchers.Main) { voiceCommand(heardFree, freeCommand, ::result) }
        }
        // "Play ___" that matched nothing: Search, with it filled in.
        if (freeLeadIn && freeTitle.isNotBlank()) return voiceSearch(heardFree!!, freeTitle, ::result)
        return when (val tried = tunes.firstOrNull()?.second ?: freeTitle.ifBlank { null }) {
            null -> result(false, best, "Didn't catch that", "nothing")
            else -> result(false, heardFree ?: best, "Nothing matches \"$tried\"", "no match")
        }
    }

    private val com.signal.voice.VoiceCommand.isTitleCommand: Boolean get() =
        this is com.signal.voice.VoiceCommand.Search || this is com.signal.voice.VoiceCommand.PlayTitle || this is com.signal.voice.VoiceCommand.ContinueTitle

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
            .firstNotNullOfOrNull { n -> search.channelHits(n, limit = VOICE_HITS).ifEmpty { null } }.orEmpty()
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
            is com.signal.voice.VoiceCommand.PlayTitle, is com.signal.voice.VoiceCommand.ContinueTitle -> fail("Didn't catch that")
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
        val version = search.version to titles.hashCode()
        voicePhrasesCache?.takeIf { it.first == version }?.let { return it.second }
        val (names, initials) = search.voiceNames()
        return com.signal.voice.VoiceCommand.phrases(names, initials, titles).also { voicePhrasesCache = version to it }
    }

    @kotlin.concurrent.Volatile private var voicePhrasesCache: Pair<Pair<Int, Int>, Set<String>>? = null

    /**
     * The titles most likely to be said (PLAN §4.3.14 → Voice commands, phase 2), folded, for the
     * grammar: Continue watching, the Library, series with a new episode and the newest of each kind.
     * A few hundred at most; the rest of the catalog is heard by the free recognizer.
     */
    suspend fun voiceTitles(): Set<String> {
        val state = watchState.value
        val out = LinkedHashSet<String>()
        continueWatching.value.forEach { out += it.title }
        state.library.forEach { out += it.title }
        newEpisodes.value.forEach { key -> state.followedSeries()[key]?.title?.let { out += it } }
        for (kind in com.signal.model.VodKind.entries) {
            runCatching { vod.recentlyAdded(kind, VOICE_RECENT_TITLES) }.getOrDefault(emptyList()).forEach { out += com.signal.data.VodRepository.displayName(it) }
        }
        return out.mapNotNullTo(LinkedHashSet()) { com.signal.match.SearchText.fold(it).ifEmpty { null } }
    }

    /**
     * Changes whenever the grammar's inputs do: the channels and the likely titles. The recognizer
     * rebuilds on it (ReadyRecognizer.follow).
     */
    val voiceInputs: kotlinx.coroutines.flow.Flow<Any> get() =
        combine(search.versions, continueWatching, watchState, newEpisodes, vodVersion) { a, b, c, d, e -> listOf(a, b.map { it.title }, c.library.map { it.title }, d, e) }
            .distinctUntilChanged()

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
        /** Newest movies and newest series in the voice grammar, each. */
        const val VOICE_RECENT_TITLES = 100
        /** Titles a voice command looks through for an exact one. */
        const val VOICE_TITLE_HITS = 20
        /** A burst of edits (a move, a rename) goes as one sync. */
        const val SYNC_DEBOUNCE_MS = 2_000L
        /** While the event stream is down (PLAN §9.2). */
        const val SYNC_POLL_MS = 5 * 60_000L
    }
}

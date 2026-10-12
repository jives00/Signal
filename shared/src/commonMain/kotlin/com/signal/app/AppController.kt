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
import kotlinx.coroutines.withContext
import com.signal.db.user.UserDatabase
import com.signal.input.RemoteDispatcher
import com.signal.model.Source
import com.signal.model.SourceKind
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

    // The guide (PLAN §6). It reads and writes only guide.db, so it gets its own IO threads: on the
    // desktop user.db runs one thread at a time, and a guide build must never queue behind or block it.
    private val guideIo = Dispatchers.IO
    val guideStore = GuideStore(platform, guideIo, scope, log)
    val epg = EpgRepository(db, settings, platform.io)
    val guide = GuideRepository(guideStore, epg, settings, guideIo, scope)
    private val guideBuilder = GuideBuilder(http, sources, guideIo, log)
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
            if (guideIsStale()) rebuildGuide()
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
            swapped
        } catch (e: Throwable) {
            log.error("Guide build failed", e)
            false
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

    // ---- Lifecycle (PLAN §4.2 → Sleep, the Home button and interruptions) ---------------------

    fun onBackground() {
        playback.release()
        settings.put(Settings.Key.BACKGROUNDED_AT, Clock.System.now().toEpochMilliseconds().toString())
    }

    fun onForeground() {
        val away = settings.raw(Settings.Key.BACKGROUNDED_AT)?.toLongOrNull()?.let { Clock.System.now().toEpochMilliseconds() - it }
        settings.put(Settings.Key.BACKGROUNDED_AT, null)
        if (away != null && away > AWAY_RESETS_TO_HOME_MS && navigator.current != Route.Setup) {
            navigator.resetTo(Route.Home)
        } else if (away != null && navigator.current.showsVideo) {
            playback.resume()
        }
        scope.launch { platform.updater?.check() }
    }

    companion object {
        /** Away longer than this, the app opens on Home instead of resuming (PLAN §4.2). */
        const val AWAY_RESETS_TO_HOME_MS = 4 * 60 * 60_000L
    }
}

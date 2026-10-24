package com.signal.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.request.receive
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import java.io.File

@Serializable
data class Health(val status: String, val version: String, val database: String)

@Serializable
data class Status(val version: String, val database: String, val secretKey: Boolean)

/** Everything the routes need, so tests can build the app without a database. */
class Companion(
    val config: Config,
    database: Database? = null,
    private val deviceStore: DeviceStore? = null,
    private val companionStore: CompanionStore? = null,
    private val syncStoreOverride: SyncStore? = null,
    /** Tests that add sources without wanting a 2-minute guide build each turn this off. */
    val buildGuideOnChange: Boolean = true,
    /** How long after a build the EPG sources it left failing are tried again (GuideJob.retryFailed). */
    val guideRetryDelayMs: Long = 60 * 60_000L,
    /** Tests: TMDB answered by a mock, and the store and titles without MySQL or a catalog. */
    private val tmdbHttp: HttpClient? = null,
    private val tmdbStoreOverride: TmdbStore? = null,
    private val vodItemsOverride: (suspend () -> List<com.signal.model.VodItem>)? = null,
) {
    /** Set once the database connects; it may not be there yet at start-up (see Main). */
    @Volatile var database: Database? = database
    val pairings = Pairings()

    /** Null until the database connects. */
    val devices: DeviceStore? get() = deviceStore ?: database?.devices
    val store: CompanionStore? get() = companionStore ?: database?.store
    val syncStore: SyncStore? get() = syncStoreOverride ?: database?.sync
    val cipher: SecretCipher? = config.secretKey?.let(::SecretCipher)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // For the guide build's downloads: EPG sources can be large and slow, like on the device.
    private val http by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                requestTimeoutMillis = 15 * 60_000
            }
        }
    }
    val guide by lazy { GuideBuilds(File(config.dataDir, "guide"), http) }
    val guideJob = GuideJob(this, scope)
    val sync = SyncService(this)
    val settings = CompanionSettings(this)
    /** Every source's channel list, for the web admin (PLAN §5.2 → Source refresh). */
    val catalog by lazy { Catalog(File(config.dataDir, "catalog"), http, cipher) }
    val guideReader = GuideReader { guide.dbFile() }

    val tmdbStore: TmdbStore? get() = tmdbStoreOverride ?: database?.tmdb
    private val tmdbClient by lazy {
        tmdbHttp ?: HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
            }
        }
    }
    val tmdb by lazy { Tmdb(tmdbClient, { settings.tmdbKey }) }
    /** App releases and their channels, for Devices & Profiles → Updates (PLAN §4.3.15). */
    val releases by lazy { Releases(tmdbClient, { settings.githubToken }) }
    /** VOD enrichment (PLAN §4.3.12). */
    val vod by lazy {
        VodEnricher(tmdb, { tmdbStore }, ::vodItems, { if (vodItemsOverride != null) 0 else catalog.version }, ::followedVod)
    }
    val images by lazy { ImageCache(File(config.dataDir, "images"), tmdbClient, { settings.imageCacheGb.toLong() shl 30 }) }

    /** Titles in any profile's Library or Continue watching (PLAN §4.3.13). */
    private fun followedVod(): Set<String> = runCatching {
        sync.liveItems().filter { it.type == com.signal.sync.SyncType.LIBRARY_ITEM || it.type == com.signal.sync.SyncType.RESUME_POSITION }
            .mapNotNull { (it.fields["vod_item_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
    }.getOrDefault(emptySet())

    /** An enrichment run, recorded in the job history; open TVs fetch the new index (PLAN §9.2). */
    suspend fun runVod(trigger: String) {
        val store = store ?: return
        val id = store.startJob(VOD_JOB, System.currentTimeMillis())
        val result = runCatching { vod.run(trigger) }
        store.finishJob(id, System.currentTimeMillis(), if (result.isSuccess) "ok" else "failed", result.getOrElse { "$trigger: ${it.message}" })
        runCatching { vod.index() }.onSuccess { events.tryEmit(com.signal.sync.SyncEvent(com.signal.sync.SyncEvent.VOD, rev = it.version)) }
    }

    suspend fun vodItems(): List<com.signal.model.VodItem> = vodItemsOverride?.invoke() ?: catalog.vodItems()
    @Volatile private var catalogById: Pair<List<com.signal.model.VodItem>, Map<String, com.signal.model.VodItem>>? = null

    /** One catalog title by ID; a map kept beside the cached list, since every provider poster asks. */
    suspend fun catalogItem(id: String): com.signal.model.VodItem? {
        val list = vodItems()
        val map = catalogById?.takeIf { it.first === list }?.second ?: list.associateBy { it.id }.also { catalogById = list to it }
        return map[id]
    }

    /** A match was fixed in the web admin: open TVs fetch the new index. */
    suspend fun vodChanged() {
        runCatching { vod.index() }.onSuccess { events.tryEmit(com.signal.sync.SyncEvent(com.signal.sync.SyncEvent.VOD, rev = it.version)) }
    }

    fun runVodInBackground(trigger: String): Boolean {
        if (vod.running) return false
        scope.launch { runVod(trigger) }
        return true
    }
    private val sourcesChanged = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** The synced sources changed (or the database just connected): the catalog follows. */
    fun onSourcesChanged() {
        sourcesChanged.trySend(Unit)
    }

    fun startCatalog() = scope.launch {
        catalog.load()
        for (signal in sourcesChanged) {
            runCatching {
                // The filter first, so a source refreshed below already leaves hidden titles out.
                val household = sync.liveItems().filter { it.scope == com.signal.sync.SyncScope.HOUSEHOLD }
                val filterRaw = household.firstOrNull { it.type == com.signal.sync.SyncType.SETTING && it.id == com.signal.data.Settings.Key.VOD_CATEGORIES.id }
                    ?.fields?.get("value")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                catalog.applyVodFilter(com.signal.match.VodCategoryFilter.parse(filterRaw))
                val wanted = household.filter { it.type == com.signal.sync.SyncType.SOURCE }
                    .map { com.signal.sync.SyncFields.source(it.id, it.fields) }
                catalog.match(wanted)
            }.onFailure { org.slf4j.LoggerFactory.getLogger("Catalog").warn("Catalog update failed: ${it.message}") }
        }
    }
    /** What /device/events announces: settings changes and guide builds (PLAN §9.2). */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<com.signal.sync.SyncEvent>(extraBufferCapacity = 64)
    val version: String =
        Companion::class.java.getResourceAsStream("/signal-version.txt")?.bufferedReader()?.readText()?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") } ?: "dev"

    companion object Jobs {
        const val VOD_JOB = "vod_enrich"
    }

    fun databaseState(): String = when {
        config.db == null -> "not configured"
        database?.ping() == true -> "ok"
        else -> "unreachable"
    }
}

fun Application.signalModule(companion: Companion) {
    install(ContentNegotiation) { json() }
    install(CallLogging) {
        level = Level.INFO
        // Health checks every minute from devdash and Docker would drown everything else.
        filter { it.request.path() != "/health" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // The client hung up mid-response (a device dropping /device/events, say): nothing to
            // answer and nothing wrong, so keep it out of the error log devdash scrapes.
            if (generateSequence(cause) { it.cause }.any { it is java.nio.channels.ClosedChannelException }) {
                call.application.environment.log.debug("Client went away: ${call.request.path()}")
                return@exception
            }
            call.application.environment.log.error("Request failed: ${call.request.path()}", cause)
            call.respondText("Something went wrong", status = HttpStatusCode.InternalServerError)
        }
    }

    routing {
        // For devdash and Docker: open to all, and says whether the database answers.
        get("/health") {
            val db = companion.databaseState()
            val ok = db == "ok"
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, Health(if (ok) "ok" else "degraded", companion.version, db))
        }

        // Everything else is for the home network and Tailscale only (PLAN §5.7). The web admin's
        // API stops there; device endpoints also need the device's own key (Pairing.kt).
        trusted {
            deviceRoutes(companion)
            guideDeviceRoutes(companion)
            syncDeviceRoutes(companion)
            vodDeviceRoutes(companion)
            vodImageRoutes(companion)
            route("/api") {
                get("/status") { call.respond(Status(companion.version, companion.databaseState(), companion.config.secretKey != null)) }
                // The web admin's Voice Commands page: the same list as the TV's (shared/voice/VoiceHelp.kt).
                get("/voice/commands") { call.respond(com.signal.voice.VoiceHelp.ALL) }
                // Release channels (PLAN §4.3.15): the recent app releases, and marking one stable or testing.
                get("/releases") { call.respond(companion.releases.list()) }
                post("/releases/{tag}") {
                    val tag = call.parameters["tag"].orEmpty()
                    if (!tag.startsWith("apk-")) return@post call.respondText("Not an app release.", status = HttpStatusCode.BadRequest)
                    val mark = call.receive<MarkRelease>()
                    when (val error = companion.releases.mark(tag, mark.stable)) {
                        null -> call.respond(HttpStatusCode.NoContent)
                        else -> call.respondText(error, status = HttpStatusCode.BadGateway)
                    }
                }
                deviceAdminRoutes(companion)
                guideAdminRoutes(companion)
                syncAdminRoutes(companion)
                adminPageRoutes(companion)
                vodAdminRoutes(companion)
            }
            webAdmin(File(companion.config.webDir))
        }
    }
}

/** Routes inside are network-trust only. A transparent node, like Ktor's authenticate { }: it adds no path. */
fun Route.trusted(build: Route.() -> Unit): Route {
    val node = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
        override fun toString() = "(trusted)"
    })
    node.install(TrustedOnly)
    node.build()
    return node
}

/** Refuses anything not on the LAN or Tailscale, or that came through Cloudflare. */
val TrustedOnly = createRouteScopedPlugin("TrustedOnly") {
    onCall { call ->
        if (!NetworkTrust.isTrusted(call.request.origin.remoteAddress, call.request.headers.names())) {
            call.respondText("Signal's admin is only available on the home network or Tailscale.", ContentType.Text.Plain, HttpStatusCode.Forbidden)
        }
    }
}

/** The built single-page app: real files as themselves, every other path gets index.html. */
private fun Route.webAdmin(dir: File) {
    get("{path...}") {
        val requested = call.parameters.getAll("path").orEmpty().joinToString("/")
        val root = dir.canonicalFile
        val file = File(root, requested).canonicalFile
        val index = File(root, "index.html")
        when {
            // Never serve anything outside the web directory.
            file.path.startsWith(root.path) && file.isFile -> call.respondFile(file)
            requested.startsWith("api/") -> call.respondText("Not found", status = HttpStatusCode.NotFound)
            index.isFile -> call.respondFile(index)
            else -> call.respondText("The web admin isn't built. Run pnpm build in web/.", status = HttpStatusCode.NotFound)
        }
    }
}

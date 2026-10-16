package com.signal.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.Log
import com.signal.data.SourceRepository
import com.signal.db.user.UserDatabase
import com.signal.match.VodCategoryFilter
import com.signal.model.Source
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

/** One cached stream, as the web admin's Channels page needs it. */
data class CatalogStream(
    val id: String,
    val sourceId: String,
    val rawName: String,
    val displayName: String,
    val groupName: String,
    val logoUrl: String?,
    val tvgId: String?,
    val providerNumber: Int?,
    val position: Int,
)

/** A source's refresh state on the companion. */
@Serializable
data class CatalogSource(
    val id: String, val lastRefreshedAt: Long, val lastError: String?, val streams: Int,
    /** What the provider allows and when the account expires, from its last refresh (Xtream only). */
    val providerConnections: Int? = null, val expiresAt: Long? = null,
)

/**
 * The companion's own copy of every source's channel list (PLAN §5.2 → Jobs → Source refresh), for
 * the web admin. It's a user.db file whose `source` rows mirror the synced sources and whose
 * `stream` rows come from the app's own SourceRepository, so stream IDs and cleaned names match
 * the devices'. Refreshed nightly before the guide build, when a source changes, and on demand.
 *
 * All database work runs on one thread (SQLite on the JVM, see CLAUDE.md); readers use [streams],
 * a snapshot in memory replaced after each refresh.
 */
class Catalog(dir: File, http: HttpClient, cipher: SecretCipher?) {
    private val log = LoggerFactory.getLogger("Catalog")
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private val db: UserDatabase
    private val repo: SourceRepository
    private val vod: com.signal.data.VodRepository

    init {
        dir.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "catalog.db").path}", Properties(), UserDatabase.Schema)
        db = UserDatabase(driver)
        // Passwords at rest in this file too (PLAN §5.7): the same key as the database.
        val box = object : SecretBox {
            override fun encrypt(plain: String) = cipher?.encrypt(plain) ?: plain
            override fun decrypt(cipher0: String) = cipher?.decrypt(cipher0) ?: cipher0
        }
        repo = SourceRepository(db, http, box, io, Log { level, message -> if (level == "I") log.info(message) else log.warn(message) }, vodFilter = { vodFilter })
        vod = com.signal.data.VodRepository(db, io)
    }

    @Volatile var streams: List<CatalogStream> = emptyList()
        private set

    /** Bumped whenever [streams] changes, so caches built on it know. */
    @Volatile var version: Int = 0
        private set

    @Volatile var refreshing: String? = null
        private set

    suspend fun load() = withContext(io) { reloadStreams() }

    private fun reloadStreams() {
        streams = db.streamQueries.allStreams().executeAsList().map {
            CatalogStream(it.id, it.source_id, it.raw_name, it.display_name, it.group_name, it.logo_url, it.tvg_id, it.provider_number?.toInt(), it.position.toInt())
        }
        version++
        reloadStates()
    }

    /**
     * Each source's refresh state, from memory: a refresh holds the database thread for minutes (the
     * movie and series lists), and the web admin's Sources page must not wait behind it.
     */
    @Volatile private var states: List<CatalogSource> = emptyList()

    private fun reloadStates() {
        states = db.sourceQueries.all().executeAsList().map { s ->
            CatalogSource(s.id, s.last_refreshed_at, s.last_error, db.streamQueries.countForSource(s.id).executeAsOne().toInt(), s.provider_connections?.toInt(), s.expires_at)
        }
    }

    fun sources(): List<CatalogSource> = states

    /**
     * Makes the catalog's sources match [wanted] (the synced household sources): new ones and ones
     * whose login or URL changed are refreshed, removed ones dropped with their streams.
     */
    suspend fun match(wanted: List<Source>) = lock.withLock {
        val toRefresh = withContext(io) {
            val have = repo.all().associateBy { it.id }
            val ids = wanted.map { it.id }.toSet()
            have.keys.filter { it !in ids }.forEach { repo.delete(it) }
            wanted.filter { w ->
                val h = have[w.id]
                val changed = h == null || h.kind != w.kind || h.url != w.url || h.username != w.username || h.password != w.password ||
                    h.enabled != w.enabled || h.userAgent != w.userAgent || h.referrer != w.referrer
                if (h == null || h != w.copy(lastRefreshedAt = h.lastRefreshedAt, lastError = h.lastError, providerConnections = h.providerConnections, expiresAt = h.expiresAt)) repo.save(w)
                changed && w.enabled
            }.map { it.id }.also { if (have.keys.any { it !in ids }) reloadStreams() else reloadStates() }
        }
        toRefresh.forEach { refreshLocked(it) }
    }

    suspend fun refresh(id: String): Result<Int> = lock.withLock { refreshLocked(id) }

    /** Every enabled source, one after another. Returns a line for the job history. */
    suspend fun refreshAll(): String = lock.withLock {
        val results = withContext(io) { repo.all() }.filter { it.enabled }.map { s -> s.name to refreshLocked(s.id) }
        results.joinToString("; ") { (name, r) -> r.fold({ "$name: $it streams" }, { "$name failed: ${it.message}" }) }.ifEmpty { "no sources" }
    }

    private suspend fun refreshLocked(id: String): Result<Int> {
        refreshing = id
        return try {
            repo.refresh(id).also { withContext(io) { reloadStreams() } }
        } finally {
            refreshing = null
        }
    }

    /** The household's hidden movie and series categories (PLAN §4.3.6 → Categories), from the synced setting. */
    @Volatile private var vodFilter: VodCategoryFilter = VodCategoryFilter.NONE
    @Volatile private var vodFilterSet = false

    /**
     * Follows the vod_categories setting: hidden titles are dropped at once, so matching skips them,
     * and a source with a category shown again is refreshed to get its titles back.
     */
    suspend fun applyVodFilter(all: VodCategoryFilter) = lock.withLock {
        val filter = all.visibility
        if (vodFilterSet && filter == vodFilter) return@withLock
        val old = vodFilter.takeIf { vodFilterSet }
        vodFilter = filter
        vodFilterSet = true
        val dropped = vod.dropHidden(filter)
        if (dropped > 0) log.info("Dropped $dropped titles in hidden categories")
        old?.let { vod.sourcesUnhidden(it, filter) }.orEmpty().forEach { refreshLocked(it) }
        if (dropped > 0) withContext(io) { reloadStreams() }
    }

    @Volatile private var vodCache: Pair<Int, List<com.signal.model.VodItem>>? = null

    /** Every movie and series of the enabled sources, for enrichment (PLAN §4.3.12). Demo titles are made up, so they're left out. */
    suspend fun vodItems(): List<com.signal.model.VodItem> {
        val v = version
        vodCache?.takeIf { it.first == v }?.let { return it.second }
        val demo = withContext(io) { repo.all() }.filter { it.kind == com.signal.model.SourceKind.DEMO }.map { it.id }.toSet()
        return vod.all().filter { it.sourceId !in demo }.also { vodCache = v to it }
    }

    /** Every movie and series category of the enabled sources, hidden ones too, with its title count (0 when hidden). */
    suspend fun vodCategories() = withContext(io) { db.vodQueries.allCategoriesCounted().executeAsList() }

    /** Every movie and series category name, for the title cleanup preview. */
    suspend fun vodCategoryNames(): List<String> = withContext(io) { db.vodQueries.allCategories().executeAsList().map { it.name } }

    suspend fun vodItem(id: String): com.signal.model.VodItem? = vod.item(id)

    /** Settings → Sources → Test connection, without saving anything. */
    suspend fun test(source: Source): Result<String> = repo.test(source)
}

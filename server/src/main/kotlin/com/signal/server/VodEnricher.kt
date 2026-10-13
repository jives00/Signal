package com.signal.server

import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.nas.EpisodeMeta
import com.signal.nas.VodIndex
import com.signal.nas.VodMeta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * VOD enrichment (PLAN §4.3.12–4.3.13): matches the catalog's movies and series to TMDB, keeps the
 * nightly lists, and serves the index and metadata the TVs read.
 *
 * Matching is resumable: each title's result is stored as it's found, a run stops when its time is
 * up, and the next one carries on with what's left. Unmatched titles are tried again after 30 days,
 * and titles fixed in the web admin (manual) are never touched.
 */
class VodEnricher(
    private val tmdb: Tmdb,
    private val storeOf: () -> TmdbStore?,
    /** The catalog's titles, Demo sources left out (their names are made up). */
    private val items: suspend () -> List<VodItem>,
    /** Changes whenever the catalog does, so the index knows to rebuild. */
    private val catalogVersion: () -> Int,
    /** Item IDs in someone's Library or Continue watching: refreshed nightly (PLAN §4.3.13). */
    private val followed: () -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("VodEnricher")
    private val json = Json { ignoreUnknownKeys = true }
    private val runLock = Mutex()

    /** What the index needs of each title, kept in memory: (kind, TMDB ID) → entry. */
    private data class Entry(val title: String, val year: Int?, val poster: String?, val genres: List<String>)
    private val entries = ConcurrentHashMap<Pair<String, Long>, Entry>()
    @Volatile private var entriesLoaded = false

    @Volatile private var cached: Pair<Int, VodIndex>? = null
    @Volatile private var changes = 0

    @Volatile var running = false
        private set
    @Volatile var progress: String? = null
        private set

    private fun store() = storeOf() ?: error("The database isn't connected yet")

    // ---- Titles ----

    private fun saveTitle(kind: String, t: TmdbTitle) {
        store().putTitle(kind, t.id, json.encodeToString(TmdbTitle.serializer(), t), now())
        entries[kind to t.id] = Entry(t.title, t.year, t.poster, t.genres)
    }

    private fun cachedTitle(kind: String, id: Long): Pair<TmdbTitle, Long>? =
        store().title(kind, id)?.let { (body, at) -> runCatching { json.decodeFromString(TmdbTitle.serializer(), body) }.getOrNull()?.let { it to at } }

    /** The title, fetched when it isn't cached or is older than [maxAgeMs]; the cached copy if TMDB fails. */
    suspend fun title(kind: String, id: Long, maxAgeMs: Long = MAX_AGE_MS): TmdbTitle? {
        val cached = cachedTitle(kind, id)
        if (cached != null && now() - cached.second < maxAgeMs) return cached.first
        return runCatching {
            TmdbParse.title(kind, tmdb.get("/${TmdbParse.path(kind)}/$id", "append_to_response" to TmdbParse.details(kind)))?.also { saveTitle(kind, it) }
        }.onFailure { if (it is Tmdb.NoKey) throw it else log.warn("TMDB title $kind $id failed: ${it.message}") }.getOrNull() ?: cached?.first
    }

    /** A season's episodes, cached like titles under kind SEASON. */
    suspend fun season(showId: Long, season: Int, maxAgeMs: Long = MAX_AGE_MS): List<EpisodeMeta> {
        val key = showId * 1000 + season
        val cached = store().title(SEASON, key)
        val episodes = ListSerializer(EpisodeMeta.serializer())
        if (cached != null && now() - cached.second < maxAgeMs) return json.decodeFromString(episodes, cached.first)
        return runCatching {
            TmdbParse.season(tmdb.get("/tv/$showId/season/$season")).also { store().putTitle(SEASON, key, json.encodeToString(episodes, it), now()) }
        }.getOrElse { e -> if (e is Tmdb.NoKey) throw e; cached?.let { json.decodeFromString(episodes, it.first) }.orEmpty() }
    }

    suspend fun search(kind: String, query: String, year: Int?): List<TmdbCandidate> {
        val path = "/search/${TmdbParse.path(kind)}"
        val yearParam = if (kind == VodKind.SERIES.name) "first_air_date_year" else "year"
        val withYear = if (year != null) TmdbParse.results(kind, tmdb.get(path, "query" to query, yearParam to year.toString())) else emptyList()
        return withYear.ifEmpty { TmdbParse.results(kind, tmdb.get(path, "query" to query)) }
    }

    // ---- Matching ----

    /** One title: the provider's TMDB ID when it checks out, else a search. Null when nothing fits. */
    private suspend fun findMatch(item: VodItem): TmdbTitle? {
        val kind = item.kind.name
        item.tmdbId?.takeIf { it > 0 }?.let { id -> title(kind, id)?.let { return it } }
        val query = TitleNormalizer.query(item.name)
        val year = TitleNormalizer.year(item)
        val pick = TitleNormalizer.pick(query, year, search(kind, query, year)) ?: return null
        return title(kind, pick.id)
    }

    /**
     * The nightly run (PLAN §4.3.13): lists, Library and Continue watching titles, then matching until
     * [budgetMs] runs out. Returns a line for the job history.
     */
    suspend fun run(trigger: String, budgetMs: Long = NIGHT_BUDGET_MS): String = runLock.withLock {
        running = true
        try {
            val started = now()
            val all = items()
            val parts = mutableListOf<String>()
            progress = "Refreshing TMDB lists"
            runCatching { refreshLists() }.onSuccess { parts += "lists" }.onFailure { if (it is Tmdb.NoKey) throw it; parts += "lists failed: ${it.message}" }
            progress = "Refreshing Library and Continue watching"
            parts += "${refreshFollowed(all)} followed"

            val matches = store().matches().associateBy { it.vodItemId }
            val retryBefore = now() - RETRY_AFTER_MS
            val pending = all.filter { item ->
                val m = matches[item.id]
                m == null || (m.status == VodMatch.UNMATCHED && m.attemptedAt < retryBefore)
            }
            // The same title in several sources is looked up once.
            val found = HashMap<String, Long?>()
            var matched = 0
            var unmatched = 0
            var done = 0
            for (item in pending) {
                if (now() - started > budgetMs) break
                progress = "Matching ${done + 1} of ${pending.size}"
                val key = "${item.kind}|${item.tmdbId ?: ""}|${TitleNormalizer.key(TitleNormalizer.query(item.name))}|${TitleNormalizer.year(item) ?: ""}"
                val id = if (key in found) found[key] else runCatching { findMatch(item)?.id }
                    .onFailure { if (it is Tmdb.NoKey) throw it }
                    .getOrElse { log.warn("Matching ${item.name} failed: ${it.message}"); continue }
                    .also { found[key] = it }
                store().putMatch(VodMatch(item.id, item.sourceId, item.kind.name, id, if (id != null) VodMatch.MATCHED else VodMatch.UNMATCHED, now()))
                if (id != null) matched++ else unmatched++
                done++
                if (done % 200 == 0) changes++
            }
            changes++
            val left = pending.size - done
            parts += "matched $matched, unmatched $unmatched" + if (left > 0) ", $left left for next time" else ""
            "$trigger: ${parts.joinToString("; ")}"
        } catch (e: Tmdb.NoKey) {
            "$trigger: skipped, no TMDB API key"
        } finally {
            running = false
            progress = null
        }
    }

    /** Titles in a Library or Continue watching: details daily, and series' newest season (new episodes). */
    private suspend fun refreshFollowed(all: List<VodItem>): Int {
        val ids = followed()
        val byId = all.associateBy { it.id }
        var n = 0
        for (id in ids) {
            val item = byId[id] ?: continue
            val m = store().match(id)?.takeIf { it.tmdbId != null && it.status != VodMatch.UNMATCHED } ?: continue
            val t = title(item.kind.name, m.tmdbId!!, FOLLOWED_MAX_AGE_MS) ?: continue
            t.seasons.lastOrNull()?.let { season(t.id, it, FOLLOWED_MAX_AGE_MS) }
            n++
        }
        return n
    }

    private suspend fun refreshLists() {
        val today = LocalDate.now()
        for ((kind, list, path, params) in LISTS) {
            val extra = if (list == NEW) arrayOf("primary_release_date.gte" to today.minusMonths(6).toString(), "primary_release_date.lte" to today.toString(), "sort_by" to "popularity.desc") else params
            val ids = (1..LIST_PAGES).flatMap { page -> TmdbParse.ids(tmdb.get(path, "page" to page.toString(), *extra)) }.distinct()
            if (ids.isNotEmpty()) store().putList(kind, list, ids)
        }
    }

    /** Fixed in the web admin: a TMDB ID, or null for "no match". The matcher leaves it alone after. */
    suspend fun setMatch(item: VodItem, tmdbId: Long?): String? {
        if (tmdbId != null && title(item.kind.name, tmdbId, 0) == null) return "TMDB has no ${TmdbParse.path(item.kind.name)} with ID $tmdbId."
        store().putMatch(VodMatch(item.id, item.sourceId, item.kind.name, tmdbId, VodMatch.MANUAL, now()))
        changes++
        return null
    }

    /** Back to automatic: matched again on the next run. */
    fun clearMatch(item: VodItem) {
        store().putMatch(VodMatch(item.id, item.sourceId, item.kind.name, null, VodMatch.UNMATCHED, 0))
        changes++
    }

    // ---- What the TVs read ----

    private fun loadEntries() {
        if (entriesLoaded) return
        for (kind in listOf(VodKind.MOVIE.name, VodKind.SERIES.name)) {
            store().forEachTitle(kind) { id, body ->
                runCatching { json.decodeFromString(TmdbTitle.serializer(), body) }.getOrNull()?.let { entries[kind to id] = Entry(it.title, it.year, it.poster, it.genres) }
            }
        }
        entriesLoaded = true
    }

    /** A title's TMDB title and year, for the web admin; null when not loaded. */
    fun entryTitle(kind: String, id: Long): Pair<String, Int?>? { loadEntries(); return entries[kind to id]?.let { it.title to it.year } }

    /** Matches that count: matched or manual, with an ID. */
    private fun liveMatches(): Map<String, VodMatch> = store().matches().filter { it.tmdbId != null && it.status != VodMatch.UNMATCHED }.associateBy { it.vodItemId }

    suspend fun index(): VodIndex {
        val stamp = catalogVersion() * 1_000_003 + changes
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        loadEntries()
        val all = items()
        val matches = liveMatches()
        val tmdbIds = LinkedHashMap<String, Long>()
        val posters = LinkedHashMap<String, String>()
        val byTmdb = HashMap<Pair<String, Long>, MutableList<String>>()
        val genres = HashMap<String, MutableMap<String, MutableList<String>>>()
        for (item in all) {
            val id = matches[item.id]?.tmdbId ?: continue
            val kind = item.kind.name
            tmdbIds[item.id] = id
            byTmdb.getOrPut(kind to id) { mutableListOf() } += item.id
            val e = entries[kind to id] ?: continue
            e.poster?.let { posters[item.id] = it }
            e.genres.forEach { g -> genres.getOrPut(kind) { sortedMapOf() }.getOrPut(g) { mutableListOf() } += item.id }
        }
        val lists = LISTS.associate { (kind, list) -> "$kind:$list" to store().list(kind, list).flatMap { byTmdb[kind to it].orEmpty() } }
            .filterValues { it.isNotEmpty() }
        val body = VodIndex(0, tmdbIds, posters, lists, genres)
        val index = body.copy(version = (body.hashCode().toLong() and 0xffffffffL))
        cached = stamp to index
        return index
    }

    /** One title's metadata, fetched again if older than 7 days (PLAN §4.3.13). Null when not matched. */
    suspend fun meta(item: VodItem): VodMeta? {
        val m = store().match(item.id)?.takeIf { it.tmdbId != null && it.status != VodMatch.UNMATCHED } ?: return null
        val t = runCatching { title(item.kind.name, m.tmdbId!!) }.getOrNull() ?: return null
        val episodes = if (item.kind == VodKind.SERIES) t.seasons.flatMap { runCatching { season(t.id, it) }.getOrDefault(emptyList()) } else emptyList()
        return VodMeta(
            itemId = item.id, tmdbId = t.id, title = t.title, year = t.year, overview = t.overview, genres = t.genres, runtimeMin = t.runtimeMin,
            rating = t.rating, contentRating = t.contentRating, poster = t.poster, backdrop = t.backdrop, trailerKey = t.trailerKey,
            cast = t.cast, director = t.director, episodes = episodes,
        )
    }

    /** Counts for the web admin's status card. */
    data class Counts(val titles: Int, val matched: Int, val manual: Int, val unmatched: Int)

    suspend fun counts(): Counts {
        val all = items()
        val matches = store().matches().associateBy { it.vodItemId }
        val rows = all.mapNotNull { matches[it.id] }
        return Counts(all.size, rows.count { it.status == VodMatch.MATCHED }, rows.count { it.status == VodMatch.MANUAL }, rows.count { it.status == VodMatch.UNMATCHED })
    }

    private data class ListSpec(val kind: String, val list: String, val path: String, val params: Array<Pair<String, String>> = emptyArray())

    companion object Lists {
        const val TRENDING = "trending"
        const val POPULAR = "popular"
        const val NEW = "new"
        const val AIRING = "airing"
        const val TOP_RATED = "top_rated"
        const val SEASON = "SEASON"
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
        const val FOLLOWED_MAX_AGE_MS = 20 * 60 * 60_000L
        const val RETRY_AFTER_MS = 30 * 24 * 60 * 60_000L
        /** A night's matching stops after 2 hours and carries on the next night. */
        const val NIGHT_BUDGET_MS = 2 * 60 * 60_000L
        /** 5 pages of 20: the top 100 of each list, before keeping only what the sources have. */
        const val LIST_PAGES = 5

        private val LISTS = listOf(
            ListSpec(VodKind.MOVIE.name, TRENDING, "/trending/movie/week"),
            ListSpec(VodKind.MOVIE.name, POPULAR, "/movie/popular"),
            ListSpec(VodKind.MOVIE.name, NEW, "/discover/movie"),
            ListSpec(VodKind.MOVIE.name, TOP_RATED, "/movie/top_rated"),
            ListSpec(VodKind.SERIES.name, TRENDING, "/trending/tv/week"),
            ListSpec(VodKind.SERIES.name, POPULAR, "/tv/popular"),
            ListSpec(VodKind.SERIES.name, AIRING, "/tv/on_the_air"),
            ListSpec(VodKind.SERIES.name, TOP_RATED, "/tv/top_rated"),
        )
    }
}

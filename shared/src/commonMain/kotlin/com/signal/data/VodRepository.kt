package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.signal.db.user.UserDatabase
import com.signal.match.CharacterCleanup
import com.signal.match.SearchText
import com.signal.match.VodTitles
import com.signal.model.VodItem
import com.signal.model.VodKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The poster grid's sort (PLAN §4.3.6 → Poster grid). */
enum class VodSort(val label: String) { RECENT("Recently added"), A_Z("A–Z"), YEAR("Year"), RATING("Rating") }

/**
 * Movies and series from the cached catalogs (PLAN §4.3.6–4.3.7): the browse pages, Search, and
 * titles across sources. A real catalog has hundreds of thousands of titles, so everything here is a
 * query; nothing holds the catalog, or the companion's matches, in memory.
 */
class VodRepository(
    private val db: UserDatabase,
    private val io: CoroutineDispatcher,
    /** A provider poster through the companion's cache, for titles TMDB has none for; null when it's out of reach. */
    private val providerPoster: (String) -> String? = { null },
    /** A TMDB poster path as a URL: the companion's cached copy, or TMDB's CDN when it's out of reach. */
    private val tmdbPoster: (String) -> String? = { null },
) {
    data class ShelfEntry(val shelf: String, val count: Int)

    /** The TMDB lists and genres with titles in enabled sources ("list:trending", "genre:Action"). */
    fun shelves(kind: VodKind): Flow<List<ShelfEntry>> = db.vodQueries.shelves(kind.name).asFlow().mapToList(io).map { rows ->
        rows.map { ShelfEntry(it.shelf, it.item_count.toInt()) }
    }

    /** A list or genre in TMDB's order; the same title from two sources shows once. */
    suspend fun shelfItems(kind: VodKind, shelf: String): List<VodItem> = withContext(io) {
        db.vodQueries.itemsInShelf(kind.name, shelf, ::row).executeAsList().distinctBy { it.tmdbId ?: it.id }
    }

    data class CategoryEntry(val sourceId: String, val categoryId: String, val name: String, val count: Int)

    fun categories(kind: VodKind): Flow<List<CategoryEntry>> = db.vodQueries.categories(kind.name).asFlow().mapToList(io).map { rows ->
        rows.map { CategoryEntry(it.source_id, it.category_id, it.name, it.item_count.toInt()) }
    }

    /** How many titles of [kind] the enabled sources have: Movies and Series hide at 0 (PLAN §4.1). */
    fun count(kind: VodKind): Flow<Long> = db.vodQueries.countForKind(kind.name).asFlow().mapToOne(io)

    suspend fun items(kind: VodKind, sourceId: String, categoryId: String, sort: VodSort): List<VodItem> = withContext(io) {
        sorted(db.vodQueries.itemsInCategory(sourceId, kind.name, categoryId, ::row).executeAsList(), sort)
    }

    suspend fun recentlyAdded(kind: VodKind, limit: Int = RECENT_LIMIT): List<VodItem> = withContext(io) {
        db.vodQueries.recentlyAdded(kind.name, limit.toLong(), ::row).executeAsList()
    }

    suspend fun item(id: String): VodItem? = withContext(io) { db.vodQueries.itemById(id, ::row).executeAsOneOrNull() }

    /** Every title of every enabled source: for the companion's matcher. */
    suspend fun all(): List<VodItem> = withContext(io) { db.vodQueries.allItems(::row).executeAsList() }

    /** Emits whenever the catalog, the sources or the TMDB matches change. */
    fun catalogChanges(): Flow<Unit> = db.vodQueries.catalogTick().asFlow().map { }

    /**
     * Fills title_key and search_name for rows cached before they existed, a batch at a time, and
     * adds them to Search. Returns how many were filled.
     */
    suspend fun fillMissingKeys(batch: Int = 2_000): Int {
        var total = 0
        while (true) {
            val done = withContext(io) {
                val rows = db.vodQueries.needsKeys(batch.toLong()).executeAsList()
                db.transaction {
                    for (r in rows) {
                        val (key, search) = keys(r.name)
                        db.vodQueries.setKeys(key, search, r.id)
                        db.vodQueries.indexFts(r.id)
                    }
                }
                rows.size
            }
            total += done
            if (done < batch) return total
        }
    }

    // ---- Titles across sources: carry-over, Play from… and Find (PLAN §4.3.4, §4.3.8, §4.3.11) ----

    /** True when an enabled source has the title with this item ID. */
    suspend fun available(id: String): Boolean = withContext(io) { db.vodQueries.availableById(id).executeAsOne() > 0 }

    /**
     * Every source's copy of [item], one per source, preferred source first (PLAN §4.3.8 → Stream
     * choice): the same TMDB ID, or else the same title and year. Just [item] when no other source has it.
     */
    suspend fun copies(item: VodItem): List<VodItem> = withContext(io) {
        val key = item.titleKey.ifEmpty { VodTitles.nameKey(item.name) }
        val byId = item.tmdbId?.let { byTmdb(item.kind, it) }.orEmpty()
        val byName = if (key.isEmpty()) emptyList() else db.vodQueries.byTitleKey(item.kind.name, key, ::row).executeAsList()
        // Both queries come back preferred source first; merged, that order is kept per source.
        val all = (byId + byName).distinctBy { it.id }.filter { sameTitle(it, item) }
        val order = sourceOrder()
        all.sortedWith(compareBy({ order[it.sourceId] ?: Int.MAX_VALUE }, { it.position })).distinctBy { it.sourceId }
            .map { if (it.id == item.id) item else it }.ifEmpty { listOf(item) }
    }

    /**
     * What a saved key plays today (PLAN §4.3.11 → Across providers): a title with that TMDB ID, else
     * the item it was saved from while its source is enabled, else the same title and year. Null when
     * no enabled source has it: the Library shows it as unavailable.
     */
    suspend fun resolve(key: String, kind: VodKind, vodItemId: String, title: String?, year: Int?): VodItem? = withContext(io) {
        tmdbOf(key)?.let { id -> byTmdb(kind, id).firstOrNull()?.let { return@withContext it } }
        for (id in listOf(key, vodItemId).distinct()) {
            if (db.vodQueries.availableById(id).executeAsOne() > 0) db.vodQueries.itemById(id, ::row).executeAsOneOrNull()?.let { return@withContext it }
        }
        val titleKey = title?.let(VodTitles::nameKey).orEmpty()
        if (titleKey.isEmpty()) null else byTitle(db.vodQueries.byTitleKey(kind.name, titleKey, ::row).executeAsList(), year)
    }

    /**
     * The confident match for a guide program (PLAN §4.3.4 → Find in Movies / Find in Series): the
     * same normalized title, plus the year within one for movies when the guide has one. Several
     * different titles by that name (two series, a remake) is not confident, and gives null.
     */
    suspend fun find(title: String, year: Int?, kind: VodKind): VodItem? = withContext(io) {
        val key = VodTitles.nameKey(title)
        if (key.isEmpty()) return@withContext null
        val candidates = db.vodQueries.byTitleKey(kind.name, key, ::row).executeAsList()
        val pool = if (kind == VodKind.MOVIE && year != null) {
            candidates.filter { yearOf(it) == year }.ifEmpty { candidates.filter { c -> yearOf(c)?.let { kotlin.math.abs(it - year) <= 1 } == true } }
        } else candidates
        pool.groupBy(::group).values.singleOrNull()?.first()
    }

    /**
     * Titles of [kind] matching every word of [query] as a word prefix (PLAN §4.3.14), through the
     * full-text index: exact, then prefix, then the rest. The same title from several sources shows once.
     */
    suspend fun search(query: String, kind: VodKind, limit: Int = 60): List<VodItem> {
        val words = SearchText.words(query)
        if (words.isEmpty()) return emptyList()
        val folded = words.joinToString(" ")
        // FTS4 prefix query; the words are letters and digits only, so nothing needs escaping.
        val match = words.joinToString(" ") { "$it*" }
        return withContext(io) {
            db.vodQueries.searchTitles(match, kind.name, folded, "$folded%", limit * 4L, ::row).executeAsList()
                .distinctBy(::group).take(limit)
        }
    }

    /** Sources by preference: the order they were added. */
    private fun sourceOrder(): Map<String, Int> = db.sourceQueries.all().executeAsList().mapIndexed { i, s -> s.id to i }.toMap()

    /** Titles with this TMDB ID, dropping provider IDs the companion overruled. */
    private fun byTmdb(kind: VodKind, tmdbId: Long): List<VodItem> =
        db.vodQueries.byTmdb(kind.name, tmdbId, ::row).executeAsList().filter { it.tmdbId == tmdbId }

    /** The one title by this name and year; with no year, only when the name alone is unambiguous. */
    private fun byTitle(candidates: List<VodItem>, year: Int?): VodItem? {
        if (year != null) {
            candidates.firstOrNull { yearOf(it) == year }?.let { return it }
            // A known different year is another title; only copies with no year are left to choose from.
            return candidates.filter { yearOf(it) == null }.groupBy(::group).values.singleOrNull()?.first()
        }
        return candidates.groupBy(::group).values.singleOrNull()?.first()
    }

    /** The same title in another source: the same TMDB ID, or else the same title and year. */
    private fun sameTitle(a: VodItem, b: VodItem): Boolean = a.kind == b.kind && when {
        a.tmdbId != null && b.tmdbId != null -> a.tmdbId == b.tmdbId
        else -> a.titleKey.isNotEmpty() && a.titleKey == b.titleKey.ifEmpty { VodTitles.nameKey(b.name) } && yearOf(a) == yearOf(b)
    }

    private fun yearOf(item: VodItem): Int? = item.year ?: VodTitles.year(item.name)

    /** One entry per distinct title, for de-duplicating across sources. */
    private fun group(item: VodItem): String = item.tmdbId?.let { "t:${item.kind}:$it" } ?: "n:${item.kind}:${item.titleKey}|${yearOf(item) ?: ""}"

    companion object {
        const val RECENT_LIMIT = 300

        fun sorted(items: List<VodItem>, sort: VodSort): List<VodItem> = when (sort) {
            VodSort.RECENT -> items.sortedWith(compareByDescending<VodItem> { it.addedAt }.thenBy { it.position })
            VodSort.A_Z -> items.sortedBy { displayName(it).lowercase() }
            VodSort.YEAR -> items.sortedWith(compareByDescending<VodItem> { it.year ?: 0 }.thenBy { displayName(it).lowercase() })
            VodSort.RATING -> items.sortedWith(compareByDescending<VodItem> { it.rating ?: 0.0 }.thenBy { displayName(it).lowercase() })
        }

        private val yearSuffix = Regex("""\s*[(\[](19\d{2}|20\d{2})[)\]]\s*$""")

        /** The title as shown: cleaned up, without a trailing "(1995)" when the year shows beside it. */
        fun displayName(item: VodItem): String = displayNameOf(item.name)

        fun displayNameOf(name: String): String {
            val clean = CharacterCleanup.clean(name, removeEmoji = true).ifEmpty { name }
            return clean.replace(yearSuffix, "").ifEmpty { clean }
        }

        /**
         * A catalog name's title_key and search_name. A name with nothing to search on gets "-", so
         * it isn't taken for a row still waiting for its keys.
         */
        fun keys(name: String): Pair<String, String> = VodTitles.nameKey(name) to SearchText.fold(displayNameOf(name)).ifEmpty { "-" }

        /** The TMDB ID in a "tmdb:movie:<id>" or "tmdb:tv:<id>" item key (episodes' ":s1e2" ignored), else null. */
        fun tmdbOf(key: String): Long? = if (key.startsWith("tmdb:")) key.split(':').getOrNull(2)?.toLongOrNull() else null
    }

    /** Every query that returns titles has these columns: the item, then the companion's match beside it. */
    @Suppress("UNUSED_PARAMETER")
    private fun row(
        id: String,
        source_id: String,
        kind: String,
        provider_id: String,
        name: String,
        category_id: String,
        poster_url: String?,
        year: Long?,
        rating: Double?,
        added_at: Long,
        tmdb_id: Long?,
        container_ext: String?,
        plot: String?,
        url: String?,
        position: Long,
        title_key: String,
        search_name: String,
        nas_tmdb: Long?,
        nas_poster: String?,
    ): VodItem {
        // TMDB's poster, else the provider's through the companion; the provider's own URL is the fallback.
        val nasPoster = nas_poster?.let(tmdbPoster) ?: poster_url?.takeIf { it.isNotBlank() }?.let { providerPoster(id) }
        return VodItem(
            id = id,
            sourceId = source_id,
            kind = runCatching { VodKind.valueOf(kind) }.getOrDefault(VodKind.MOVIE),
            providerId = provider_id,
            name = name,
            categoryId = category_id,
            posterUrl = nasPoster ?: poster_url,
            fallbackPosterUrl = if (nasPoster != null) poster_url else null,
            year = year?.toInt(),
            rating = rating,
            addedAt = added_at,
            // The companion's match wins: it checked the provider's ID, or found one where there was none.
            tmdbId = nas_tmdb ?: tmdb_id,
            containerExt = container_ext,
            plot = plot,
            url = url,
            position = position.toInt(),
            titleKey = title_key,
        )
    }
}

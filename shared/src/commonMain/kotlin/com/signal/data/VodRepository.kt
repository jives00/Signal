package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.signal.db.user.UserDatabase
import com.signal.match.CharacterCleanup
import com.signal.model.VodItem
import com.signal.model.VodKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The poster grid's sort (PLAN §4.3.6 → Poster grid). */
enum class VodSort(val label: String) { RECENT("Recently added"), A_Z("A–Z"), YEAR("Year"), RATING("Rating") }

/** Movies and series from the cached catalogs (PLAN §4.3.6–4.3.7), for the browse pages. */
class VodRepository(private val db: UserDatabase, private val io: CoroutineDispatcher) {
    data class CategoryEntry(val sourceId: String, val categoryId: String, val name: String, val count: Int)

    fun categories(kind: VodKind): Flow<List<CategoryEntry>> = db.vodQueries.categories(kind.name).asFlow().mapToList(io).map { rows ->
        rows.map { CategoryEntry(it.source_id, it.category_id, it.name, it.item_count.toInt()) }
    }

    /** How many titles of [kind] the enabled sources have: Movies and Series hide at 0 (PLAN §4.1). */
    fun count(kind: VodKind): Flow<Long> = db.vodQueries.countForKind(kind.name).asFlow().mapToOne(io)

    suspend fun items(kind: VodKind, sourceId: String, categoryId: String, sort: VodSort): List<VodItem> = withContext(io) {
        sorted(db.vodQueries.itemsInCategory(sourceId, kind.name, categoryId).executeAsList().map { it.toModel() }, sort)
    }

    suspend fun recentlyAdded(kind: VodKind, limit: Int = RECENT_LIMIT): List<VodItem> = withContext(io) {
        db.vodQueries.recentlyAdded(kind.name, limit.toLong()).executeAsList().map { it.toModel() }
    }

    suspend fun item(id: String): VodItem? = withContext(io) { db.vodQueries.itemById(id).executeAsOneOrNull()?.toModel() }

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
        fun displayName(item: VodItem): String {
            val clean = CharacterCleanup.clean(item.name, removeEmoji = true).ifEmpty { item.name }
            return clean.replace(yearSuffix, "").ifEmpty { clean }
        }
    }

    private fun com.signal.db.user.Vod_item.toModel() = VodItem(
        id = id,
        sourceId = source_id,
        kind = runCatching { VodKind.valueOf(kind) }.getOrDefault(VodKind.MOVIE),
        providerId = provider_id,
        name = name,
        categoryId = category_id,
        posterUrl = poster_url,
        year = year?.toInt(),
        rating = rating,
        addedAt = added_at,
        tmdbId = tmdb_id,
        containerExt = container_ext,
        plot = plot,
        url = url,
        position = position.toInt(),
    )
}

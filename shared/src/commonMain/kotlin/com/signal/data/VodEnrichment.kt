package com.signal.data

import com.signal.db.user.UserDatabase
import com.signal.model.MovieDetail
import com.signal.model.SeriesDetail
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.nas.NasConnection
import com.signal.nas.VodIndex
import com.signal.nas.TmdbImages
import com.signal.nas.VodMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The companion's VOD enrichment on the device (PLAN §4.3.12): its index (TMDB IDs, posters, lists
 * and genres) kept in vod_tmdb and vod_shelf, and each title's metadata when its page opens. Without
 * the NAS nothing here changes, and the pages show the provider's own data.
 */
class VodEnrichment(
    private val db: UserDatabase,
    private val settings: Settings,
    private val vod: VodRepository,
    private val nas: () -> NasConnection?,
    private val io: CoroutineDispatcher,
    private val log: Log,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Fetches the index unless it's unchanged. True when something new was stored. */
    suspend fun sync(): Boolean = lock.withLock {
        val connection = nas() ?: return false
        val have = settings.raw(Settings.Key.VOD_INDEX_VERSION)?.toLongOrNull()
        when (val r = connection.vodIndex(have)) {
            is NasConnection.IndexResult.Fresh -> { store(r.index); true }
            else -> false
        }
    }

    /** Replaces the stored index, then moves watch data onto the new TMDB keys. */
    suspend fun store(index: VodIndex) {
        withContext(io) {
            db.transaction {
                db.vodQueries.clearEnrichment()
                for ((item, id) in index.tmdbIds) db.vodQueries.insertTmdb(item, id, index.posters[item])
                for ((key, items) in index.lists) {
                    val kind = key.substringBefore(':')
                    items.forEachIndexed { i, item -> db.vodQueries.insertShelf(kind, "$LIST${key.substringAfter(':')}", i.toLong(), item) }
                }
                for ((kind, genres) in index.genres) for ((genre, items) in genres) {
                    items.forEachIndexed { i, item -> db.vodQueries.insertShelf(kind, "$GENRE$genre", i.toLong(), item) }
                }
            }
            vod.enrichmentChanged()
        }
        settings.put(Settings.Key.VOD_INDEX_VERSION, index.version.toString())
        val moved = rekey(index)
        log.info("VOD index ${index.version}: ${index.tmdbIds.size} matched, ${index.lists.size} lists" + if (moved > 0) ", $moved titles moved to TMDB keys" else "")
    }

    /**
     * Library, resume and watched rows still keyed by a source's item ID move to the TMDB key once the
     * companion matches the title (PLAN §5.4), so they survive a provider switch. Sync pushes the moves.
     */
    private suspend fun rekey(index: VodIndex): Int = withContext(io) {
        val keys = db.watchQueries.itemKeyed().executeAsList().mapNotNull { it.item_key }.toSet()
        var moved = 0
        db.transaction {
            for (old in keys) {
                val id = index.tmdbIds[old] ?: continue
                val kind = if (old.contains(":s:")) VodKind.SERIES else VodKind.MOVIE
                val new = if (kind == VodKind.SERIES) "tmdb:tv:$id" else "tmdb:movie:$id"
                db.watchQueries.rekeyLibrary(new, old)
                moved++
            }
        }
        moved
    }

    /**
     * A title's enriched metadata: from the companion when it answers (it refreshes from TMDB after 7
     * days), else the copy kept from last time. Null for titles it hasn't matched.
     */
    suspend fun meta(item: VodItem): VodMeta? {
        val cacheKey = "$META_PREFIX${item.id}"
        val fresh = nas()?.vodMeta(item.id)
        if (fresh != null) {
            withContext(io) { db.watchQueries.putInfo(cacheKey, Clock.System.now().toEpochMilliseconds(), json.encodeToString(VodMeta.serializer(), fresh)) }
            return fresh
        }
        return withContext(io) { db.watchQueries.infoFor(cacheKey).executeAsOneOrNull() }?.let { runCatching { json.decodeFromString(VodMeta.serializer(), it.body) }.getOrNull() }
    }

    companion object {
        const val LIST = "list:"
        const val GENRE = "genre:"
        /** vod_info rows for the companion's metadata, beside the provider's under the bare item ID. */
        const val META_PREFIX = "nas:"

        /**
         * The provider's detail with the companion's on top (PLAN §4.3.8): TMDB's overview, genres,
         * credits and images where it has them. A movie the provider can't describe still gets TMDB's.
         */
        fun merge(detail: MovieDetail?, meta: VodMeta?, image: (String, String) -> String): MovieDetail? {
            if (meta == null) return detail
            val d = detail ?: MovieDetail()
            return d.copy(
                plot = meta.overview ?: d.plot,
                genres = meta.genres.ifEmpty { d.genres },
                cast = meta.cast.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: d.cast,
                director = meta.director ?: d.director,
                durationSec = meta.runtimeMin?.let { it * 60 } ?: d.durationSec,
                rating = meta.rating ?: d.rating,
                backdropUrl = meta.backdrop?.let { image(TmdbImages.BACKDROP, it) } ?: d.backdropUrl,
                posterUrl = meta.poster?.let { image(TmdbImages.POSTER, it) } ?: d.posterUrl,
                trailerKey = meta.trailerKey ?: d.trailerKey,
                tmdbId = meta.tmdbId,
                contentRating = meta.contentRating ?: d.contentRating,
            )
        }

        /** Series too, and each episode gets TMDB's title, overview and still when the provider's are missing or generic. */
        fun merge(detail: SeriesDetail?, meta: VodMeta?, image: (String, String) -> String): SeriesDetail? {
            if (detail == null || meta == null) return detail
            val tmdbEpisodes = meta.episodes.associateBy { it.season to it.episode }
            return detail.copy(
                plot = meta.overview ?: detail.plot,
                genres = meta.genres.ifEmpty { detail.genres },
                cast = meta.cast.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: detail.cast,
                director = meta.director ?: detail.director,
                rating = meta.rating ?: detail.rating,
                backdropUrl = meta.backdrop?.let { image(TmdbImages.BACKDROP, it) } ?: detail.backdropUrl,
                trailerKey = meta.trailerKey ?: detail.trailerKey,
                tmdbId = meta.tmdbId,
                contentRating = meta.contentRating ?: detail.contentRating,
                episodes = detail.episodes.map { e ->
                    val t = tmdbEpisodes[e.season to e.episode] ?: return@map e
                    e.copy(
                        title = t.title?.takeIf { e.title.isBlank() || e.title.matches(GENERIC_EPISODE) } ?: e.title,
                        plot = e.plot ?: t.overview,
                        stillUrl = t.still?.let { image(TmdbImages.STILL, it) } ?: e.stillUrl,
                        durationSec = e.durationSec ?: t.runtimeMin?.let { it * 60 },
                    )
                },
            )
        }

        /** "Episode 3", "Ep. 3", "E03", "S01E03", or a file-like name with S01E03 in it. */
        private val GENERIC_EPISODE = Regex("""(?i)(episode|ep\.?|e)\s*\d+|s\d+\s*e\d+|.*\bS\d{1,2}E\d{1,3}\b.*""")

        /** The Lists entries in the order PLAN §4.3.6–4.3.7 gives, after Recently added. */
        fun lists(kind: VodKind): List<Pair<String, String>> = if (kind == VodKind.MOVIE) {
            listOf("trending" to "Trending", "popular" to "Popular", "new" to "New releases", "top_rated" to "Top rated")
        } else {
            listOf("trending" to "Trending", "popular" to "Popular", "airing" to "Airing now", "top_rated" to "Top rated")
        }
    }
}

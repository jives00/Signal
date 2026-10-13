package com.signal.data

import com.signal.db.user.UserDatabase
import com.signal.demo.DemoVod
import com.signal.model.MovieDetail
import com.signal.model.SeriesDetail
import com.signal.model.SourceKind
import com.signal.model.VodItem
import com.signal.parse.XtreamParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Movie and series detail from the provider (PLAN §4.3.8–4.3.9), cached in user.db: refreshed when
 * opened if older than 7 days (PLAN §4.3.13), and kept when a refresh fails. The NAS's enriched
 * metadata layers on top in slice 3.
 */
class VodDetailRepository(
    private val db: UserDatabase,
    private val sources: SourceRepository,
    private val io: CoroutineDispatcher,
    private val log: Log,
) {
    /** Bumped when fresh detail is stored: Continue watching and the New episode badges rebuild. */
    val updates = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** True when [item]'s cached detail is missing or older than [maxAgeMs]. Demo detail is generated, never stale. */
    suspend fun isStale(item: VodItem, maxAgeMs: Long): Boolean = withContext(io) {
        if (sources.byId(item.sourceId)?.kind != SourceKind.XTREAM) return@withContext false
        val cached = db.watchQueries.infoFor(item.id).executeAsOneOrNull() ?: return@withContext true
        Clock.System.now().toEpochMilliseconds() - cached.fetched_at >= maxAgeMs
    }

    private suspend fun body(item: VodItem, maxAgeMs: Long, fetch: suspend () -> String): String? = withContext(io) {
        val cached = db.watchQueries.infoFor(item.id).executeAsOneOrNull()
        val now = Clock.System.now().toEpochMilliseconds()
        if (cached != null && now - cached.fetched_at < maxAgeMs) return@withContext cached.body
        runCatching { fetch() }
            .onSuccess { db.watchQueries.putInfo(item.id, now, it); updates.value++ }
            .onFailure { log.warn("Detail for ${VodRepository.displayName(item)} failed: ${it.message}") }
            .getOrNull() ?: cached?.body
    }

    suspend fun movie(item: VodItem): MovieDetail? {
        val source = sources.byId(item.sourceId) ?: return null
        if (source.kind == SourceKind.DEMO) return DemoVod.movieDetail(item)
        if (source.kind != SourceKind.XTREAM) return null
        return body(item, MAX_AGE_MS) { sources.xtream(source, "get_vod_info", "vod_id" to item.providerId) }
            ?.let { runCatching { XtreamParser.movieDetail(it) }.getOrNull() }
    }

    /** [maxAgeMs] lower for series in the Library or Continue watching (PLAN §4.3.13: new episodes). */
    suspend fun series(item: VodItem, maxAgeMs: Long = MAX_AGE_MS): SeriesDetail? {
        val source = sources.byId(item.sourceId) ?: return null
        if (source.kind == SourceKind.DEMO) return DemoVod.seriesDetail(item)
        if (source.kind != SourceKind.XTREAM) return null
        return body(item, maxAgeMs) { sources.xtream(source, "get_series_info", "series_id" to item.providerId) }
            ?.let { runCatching { XtreamParser.seriesDetail(source, it) }.getOrNull() }
    }

    /** The cached series detail only, never fetched: for building Continue watching quickly. */
    suspend fun cachedSeries(item: VodItem): SeriesDetail? = withContext(io) {
        val source = sources.byId(item.sourceId) ?: return@withContext null
        if (source.kind == SourceKind.DEMO) return@withContext DemoVod.seriesDetail(item)
        db.watchQueries.infoFor(item.id).executeAsOneOrNull()?.body?.let { runCatching { XtreamParser.seriesDetail(source, it) }.getOrNull() }
    }

    companion object {
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
        const val FOLLOWED_MAX_AGE_MS = 24 * 60 * 60_000L
    }
}

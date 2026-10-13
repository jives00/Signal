package com.signal.data

import com.signal.model.SeriesDetail
import com.signal.model.VodKind

/**
 * The "New episode" badge (PLAN §4.3.7, §4.3.9): a followed series has an episode the source added
 * after the user last watched it (or added it to the Library), past the last episode they finished.
 * It comes from the episodes' `added` dates, so it needs no state of its own and clears as soon as
 * the user watches anything in the series.
 */
object NewEpisodes {
    fun isNew(followed: WatchRepository.Followed, detail: SeriesDetail, state: WatchRepository.State): Boolean {
        val key = followed.seriesKey
        val last = detail.episodes.filter { ItemKeys.episode(key, it) in state.watched }.maxWithOrNull(compareBy({ it.season }, { it.episode }))
        return detail.episodes.any { e ->
            e.addedAt > 0 && e.addedAt * 1000 > followed.lastActivity &&
                (last == null || e.season > last.season || (e.season == last.season && e.episode > last.episode)) &&
                ItemKeys.episode(key, e) !in state.watched
        }
    }

    /** The series keys that carry the badge, from each followed series' cached detail. */
    suspend fun build(state: WatchRepository.State, vod: VodRepository, details: VodDetailRepository): Set<String> {
        val out = HashSet<String>()
        for (f in state.followedSeries().values) {
            val item = vod.resolve(f.seriesKey, VodKind.SERIES, f.vodItemId, f.title, f.year) ?: continue
            val detail = details.cachedSeries(item) ?: continue
            if (isNew(f, detail, state)) out += f.seriesKey
        }
        return out
    }

    /**
     * Refreshes the provider's detail of followed series older than [VodDetailRepository.FOLLOWED_MAX_AGE_MS]
     * (PLAN §4.3.13): a few calls, not a catalog scan. True when anything was fetched.
     */
    suspend fun refresh(state: WatchRepository.State, vod: VodRepository, details: VodDetailRepository): Boolean {
        var fetched = false
        for (f in state.followedSeries().values) {
            val item = vod.resolve(f.seriesKey, VodKind.SERIES, f.vodItemId, f.title, f.year) ?: continue
            if (details.isStale(item, VodDetailRepository.FOLLOWED_MAX_AGE_MS)) {
                details.series(item, VodDetailRepository.FOLLOWED_MAX_AGE_MS)
                fetched = true
            }
        }
        return fetched
    }
}

package com.signal.data

import com.signal.model.Episode
import com.signal.model.VodItem
import com.signal.model.VodKind

/**
 * Home's Continue watching row (PLAN §4.3.1), modeled on Nuvio-Fork's: started movies and episodes
 * under 90%, and the next episode after a finished one. Most recent first; older than 60 days drops off.
 */
object ContinueWatching {
    data class Card(
        val key: String,
        val title: String,
        /** Episodes: "S2 E3 · Title". */
        val subtitle: String?,
        /** Null for next up, which shows "Next up" in place of the bar. */
        val fraction: Float?,
        val item: VodItem,
        val episode: Episode?,
        val seriesKey: String?,
        val at: Long,
    ) {
        val nextUp: Boolean get() = fraction == null
    }

    const val MAX_AGE_MS = 60L * 24 * 60 * 60_000
    const val MAX_CARDS = 20

    suspend fun build(state: WatchRepository.State, vod: VodRepository, details: VodDetailRepository, now: Long): List<Card> {
        val cards = ArrayList<Card>()
        val items = HashMap<String, VodItem?>()
        suspend fun item(id: String) = items.getOrPut(id) { vod.item(id) }

        // Started, not finished.
        for (p in state.positions.values) {
            if (now - p.updatedAt > MAX_AGE_MS || p.key in state.watched) continue
            val it = item(p.vodItemId) ?: continue
            val episode = if (p.kind == "EPISODE") details.cachedSeries(it)?.episodes?.firstOrNull { e -> e.id == p.episodeId } else null
            if (p.kind == "EPISODE" && episode == null) continue
            cards += Card(p.key, VodRepository.displayName(it), episode?.let { e -> "${e.code} · ${e.title}" }, p.fraction, it, episode, p.seriesKey, p.updatedAt)
        }

        // Next up: the episode after the last one finished, per series, unless one is already in progress.
        val inProgress = state.positions.values.mapNotNull { it.seriesKey }.toSet()
        val lastBySeries = state.watched.values.filter { it.kind == "EPISODE" && it.seriesKey != null && it.season != null && it.episode != null }
            .groupBy { it.seriesKey!! }.mapValues { (_, w) -> w.maxBy { it.watchedAt } }
        for ((seriesKey, last) in lastBySeries) {
            if (seriesKey in state.hidden || seriesKey in inProgress || now - last.watchedAt > MAX_AGE_MS) continue
            val series = item(last.vodItemId)?.takeIf { it.kind == VodKind.SERIES } ?: continue
            val detail = details.cachedSeries(series) ?: continue
            val next = detail.after(last.season!!, last.episode!!) ?: continue
            val key = ItemKeys.episode(seriesKey, next)
            if (key in state.watched) continue
            cards += Card(key, VodRepository.displayName(series), "Next up ${next.code} · ${next.title}", null, series, next, seriesKey, last.watchedAt)
        }
        return cards.sortedByDescending { it.at }.take(MAX_CARDS)
    }
}

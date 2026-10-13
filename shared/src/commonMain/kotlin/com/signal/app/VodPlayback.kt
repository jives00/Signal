package com.signal.app

import com.signal.data.ItemKeys
import com.signal.data.Log
import com.signal.data.SourceRepository
import com.signal.data.VodDetailRepository
import com.signal.data.VodRepository
import com.signal.data.WatchRepository
import com.signal.model.CredentialMasking
import com.signal.model.Episode
import com.signal.model.SeriesDetail
import com.signal.model.StreamUrls
import com.signal.model.VodItem
import com.signal.player.PlayRequest
import com.signal.player.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Movie and episode playback (PLAN §4.3.10): what's playing, the resume position saved every 10
 * seconds and on pause, Back and leaving the app, and the next episode for autoplay (PLAN §4.3.9).
 * Live TV stays in PlaybackController; the two share the one player.
 */
class VodPlayback(
    private val player: VideoPlayer,
    private val sources: SourceRepository,
    private val details: VodDetailRepository,
    private val watch: WatchRepository,
    private val scope: CoroutineScope,
    private val log: Log,
) {
    data class Now(
        val item: VodItem,
        val key: String,
        val title: String,
        /** Episodes: "S2 E3 · Title". */
        val subtitle: String?,
        val episode: Episode? = null,
        val series: SeriesDetail? = null,
        val seriesKey: String? = null,
    ) {
        val next: Episode? get() = episode?.let { e -> series?.after(e.season, e.episode) }
    }

    private val _current = MutableStateFlow<Now?>(null)
    val current: StateFlow<Now?> = _current.asStateFlow()
    private var saver: Job? = null

    /** Autoplay's "Still watching?" counter (PLAN §4.3.9): episodes in a row with no key pressed. */
    var unattendedEpisodes = 0

    suspend fun playMovie(item: VodItem, fromStart: Boolean = false) {
        val detail = details.movie(item)
        val key = ItemKeys.movie(item, detail?.tmdbId ?: item.tmdbId)
        val start = if (fromStart) 0 else watch.current().positions[key]?.positionMs ?: 0
        if (fromStart) watch.clearPosition(key)
        val source = sources.byId(item.sourceId) ?: return
        val url = StreamUrls.movie(source, item, detail?.containerExt)
        start(Now(item, key, VodRepository.displayName(item), null), url, source.headers, start)
    }

    suspend fun playEpisode(series: VodItem, detail: SeriesDetail, episode: Episode, fromStart: Boolean = false) {
        val seriesKey = ItemKeys.series(series, detail.tmdbId ?: series.tmdbId)
        val key = ItemKeys.episode(seriesKey, episode)
        val start = if (fromStart) 0 else watch.current().positions[key]?.positionMs ?: 0
        if (fromStart) watch.clearPosition(key)
        val source = sources.byId(series.sourceId) ?: return
        val now = Now(series, key, VodRepository.displayName(series), "${episode.code} · ${episode.title}", episode, detail, seriesKey)
        start(now, StreamUrls.episode(source, episode), source.headers, start)
    }

    private fun start(now: Now, url: String, headers: Map<String, String>, startMs: Long) {
        saveNow()
        _current.value = now
        log.info("Play ${now.title}${now.subtitle?.let { " $it" } ?: ""} (${CredentialMasking.forLog(url)})${if (startMs > 0) " from ${startMs / 1000}s" else ""}")
        player.play(PlayRequest(url = url, headers = headers, isLive = false, startPositionMs = startMs, label = now.subtitle ?: now.title))
        saver?.cancel()
        saver = scope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (!player.state.value.paused) saveNow()
            }
        }
    }

    /** Saves the position of what's playing (PLAN §4.3.10). At 90% it's marked watched instead. */
    fun saveNow() {
        val now = _current.value ?: return
        val pos = player.position()
        if (pos.durationMs <= 0) return
        val p = WatchRepository.Position(
            key = now.key,
            kind = if (now.episode != null) "EPISODE" else "MOVIE",
            vodItemId = now.item.id,
            episodeId = now.episode?.id,
            seriesKey = now.seriesKey,
            season = now.episode?.season,
            episode = now.episode?.episode,
            title = now.subtitle?.let { "${now.title} · $it" } ?: now.title,
            positionMs = pos.positionMs,
            durationMs = pos.durationMs,
            updatedAt = 0,
        )
        scope.launch { watch.savePosition(p) }
    }

    fun setPaused(paused: Boolean) {
        player.setPaused(paused)
        if (paused) saveNow()
    }

    /** Back, the end of a movie, or leaving the app: save and stop. */
    fun stop() {
        saveNow()
        saver?.cancel()
        player.stop()
        _current.value = null
    }

    /** The app went to the background (PLAN §4.2): save, free the decoder, and keep what was playing. */
    fun release() {
        saveNow()
        saver?.cancel()
        player.release()
    }

    /** Back from the background: play on from the saved position. */
    suspend fun resume() {
        val now = _current.value ?: return
        val episode = now.episode
        if (episode != null && now.series != null) playEpisode(now.item, now.series, episode) else playMovie(now.item)
    }

    /** A finished video: marked watched (it played to the end, so past 90%). */
    fun finished() {
        val now = _current.value ?: return
        saver?.cancel()
        scope.launch {
            watch.markWatched(now.key, if (now.episode != null) "EPISODE" else "MOVIE", now.item.id, now.seriesKey, now.episode?.season, now.episode?.episode)
        }
    }

    companion object {
        const val SAVE_EVERY_MS = 10_000L
    }
}

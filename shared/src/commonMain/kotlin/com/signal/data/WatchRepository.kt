package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import com.signal.db.user.UserDatabase
import com.signal.model.Episode
import com.signal.model.VodItem
import com.signal.model.VodKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Item keys (PLAN §5.4): a TMDB ID when known, so the Library and resume positions carry across
 * providers, else the source's own item ID.
 */
object ItemKeys {
    fun movie(item: VodItem, tmdbId: Long? = item.tmdbId) = tmdbId?.let { "tmdb:movie:$it" } ?: item.id
    fun series(item: VodItem, tmdbId: Long? = item.tmdbId) = tmdbId?.let { "tmdb:tv:$it" } ?: item.id
    fun episode(seriesKey: String, episode: Episode) = "$seriesKey:s${episode.season}e${episode.episode}"
    fun of(item: VodItem) = if (item.kind == VodKind.MOVIE) movie(item) else series(item)
}

/**
 * The Library, resume positions and watched state for the device's profile (PLAN §4.3.8–4.3.11).
 * All profile scope, so they sync like favorites.
 */
class WatchRepository(private val db: UserDatabase, private val settings: Settings, private val io: CoroutineDispatcher) {
    private val profile get() = settings.profileId
    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    data class LibraryEntry(val key: String, val kind: VodKind, val vodItemId: String, val title: String, val year: Int?, val posterUrl: String?, val addedAt: Long)

    data class Position(
        val key: String,
        val kind: String,
        val vodItemId: String,
        val episodeId: String?,
        val seriesKey: String?,
        val season: Int?,
        val episode: Int?,
        val title: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
    ) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    data class Watched(val key: String, val kind: String, val vodItemId: String, val seriesKey: String?, val season: Int?, val episode: Int?, val watchedAt: Long)

    data class State(
        val library: List<LibraryEntry> = emptyList(),
        val positions: Map<String, Position> = emptyMap(),
        val watched: Map<String, Watched> = emptyMap(),
        val hidden: Set<String> = emptySet(),
    ) {
        val libraryKeys: Set<String> get() = library.map { it.key }.toSet()
        fun inLibrary(key: String) = library.any { it.key == key }
    }

    /** Everything above, for the profile, re-read whenever any of it changes. */
    fun state(): Flow<State> = db.watchQueries.watchTick().asFlow().map { withContext(io) { read() } }

    suspend fun current(): State = withContext(io) { read() }

    private fun read(): State {
        val p = profile
        return State(
            library = db.watchQueries.libraryFor(p).executeAsList().map {
                LibraryEntry(it.item_key, runCatching { VodKind.valueOf(it.kind) }.getOrDefault(VodKind.MOVIE), it.vod_item_id, it.title, it.year?.toInt(), it.poster_url, it.added_at)
            },
            positions = db.watchQueries.resumeFor(p).executeAsList().associate {
                it.item_key to Position(it.item_key, it.kind, it.vod_item_id, it.episode_id, it.series_key, it.season?.toInt(), it.episode?.toInt(), it.title, it.position_ms, it.duration_ms, it.updated_at)
            },
            watched = db.watchQueries.watchedFor(p).executeAsList().associate {
                it.item_key to Watched(it.item_key, it.kind, it.vod_item_id, it.series_key, it.season?.toInt(), it.episode?.toInt(), it.watched_at)
            },
            hidden = db.watchQueries.hiddenFor(p).executeAsList().map { it.series_key }.toSet(),
        )
    }

    // ---- Library (PLAN §4.3.11) ----------------------------------------------------------------

    suspend fun addToLibrary(item: VodItem, key: String = ItemKeys.of(item)) = withContext(io) {
        db.watchQueries.putLibrary(
            com.signal.db.user.Library_item(profile, key, item.kind.name, item.id, VodRepository.displayName(item), item.year?.toLong(), item.posterUrl, nowMs()),
        )
    }

    suspend fun removeFromLibrary(key: String) = withContext(io) { db.watchQueries.removeLibrary(profile, key) }

    // ---- Resume and watched (PLAN §4.3.10) --------------------------------------------------------

    /**
     * Saves where playback is. At [WATCHED_AT] of the length it counts as watched instead, and leaves
     * Continue watching (PLAN §4.3.8 → End of playback). Under 10 seconds in, nothing is saved.
     */
    suspend fun savePosition(p: Position) = withContext(io) {
        if (p.durationMs > 0 && p.positionMs >= p.durationMs * WATCHED_AT) {
            markWatchedNow(p.key, p.kind, p.vodItemId, p.seriesKey, p.season, p.episode)
            return@withContext
        }
        if (p.positionMs < MIN_SAVED_MS) return@withContext
        db.watchQueries.putResume(
            com.signal.db.user.Resume_position(
                profile, p.key, p.kind, p.vodItemId, p.episodeId, p.seriesKey, p.season?.toLong(), p.episode?.toLong(), p.title, p.positionMs, p.durationMs, nowMs(),
            ),
        )
        // Watching an episode brings a hidden series back into Continue watching.
        p.seriesKey?.let { db.watchQueries.removeHidden(profile, it) }
    }

    suspend fun markWatched(key: String, kind: String, vodItemId: String, seriesKey: String? = null, season: Int? = null, episode: Int? = null) =
        withContext(io) { markWatchedNow(key, kind, vodItemId, seriesKey, season, episode) }

    private fun markWatchedNow(key: String, kind: String, vodItemId: String, seriesKey: String?, season: Int?, episode: Int?) {
        db.transaction {
            db.watchQueries.putWatched(com.signal.db.user.Watched(profile, key, kind, vodItemId, seriesKey, season?.toLong(), episode?.toLong(), nowMs()))
            db.watchQueries.removeResume(profile, key)
            seriesKey?.let { db.watchQueries.removeHidden(profile, it) }
        }
    }

    suspend fun markUnwatched(key: String) = withContext(io) {
        db.transaction {
            db.watchQueries.removeWatched(profile, key)
            db.watchQueries.removeResume(profile, key)
        }
    }

    /** "Play from start" and "Remove from Continue watching" on a movie or episode. */
    suspend fun clearPosition(key: String) = withContext(io) { db.watchQueries.removeResume(profile, key) }

    /** Remove from Continue watching on a series: no next-up until another episode is watched (PLAN §4.3.1). */
    suspend fun hideSeries(seriesKey: String) = withContext(io) {
        db.transaction {
            db.watchQueries.putHidden(profile, seriesKey, nowMs())
            db.watchQueries.resumeFor(profile).executeAsList().filter { it.series_key == seriesKey }.forEach { db.watchQueries.removeResume(profile, it.item_key) }
        }
    }

    companion object {
        const val WATCHED_AT = 0.9
        const val MIN_SAVED_MS = 10_000L
    }
}

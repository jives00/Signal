package com.signal.sports

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ESPN's games for Live Sports and the info bar (live-sports.md → Round 2): the companion's copy when
 * it answers, else the TV asks ESPN itself, for today and tomorrow only (a day of college basketball
 * is a megabyte). Loads in the background; a caller waits only [waitMs] and takes what's there, so a
 * slow ESPN never holds the page up. The next look picks up what arrived.
 */
class SportsScores(
    private val scope: CoroutineScope,
    /** The companion's games; null when it isn't paired, can't be reached or doesn't know the route. */
    private val fromCompanion: suspend () -> List<EspnGame>?,
    private val direct: EspnScoreboards,
    private val clock: () -> Long,
    private val log: ((String) -> Unit)? = null,
) {
    private val lock = Mutex()
    private var index = EspnMatch.Index(emptyList())
    private var loadedAt = 0L
    private var loading: Deferred<Unit>? = null

    internal suspend fun index(waitMs: Long = WAIT_MS): EspnMatch.Index {
        val job = lock.withLock {
            if (clock() - loadedAt < FRESH_MS) return index
            loading?.takeIf { it.isActive } ?: scope.async { load() }.also { loading = it }
        }
        withTimeoutOrNull(waitMs) { job.await() }
        return lock.withLock { index }
    }

    private suspend fun load() {
        val now = clock()
        val viaCompanion = runCatching { fromCompanion() }.getOrNull()
        val games = viaCompanion ?: runCatching { direct.games(EspnScoreboards.days(now, ahead = 1)) }.getOrDefault(emptyList())
        log?.invoke("Live Sports: ${games.size} ESPN games from ${if (viaCompanion != null) "the companion" else "ESPN"}")
        lock.withLock {
            index = EspnMatch.Index(games)
            // Nothing at all is likely a failure: try again sooner.
            loadedAt = if (games.isEmpty()) now - FRESH_MS + RETRY_MS else now
        }
    }

    private companion object {
        const val FRESH_MS = 30 * 60_000L
        const val RETRY_MS = 5 * 60_000L
        const val WAIT_MS = 8_000L
    }
}

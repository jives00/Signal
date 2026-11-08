package com.signal.sports

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ESPN's games for Live Sports and the info bar (live-sports.md → Round 2): the companion's copy when
 * it answers, else the TV asks ESPN itself, for today and tomorrow only (a day of college basketball
 * is a megabyte). Loads in the background; a caller waits only [waitMs] and takes what's there, so a
 * slow ESPN never holds the page up. The next look picks up what arrived.
 *
 * Games around now are looked at again every few minutes ([start]) for their status and score, and
 * every load goes to [EventChannels], which hides event channels whose game ESPN says is over.
 */
class SportsScores(
    private val scope: CoroutineScope,
    /** The companion's games; null when it isn't paired, can't be reached or doesn't know the route. */
    private val fromCompanion: suspend () -> List<EspnGame>?,
    private val direct: EspnScoreboards,
    private val clock: () -> Long,
    private val log: ((String) -> Unit)? = null,
    /** The companion's games around now, for their status; null as for [fromCompanion]. */
    private val nowFromCompanion: suspend () -> List<EspnGame>? = { null },
) {
    private val lock = Mutex()
    private var games: List<EspnGame> = emptyList()
    private var index = EspnMatch.Index(emptyList())
    private var loadedAt = 0L
    private var statusAt = 0L
    private var loading: Deferred<Unit>? = null

    internal suspend fun index(waitMs: Long = WAIT_MS): EspnMatch.Index {
        val job = lock.withLock {
            if (clock() - loadedAt < FRESH_MS) return index
            loading?.takeIf { it.isActive } ?: scope.async { load() }.also { loading = it }
        }
        withTimeoutOrNull(waitMs) { job.await() }
        return lock.withLock { index }
    }

    /** Keeps the games, and the status of those around now, current while [active]. */
    fun start(active: () -> Boolean): Job = scope.launch {
        while (true) {
            if (active()) runCatching { refresh() }.onFailure { log?.invoke("Live Sports: ESPN status failed: ${it.message}") }
            delay(STATUS_MS)
        }
    }

    private suspend fun refresh() {
        index(waitMs = 0)
        val now = clock()
        if (lock.withLock { now - statusAt < STATUS_MS || now - loadedAt < STATUS_MS }) return
        val fresh = runCatching { nowFromCompanion() }.getOrNull()
            ?: runCatching { direct.games(EspnScoreboards.days(now, ahead = 0)) }.getOrNull()
            ?: return
        lock.withLock {
            val byKey = fresh.associateBy { it.key }
            val kept = games.map { byKey[it.key] ?: it }
            val known = kept.mapTo(HashSet()) { it.key }
            val added = fresh.filter { it.key !in known }
            games = kept + added
            // Only scores and status changed: what Live Sports matched still holds.
            index = if (added.isEmpty()) EspnMatch.Index(games, index.memo) else EspnMatch.Index(games)
            statusAt = now
        }
        EventChannels.update(games)
    }

    private suspend fun load() {
        val now = clock()
        val viaCompanion = runCatching { fromCompanion() }.getOrNull()
        val found = viaCompanion ?: runCatching { direct.games(EspnScoreboards.days(now, ahead = 1)) }.getOrDefault(emptyList())
        log?.invoke("Live Sports: ${found.size} ESPN games from ${if (viaCompanion != null) "the companion" else "ESPN"}")
        lock.withLock {
            games = found
            index = EspnMatch.Index(found)
            // Nothing at all is likely a failure: try again sooner.
            loadedAt = if (found.isEmpty()) now - FRESH_MS + RETRY_MS else now
            statusAt = now
        }
        EventChannels.update(found)
    }

    private companion object {
        const val FRESH_MS = 30 * 60_000L
        const val RETRY_MS = 5 * 60_000L
        const val WAIT_MS = 8_000L
        /** How often games around now are looked at again for their status and score. */
        const val STATUS_MS = 2 * 60_000L
    }
}

package com.signal.server

import com.signal.sports.EspnGame
import com.signal.sports.EspnGames
import com.signal.sports.EspnPaths
import com.signal.sports.EspnScoreboards
import io.ktor.client.HttpClient
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * ESPN's games for the week, for the TVs' Live Sports (live-sports.md → Round 2): fetched here once and
 * kept warm, so a TV asks one request instead of dozens of megabyte scoreboards. ESPN's own answers
 * are kept for an hour (today) or six (later days), so the half-hourly look is mostly from memory.
 */
class SportsSchedule(http: HttpClient, private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger("Sports")
    private val boards = EspnScoreboards(http, clock = clock, parallel = 6)

    @Volatile private var games: List<EspnGame> = emptyList()

    /** Yesterday (while a late game can still be on) through a week ahead. */
    suspend fun refresh(): List<EspnGame> {
        val started = clock()
        return boards.games(EspnScoreboards.days(clock(), ahead = 7)).also {
            games = it
            log.info("Sports: ${it.size} ESPN games in ${clock() - started} ms")
        }
    }

    /** What's kept, or the first fetch if nothing is yet (at most [waitMs]). */
    suspend fun current(waitMs: Long = 15_000): List<EspnGame> = games.ifEmpty { withTimeoutOrNull(waitMs) { refresh() } ?: games }

    fun start() = scope.launch {
        while (true) {
            runCatching { refresh() }.onFailure { log.warn("Sports: ESPN failed: ${it.message}") }
            delay(REFRESH_MS)
        }
    }

    private companion object {
        const val REFRESH_MS = 30 * 60_000L
    }
}

/** For the TVs, with the device key: the week's games. */
fun Route.sportsDeviceRoutes(companion: Companion) {
    get(EspnPaths.GAMES) {
        call.device(companion) ?: return@get
        call.respond(EspnGames(companion.sportsSchedule.current()))
    }
}

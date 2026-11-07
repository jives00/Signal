package com.signal.server

import com.signal.sports.EspnGame
import com.signal.sports.EspnGames
import com.signal.sports.EspnPaths
import com.signal.sports.EspnScoreboards
import com.signal.sports.EventChannels
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
 * are kept for an hour (today) or six (later days), and two minutes for a scoreboard with a game on, so
 * the look every two minutes mostly reads memory and keeps live games' status and score current. Each
 * look also goes to [EventChannels], which hides event channels whose game is over.
 */
class SportsSchedule(http: HttpClient, private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger("Sports")
    private val boards = EspnScoreboards(http, clock = clock, parallel = 6)

    @Volatile private var games: List<EspnGame> = emptyList()

    /** Yesterday (while a late game can still be on) through a week ahead. */
    suspend fun refresh(): List<EspnGame> {
        val started = clock()
        return boards.games(EspnScoreboards.days(clock(), ahead = 7)).also {
            val first = games.isEmpty()
            games = it
            EventChannels.update(it)
            val took = clock() - started
            if (first || took > SLOW_MS) log.info("Sports: ${it.size} ESPN games in $took ms")
        }
    }

    /** Games around now (started in the last [BACK_MS] or starting within [AHEAD_MS]), for TVs to ask about every few minutes. */
    fun aroundNow(): List<EspnGame> {
        val now = clock()
        return games.filter { it.start in (now - BACK_MS)..(now + AHEAD_MS) }
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
        const val REFRESH_MS = 2 * 60_000L
        const val SLOW_MS = 10_000L
        const val BACK_MS = 12 * 60 * 60_000L
        const val AHEAD_MS = 2 * 60 * 60_000L
    }
}

/** For the TVs, with the device key: the week's games, and the games around now for their status. */
fun Route.sportsDeviceRoutes(companion: Companion) {
    get(EspnPaths.GAMES) {
        call.device(companion) ?: return@get
        call.respond(EspnGames(companion.sportsSchedule.current()))
    }
    get(EspnPaths.NOW) {
        call.device(companion) ?: return@get
        call.respond(EspnGames(companion.sportsSchedule.aroundNow()))
    }
}

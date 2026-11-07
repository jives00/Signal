package com.signal.sports

import com.signal.match.SearchText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.time.Instant

/** One side of a game in ESPN's scoreboard. */
@Serializable
data class EspnTeam(
    val name: String,
    val short: String? = null,
    val abbr: String? = null,
    val location: String? = null,
    val nickname: String? = null,
    val logo: String? = null,
    val record: String? = null,
    val rank: Int? = null,
    /** The score once the game has started, as ESPN writes it. */
    val score: String? = null,
)

/** Where a game is: not started, on, or over (ESPN's status.type.state). */
@Serializable
enum class GameState { PRE, IN, POST }

/** A game in ESPN's scoreboard (live-sports.md → Round 2). [league] is a [Sport] name. */
@Serializable
data class EspnGame(
    val league: String,
    val start: Long,
    val away: EspnTeam,
    val home: EspnTeam,
    val venue: String? = null,
    val networks: List<String> = emptyList(),
    /** For a feed with no filter of its own ([Sport.OTHER]), its sport: "Field Hockey". */
    val label: String? = null,
    /** Null from a companion that doesn't read it yet. */
    val state: GameState? = null,
    /** ESPN's short status: "Q3 5:12", "Halftime", "Final", "Final/OT". */
    val detail: String? = null,
) {
    /** One game across fetches: the league, the start and both teams. */
    val key: String get() = "$league|$start|${away.name}|${home.name}"

    val sport: Sport get() = Sport.entries.firstOrNull { it.name == league } ?: Sport.OTHER
}

/** What the companion answers at [EspnPaths.GAMES]. */
@Serializable
data class EspnGames(val games: List<EspnGame> = emptyList())

object EspnPaths {
    /** GET with the device's key → [EspnGames]: the week's games, from the companion's copy of ESPN's scoreboards. */
    const val GAMES = "/device/sports/games"
    /** GET with the device's key → [EspnGames]: only games around now, for their status and score every few minutes. */
    const val NOW = "/device/sports/games/now"
}

/**
 * ESPN's public scoreboards, one per league and day. Unofficial: anything that fails gives no games,
 * and Live Sports carries on with what the names say. Answers are kept for an hour (today) or six
 * (later days), so a page looking every minute doesn't ask again.
 */
class EspnScoreboards(
    private val http: HttpClient,
    private val base: String = BASE,
    private val clock: () -> Long,
    private val parallel: Int = 3,
) {
    /** One scoreboard: a sport/league path and its query (college divisions). */
    data class Feed(val sport: Sport, val path: String, val query: String = "", val label: String? = null)

    private class Kept(val until: Long, val games: List<EspnGame>)

    private val json = Json { ignoreUnknownKeys = true }
    private val kept = HashMap<Pair<Feed, String>, Kept>()
    private val lock = Mutex()

    /** Every feed's games on [days] (ESPN's days, US Eastern, as yyyyMMdd). */
    suspend fun games(days: List<String>): List<EspnGame> = coroutineScope {
        val gate = Semaphore(parallel)
        val today = day(clock(), 0)
        FEEDS.flatMap { feed -> days.map { feed to it } }.map { key ->
            async {
                lock.withLock { kept[key]?.takeIf { it.until > clock() }?.games }
                    ?: gate.withPermit {
                        val found = fetch(key.first, key.second)
                        val keepFor = when {
                            found == null -> FAILED_MS
                            // A game on, or about to start: its status and score change by the minute.
                            found.any { live(it, clock()) } -> LIVE_MS
                            key.second == today -> TODAY_MS
                            else -> LATER_MS
                        }
                        lock.withLock { kept[key] = Kept(clock() + keepFor, found.orEmpty()) }
                        found.orEmpty()
                    }
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetch(feed: Feed, date: String): List<EspnGame>? {
        val query = listOf(feed.query, "dates=$date", "limit=$LIMIT").filter { it.isNotEmpty() }.joinToString("&")
        return runCatching {
            withTimeoutOrNull(REQUEST_MS) {
                val r = http.get("$base/${feed.path}/scoreboard?$query")
                if (!r.status.isSuccess()) return@withTimeoutOrNull null
                val body = r.bodyAsText()
                withContext(Dispatchers.Default) { parse(feed.sport, body).map { if (feed.label != null) it.copy(label = feed.label) else it } }
            }
        }.getOrNull()
    }

    internal fun parse(sport: Sport, body: String): List<EspnGame> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        return root.arr("events").mapNotNull { ev ->
            val e = ev as? JsonObject ?: return@mapNotNull null
            val c = e.arr("competitions").firstOrNull() as? JsonObject ?: return@mapNotNull null
            val start = (c.str("date") ?: e.str("date"))?.let(::time)
                ?: return@mapNotNull null
            val sides = c.arr("competitors").mapNotNull { it as? JsonObject }
            val status = (c.obj("status") ?: e.obj("status"))?.obj("type")
            val state = when (status?.str("state")) { "pre" -> GameState.PRE; "in" -> GameState.IN; "post" -> GameState.POST; else -> null }
            fun side(which: String): EspnTeam? {
                val s = sides.firstOrNull { it.str("homeAway") == which } ?: return null
                val t = s.obj("team") ?: return null
                val name = t.str("displayName") ?: return null
                val record = s.arr("records").mapNotNull { it as? JsonObject }.firstOrNull { it.str("type") == "total" || it.str("name") == "overall" }?.str("summary")
                val rank = (s.obj("curatedRank")?.get("current") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..25 }
                return EspnTeam(
                    name = name, short = t.str("shortDisplayName"), abbr = t.str("abbreviation"), location = t.str("location"),
                    nickname = t.str("name"), logo = t.str("logo"), record = record, rank = rank,
                    score = s.str("score")?.takeIf { state != GameState.PRE },
                )
            }
            val networks = c.arr("broadcasts").flatMap { (it as? JsonObject)?.arr("names").orEmpty() }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            EspnGame(
                sport.name, start, side("away") ?: return@mapNotNull null, side("home") ?: return@mapNotNull null, c.obj("venue")?.str("fullName"), networks.distinct(),
                state = state, detail = status?.str("shortDetail"),
            )
        }
    }

    companion object {
        const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
        /** ESPN answers only 25 games when asked for more than it allows (1000 did that, 2026-11-07); 300 gets them all. */
        const val LIMIT = 300
        private const val REQUEST_MS = 20_000L
        private const val TODAY_MS = 60 * 60_000L
        private const val LATER_MS = 6 * 60 * 60_000L
        private const val FAILED_MS = 15 * 60_000L
        private const val LIVE_MS = 2 * 60_000L
        private const val SOON_MS = 15 * 60_000L

        /** On now, or due to start: worth asking about again in [LIVE_MS]. */
        fun live(g: EspnGame, now: Long): Boolean = g.state == GameState.IN || (g.state == GameState.PRE && g.start <= now + SOON_MS && g.start > now - 12 * 60 * 60_000L)
        private val eastern = TimeZone.of("America/New_York")

        val FEEDS: List<Feed> = listOf(
            Feed(Sport.NFL, "football/nfl"),
            Feed(Sport.COLLEGE_FOOTBALL, "football/college-football", "groups=80"),
            Feed(Sport.COLLEGE_FOOTBALL, "football/college-football", "groups=81"),
            Feed(Sport.CFL, "football/cfl"),
            Feed(Sport.NBA, "basketball/nba"),
            Feed(Sport.WNBA, "basketball/wnba"),
            Feed(Sport.G_LEAGUE, "basketball/nba-development"),
            Feed(Sport.MENS_COLLEGE_BASKETBALL, "basketball/mens-college-basketball", "groups=50"),
            Feed(Sport.WOMENS_COLLEGE_BASKETBALL, "basketball/womens-college-basketball", "groups=50"),
            Feed(Sport.NHL, "hockey/nhl"),
            Feed(Sport.COLLEGE_HOCKEY, "hockey/mens-college-hockey"),
            Feed(Sport.COLLEGE_HOCKEY, "hockey/womens-college-hockey"),
            Feed(Sport.MLB, "baseball/mlb"),
            Feed(Sport.COLLEGE_BASEBALL, "baseball/college-baseball"),
            Feed(Sport.VOLLEYBALL, "volleyball/womens-college-volleyball"),
            Feed(Sport.SOCCER, "soccer/usa.1"),
            Feed(Sport.SOCCER, "soccer/usa.nwsl"),
            Feed(Sport.SOCCER, "soccer/eng.1"),
            Feed(Sport.SOCCER, "soccer/uefa.champions"),
            Feed(Sport.SOCCER, "soccer/esp.1"),
            Feed(Sport.SOCCER, "soccer/mex.1"),
            Feed(Sport.SOCCER, "soccer/usa.ncaa.m.1"),
            Feed(Sport.SOCCER, "soccer/usa.ncaa.w.1"),
            // Field hockey has no filter of its own, but ESPN+ names its games with nothing else to go on.
            Feed(Sport.OTHER, "field-hockey/womens-college-field-hockey", label = "Field Hockey"),
        )

        /** ESPN's day [offset] days from [now]'s, as yyyyMMdd. Its days are US Eastern. */
        fun day(now: Long, offset: Int): String {
            val d = Instant.fromEpochMilliseconds(now).toLocalDateTime(eastern).date.plus(offset, DateTimeUnit.DAY)
            return "${d.year}${d.month.number.toString().padStart(2, '0')}${d.day.toString().padStart(2, '0')}"
        }

        /** Yesterday too while a late game from it can still be on. */
        fun days(now: Long, ahead: Int): List<String> {
            val hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(eastern).hour
            return ((if (hour < 4) -1 else 0)..ahead).map { day(now, it) }
        }

        /** ESPN writes "2026-11-07T17:00Z", without seconds. */
        private val noSeconds = Regex("""T(\d{2}):(\d{2})Z$""")

        internal fun time(text: String): Long? =
            runCatching { Instant.parse(text.replace(noSeconds) { "T${it.groupValues[1]}:${it.groupValues[2]}:00Z" }).toEpochMilliseconds() }.getOrNull()

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
        private fun JsonObject.arr(key: String) = (this[key] as? JsonArray).orEmpty()
    }
}

/**
 * Finds a feed's game in ESPN's scoreboard (live-sports.md → Round 2): both teams by name or
 * abbreviation, near the same start. A game with no teams ("College Football") is found by its venue
 * or both teams named in the description. A match gives the league, ESPN's team names (so feeds that
 * abbreviate merge with ones that don't), logos and records.
 */
internal object EspnMatch {
    private const val HOUR_MS = 3_600_000L
    private const val WINDOW_MS = 4 * HOUR_MS
    private const val GUESSED_DAY_MS = 4 * 24 * HOUR_MS
    private const val EARLY_MS = 75 * 60_000L
    private const val LATE_MS = 45 * 60_000L

    private val rank = Regex("""^\s*(?:#\d+|No\. ?\d+)\s+""", RegexOption.IGNORE_CASE)
    private val abbr = Regex("""^[A-Z&]{2,5}$""")
    private val filler = setOf("fc", "cf", "afc", "sc", "the", "of", "university", "univ", "club", "st", "state")

    class Index(val games: List<EspnGame>) {
        val byWord: Map<String, List<EspnGame>> = HashMap<String, MutableList<EspnGame>>().also { map ->
            for (g in games) for (t in listOf(g.away, g.home)) for (w in names(t).flatten().toSet() + listOfNotNull(t.abbr?.lowercase())) map.getOrPut(w) { ArrayList(2) } += g
        }
    }

    private fun words(text: String): Set<String> = SearchText.words(text.replace(rank, "").replace("'", "")).toSet()

    private fun names(t: EspnTeam): List<Set<String>> =
        listOfNotNull(t.name, t.short, t.location, t.location?.let { l -> t.nickname?.let { "$l $it" } }).map(::words).filter { it.isNotEmpty() }

    /** 2 for a side that is the team's name, 1 for one that only shares its words, 0 for neither. */
    private fun score(side: String, t: EspnTeam): Int {
        val text = side.replace(rank, "").trim()
        if (abbr.matches(text)) return if (t.abbr.equals(text, ignoreCase = true)) 2 else 0
        val w = words(text)
        if (w.isEmpty()) return 0
        val ns = names(t)
        if (ns.any { it == w }) return 2
        val core = w - filler
        if (core.isEmpty()) return 0
        return if (ns.any { n -> (n - filler).let { it.isNotEmpty() && (it.containsAll(core) || core.containsAll(it)) } }) 1 else 0
    }

    private fun side(t: EspnTeam) = Team(t.name, t.logo, t.record, t.rank, t.score)

    fun status(g: EspnGame): GameStatus? = g.state?.let { GameStatus(it, g.detail) }

    /** A game on runs at least this long past now, whatever the guess said. */
    private const val RUNNING_MS = 30 * 60_000L

    /** A stop that follows ESPN: on past the guess while it's in progress, and over once it's final. */
    fun stopFor(g: EspnGame, stop: Long, now: Long): Long = when (g.state) {
        GameState.IN -> maxOf(stop, now + RUNNING_MS)
        GameState.POST -> minOf(stop, now)
        else -> stop
    }

    private fun fits(sport: Sport, certainty: Int, g: EspnGame): Boolean =
        certainty == Classified.NONE || sport.family == SportFamily.OTHER || g.sport.family == sport.family

    /** [found] with each game ESPN has filled in, its status and score, and a stop that follows ESPN. */
    fun apply(found: List<Found>, index: Index, now: Long): List<Found> {
        if (index.games.isEmpty()) return found
        return found.map { f -> (if (f.teams != null) byTeams(f, f.teams, index, now) else byDescription(f, index, now)) ?: f }
    }

    /** The game ESPN has for [teams] near [start], and whether its home team is the one written first. */
    fun game(index: Index, teams: Teams, sport: Sport, certainty: Int, start: Long, dayGuessed: Boolean): Pair<EspnGame, Boolean>? {
        val first = words(teams.first) + listOfNotNull(teams.first.replace(rank, "").trim().takeIf { abbr.matches(it) }?.lowercase())
        val within = if (dayGuessed) GUESSED_DAY_MS else WINDOW_MS
        return first.flatMap { index.byWord[it].orEmpty() }.distinct()
            .filter { abs(it.start - start) <= within && fits(sport, certainty, it) }
            .mapNotNull { g ->
                val straight = score(teams.first, g.away).let { a -> if (a == 0) 0 else score(teams.second, g.home).let { b -> if (b == 0) 0 else a + b } }
                val swapped = score(teams.first, g.home).let { a -> if (a == 0) 0 else score(teams.second, g.away).let { b -> if (b == 0) 0 else a + b } }
                when {
                    straight == 0 && swapped == 0 -> null
                    straight >= swapped -> Triple(g, straight, false)
                    else -> Triple(g, swapped, true)
                }
            }
            .sortedWith(compareByDescending<Triple<EspnGame, Int, Boolean>> { it.second }.thenBy { abs(it.first.start - start) })
            .firstOrNull()?.let { it.first to it.third }
    }

    /** ESPN's teams for [g], in the order [teams] wrote them. */
    private fun teamsOf(g: EspnGame, swapped: Boolean): Teams =
        if (swapped) Teams.of(side(g.home), side(g.away), status(g)) else Teams.of(side(g.away), side(g.home), status(g))

    private fun byTeams(f: Found, teams: Teams, index: Index, now: Long): Found? {
        val (g, swapped) = game(index, teams, f.sport, f.certainty, f.start, f.dayGuessed) ?: return null
        val start = if (f.dayGuessed) g.start else f.start
        val stop = stopFor(g, if (f.dayGuessed) g.start + (f.stop - f.start) else f.stop, now)
        return f.copy(sport = g.sport, sportName = g.label ?: f.sportName, certainty = Classified.ESPN, teams = teamsOf(g, swapped), start = start, stop = stop, dayGuessed = false)
    }

    private fun byDescription(f: Found, index: Index, now: Long): Found? {
        if (!f.sport.teamSport) return null
        val text = f.channels.firstNotNullOfOrNull { it.program?.description } ?: f.detail ?: return null
        val d = " " + SearchText.fold(text) + " "
        fun mentions(phrase: String?) = phrase != null && phrase.length >= 4 && d.contains(" ${SearchText.fold(phrase)} ")
        val scored = index.games.asSequence()
            .filter { it.start in (f.start - LATE_MS)..(f.start + EARLY_MS) && it.sport.family == f.sport.family }
            .map { g ->
                var s = 0
                if (mentions(g.venue?.substringBefore('('))) s += 1
                if (listOf(g.away, g.home).all { t -> listOfNotNull(t.name, t.short, t.location).any(::mentions) }) s += 2
                g to s
            }
            .filter { it.second > 0 }.sortedByDescending { it.second }.toList()
        if (scored.isEmpty() || (scored.size > 1 && scored[0].second == scored[1].second)) return null
        val g = scored[0].first
        return f.copy(sport = g.sport, sportName = g.label ?: f.sportName, certainty = Classified.ESPN, teams = teamsOf(g, false), stop = stopFor(g, f.stop, now))
    }

    /** The game for a listing's teams, for the info bar: none unless both teams and the time agree. */
    fun find(index: Index, first: String, second: String, start: Long): Teams? {
        val teams = Teams(first, second)
        return game(index, teams, Sport.OTHER, Classified.NONE, start, false)?.let { (g, swapped) -> teamsOf(g, swapped) }
    }
}

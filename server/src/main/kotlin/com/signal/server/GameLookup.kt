package com.signal.server

import com.signal.db.guide.GuideDatabase
import com.signal.guide.GameTitles
import com.signal.guide.GuideBuilder
import com.signal.match.GuideNames
import com.signal.match.SearchText
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Team names for games the guide doesn't name them for (PLAN §6 → Game titles), run on the NAS's
 * guide build before it's published. Each game is looked up in ESPN's public scoreboard feed by
 * league and start time, and matched by its network, its venue or both teams in the description.
 * The feed is unofficial: anything that fails leaves the titles as the build wrote them.
 */
class GameLookup(private val http: HttpClient, private val base: String = "https://site.api.espn.com/apis/site/v2/sports") {
    private val log = LoggerFactory.getLogger("Guide")
    private val json = Json { ignoreUnknownKeys = true }

    data class Team(val displayName: String, val names: List<String>)
    data class Event(val start: Long, val away: Team, val home: Team, val networks: List<String>, val venue: String?)

    /** One scoreboard: a sport/league path and any extra query (college divisions). */
    data class Feed(val path: String, val query: String = "")

    /** Updates titles in [db]; [shifts] is each EPG source's time shift. Returns how many it named. */
    suspend fun apply(db: GuideDatabase, shifts: Map<String, Long>, now: Long): Int {
        val q = db.guideQueries
        val programs = withContext(Dispatchers.IO) {
            // Guide time, so the widest shift still covers the next week of real time.
            val slack = shifts.values.maxOfOrNull { kotlin.math.abs(it) } ?: 0L
            q.gameCandidates(now - slack, now + WEEK_MS + slack).executeAsList()
        }.filter {
            val categories = it.categories?.split(GuideBuilder.SEPARATOR).orEmpty()
            GameTitles.isGame(GameTitles.withoutMatchup(it.title), categories) &&
                (GameTitles.hasMatchup(it.title) || !GameTitles.namesTeams(it.title))
        }
        if (programs.isEmpty()) return 0

        val channelNames = HashMap<String, Map<String, List<String>>>()
        val events = HashMap<Pair<Feed, String>, List<Event>>()
        val failed = HashSet<Feed>()
        val updates = ArrayList<Triple<Long, String, String?>>()
        for (p in programs) {
            val base = GameTitles.withoutMatchup(p.title)
            val feeds = feedsFor(base).filter { it !in failed }
            if (feeds.isEmpty()) continue
            val start = p.start + (shifts[p.epg_source_id] ?: 0L)
            val candidates = feeds.flatMap { feed ->
                datesFor(start).flatMap { date ->
                    events.getOrPut(feed to date) {
                        runCatching { fetch(feed, date) }.getOrElse { e ->
                            log.warn("Game lookup: ${feed.path} $date failed: ${e.message}")
                            failed += feed
                            emptyList()
                        }
                    }
                }
            }.filter { it.start in (start - EARLY_MS)..(start + LATE_MS) }.distinct()
            if (candidates.isEmpty()) continue
            val names = channelNames.getOrPut(p.epg_source_id) {
                q.channelNamesForSource(p.epg_source_id).executeAsList().groupBy({ it.guide_channel_id }, { it.normalized_name })
            }[p.guide_channel_id].orEmpty()
            val event = pick(candidates, names, p.description) ?: continue
            val title = GameTitles.withMatchup(base, GameTitles.Matchup(event.away.displayName, event.home.displayName))
            if (title != p.title) updates += Triple(p.id, title, p.sub_title)
        }
        withContext(Dispatchers.IO) {
            db.transaction {
                for ((id, title, subTitle) in updates) {
                    q.setProgramTitle(title, id)
                    // FTS rows can't be updated through SQLDelight: replaced instead.
                    q.deleteSearchText(id)
                    q.insertSearchText(id, SearchText.fold(title), subTitle?.let(SearchText::fold).orEmpty())
                }
            }
        }
        log.info("Game lookup: named ${updates.size} of ${programs.size} games (${events.size} scoreboards)")
        return updates.size
    }

    /** The one event the program is best evidenced as, or null when none is or two tie. */
    internal fun pick(candidates: List<Event>, channelNames: List<String>, description: String?): Event? {
        val d = " " + SearchText.fold(description.orEmpty()) + " "
        fun mentions(phrase: String) = phrase.length >= 3 && d.contains(" $phrase ")
        val scored = candidates.map { e ->
            var score = 0
            if (e.networks.any { onChannel(it, channelNames) }) score += 2
            // "Tiger Stadium (LA)" is "From Tiger Stadium in Baton Rouge" in the guide.
            if (e.venue?.let { mentions(SearchText.fold(it.substringBefore('('))) } == true) score += 1
            if (listOf(e.away, e.home).all { t -> t.names.any { mentions(SearchText.fold(it)) } }) score += 2
            e to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (scored.isEmpty() || (scored.size > 1 && scored[0].second == scored[1].second)) return null
        return scored[0].first
    }

    /** ESPN's network name ("CBSSN", "ACC Network", "USA Net") on a guide channel's names. */
    internal fun onChannel(network: String, channelNames: List<String>): Boolean {
        // Streaming services aren't channels, and "ESPN+" would read as ESPN.
        if (network.contains('+') || streaming.any { network.startsWith(it, ignoreCase = true) }) return false
        var n = GuideNames.normalize(network)
        n = aliases[n] ?: n.replace(Regex("""\bnet$"""), "network")
        if (n.isEmpty()) return false
        // "fs1" is on "fs1 fox sports 1", but "cbs" isn't on "cbs sports network".
        return channelNames.any { it == n || (n !in broadcasters && it.startsWith("$n ")) }
    }

    private suspend fun fetch(feed: Feed, date: String): List<Event> {
        val url = "$base/${feed.path}/scoreboard?${feed.query}${if (feed.query.isEmpty()) "" else "&"}dates=$date&limit=1000"
        val r = http.get(url) { timeout { requestTimeoutMillis = 30_000 } }
        if (r.status.value >= 400) error("HTTP ${r.status.value}")
        return parse(r.bodyAsText())
    }

    internal fun parse(body: String): List<Event> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return root.arr("events").mapNotNull { ev ->
            val e = ev as? JsonObject ?: return@mapNotNull null
            val c = e.arr("competitions").firstOrNull() as? JsonObject ?: return@mapNotNull null
            val start = (c.str("date") ?: e.str("date"))?.let(::parseTime) ?: return@mapNotNull null
            val teams = c.arr("competitors").mapNotNull { it as? JsonObject }
            fun side(which: String): Team? {
                val t = teams.firstOrNull { it.str("homeAway") == which }?.obj("team") ?: return null
                val display = t.str("displayName") ?: return null
                return Team(display, listOfNotNull(display, t.str("shortDisplayName"), t.str("location"), t.str("name")).distinct())
            }
            val networks = c.arr("broadcasts").flatMap { (it as? JsonObject)?.arr("names").orEmpty() }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } +
                c.arr("geoBroadcasts").mapNotNull { (it as? JsonObject)?.obj("media")?.str("shortName") }
            Event(start, side("away") ?: return@mapNotNull null, side("home") ?: return@mapNotNull null, networks.distinct(), c.obj("venue")?.str("fullName"))
        }
    }

    companion object Leagues {
        private const val WEEK_MS = 7 * 24 * 60 * 60_000L
        // How far a game's listed start can be from ESPN's: the slot can open before kickoff ("7:00" for
        // 7:08), and guides and ESPN disagree by up to an hour (the NLCS on FS1, 2026-10-15).
        private const val EARLY_MS = 75 * 60_000L
        private const val LATE_MS = 45 * 60_000L
        private val eastern = ZoneId.of("America/New_York")
        private val day = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** Which scoreboards a title could be on, most specific first. */
        internal fun feedsFor(title: String): List<Feed> {
            val t = title.lowercase().replace("’", "'")
            val women = t.contains("women")
            return when {
                t.contains("college basketball") || t.contains("ncaa basketball") ->
                    listOf(Feed(if (women) "basketball/womens-college-basketball" else "basketball/mens-college-basketball", "groups=50"))
                t.contains("wnba") -> listOf(Feed("basketball/wnba"))
                t.contains("nba") -> listOf(Feed("basketball/nba"))
                t.contains("college football") || t.contains("ncaa football") ->
                    listOf(Feed("football/college-football", "groups=80"), Feed("football/college-football", "groups=81"))
                t.contains("nfl") -> listOf(Feed("football/nfl"))
                t.contains("college hockey") -> listOf(Feed(if (women) "hockey/womens-college-hockey" else "hockey/mens-college-hockey"))
                t.contains("nhl") || t.contains("hockey") -> listOf(Feed("hockey/nhl"))
                t.contains("college baseball") || t.contains("softball") -> emptyList()
                t.contains("mlb") || t.contains("baseball") -> listOf(Feed("baseball/mlb"))
                t.contains("volleyball") && t.contains("college") && women -> listOf(Feed("volleyball/womens-college-volleyball"))
                women -> listOf(Feed("soccer/usa.nwsl")).filter { t.contains("nwsl") }
                t.contains("premier league") -> listOf(Feed("soccer/eng.1"))
                t.contains("efl championship") -> listOf(Feed("soccer/eng.2"))
                t.contains("nwsl") -> listOf(Feed("soccer/usa.nwsl"))
                t.contains("mls") -> listOf(Feed("soccer/usa.1"))
                t.contains("champions league") -> listOf(Feed("soccer/uefa.champions"))
                t.contains("europa league") -> listOf(Feed("soccer/uefa.europa"))
                t.contains("laliga") || t.contains("la liga") -> listOf(Feed("soccer/esp.1"))
                t.contains("bundesliga") -> listOf(Feed("soccer/ger.1"))
                t.contains("serie a") -> listOf(Feed("soccer/ita.1"))
                t.contains("ligue 1") -> listOf(Feed("soccer/fra.1"))
                t.contains("liga mx") -> listOf(Feed("soccer/mex.1"))
                else -> emptyList()
            }
        }

        /** ESPN's scoreboard days are US Eastern; a game just after midnight can sit on the day before. */
        internal fun datesFor(start: Long): List<String> {
            val at = Instant.ofEpochMilli(start).atZone(eastern)
            return if (at.hour < 4) listOf(at.minusDays(1).format(day), at.format(day)) else listOf(at.format(day))
        }

        private fun parseTime(s: String): Long? = runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

        // ESPN's short network names, as guide channels spell them out.
        private val aliases = mapOf(
            "cbssn" to "cbs sports network", "secn" to "sec network", "accn" to "acc network", "btn" to "big ten network",
            "pac 12 net" to "pac 12 network", "espnews" to "espnews", "fs1" to "fs1", "fs2" to "fs2",
        )
        // Networks whose name starts other channels' names ("cbs sports network", "fox sports 1").
        private val broadcasters = setOf("abc", "cbs", "nbc", "fox", "cw", "ion", "espn", "tnt", "usa", "golf")
        private val streaming = listOf("Peacock", "Prime Video", "Netflix", "Apple TV", "YouTube", "Max", "Fubo", "FloSports", "DAZN", "NBA League Pass")

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
        private fun JsonObject.arr(key: String) = (this[key] as? JsonArray).orEmpty()
    }
}

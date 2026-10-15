package com.signal.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.guide.GuideDatabase
import com.signal.match.SearchText
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Shaped like ESPN's scoreboard on 2026-10-15, trimmed to what GameLookup reads.
class GameLookupTest {
    private fun event(at: String, away: Pair<String, String>, home: Pair<String, String>, network: String, venue: String) = """
        {"date":"$at","competitions":[{"date":"$at","venue":{"fullName":"$venue"},
          "competitors":[
            {"homeAway":"home","team":{"displayName":"${home.first} ${home.second}","location":"${home.first}","name":"${home.second}"}},
            {"homeAway":"away","team":{"displayName":"${away.first} ${away.second}","location":"${away.first}","name":"${away.second}"}}],
          "broadcasts":[{"market":"national","names":["$network"]}]}]}
    """

    private val cfb = """{"events":[
        ${event("2026-10-15T23:30Z", "East Carolina" to "Pirates", "UAB" to "Blazers", "ESPN", "Protective Stadium")},
        ${event("2026-10-15T23:30Z", "Georgia Southern" to "Eagles", "Old Dominion" to "Monarchs", "ESPN2", "S.B. Ballard Stadium")},
        ${event("2026-10-16T00:00Z", "Colorado State" to "Rams", "Texas State" to "Bobcats", "CBSSN", "UFCU Stadium")}
    ]}"""

    private val requested = ArrayList<String>()
    private val http = HttpClient(MockEngine { req ->
        requested += req.url.toString()
        val body = if (req.url.encodedPath.contains("college-football") && req.url.parameters["groups"] == "80") cfb else """{"events":[]}"""
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun namesGamesByNetworkAndTeams() = runBlocking {
        val db = GuideDatabase(JdbcSqliteDriver("jdbc:sqlite:", Properties(), GuideDatabase.Schema))
        val q = db.guideQueries
        listOf("cbssn" to "cbs sports network", "espn" to "espn", "espn2" to "espn2", "deportes" to "espn deportes", "local" to "wxyz abc 7")
            .forEach { (id, name) -> q.insertChannelName("e", id, name) }
        var nextId = 1L
        fun program(channel: String, at: String, title: String, description: String, categories: String? = "Football"): Long {
            val id = nextId++
            q.insertProgram(id, "e", channel, t(at), t(at) + 3 * 3_600_000, title, null, description, categories, null, null, null, null, null, null, 0, null)
            q.insertSearchText(id, SearchText.fold(title), "")
            return id
        }
        val venueOnly = program("cbssn", "2026-10-16T00:00:00Z", "Live: College Football", "From Bobcat Stadium in San Marcos, Texas.")
        val nicknames = program("espn", "2026-10-15T23:30:00Z", "Live: College Football - Pirates vs. Blazers", "The Pirates visit the Blazers in an American Conference game.")
        val byTeams = program("deportes", "2026-10-15T23:30:00Z", "Fútbol Americano Universitario", "Georgia Southern Eagles y Old Dominion Monarchs.", "Football")
        val noEvidence = program("local", "2026-10-15T23:30:00Z", "College Football", "From a stadium.")
        val show = program("espn2", "2026-10-15T23:00:00Z", "Live: College Football Countdown", "Previews.")
        val replay = program("espn2", "2026-10-16T08:00:00Z", "College Football", "From S.B. Ballard Stadium.")
        val nfl = program("espn", "2026-10-16T00:15:00Z", "NFL Football", "From Empower Field.")

        val named = GameLookup(http, "https://espn.test").apply(db, mapOf("e" to 0L), t("2026-10-15T20:00:00Z"))
        fun title(id: Long) = q.programById(id).executeAsOne().title
        assertEquals("Live: College Football - Colorado State Rams vs. Texas State Bobcats", title(venueOnly))
        assertEquals("Live: College Football - East Carolina Pirates vs. UAB Blazers", title(nicknames))
        assertEquals("College Football", title(noEvidence))
        assertEquals("Live: College Football Countdown", title(show))
        assertEquals("College Football", title(replay))
        assertEquals("NFL Football", title(nfl))
        // "Fútbol Americano Universitario" names no league GameLookup knows.
        assertEquals("Fútbol Americano Universitario", title(byTeams))
        assertEquals(2, named)
        // Search finds a game by a team it didn't name before.
        assertEquals(listOf(venueOnly), q.searchPrograms("bobcats", 0, 10).executeAsList().map { it.id })
        // One scoreboard per feed and day; FBS and FCS for college football.
        assertTrue(requested.any { "groups=81" in it && "dates=20261015" in it })
        assertTrue(requested.any { "football/nfl" in it && "dates=20261015" in it })
    }

    @Test fun picksByTeamsWhenTheNetworkIsUnknown() {
        val lookup = GameLookup(http)
        val events = lookup.parse(cfb)
        assertEquals(3, events.size)
        val picked = lookup.pick(events, listOf("some regional network"), "The Georgia Southern Eagles visit the Old Dominion Monarchs.")
        assertEquals("Old Dominion Monarchs", picked?.home?.displayName)
        // Two games on the same evidence: neither.
        assertEquals(null, lookup.pick(events.take(2), listOf("espn", "espn2"), null))
    }

    @Test fun networksOnChannels() {
        val lookup = GameLookup(http)
        assertTrue(lookup.onChannel("CBSSN", listOf("cbs sports network")))
        assertTrue(lookup.onChannel("CHSN", listOf("chsn chicago sports network")))
        assertTrue(lookup.onChannel("FS1", listOf("fs1 fox sports 1")))
        assertTrue(lookup.onChannel("ACC Network", listOf("acc network")))
        assertTrue(lookup.onChannel("NFL Net", listOf("nfl network")))
        assertFalse(lookup.onChannel("CBS", listOf("cbs sports network")))
        assertFalse(lookup.onChannel("ESPN", listOf("espn2")))
        assertFalse(lookup.onChannel("ESPN", listOf("espn deportes")))
        assertFalse(lookup.onChannel("ESPN+", listOf("espn")))
        assertFalse(lookup.onChannel("Peacock", listOf("peacock")))
    }

    @Test fun feedsForTitles() {
        assertEquals("basketball/womens-college-basketball", GameLookup.feedsFor("Live: Women's College Basketball").single().path)
        assertEquals("basketball/mens-college-basketball", GameLookup.feedsFor("College Basketball").single().path)
        assertEquals("basketball/wnba", GameLookup.feedsFor("Live: 2026 WNBA Finals").single().path)
        assertEquals("hockey/nhl", GameLookup.feedsFor("Live: NHL Hockey").single().path)
        assertEquals("baseball/mlb", GameLookup.feedsFor("Live: MLB Baseball").single().path)
        assertEquals("soccer/eng.1", GameLookup.feedsFor("Live: Premier League Soccer").single().path)
        assertEquals(2, GameLookup.feedsFor("Live: College Football").size)
        assertTrue(GameLookup.feedsFor("Live: Women's Italian Serie A Soccer").isEmpty())
        // 1am Eastern is still the previous day's scoreboard too.
        assertEquals(listOf("20261015", "20261016"), GameLookup.datesFor(Instant.parse("2026-10-16T05:00:00Z").toEpochMilli()))
    }
}

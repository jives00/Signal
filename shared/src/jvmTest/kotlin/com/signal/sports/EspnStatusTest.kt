package com.signal.sports

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** backlog → PPV channels → ESPN: a game's status and score from ESPN, and event channels following it. */
class EspnStatusTest {
    private val et = TimeZone.of("America/New_York")
    private fun et(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime(y, mo, d, h, mi).toInstant(et).toEpochMilliseconds()
    private val now = et(2026, 11, 7, 11, 0)
    private val nfl = "US| NFL PPV"
    private val colts = "NFL  | 03 - 1pm Cowboys at Colts"

    @AfterTest fun forget() = EventChannels.update(emptyList())

    private fun board(state: String, detail: String, away: String?, home: String?): String {
        fun score(s: String?) = if (s == null) "null" else "\"$s\""
        return """
            {"events":[{"date":"2026-11-08T18:00Z","competitions":[{"date":"2026-11-08T18:00Z",
              "status":{"type":{"state":"$state","shortDetail":"$detail"}},
              "competitors":[
                {"homeAway":"home","score":${score(home)},"team":{"displayName":"Indianapolis Colts","shortDisplayName":"Colts","abbreviation":"IND","location":"Indianapolis","name":"Colts","logo":"https://e.test/ind.png"}},
                {"homeAway":"away","score":${score(away)},"team":{"displayName":"Dallas Cowboys","shortDisplayName":"Cowboys","abbreviation":"DAL","location":"Dallas","name":"Cowboys","logo":"https://e.test/dal.png"}}
              ]}]}]}
        """.trimIndent()
    }

    private fun games(state: String, detail: String, away: String? = null, home: String? = null): List<EspnGame> =
        EspnScoreboards(io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no network") }), clock = { now })
            .parse(Sport.NFL, board(state, detail, away, home))

    @Test fun readsStatusAndScore() {
        val g = games("in", "Q3 5:12", away = "21", home = "17").single()
        assertEquals(GameState.IN, g.state)
        assertEquals("Q3 5:12", g.detail)
        assertEquals("21", g.away.score)
        assertNull(games("pre", "Sun, November 8th at 1:00 PM EST", away = "0", home = "0").single().away.score, "no score before it starts")
    }

    @Test fun aGameOnStaysPastItsGuessedEnd() {
        val late = et(2026, 11, 8, 17, 30) // the guess said 5pm
        assertTrue(EventChannels.hides(nfl, colts, late), "without ESPN, the guess")
        EventChannels.update(games("in", "4th 2:00", away = "24", home = "24"))
        assertFalse(EventChannels.hides(nfl, colts, late), "ESPN says it's still on")
        val p = assertNotNull(EventChannels.program("s", nfl, colts, late))
        assertTrue(p.isOn(late) && p.isLive)
        assertEquals("NFL · 4th 2:00", p.subTitle)
    }

    @Test fun aFinalGameGoesBeforeItsGuessedEnd() {
        val early = et(2026, 11, 8, 16) // the guess said 5pm
        assertFalse(EventChannels.hides(nfl, colts, early))
        EventChannels.update(games("post", "Final", away = "31", home = "10"))
        assertTrue(EventChannels.hides(nfl, colts, early))
        assertNull(EventChannels.program("s", nfl, colts, early))
    }

    @Test fun listsLookAgainWhenAGameStartsOrEnds() {
        val before = EventChannels.changes.value
        EventChannels.update(games("pre", "1:00 PM"))
        EventChannels.update(games("pre", "1:00 PM"))
        assertEquals(before + 1, EventChannels.changes.value, "nothing moved the second time")
        EventChannels.update(games("in", "Q1 12:00", "0", "0"))
        assertEquals(before + 2, EventChannels.changes.value)
    }

    @Test fun liveSportsGetsTheScoreAndFollowsEspn() {
        val at = et(2026, 11, 8, 17, 30)
        val f = Found(Sport.NFL, "Cowboys vs. Colts", null, Teams("Cowboys", "Colts"), et(2026, 11, 8, 13), et(2026, 11, 8, 17), true, true, emptyList())
        val on = EspnMatch.apply(listOf(f), EspnMatch.Index(games("in", "Halftime", away = "14", home = "7")), at).single()
        assertTrue(on.stop > at, "kept while on")
        assertEquals("14–7", on.teams!!.score)
        assertEquals("Halftime", on.teams!!.status!!.detail)
        val over = EspnMatch.apply(listOf(f), EspnMatch.Index(games("post", "Final", away = "31", home = "10")), et(2026, 11, 8, 16)).single()
        assertTrue(over.stop <= et(2026, 11, 8, 16), "over once it's final")
    }

    // The match is kept across a status refresh (2026-11-07), but the score always comes from the newest index.
    @Test fun aKeptMatchReadsTheNewestScore() {
        val at = et(2026, 11, 8, 17, 30)
        val f = Found(Sport.NFL, "Cowboys vs. Colts", null, Teams("Cowboys", "Colts"), et(2026, 11, 8, 13), et(2026, 11, 8, 17), true, true, emptyList())
        val first = EspnMatch.Index(games("in", "Halftime", away = "14", home = "7"))
        assertEquals("14–7", EspnMatch.apply(listOf(f), first, at).single().teams!!.score)
        assertEquals(1, first.memo.byTeams.size)
        val refreshed = EspnMatch.Index(games("in", "Q3 5:12", away = "21", home = "7"), first.memo)
        val again = EspnMatch.apply(listOf(f), refreshed, at).single()
        assertEquals("21–7", again.teams!!.score)
        assertEquals("Q3 5:12", again.teams!!.status!!.detail)
        assertEquals(1, refreshed.memo.byTeams.size, "matched from the kept memo")
    }
}

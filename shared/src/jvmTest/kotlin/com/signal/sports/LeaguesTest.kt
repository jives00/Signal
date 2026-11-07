package com.signal.sports

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** live-sports.md → Round 2: leagues, multi-game channels, ESPN, images. Names are the real ones, 2026-11-07. */
class LeaguesTest {
    private val et = TimeZone.of("America/New_York")
    private fun et(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime(y, mo, d, h, mi).toInstant(et).toEpochMilliseconds()
    private val now = et(2026, 11, 7, 11, 0)

    private fun sport(group: String, name: String) = assertNotNull(PpvNames.parse(group, name, now), name).sport

    @Test fun aChannelWithSeveralGamesIsEach() {
        val all = PpvNames.parseAll("US| ESPN+ PPV", "US (ESPN+ 392) | NCAA Men`s Basketball: MORE vs. #11 LOU • UND vs. WIN (MORE vs. #11 LOU ) (2026-11-07 12:01:55)", now)
        assertEquals(listOf(Teams("MORE", "#11 LOU"), Teams("UND", "WIN")), all.map { it.teams })
        assertEquals(listOf(false, true), all.map { it.sharedWithOthers }, "the brackets name the one on now")
        assertTrue(all.all { it.sport == Sport.MENS_COLLEGE_BASKETBALL })
        assertTrue(PpvNames.parseAll("US| ESPN+ PPV", "US (ESPN+ 350) | NCAA Football: College GameDay • SEC Nation (College GameDay ) (2026-11-07 10:00:15)", now).isEmpty(), "shows")
        val stan = assertNotNull(PpvNames.parse("US| STAN PPV", "AU (STAN 15) |  • Leeds United v Tottenham Hotspur  Premier League Matchweek 10 2026/2027 (2026-11-07 23:20:50)", now))
        assertFalse(stan.sharedWithOthers, "a leading bullet is one game")
    }

    @Test fun leaguesFromTheName() {
        assertEquals(Sport.WOMENS_COLLEGE_BASKETBALL, sport("US| ESPN+ PPV", "US (ESPN+ 449) | NCAA Women`s Basketball: UMass vs. Providence (UMass vs. Providence) (2026-11-07 13:01:30)"))
        assertEquals(Sport.COLLEGE_FOOTBALL, sport("US| ESPN+ PPV", "US (ESPN+ 300) | NCAA Football: Yale vs. Brown (Yale vs. Brown) (2026-11-07 13:00:00)"))
        assertEquals(Sport.SOCCER, sport("US| ESPN+ PPV", "US (ESPN+ 338) | Soccer: Alemannia vs. Essen (Alemannia vs. Essen) (2026-11-07 08:00:00)"))
        assertEquals(Sport.VOLLEYBALL, sport("US| BTN+ PPV", "(US) (BTN+ 026) | Volleyball (W): Illinois at Washington (2026-11-07 21:50:10)"))
        assertEquals(Sport.RUGBY, sport("US| RUGBY PPV", "Rugby 10: Southland vs Counties Manukau 5:35am Sun"))
        assertEquals(Sport.RUGBY, sport("US| PARAMOUNT+ PPV", "World Rugby: Georgia vs Canada @ Nov 7 8:50 AM :Paramount+  06"))
        assertEquals(Sport.SOCCER, sport("US| PARAMOUNT+ PPV", "English Football League: Charlton Athletic vs Sheffield United @ Nov 7 9:45 AM :Paramount+  10"))
        assertEquals(Sport.OTHER, sport("US| BTN+ PPV", "(US) (BTN+ 066) | Field Hockey: Penn State at Michigan State (2026-11-07 14:50:00)"))
    }

    @Test fun proTeamsAndCollegeByDefault() {
        assertEquals(Sport.NFL, sport("US| PEACOCK PPV VIP", "US (Peacock 087) | Vikings vs. 49ers (2026-11-08 19:00:00)"))
        assertEquals(Sport.NBA, sport("US| PEACOCK PPV VIP", "US (Peacock 044) | Performance View: OKC vs. POR (2026-11-07 23:00:00)"))
        assertEquals(Sport.NBA, sport("US| PEACOCK PPV VIP", "US (Peacock 093) | Celtics vs. Wizards (2026-11-07 19:00:00)"))
        assertEquals(Sport.NHL, sport("US| ESPN+ PPV", "US (ESPN+ 120) | Columbus Blue Jackets vs. Buffalo Sabres (2026-11-07 19:00:00)"))
        assertEquals(Sport.MENS_COLLEGE_BASKETBALL, sport("US| ESPN+ PPV", "US (ESPN+ 215) | Basketball: James Madison vs. #23 Kentucky (James Madison vs. #23 Kentucky) (2026-11-07 19:06:15)"))
        assertEquals(Sport.COLLEGE_FOOTBALL, sport("US| MAX PPV", "Football: Oklahoma St at Kansas St @ Nov 7 12:30 PM :MAX US 03"))
        assertEquals(Sport.SOCCER, sport("US| PEACOCK PPV VIP", "US (Peacock 015) | Arsenal v. Hull City (2026-11-07 09:50:00)"), "v. is soccer's")
        // A city alone isn't a pro team.
        assertEquals(Sport.OTHER, sport("US| PEACOCK PPV VIP", "US (Peacock 019) | Miami vs. Notre Dame (2026-11-07 18:30:00)"))
        assertNull(ProTeams.league("Panthers", "Lions", SportFamily.HOCKEY))
        assertEquals(Sport.NHL, ProTeams.league("Panthers", "Capitals"))
    }

    @Test fun guideListings() {
        assertFalse(GuideGames.isLiveGame("Live: Marty & McGee", listOf("Sports", "Football")))
        assertFalse(GuideGames.isLiveGame("Live: SEC Nation", listOf("Sports", "Football")))
        assertFalse(GuideGames.isLiveGame("Live: Fanta in 60", listOf("Sports", "Miscellaneous")))
        assertFalse(GuideGames.isLiveGame("Live: Bruins Face-Off Live", listOf("Sports", "Hockey")))
        assertTrue(GuideGames.isLiveGame("Live: College Football", listOf("Sports", "Football")))
        assertTrue(GuideGames.isLiveGame("Live: 2026 ACC Mens U19 Asia Cup", listOf("Sports", "Cricket")), "the category names it")
        assertTrue(GuideGames.isLiveGame("Live: PBR Team Series", listOf("Sports", "Bull riding")))
        assertEquals(Sport.VOLLEYBALL, GuideGames.sport("Live: Women's College Volleyball", listOf("Sports", "Volleyball")))
        assertEquals(Sport.CFL, GuideGames.sport("Live: CFL Football - Blue Bombers vs. Alouettes", listOf("Sports", "Playoff sports", "Football")))
        assertEquals(Sport.G_LEAGUE, GuideGames.sport("Live: NBA G League Basketball", listOf("Sports", "Basketball")))
        assertEquals(Sport.HIGH_SCHOOL_FOOTBALL, GuideGames.sport("Live: High School Football", listOf("Sports", "Football")))
        assertEquals(Sport.AUSSIE_RULES, GuideGames.sport("Live: AFL Women's Premiership Football", listOf("Sports", "Australian rules football")))
        assertEquals(Sport.OTHER, GuideGames.sport("Live: College Field Hockey", listOf("Sports", "Field hockey")))
        assertEquals(Sport.NFL, GuideGames.sport("Live: Fútbol Americano NFL - Green Bay Packers vs. New England Patriots", listOf("Sports", "Football")))
    }

    @Test fun oldSettingsNames() {
        assertEquals(setOf("MENS_COLLEGE_BASKETBALL", "WOMENS_COLLEGE_BASKETBALL", "NFL"), SportsSettings.parse("""{"hiddenSports":["COLLEGE_BASKETBALL","NFL"]}""").hiddenSports)
        assertEquals(setOf(Sport.G_LEAGUE, Sport.INTL_BASKETBALL), SportsFilters.parse("""{"sports":["BASKETBALL"]}""").chosen)
    }

    @Test fun images() {
        assertEquals("https://a.espncdn.com/i/teamlogos/leagues/500/nba.png", SportImages.url(Sport.NBA, emptyMap()))
        val chosen = SportImages.parse(SportImages.encode(mapOf("NBA" to "https://x.test/nba.png", "NFL" to "")))
        assertEquals("https://x.test/nba.png", SportImages.url(Sport.NBA, chosen))
        assertNull(SportImages.url(Sport.NFL, chosen), "chosen none")
        assertNull(SportImages.url(Sport.OTHER, emptyMap()), "a badge")
        assertNull(SportImages.encode(emptyMap()))
        assertTrue(SportImages.parse("not json").isEmpty())
    }

    private val scoreboard = """
        {"events":[
          {"date":"2026-11-07T17:00Z","competitions":[{"date":"2026-11-07T17:00Z","venue":{"fullName":"Vaught-Hemingway Stadium"},
            "broadcasts":[{"names":["ABC"]}],
            "competitors":[
              {"homeAway":"home","team":{"displayName":"Ole Miss Rebels","shortDisplayName":"Ole Miss","abbreviation":"MISS","location":"Ole Miss","name":"Rebels","logo":"https://e.test/miss.png"},"records":[{"type":"total","summary":"6-2"}],"curatedRank":{"current":10}},
              {"homeAway":"away","team":{"displayName":"Georgia Bulldogs","shortDisplayName":"Georgia","abbreviation":"UGA","location":"Georgia","name":"Bulldogs","logo":"https://e.test/uga.png"},"records":[{"type":"total","summary":"7-1"}],"curatedRank":{"current":99}}
            ]}]}
        ]}
    """.trimIndent()

    private fun espnGames(): List<EspnGame> {
        val boards = EspnScoreboards(io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no network") }), clock = { now })
        return boards.parse(Sport.COLLEGE_FOOTBALL, scoreboard)
    }

    @Test fun readsEspn() {
        val g = espnGames().single()
        assertEquals(et(2026, 11, 7, 12), g.start)
        assertEquals("Georgia Bulldogs", g.away.name)
        assertEquals("7-1", g.away.record)
        assertNull(g.away.rank, "99 is unranked")
        assertEquals(10, g.home.rank)
        assertEquals(Sport.COLLEGE_FOOTBALL, g.sport)
        assertEquals("20261107", EspnScoreboards.day(now, 0))
        assertEquals(listOf("20261107", "20261108"), EspnScoreboards.days(now, 1))
    }

    private fun ch(id: String, favorite: Boolean = false, hidden: Boolean = false, shared: Boolean = false, description: String? = null) =
        EventChannel(id, "s", id, "US| X", hidden, favorite, program = description?.let { com.signal.model.Program(0, "", "", 0, 0, "t", description = it) }, sharedWithOthers = shared)

    @Test fun findsGamesInEspn() {
        val index = EspnMatch.Index(espnGames())
        val start = et(2026, 11, 7, 12)
        val abbreviated = Found(Sport.OTHER, "UGA vs. MISS", null, Teams("UGA", "#10 MISS"), start, start + 3 * 3_600_000L, true, false, listOf(ch("a")), Classified.NONE)
        val byTeams = EspnMatch.apply(listOf(abbreviated), index).single()
        assertEquals(Sport.COLLEGE_FOOTBALL, byTeams.sport)
        assertEquals(Classified.ESPN, byTeams.certainty)
        assertEquals("Georgia Bulldogs", byTeams.teams!!.first)
        assertEquals("https://e.test/miss.png", byTeams.teams!!.secondTeam.logo)

        val swapped = Found(Sport.COLLEGE_FOOTBALL, "x", null, Teams("Ole Miss", "Georgia"), start + 600_000, start, true, false, listOf(ch("b")))
        assertEquals("Ole Miss Rebels", EspnMatch.apply(listOf(swapped), index).single().teams!!.first, "the order it was written")

        val noTeams = Found(Sport.COLLEGE_FOOTBALL, "College Football", "From Vaught-Hemingway Stadium in Oxford, Miss.", null, start - 30 * 60_000, start, false, false,
            listOf(ch("c", description = "From Vaught-Hemingway Stadium in Oxford, Miss.")), Classified.NAMED)
        assertEquals("Georgia Bulldogs vs. Ole Miss Rebels", EspnMatch.apply(listOf(noTeams), index).single().teams!!.let { "${it.first} vs. ${it.second}" })

        val otherSport = Found(Sport.MENS_COLLEGE_BASKETBALL, "x", null, Teams("Georgia", "Ole Miss"), start, start, true, false, listOf(ch("d")), Classified.NAMED)
        assertNull(EspnMatch.apply(listOf(otherSport), index).single().teams!!.sides, "basketball isn't the football game")

        val days = Found(Sport.OTHER, "x", null, Teams("Georgia", "Ole Miss"), start + 2 * 24 * 3_600_000L, start, true, false, listOf(ch("e")), Classified.NONE)
        assertNull(EspnMatch.apply(listOf(days), index).single().teams!!.sides, "two days off")

        assertNotNull(EspnMatch.find(index, "Georgia Bulldogs", "Ole Miss Rebels", start - 15 * 60_000))
    }

    @Test fun theSurestLeagueAndTheBestChannel() {
        val start = et(2026, 11, 7, 12)
        val rows = SportsMerge.merge(
            listOf(
                Found(Sport.OTHER, "Rhode Island vs. Dayton", null, Teams("Rhode Island", "Dayton"), start, start + 3_600_000, true, false, listOf(ch("vip", hidden = true)), Classified.NONE),
                Found(Sport.VOLLEYBALL, "Rhode Island vs. Dayton", null, Teams("RHODE ISLAND", "DAYTON"), start, start + 3_600_000, true, false, listOf(ch("multi", shared = true)), Classified.NAMED),
                Found(Sport.VOLLEYBALL, "Rhode Island vs. Dayton", null, Teams("Rhode Island", "Dayton"), start, start + 3_600_000, true, false, listOf(ch("fav", favorite = true, hidden = true)), Classified.GUESSED),
                Found(Sport.VOLLEYBALL, "Rhode Island vs. Dayton", null, Teams("Rhode Island", "Dayton"), start, start + 3_600_000, true, false, listOf(ch("plain")), Classified.GUESSED),
            ),
        )
        val game = rows.single()
        assertEquals(Sport.VOLLEYBALL, game.sport)
        assertEquals(listOf("fav", "plain", "vip", "multi"), game.channels.map { it.streamId }, "a favorite first, a multi-game channel last")
    }
}

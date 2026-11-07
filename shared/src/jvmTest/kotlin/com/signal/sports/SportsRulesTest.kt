package com.signal.sports

import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsRulesTest {
    private val et = TimeZone.of("America/New_York")
    private fun et(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime(y, mo, d, h, mi).toInstant(et).toEpochMilliseconds()

    /** Friday evening, as the names below were captured. */
    private val now = et(2026, 11, 6, 21, 30)

    @Test fun gamesInTheGuide() {
        val sports = listOf("Sports", "Football")
        assertTrue(GuideGames.isLiveGame("Live: NFL Football - Tampa Bay Buccaneers vs. Chicago Bears", sports))
        assertTrue(GuideGames.isLiveGame("Live: NASCAR Cup Series", listOf("Sports", "Auto racing")))
        assertTrue(GuideGames.isLiveGame("Live: NFL RedZone", listOf("Series", "Football")))
        assertTrue(GuideGames.isLiveGame("Live: BKFC 96 Glasgow: Weir vs. Cohoon", listOf("Boxing")))
        assertFalse(GuideGames.isLiveGame("NFL Football - Jacksonville Jaguars vs. Baltimore Ravens", sports), "a replay")
        assertFalse(GuideGames.isLiveGame("Live: NFL RedZone Countdown", listOf("Football")))
        assertFalse(GuideGames.isLiveGame("Live: The NFL Today", listOf("Series", "Sports talk", "Football")))
        assertFalse(GuideGames.isLiveGame("Live: Football Night in America", listOf("Sports talk", "Football")))
        assertFalse(GuideGames.isLiveGame("Live: SEC Football Final", sports))
        assertFalse(GuideGames.isLiveGame("Live: SEC Now", listOf("Sports", "Talk Show")))
        assertFalse(GuideGames.isLiveGame("Live: Premier League Mornings - Tottenham Hotspur vs. Leeds United", listOf("Soccer")))
        assertFalse(GuideGames.isLiveGame("Live: NHL Hockey Central Saturday - Utah Mammoth vs. Ottawa Senators", listOf("Hockey", "Sports talk")))
        assertFalse(GuideGames.isLiveGame("Live: News 4 at 11", listOf("News")))
    }

    @Test fun sportsOfGuideTitles() {
        assertEquals(Sport.NFL, GuideGames.sport("Live: Fútbol Americano NFL - Green Bay Packers vs. New England Patriots", listOf("Sports", "Football")))
        assertEquals(Sport.SOCCER, GuideGames.sport("Live: Fútbol Serie A - Juventus vs. Fiorentina", listOf("Sports", "Soccer")))
        assertEquals(Sport.COLLEGE_FOOTBALL, GuideGames.sport("Live: College Football", listOf("Sports", "Football")))
        assertEquals(Sport.RACING, GuideGames.sport("Live: NASCAR Cup Series", listOf("Sports", "Auto racing")))
        assertEquals(Sport.GOLF, GuideGames.sport("Live: PGA Tour Golf", listOf("Sports", "Golf")))
        assertEquals(Sport.BASKETBALL, GuideGames.sport("Live: WNBA Basketball", listOf("Sports", "WNBA")))
    }

    @Test fun slotNames() {
        val nfl = assertNotNull(PpvNames.parse("US| NFL PPV", "NFL  | 03 - 1pm Cowboys at Colts", now))
        assertEquals(Teams("Cowboys", "Colts"), nfl.teams)
        assertEquals(Sport.NFL, nfl.sport)
        assertEquals(et(2026, 11, 8, 13), nfl.start, "an NFL slot with no day is Sunday")
        assertTrue(nfl.dayGuessed)

        val tnf = assertNotNull(PpvNames.parse("US| NFL PPV", "NFL  | 01 - TNF 8:15pm Jaguars at Ravens", now))
        assertEquals(et(2026, 11, 5, 20, 15), tnf.start, "Thursday's, not next week's")

        val mnf = assertNotNull(PpvNames.parse("US| NFL PPV", "NFL  | 15 - MNF 8:15pm Bills at Vikings", now))
        assertEquals(et(2026, 11, 9, 20, 15), mnf.start)

        val nhl = assertNotNull(PpvNames.parse("US| NHL PPV", "NHL 02: 8pm Golden Knights @ Red Wings", now))
        assertEquals(Teams("Golden Knights", "Red Wings"), nhl.teams)
        assertEquals(et(2026, 11, 6, 20), nhl.start)

        val uefa = assertNotNull(PpvNames.parse("US| UEFA PPV", "UEFA | 01 - Home FC vs Away FC 20:00pm", now))
        assertEquals(et(2026, 11, 6, 15), uefa.start, "UK time")
        assertEquals(Sport.SOCCER, uefa.sport)
    }

    @Test fun datedNames() {
        val pipes = assertNotNull(PpvNames.parse("US| NBA PASS PPV 8K", "NEXT | ATL - HAWKS VS WAS - WIZARDS | Fri 06 Nov 18:00 EST (US) | 8K EXCLUSIVE | US: NBA PASS PPV 1", now))
        assertEquals(Teams("Hawks", "Wizards"), pipes.teams)
        assertEquals(et(2026, 11, 6, 18), pipes.start)

        val atDate = assertNotNull(PpvNames.parse("US| NBA PPV", "Phoenix Suns vs Orlando Magic @ Nov 5 9:00 PM :NBA  01", now))
        assertEquals(Teams("Phoenix Suns", "Orlando Magic"), atDate.teams)
        assertEquals(et(2026, 11, 5, 21), atDate.start)

        val tagged = assertNotNull(PpvNames.parse("US| PARAMOUNT+ PPV", "UEFA Europa League: Milan vs Lyon @ Nov 5 12:00 PM :Paramount+  01", now))
        assertEquals(Sport.SOCCER, tagged.sport)
        assertEquals(Teams("Milan", "Lyon"), tagged.teams)

        val bracket = assertNotNull(PpvNames.parse("US| ESPN+ PPV", "US (ESPN+ 121) | Chicago State vs. Incarnate Word Nov 05 7:00PM ET (2026-11-05 19:00:00)", now))
        assertEquals(Teams("Chicago State", "Incarnate Word"), bracket.teams)
        assertEquals(et(2026, 11, 5, 19), bracket.start)

        val fight = assertNotNull(PpvNames.parse("US| UFC PPV", "UFC event: 1 name: UFC FIGHT NIGHT: A VS B PRELIMS start:2026-11-07 21:55:00 stop:2026-11-08 02:00:00", now))
        assertEquals(et(2026, 11, 7, 21, 55), fight.start)
        assertEquals(et(2026, 11, 8, 2), fight.stop)

        val race = assertNotNull(PpvNames.parse("US| PPV EVENT (BK)", "PPV EVENT 01: The Dirt Track at Charlotte (11.5 1:15 PM ET)", now))
        assertEquals("The Dirt Track at Charlotte", race.title)
        assertNull(race.teams)
    }

    @Test fun notEvents() {
        listOf(
            "US| NFL PPV" to "##### NFL LIVE EVENTS #####",
            "US| NFL PPV" to "US: NFL NETWORK ᴴᴰ",
            "US| NFL PPV" to "NFL  | 16 -",
            "US| NCAAF PPV" to "NCAAF 01: ESPN",
            "US| NCAAF PPV" to "NCAAF 18:",
            "US| NBA PPV" to ":NBA  12",
            "US| ESPN+ PPV VIP" to "- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ESPN+ PPV 201",
            "US| DAZN PPV" to "NO EVENT STREAMING NOW - | 8K EXCLUSIVE | US: DAZN PPV 1",
            "US| PPV EVENT" to "LIVE EVENT 15 - NO EVENT",
            "US| BTN+ PPV" to "(US) (BTN+ 091) |  (2098-12-31 08:00:22)",
            "US| SOCCER PPV" to "End | Home vs. Away | all | 03-11-2026 | 00:00 (GMT) | 8K EXCLUSIVE | US: SOCCER PPV 1",
            "US| ESPN+ PPV" to "US (ESPN+ 001) | Football: Good Morning Football (Good Morning Football) (2026-11-05 08:00:00)",
            "US| ESPN+ PPV" to "US (ESPN+ 361) | Football: College Football Live (College Football Live) (2026-11-06 17:00:00)",
        ).forEach { (group, name) -> assertNull(PpvNames.parse(group, name, now), name) }
    }

    @Test fun regions() {
        assertTrue(SportsGroups.isUs("US| NFL PPV", "NFL  | 03 - 1pm Cowboys at Colts"))
        assertFalse(SportsGroups.isUs("US| STAN PPV", "AU (STAN 01) | Milan v Lyon (2026-11-06 04:40:50)"), "the name overrides the group")
        assertFalse(SportsGroups.isUs("UK| SPORT HD", "UK: Something"))
        assertFalse(SportsGroups.isUs("8K| SPORT ON AIR 8K", "NZ: SKY ESPN 1"))
        assertFalse(SportsGroups.isUs("4K| UHD 3840P", "4K: ESPN"), "4K isn't a country")
    }

    @Test fun groups() {
        assertTrue(SportsGroups.readByDefault("US| NFL PPV"))
        assertTrue(SportsGroups.readByDefault("US| PEACOCK PPV VIP"))
        assertFalse(SportsGroups.readByDefault("US| FLO VIP PPV"))
        assertFalse(SportsGroups.readByDefault("US| NFL REPLAY"))
        assertFalse(SportsGroups.readByDefault("US| 24/7 PPV RAW 60fps"))
        assertTrue(SportsGroups.carriesGames("US| CBS HD/RAW 60fps"))
        assertFalse(SportsGroups.carriesGames("US| KIDS HD/RAW 60fps"))
        assertFalse(SportsGroups.carriesGames("FR| CINEMA HD"))
        val s = SportsSettings(groupsOn = setOf("US| FLO VIP PPV"), groupsOff = setOf("US| NFL PPV"))
        assertTrue(s.reads("US| FLO VIP PPV"))
        assertFalse(s.reads("US| NFL PPV"))
        assertEquals(s, SportsSettings.parse(s.encode()))
        assertEquals(SportsSettings(), SportsSettings.parse("not json"))
        assertNull(SportsSettings().encode())
    }

    private fun channel(id: String, hidden: Boolean = false, favorite: Boolean = false, order: Int = 0) =
        EventChannel(id, "s", id, "US| X", hidden, favorite, order = order)

    private fun found(title: String, teams: Teams?, start: Long, vararg ch: EventChannel, guessed: Boolean = false, dayGuessed: Boolean = false, detail: String? = null) =
        Found(Sport.NFL, title, detail, teams, start, start + 3 * 3_600_000L, guessed, dayGuessed, ch.toList())

    @Test fun oneRowPerGame() {
        val start = et(2026, 11, 8, 13)
        val rows = SportsMerge.merge(
            listOf(
                found("Jets at Chiefs", Teams("Jets", "Chiefs"), start, channel("ppv", hidden = true, order = 9), guessed = true, dayGuessed = true),
                found("NFL Football - New York Jets vs. Kansas City Chiefs", Teams("New York Jets", "Kansas City Chiefs"), start, channel("cbs", order = 1)),
                found("Chiefs vs. Jets", Teams("Chiefs", "Jets"), start + 600_000, channel("backup", hidden = true, order = 12), guessed = true),
                found("NFL Football - Other vs. Team", Teams("Other", "Team"), start, channel("fox")),
            ),
        )
        assertEquals(2, rows.size)
        val game = rows.first { it.title.contains("Jets") }
        assertEquals("New York Jets vs. Kansas City Chiefs", game.heading)
        assertEquals(listOf("cbs", "ppv", "backup"), game.channels.map { it.streamId })
        assertFalse(game.stopGuessed)
    }

    @Test fun titlesWithoutTeamsStayApart() {
        val start = et(2026, 11, 7, 15)
        val rows = SportsMerge.merge(
            listOf(
                found("College Football", null, start, channel("a"), detail = "From Field One"),
                found("College Football", null, start, channel("b"), detail = "From Field Two"),
                found("College Football", null, start, channel("c"), detail = "From Field One"),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(2, rows.first { it.detail == "From Field One" }.channels.size)
    }

    @Test fun theSamePairWeeksApartIsTwoGames() {
        val start = et(2026, 11, 8, 13)
        val rows = SportsMerge.merge(
            listOf(
                found("A vs. B", Teams("Aaa", "Bbb"), start, channel("x")),
                found("A vs. B", Teams("Aaa", "Bbb"), start + 7 * 24 * 3_600_000L, channel("y")),
            ),
        )
        assertEquals(2, rows.size)
    }

    @Test fun filters() {
        val zone = et
        val event = { key: String, start: Long, sport: Sport ->
            SportEvent(key, sport, key, null, null, start, start + 3 * 3_600_000L, false, emptyList())
        }
        val on = event("on", now - 3_600_000, Sport.NFL)
        val later = event("later", now + 3_600_000, Sport.NBA)
        val tomorrow = event("tomorrow", et(2026, 11, 7, 15), Sport.NFL)
        val sunday = event("sunday", et(2026, 11, 8, 13), Sport.NFL)
        val over = event("over", now - 5 * 3_600_000, Sport.NFL)
        val all = listOf(sunday, tomorrow, later, on, over)
        assertEquals(listOf("on"), SportsList.filter(all, SportsWhen.LIVE, emptySet(), now, zone).map { it.key })
        assertEquals(listOf("on", "later"), SportsList.filter(all, SportsWhen.TODAY, emptySet(), now, zone).map { it.key })
        assertEquals(listOf("tomorrow"), SportsList.filter(all, SportsWhen.TOMORROW, emptySet(), now, zone).map { it.key })
        assertEquals(listOf("on", "tomorrow", "sunday"), SportsList.filter(all, SportsWhen.WEEK, setOf(Sport.NFL), now, zone).map { it.key })
        assertEquals(listOf(Sport.NFL, Sport.NBA), SportsList.sports(all, SportsWhen.TODAY, now, zone))
        assertEquals(0.5f, event("half", now - 90 * 60_000, Sport.NFL).progress(now), 0.01f)
    }

    @Test fun filtersAreKept() {
        val f = SportsFilters(region = SportsRegion.WORLDWIDE, whenOn = SportsWhen.LIVE).toggled(Sport.NFL).toggled(Sport.NBA)
        assertEquals(f, SportsFilters.parse(f.encode()))
        assertEquals(setOf(Sport.NFL, Sport.NBA), f.chosen)
        assertEquals("NFL, NBA", f.sportLabel)
        assertEquals("3 sports", f.toggled(Sport.MLB).sportLabel)
        assertEquals("All sports", f.toggled(Sport.NFL).toggled(Sport.NBA).sportLabel)
        assertEquals(SportsFilters(), SportsFilters.parse("""{"whenOn":"NEVER"}"""), "a value this version does not know")
        assertNull(SportsFilters().encode())
    }

    @Test fun recordingWarning() {
        fun rec(state: String, stream: String) = com.signal.nas.NasRecording(id = stream, title = "t", channelName = "c", startMs = 0, stopMs = 1, state = state, streamId = stream)
        val one = listOf(rec("RECORDING", "src:x:1"))
        assertEquals(one, recordingsAtRisk(one, "src", logins = 1))
        assertTrue(recordingsAtRisk(one, "other", logins = 1).isEmpty(), "another source")
        assertTrue(recordingsAtRisk(one, "src", logins = 2).isEmpty(), "a free login gets borrowed")
        assertTrue(recordingsAtRisk(listOf(rec("SCHEDULED", "src:x:1"), rec("WAITING", "src:x:2")), "src", logins = 1).isEmpty())
        assertEquals(2, recordingsAtRisk(one + rec("RECORDING", "src:x:2"), "src", logins = 2).size)
    }

    /** Against the real names, when they're here (fixtures-private isn't in the repo): prints what was read and missed. */
    @Test fun realNames() {
        val file = File("../fixtures-private/sports/ppv-channels.tsv").takeIf { it.exists() } ?: return
        val rows = file.readLines().map { it.split('\t') }.filter { it.size >= 2 }
        val parsed = rows.map { Triple(it[0], it[1], PpvNames.parse(it[0], it[1], now)) }
        val us = parsed.filter { SportsGroups.readByDefault(it.first) && SportsGroups.country(it.first) == "US" }
        println("Real names: ${us.size} channels in default US groups, ${us.count { it.third != null }} read")
        us.groupBy { it.first }.forEach { (g, list) ->
            val hits = list.filter { it.third != null }
            println("$g: ${hits.size}/${list.size}  ${hits.take(2).joinToString(" || ") { "${it.third!!.sport} ${it.third!!.title}" }}")
        }
        val notRead = us.filter { it.third == null && !Regex("""NO EVENT|^#|2098-|^:|^\S+\s*\d*\s*:?$""").containsMatchIn(it.second) }
        println("Not read, ${notRead.size}:")
        notRead.shuffled(kotlin.random.Random(1)).take(40).forEach { println("  ${it.first} :: ${it.second}") }
    }
}

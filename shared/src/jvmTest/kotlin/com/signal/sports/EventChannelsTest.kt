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

/** backlog → PPV channels: event channels named for their event. Names are the real ones, 2026-11-07. */
class EventChannelsTest {
    private val et = TimeZone.of("America/New_York")
    private fun et(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime(y, mo, d, h, mi).toInstant(et).toEpochMilliseconds()
    /** Saturday morning. */
    private val now = et(2026, 11, 7, 11, 0)
    private val nfl = "US| NFL PPV"
    private val espn = "US| ESPN+ PPV"

    @Test fun nflSlotsAreTheGameAndItsSundayTime() {
        val e = assertNotNull(PpvNames.event(nfl, "NFL  | 03 - 1pm Cowboys at Colts", now))
        assertEquals("Cowboys at Colts", e.title)
        assertEquals(et(2026, 11, 8, 13), e.start, "Sunday")
        assertEquals(et(2026, 11, 8, 17), e.stop, "a typical NFL game's length")
        assertTrue(e.dayGuessed && e.stopGuessed)
        assertEquals("Buccaneers at Bears", EventChannels.shortName(nfl, "NFL   | 4k - SNF 8:20pm Buccaneers at Bears", now))
        val mnf = assertNotNull(PpvNames.event(nfl, "NFL  | 15 - MNF 8:15pm Bills at Vikings", now))
        assertEquals("Bills at Vikings", mnf.title)
        assertEquals(et(2026, 11, 9, 20, 15), mnf.start, "Monday")
    }

    @Test fun espnNamesLoseTheirDateAndRepeats() {
        assertEquals("Adelaide United vs. Wellington Phoenix", EventChannels.shortName(espn, "US (ESPN+ 001) | Adelaide United vs. Wellington Phoenix Nov 06 2:55AM ET (2026-11-06 02:55:05)", now))
        assertEquals("Dartmouth vs. Princeton", EventChannels.shortName(espn, "US (ESPN+ 375) | Football: Dartmouth vs. Princeton (Dartmouth vs. Princeton) (2026-11-07 12:00:30)", now))
        assertEquals("Ivy League Field Hockey Championship (Semifinal)", EventChannels.shortName(espn, "US (ESPN+ 007) | Ivy League Field Hockey Championship (Semifinal) Nov 06 11:30AM ET (2026-11-06 11:30:00)", now))
        assertEquals("Good Morning Football", EventChannels.shortName(espn, "US (ESPN+ 004) | Football: Good Morning Football (Good Morning Football) (2026-11-06 08:00:00)", now), "shows too, not only games")
        assertEquals("St. John's vs. Providence", EventChannels.shortName(espn, "US (ESPN+ 165) | St. John`s vs. Providence Nov 06 7:00PM ET (2026-11-06 19:01:50)", now))
        val prime = assertNotNull(PpvNames.event(espn, "US (ESPN+ 003) | Football: NFL Football: Prime Vision 2026 Week 9 Jacksonville Jaguars vs. Baltimore Ravens (NFL Football: Prime Vision 2026 Week 9 Jacksonville Jaguars vs. Baltimore Ravens) (2026-11-06 05:00:00)", now))
        assertEquals("Prime Vision 2026 Week 9 Jacksonville Jaguars vs. Baltimore Ravens", prime.title)
        assertEquals("NFL Football", prime.tag)
    }

    @Test fun aChannelWithSeveralGamesIsNamedForTheOneOn() {
        val e = assertNotNull(PpvNames.event(espn, "US (ESPN+ 390) | NCAA Football: #7 TEX vs. #18 MIZ • #9 BYU vs. #3 UTAH • Crown Jewel 2026 (#7 TEX vs. #18 MIZ ) (2026-11-07 12:01:45)", now))
        assertEquals("#7 TEX vs. #18 MIZ", e.title)
        assertEquals(listOf("#9 BYU vs. #3 UTAH", "Crown Jewel 2026"), e.others)
        assertEquals("NCAA Football", e.tag)
    }

    @Test fun shoutedNamesAreCased() {
        assertEquals("Adelaide United vs. Wellington Phoenix", EventChannels.shortName("US| ESPN+ PPV VIP", "LIVE | ADELAIDE UNITED VS. WELLINGTON PHOENIX | Fri 06 Nov 02:55 EST (US) | 8K EXCLUSIVE | US: ESPN+ PPV 1", now))
        fun vip(game: String) = EventChannels.shortName("US| ESPN+ PPV VIP", "NEXT | $game | Sat 07 Nov 15:00 EST (US) | 8K EXCLUSIVE | US: ESPN+ PPV 59", now)
        assertEquals("Samford vs. UNC Greensboro", vip("SAMFORD VS. UNC GREENSBORO"))
        assertEquals("Prairie View A&M vs. UT Rio Grande Valley", vip("PRAIRIE VIEW A&M VS. UT RIO GRANDE VALLEY"))
        assertEquals("UIC vs. Bradley", vip("UIC VS. BRADLEY"))
        assertEquals("Miami (OH) vs. UConn", vip("MIAMI (OH) VS. UCONN"))
    }

    @Test fun onNowWithNoTimeKeepsOneStart() {
        val name = "LIVE | Jets vs. Chiefs | all | 8K EXCLUSIVE | US: NFL PPV 1"
        val first = assertNotNull(PpvNames.parse(nfl, name, now + 60_000)).start
        assertEquals(first, assertNotNull(PpvNames.parse(nfl, name, now + 20 * 60_000)).start, "read again later in the hour")
        assertEquals(now, first)
    }

    @Test fun overAndEmptySlotsAreHidden() {
        assertTrue(EventChannels.hides(espn, "US (ESPN+ 001) | Adelaide United vs. Wellington Phoenix Nov 06 2:55AM ET (2026-11-06 02:55:05)", now), "yesterday's")
        assertFalse(EventChannels.hides(espn, "US (ESPN+ 375) | Football: Dartmouth vs. Princeton (Dartmouth vs. Princeton) (2026-11-07 12:00:30)", now), "today's, still to come")
        assertFalse(EventChannels.hides(nfl, "NFL  | 03 - 1pm Cowboys at Colts", now))
        assertTrue(EventChannels.hides(nfl, "NFL  | 03 - 1pm Cowboys at Colts", et(2026, 11, 10, 9)), "Tuesday: Sunday's game is over")
        assertTrue(EventChannels.hides(nfl, "NFL  | 01 -", now))
        assertTrue(EventChannels.hides("US| MLB PPV", "MLB 03 :", now))
        assertTrue(EventChannels.hides("US| MLB PPV", "MLB 01 |  Brewers x Rays start:2026-10-28 00:00:00 stop:2026-10-28 07:13:20", now))
        assertTrue(EventChannels.hides("UK| BOXING PPV", "End | Gibson vs. Oliynykova | all | 03-11-2026 | 19:00 (GMT)", now))
        assertFalse(EventChannels.hides(nfl, "##### NFL LIVE EVENTS #####", now), "a heading stays")
        assertFalse(EventChannels.hides(nfl, "US: NFL NETWORK ᴴᴰ", now), "a channel")
    }

    @Test fun onlyEventGroupsAreRead() {
        assertNull(EventChannels.shortName("US| ENTERTAINMENT", "NFL  | 03 - 1pm Cowboys at Colts", now))
        assertFalse(EventChannels.hides("US| ENTERTAINMENT", "MLB 03 :", now))
        assertNull(EventChannels.shortName(nfl, "US: NFL REDZONE ᴴᴰ", now))
    }

    @Test fun theEventIsTheProgram() {
        val p = assertNotNull(EventChannels.program("s1", nfl, "NFL  | 03 - 1pm Cowboys at Colts", now))
        assertEquals("Cowboys at Colts", p.title)
        assertEquals(et(2026, 11, 8, 13), p.start)
        assertTrue(p.id < 0, "never a guide row's")
        assertEquals("NFL", p.subTitle)
        assertNull(EventChannels.program("s2", "UK| BOXING PPV", "End | Gibson vs. Oliynykova | all | 03-11-2026 | 19:00 (GMT)", now), "over")
    }
}

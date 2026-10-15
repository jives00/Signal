package com.signal.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Descriptions are from the real provider's guide (2026-10-15).
class GameTitlesTest {
    private fun titled(title: String, description: String?, vararg categories: String) =
        GameTitles.titled(title, categories.toList(), description)

    @Test fun visitPutsTheVisitorsFirst() {
        assertEquals(
            "Live: College Football - Colorado State Rams vs. Texas State Bobcats",
            titled(
                "Live: College Football",
                "The Colorado State Rams visit the Texas State Bobcats in a Pac-12 game. Colorado State aims to break their three-game losing streak.",
                "Sports", "Football",
            ),
        )
        assertEquals(
            "Live: NHL Hockey - Chicago Blackhawks vs. Winnipeg Jets",
            titled("Live: NHL Hockey", "The Chicago Blackhawks visit the Winnipeg Jets at Canada Life Centre for a Central Division game.", "Sports", "Hockey"),
        )
        assertEquals(
            "Live: College Football - Pirates vs. Blazers",
            titled("Live: College Football", "The Pirates visit the Blazers in an American Conference game.", "Football"),
        )
    }

    @Test fun hostPutsTheVisitorsFirst() {
        assertEquals(
            "Live: Premier League Soccer - Chelsea vs. Everton",
            titled("Live: Premier League Soccer", "Everton hosts Chelsea in Matchweek 7 of the Premier League at Hill Dickinson Stadium."),
        )
        assertEquals(
            "Live: 2026 WNBA Finals - Atlanta Dream vs. Golden State Valkyries",
            titled("Live: 2026 WNBA Finals", "The Golden State Valkyries host the Atlanta Dream in Game 1 of the 2026 WNBA Finals at Chase Center."),
        )
        assertEquals(
            "Live: NWSL Soccer - Angel City FC vs. Denver Summit FC",
            titled("Live: NWSL Soccer", "Denver Summit FC hosts Angel City FC at Centennial Stadium."),
        )
    }

    @Test fun atAndAbbreviations() {
        assertEquals(
            "Live: High School Football - Fayetteville-Manlius vs. West Genesee",
            titled("Live: High School Football", "Fayetteville-Manlius at West Genesee."),
        )
        assertEquals(
            "NHL Hockey - St. Louis Blues vs. Texas A&M Aggies",
            titled("NHL Hockey", "The St. Louis Blues visit the Texas A&M Aggies."),
        )
        assertEquals(
            "College Basketball - Miami (OH) RedHawks vs. Ohio Bobcats",
            titled("College Basketball", "The Miami (OH) RedHawks visit the Ohio Bobcats in a MAC game."),
        )
    }

    @Test fun aNameEndsAtItsSentence() {
        assertEquals(
            "Live: College Football - Washington Huskies vs. Purdue Boilermakers",
            titled("Live: College Football", "The Washington Huskies visit the Purdue Boilermakers. Washington aims to stay unbeaten."),
        )
        assertEquals(
            "College Football - No. 4 Miami Hurricanes vs. N.C. State Wolfpack",
            titled("College Football", "The N.C. State Wolfpack host the No. 4 Miami Hurricanes. Miami leads the series."),
        )
    }

    @Test fun descriptionsWithoutTeamsLeaveTheTitle() {
        for (d in listOf(
            "From Bobcat Stadium in San Marcos, Texas.",
            "National League Championship Series, Game 4.",
            "Action from high school football.",
            "Coverage of men's college hockey action.",
            null,
        )) assertEquals("Live: College Football", titled("Live: College Football", d, "Football"))
    }

    @Test fun onlyGamesGetTeams() {
        // Not a sport.
        assertEquals("Family Drama", titled("Family Drama", "The Smiths visit the Joneses at their lake house."))
        // A show about a sport.
        assertEquals(
            "Live: College Football Countdown",
            titled("Live: College Football Countdown", "The Pirates visit the Blazers in an American Conference game."),
        )
        assertFalse(GameTitles.isGame("Live: UEFA Europa League Post Match Show", listOf("Soccer")))
        assertFalse(GameTitles.isGame("Live: College Football Scoreboard", listOf("Football")))
        assertFalse(GameTitles.isGame("Live: MLB on TBS: Leadoff", listOf("Baseball")))
        assertTrue(GameTitles.isGame("Live: MLB Baseball", emptyList()))
        assertTrue(GameTitles.isGame("Live: Game", listOf("Sports", "Volleyball")))
    }

    @Test fun addedOnlyOnceAndRemovable() {
        val d = "The Georgia Southern Eagles visit the Old Dominion Monarchs in a Sun Belt game."
        val once = titled("Live: College Football", d, "Football")
        assertEquals(once, titled(once, d, "Football"))
        assertTrue(GameTitles.hasMatchup(once))
        assertEquals("Live: College Football", GameTitles.withoutMatchup(once))
        assertEquals("Live: Pre-Season - Week 1", GameTitles.withoutMatchup("Live: Pre-Season - Week 1"))
        assertNull(GameTitles.fromDescription("From Panther Stadium in Prairie View, Texas."))
    }

    @Test fun titlesThatNameTheTeamsAreLeftAlone() {
        val d = "The Chicago Bears visit the Green Bay Packers."
        assertEquals("NFL Football: Bears at Packers", titled("NFL Football: Bears at Packers", d))
        assertEquals("NFL: Bears vs. Packers", titled("NFL: Bears vs. Packers", d))
        assertFalse(GameTitles.namesTeams("Live: College Football"))
    }
}

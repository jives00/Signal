package com.signal.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web admin's Live Sports page: the filter's order, leagues counted as Other, and sports given their own entry. */
class SportsOrderTest {
    private fun game(sport: Sport, sportName: String? = null) =
        SportEvent("k", sport, "t", null, null, 0, 1, false, emptyList(), sportName)

    @Test fun usualOrderWithoutSettings() {
        val s = SportsSettings()
        assertEquals(listOf("NFL", "NBA", "SOCCER", "OTHER"), s.sorted(listOf("OTHER", "SOCCER", "NBA", "NFL")))
        assertEquals("CFL", s.key(game(Sport.CFL)))
        assertEquals("OTHER", s.key(game(Sport.OTHER, "Field Hockey")))
    }

    @Test fun theProfilesOrderFirst() {
        val s = SportsSettings(order = listOf("SOCCER", "NFL"))
        assertEquals(listOf("SOCCER", "NFL", "NBA", "OTHER"), s.sorted(listOf("NFL", "NBA", "OTHER", "SOCCER")), "unlisted ones keep their usual place after")
    }

    @Test fun leaguesCanBeOther() {
        val s = SportsSettings(asOther = setOf("CFL", "CRICKET"))
        assertEquals("OTHER", s.key(game(Sport.CFL)))
        assertEquals("NFL", s.key(game(Sport.NFL)))
        val all = listOf(game(Sport.CFL), game(Sport.NFL))
        assertEquals(listOf("NFL", "OTHER"), SportsList.sports(all, SportsWhen.WEEK, 0, settings = s))
        assertEquals(1, SportsList.filter(all, SportsWhen.WEEK, setOf("OTHER"), 0, settings = s).size, "filtering by Other finds the CFL game")
    }

    @Test fun otherSportsCanHaveTheirOwnEntry() {
        val s = SportsSettings(ownEntry = setOf("Field Hockey"))
        assertEquals("named:Field Hockey", s.key(game(Sport.OTHER, "field hockey")), "by name, ignoring case")
        assertEquals("OTHER", s.key(game(Sport.OTHER, "Wrestling")))
        assertEquals("Field Hockey", SportKeys.label("named:Field Hockey"))
        assertEquals(listOf("SOCCER", "named:Field Hockey", "OTHER"), s.sorted(listOf("OTHER", "named:Field Hockey", "SOCCER")), "just before Other")
    }

    @Test fun hidingAnEntry() {
        val s = SportsSettings(ownEntry = setOf("Field Hockey"), hiddenSports = setOf("named:Field Hockey"))
        assertFalse(s.shows(game(Sport.OTHER, "Field Hockey")))
        assertTrue(s.shows(game(Sport.OTHER, "Wrestling")))
        assertTrue(SportsSettings.parse(s.encode()).ownEntry.contains("Field Hockey"), "stored and read back")
    }
}

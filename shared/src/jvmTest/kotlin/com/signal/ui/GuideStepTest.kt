package com.signal.ui

import com.signal.model.Program
import com.signal.ui.screens.GuideStep
import com.signal.ui.screens.guideStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Left and Right in the guide. A row's listings ending mustn't stop the guide (Fox 32 at 4 AM, 2026-11-06). */
class GuideStepTest {
    private val minute = 60_000L
    private val now = 1_000_000 * minute
    private fun program(fromMinutes: Int, toMinutes: Int) = Program(0, "", "", now + fromMinutes * minute, now + toMinutes * minute, "Show")
    private val row = listOf(program(-10, 30), program(30, 60), program(60, 120))

    @Test fun rightGoesThroughTheListings() {
        assertEquals(GuideStep.ToProgram(row[1]), guideStep(row, now, now, 1))
        assertEquals(GuideStep.ToProgram(row[2]), guideStep(row, row[1].start, now, 1))
    }

    @Test fun rightCarriesOnPastTheLastProgram() {
        assertEquals(GuideStep.ToTime(row[2].stop), guideStep(row, row[2].start, now, 1))
        val past = row[2].stop + 10 * minute
        assertEquals(GuideStep.ToTime(past + 30 * minute), guideStep(row, past, now, 1))
    }

    @Test fun aRowWithNoListingsStillMovesTime() {
        assertEquals(GuideStep.ToTime(now + 30 * minute), guideStep(emptyList(), now, now, 1))
    }

    @Test fun rightStopsAtTheEndOfTheGuide() {
        assertNull(guideStep(emptyList(), now + 7 * 24 * 60 * minute, now, 1))
    }

    @Test fun rightCrossesAGapInTheListings() {
        val gap = listOf(program(-10, 30), program(90, 120))
        assertEquals(GuideStep.ToProgram(gap[1]), guideStep(gap, now + 40 * minute, now, 1))
    }

    @Test fun leftGoesBackThroughTheListingsToTheChannels() {
        assertEquals(GuideStep.ToProgram(row[0]), guideStep(row, row[1].start, now, -1))
        assertEquals(GuideStep.Channels, guideStep(row, now, now, -1))
    }

    @Test fun leftWalksBackThroughNoInformationThenLandsOnTheLastProgram() {
        val past = row[2].stop + 2 * 60 * minute
        assertEquals(GuideStep.ToTime(past - 30 * minute), guideStep(row, past, now, -1))
        assertEquals(GuideStep.ToProgram(row[2]), guideStep(row, row[2].stop + 10 * minute, now, -1))
    }

    @Test fun leftOnAnEmptyRowComesHomeToTheChannels() {
        assertEquals(GuideStep.Channels, guideStep(emptyList(), now + 20 * minute, now, -1))
        assertEquals(GuideStep.ToTime(now + 30 * minute), guideStep(emptyList(), now + 60 * minute, now, -1))
    }
}

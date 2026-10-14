package com.signal.ui

import com.signal.ui.screens.halfHourBefore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guide's window starts on the half hour before now (milestone 6). */
class GuideWindowTest {
    private fun at(zone: TimeZone, h: Int, m: Int) = LocalDateTime(2026, 10, 14, h, m).toInstant(zone).toEpochMilliseconds()

    @Test fun startsOnTheHalfHourBeforeNow() {
        val ny = TimeZone.of("America/New_York")
        assertEquals(at(ny, 16, 30), halfHourBefore(at(ny, 16, 56), ny))
        assertEquals(at(ny, 16, 0), halfHourBefore(at(ny, 16, 29), ny))
        assertEquals(at(ny, 16, 30), halfHourBefore(at(ny, 16, 30), ny))
    }

    @Test fun followsTheLocalClockInAQuarterHourZone() {
        val kathmandu = TimeZone.of("Asia/Kathmandu") // UTC+5:45
        assertEquals(at(kathmandu, 16, 30), halfHourBefore(at(kathmandu, 16, 56), kathmandu))
    }
}

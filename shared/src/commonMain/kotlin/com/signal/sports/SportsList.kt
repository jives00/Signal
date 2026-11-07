package com.signal.sports

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What the page's filters pick from the games. */
object SportsList {
    /** Games on in [whenOn], live ones first, then by start. No [sports] is every sport. */
    fun filter(all: List<SportEvent>, whenOn: SportsWhen, sports: Set<Sport>, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): List<SportEvent> {
        val today: LocalDate = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        fun dayStart(days: Int) = today.plus(days, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        return all.asSequence()
            .filter { it.stop > now && (sports.isEmpty() || it.sport in sports) }
            .filter {
                when (whenOn) {
                    SportsWhen.LIVE -> it.isOn(now)
                    SportsWhen.TODAY -> it.start < dayStart(1)
                    SportsWhen.TOMORROW -> it.start >= dayStart(1) && it.start < dayStart(2)
                    SportsWhen.WEEK -> true
                }
            }
            .sortedWith(compareBy({ !it.isOn(now) }, { it.start }, { it.title }))
            .toList()
    }

    /** The sports with something to show in [whenOn], in the filter's order. */
    fun sports(all: List<SportEvent>, whenOn: SportsWhen, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): List<Sport> {
        val present = filter(all, whenOn, emptySet(), now, zone).mapTo(HashSet()) { it.sport }
        return Sport.entries.filter { it in present }
    }
}

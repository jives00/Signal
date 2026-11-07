package com.signal.sports

import com.signal.model.Program

/** What the Live Sports page filters by (live-sports.md). Order is the filter's order. */
enum class Sport(val label: String) {
    NFL("NFL"),
    COLLEGE_FOOTBALL("College Football"),
    NBA("NBA"),
    COLLEGE_BASKETBALL("College Basketball"),
    BASKETBALL("Basketball"),
    MLB("MLB"),
    NHL("NHL"),
    SOCCER("Soccer"),
    RACING("Racing"),
    GOLF("Golf"),
    TENNIS("Tennis"),
    FIGHTING("Fighting"),
    OTHER("Other"),
}

/** US shows only US channels; Worldwide adds the rest. */
@kotlinx.serialization.Serializable
enum class SportsRegion(val label: String) { US("US"), WORLDWIDE("Worldwide") }

/** When a game has to be on to show. */
@kotlinx.serialization.Serializable
enum class SportsWhen(val label: String) {
    LIVE("Live now"),
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    WEEK("This week"),
}

/** The two sides of a game, in the order they were written. */
data class Teams(val first: String, val second: String)

/** One channel carrying a game. */
data class EventChannel(
    val streamId: String,
    val sourceId: String,
    val name: String,
    val group: String,
    val hidden: Boolean,
    val favorite: Boolean,
    /** The guide listing this came from; null for an event channel known only by its name. */
    val program: Program? = null,
    val order: Int = 0,
)

/** One game or race, once, with every channel carrying it, best first. */
data class SportEvent(
    val key: String,
    val sport: Sport,
    val title: String,
    /** Says which game a title without teams is ("From Merlin Olsen Field..."). */
    val detail: String?,
    val teams: Teams?,
    val start: Long,
    val stop: Long,
    /** True when the stop time is a guess, because only a channel's name gave the start. */
    val stopGuessed: Boolean,
    val channels: List<EventChannel>,
) {
    fun isOn(now: Long) = now in start until stop

    /** What a row says: the teams, since the row names the sport, or else the title. */
    val heading: String get() = teams?.let { "${it.first} vs. ${it.second}" } ?: title

    /** How far through it is now, 0 to 1. */
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

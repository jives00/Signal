package com.signal.sports

import com.signal.model.Program

/** What a league is a kind of. The filter lists leagues in this order. */
enum class SportFamily { FOOTBALL, BASKETBALL, HOCKEY, BASEBALL, SOCCER, RUGBY, VOLLEYBALL, CRICKET, LACROSSE, RACING, GOLF, TENNIS, FIGHTING, OTHER }

/**
 * What the Live Sports page filters by (live-sports.md → Round 2): a league where one can be told, else
 * a sport. There's no plain "Basketball": games are NBA, college and so on, and only what can't be
 * placed in the US is "International". Order is the filter's order. [short] is the badge when there's
 * no image. The names are stored in settings, so keep them.
 */
enum class Sport(val label: String, val family: SportFamily, val short: String) {
    NFL("NFL", SportFamily.FOOTBALL, "NFL"),
    COLLEGE_FOOTBALL("College Football", SportFamily.FOOTBALL, "CFB"),
    CFL("CFL", SportFamily.FOOTBALL, "CFL"),
    HIGH_SCHOOL_FOOTBALL("High School Football", SportFamily.FOOTBALL, "HS"),
    AUSSIE_RULES("Australian Football", SportFamily.FOOTBALL, "AFL"),
    NBA("NBA", SportFamily.BASKETBALL, "NBA"),
    WNBA("WNBA", SportFamily.BASKETBALL, "WNBA"),
    G_LEAGUE("G League", SportFamily.BASKETBALL, "G"),
    MENS_COLLEGE_BASKETBALL("Men's College Basketball", SportFamily.BASKETBALL, "MBB"),
    WOMENS_COLLEGE_BASKETBALL("Women's College Basketball", SportFamily.BASKETBALL, "WBB"),
    INTL_BASKETBALL("International Basketball", SportFamily.BASKETBALL, "BKB"),
    NHL("NHL", SportFamily.HOCKEY, "NHL"),
    COLLEGE_HOCKEY("College Hockey", SportFamily.HOCKEY, "CHK"),
    INTL_HOCKEY("Other Hockey", SportFamily.HOCKEY, "HKY"),
    MLB("MLB", SportFamily.BASEBALL, "MLB"),
    MILB("Minor League Baseball", SportFamily.BASEBALL, "MiLB"),
    COLLEGE_BASEBALL("College Baseball", SportFamily.BASEBALL, "CBB"),
    SOCCER("Soccer", SportFamily.SOCCER, "SOC"),
    RUGBY("Rugby", SportFamily.RUGBY, "RUG"),
    VOLLEYBALL("Volleyball", SportFamily.VOLLEYBALL, "VB"),
    CRICKET("Cricket", SportFamily.CRICKET, "CRK"),
    LACROSSE("Lacrosse", SportFamily.LACROSSE, "LAX"),
    RACING("Racing", SportFamily.RACING, "RACE"),
    GOLF("Golf", SportFamily.GOLF, "GOLF"),
    TENNIS("Tennis", SportFamily.TENNIS, "TEN"),
    FIGHTING("Fighting", SportFamily.FIGHTING, "FIGHT"),
    OTHER("Other", SportFamily.OTHER, "MISC"),
    ;

    /** Games between two sides, which need both named to be a game. */
    val teamSport: Boolean get() = family in teamFamilies

    companion object {
        private val teamFamilies = setOf(
            SportFamily.FOOTBALL, SportFamily.BASKETBALL, SportFamily.HOCKEY, SportFamily.BASEBALL, SportFamily.SOCCER,
            SportFamily.RUGBY, SportFamily.VOLLEYBALL, SportFamily.CRICKET, SportFamily.LACROSSE,
        )

        /** Names from before leagues (1.1.11): College Basketball was both, Basketball is gone. */
        private val renamed = mapOf(
            "COLLEGE_BASKETBALL" to listOf(MENS_COLLEGE_BASKETBALL, WOMENS_COLLEGE_BASKETBALL),
            "BASKETBALL" to listOf(G_LEAGUE, INTL_BASKETBALL),
        )

        /** Stored names as this version's: old ones mapped across, unknown ones kept for a newer version. */
        fun upgrade(names: Set<String>): Set<String> =
            names.flatMapTo(LinkedHashSet()) { n -> renamed[n]?.map { it.name } ?: listOf(n) }
    }
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

/** One side of a game, with what ESPN knows of it when it was found there. */
data class Team(val name: String, val logo: String? = null, val record: String? = null, val rank: Int? = null, val score: String? = null)

/** ESPN's word on a game: not started, on or over, with its short status ("Q3 5:12", "Final"). */
data class GameStatus(val state: GameState, val detail: String?)

/** The two sides of a game, in the order they were written. */
data class Teams(
    val first: String,
    val second: String,
    /** ESPN's logos, records and rankings, when the game was found there (live-sports.md → Round 2). */
    val sides: Pair<Team, Team>? = null,
    /** ESPN's status, when the game was found there. */
    val status: GameStatus? = null,
) {
    /** "21–17", once ESPN has a score for both. */
    val score: String? get() = sides?.let { (a, b) -> if (a.score != null && b.score != null && status?.state != GameState.PRE) "${a.score}–${b.score}" else null }

    val firstTeam: Team get() = sides?.first ?: Team(first)
    val secondTeam: Team get() = sides?.second ?: Team(second)
    val hasLogos: Boolean get() = sides?.let { it.first.logo != null || it.second.logo != null } == true

    companion object {
        fun of(first: Team, second: Team, status: GameStatus? = null): Teams = Teams(first.name, second.name, first to second, status)
    }
}

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
    /** One of several games this channel's name lists, and not the one it says is on: offered last. */
    val sharedWithOthers: Boolean = false,
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
    /** For [Sport.OTHER], the sport the names gave ("Swimming and Diving"); the row says it instead of "Other". */
    val sportName: String? = null,
) {
    fun isOn(now: Long) = now in start until stop

    /** What a row says: the teams, since the row names the sport, or else the title. */
    val heading: String get() = teams?.let { "${it.first} vs. ${it.second}" } ?: title

    /** The league or sport as a row names it. */
    val sportLabel: String get() = sportName ?: sport.label

    /** How far through it is now, 0 to 1. */
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

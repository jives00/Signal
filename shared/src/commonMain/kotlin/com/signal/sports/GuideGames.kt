package com.signal.sports

/**
 * Which guide listings are live games (live-sports.md). Measured on the real guide, 2026-11-06: the
 * guide's own live flag is never set, so "Live: " in the title is what says live (replays don't have
 * it). News and shopping have it too, and so do studio shows, so the category decides: games are
 * "Sports"; shows are "Sports talk", or a sport with no "Sports" ("Football", "Series, Football").
 */
object GuideGames {
    private const val LIVE = "Live: "
    private const val SPORTS = "Sports"

    private val talkCategories = setOf("Sports talk", "Talk Show", "News")

    /** Studio shows the guide files under "Sports" all the same. */
    private val studio = Regex("""\b(?:Football Final|Countdown|Kickoff|Pregame|Pre-Game|Postgame|Post-Game|Scoreboard|Highlights|Now|Tailgate|Wrapup)\b""", RegexOption.IGNORE_CASE)

    /** Live coverage that isn't one game but is wanted anyway. */
    private val wanted = Regex("""^(?:NFL )?Red ?Zone$""", RegexOption.IGNORE_CASE)

    /** Fight cards can come with only this, and no "Sports". */
    private val fighting = setOf("Boxing", "MMA", "Mixed martial arts", "Wrestling")

    fun isLiveGame(title: String, categories: List<String>): Boolean {
        if (!title.startsWith(LIVE)) return false
        val name = title.removePrefix(LIVE)
        if (wanted.matches(name)) return true
        if (categories.any { it in talkCategories }) return false
        if (studio.containsMatchIn(name.substringBefore(" - "))) return false
        return SPORTS in categories || (categories.any { it in fighting } && " vs. " in name)
    }

    /** The title without the "Live: " the guide gave it. */
    fun cleanTitle(title: String): String = title.removePrefix(LIVE).trim()

    fun sport(title: String, categories: List<String>): Sport = sportOf(cleanTitle(title) + " " + categories.joinToString(" "))
}

private val sportWords: List<Pair<Regex, Sport>> = listOf(
    """\b(?:NFL|Red ?Zone)\b""" to Sport.NFL,
    """\b(?:NCAAF|College Football)\b""" to Sport.COLLEGE_FOOTBALL,
    """\bWNBA\b|\bG League\b|\bNBL\b""" to Sport.BASKETBALL,
    """\bNBA\b""" to Sport.NBA,
    """\b(?:NCAAB|NCAAM|NCAAW|College Basketball)\b""" to Sport.COLLEGE_BASKETBALL,
    """\b(?:MLB|MiLB)\b""" to Sport.MLB,
    """\bNHL\b""" to Sport.NHL,
    """\b(?:Soccer|F[uú]tbol|Premier League|EPL|La ?Liga|Serie A|Bundesliga|Ligue 1|MLS|NWSL|UEFA|FIFA|Champions League|Europa League|Liga MX)\b""" to Sport.SOCCER,
    """\b(?:NASCAR|IndyCar|Formula 1|Formula One|F1|Grand Prix|Auto racing|Motorsports|Supercross|MotoGP|Rally|Racing)\b""" to Sport.RACING,
    """\b(?:Golf|PGA|LPGA)\b""" to Sport.GOLF,
    """\b(?:Tennis|ATP|WTA)\b""" to Sport.TENNIS,
    """\b(?:UFC|Boxing|Boxeo|BKFC|MMA|Mixed martial arts|PFL|Wrestling|WWE|AEW)\b""" to Sport.FIGHTING,
    """\b(?:Basketball|Hoops)\b""" to Sport.BASKETBALL,
    """\bBaseball\b""" to Sport.MLB,
    """\bHockey\b""" to Sport.NHL,
).map { Regex(it.first, RegexOption.IGNORE_CASE) to it.second }

internal fun sportOf(text: String): Sport = sportWords.firstOrNull { it.first.containsMatchIn(text) }?.second ?: Sport.OTHER

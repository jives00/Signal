package com.signal.sports

import com.signal.guide.GameTitles

/**
 * Which guide listings are live games (live-sports.md). Measured on the real guide, 2026-11-06: the
 * guide's own live flag is never set, so "Live: " in the title is what says live (replays don't have
 * it). News and shopping have it too, and so do studio shows, so the category decides: games are
 * "Sports"; shows are "Sports talk", or a sport with no "Sports" ("Football", "Series, Football").
 * A listing with no teams also has to name a sport or league in its title: studio shows filed under
 * Sports ("Marty & McGee", "SEC Nation", "The OT") don't (2026-11-07).
 */
object GuideGames {
    private const val LIVE = "Live: "
    private const val SPORTS = "Sports"

    private val talkCategories = setOf("Sports talk", "Talk Show", "News")

    /** Studio shows the guide files under "Sports" all the same, though they name a sport. */
    private val studio = Regex("""\b(?:Football Final|Countdown|Kickoff|Pregame|Pre-Game|Postgame|Post-Game|Scoreboard|Highlights|Now|Tailgate|Wrapup|Nation|Live|Tonight|Central|Insider|Daily|Report|Mornings?|Preview|Review|Goal Zone|Extra|Face-Off|Overtime)\b""", RegexOption.IGNORE_CASE)

    /** Live coverage that isn't one game but is wanted anyway. */
    private val wanted = Regex("""^(?:NFL )?Red ?Zone$""", RegexOption.IGNORE_CASE)

    /** Fight cards can come with only this, and no "Sports". */
    private val fighting = setOf("Boxing", "MMA", "Mixed martial arts", "Wrestling")

    fun isLiveGame(title: String, categories: List<String>): Boolean {
        if (!title.startsWith(LIVE)) return false
        val name = title.removePrefix(LIVE)
        if (wanted.matches(name)) return true
        if (categories.any { it in talkCategories }) return false
        val base = name.substringBefore(" - ")
        if (studio.containsMatchIn(base)) return false
        val sports = SPORTS in categories || (categories.any { it in fighting } && " vs. " in name)
        if (!sports) return false
        // With teams it's a game; without, the title has to say what sport it is.
        // A category can also name one outright ("Cricket"); "Football" alone can't, studio shows carry it.
        return GameTitles.matchup(name) != null || " vs. " in name || Leagues.namesSport(base) ||
            categories.any { it != SPORTS && Leagues.named(it) != null }
    }

    /** The title without the "Live: " the guide gave it. */
    fun cleanTitle(title: String): String = title.removePrefix(LIVE).trim()

    /** The listing's league: from its title and categories, then its teams. */
    fun classify(title: String, categories: List<String>, teams: Teams? = null): Classified =
        Leagues.classify(cleanTitle(title) + " " + categories.filter { it != SPORTS }.joinToString(" "), teams = teams)

    fun sport(title: String, categories: List<String>, teams: Teams? = null): Sport = classify(title, categories, teams).sport
}

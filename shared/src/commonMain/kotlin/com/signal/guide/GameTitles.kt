package com.signal.guide

/**
 * Team names in game titles (PLAN §6 → Game titles): "Live: College Football" becomes
 * "Live: College Football - Colorado State Rams vs. Texas State Bobcats", visiting team first.
 *
 * Guides never put the teams in the title. Some name them in the description's first sentence
 * ("The Colorado State Rams visit the Texas State Bobcats in a Pac-12 game."), which the build reads
 * here, on the device and the NAS alike. The NAS then looks up the rest, which only give the venue,
 * in a schedule feed (the server's GameLookup) and replaces what this found.
 */
object GameTitles {
    data class Matchup(val away: String, val home: String) {
        val text: String get() = "$away vs. $home"
    }

    /** A program that could be a game: a sport in its title or categories, and not a show about one. */
    fun isGame(title: String, categories: List<String>): Boolean {
        if (show.containsMatchIn(title)) return false
        return sport.containsMatchIn(title) || categories.any { sport.containsMatchIn(it) }
    }

    /** The teams the description's opening names, or null. */
    fun fromDescription(description: String?): Matchup? {
        val m = opening.find(description?.trimStart() ?: return null) ?: return null
        val first = m.groupValues[1].clean()
        val second = m.groupValues[3].clean()
        if (first.isEmpty() || second.isEmpty() || first.substringBefore(' ') in notTeams || first == second) return null
        return if (m.groupValues[2].startsWith("host")) Matchup(away = second, home = first) else Matchup(away = first, home = second)
    }

    /** The build's title for a program: the teams added when it's a game and the description names them. */
    fun titled(title: String, categories: List<String>, description: String?): String {
        if (!isGame(title, categories) || namesTeams(title)) return title
        return fromDescription(description)?.let { withMatchup(title, it) } ?: title
    }

    fun withMatchup(title: String, matchup: Matchup): String =
        if (title.contains(matchup.text)) title else "$title$SEPARATOR${matchup.text}"

    /** The teams in a title this named ("NFL Football - Pittsburgh Steelers vs. Cleveland Browns"), or null. */
    fun matchup(title: String): Matchup? {
        if (!hasMatchup(title)) return null
        val teams = title.substringAfterLast(SEPARATOR).split(" vs. ")
        return if (teams.size == 2 && teams.all { it.isNotBlank() }) Matchup(teams[0].trim(), teams[1].trim()) else null
    }

    /**
     * How a team is said (PLAN §4.3.14 → Voice commands, phase 2): its full name, its nickname (the
     * last word) and its place (the rest): "Pittsburgh Steelers", "Steelers", "Pittsburgh".
     */
    fun spokenNames(team: String): List<String> {
        val words = team.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return words
        return listOf(team, words.last(), words.dropLast(1).joinToString(" ")).distinct()
    }

    /** True when the title ends with teams this added (either step). */
    fun hasMatchup(title: String): Boolean = title.substringAfterLast(SEPARATOR, "").contains(" vs. ")

    /** [title] without the teams this added, so better ones can replace them. */
    fun withoutMatchup(title: String): String = if (hasMatchup(title)) title.substringBeforeLast(SEPARATOR) else title

    /** The guide's own title already names the teams ("NFL: Bears vs. Packers", "Bears at Packers"). */
    fun namesTeams(title: String): Boolean = teamsInTitle.containsMatchIn(title)

    private const val SEPARATOR = " - "

    private fun String.clean() = trim().trimEnd('.', ',', ';', ':').trim()

    // A period ends a name ("…the Purdue Boilermakers. Washington aims…") except in an abbreviation
    // ("St. Louis", "No. 4", "N.C. State") or inside a word ("Crypto.com").
    private const val word = """(?:(?:St|Mt|Ft|No|Jr|Sr)\.|(?:\p{Lu}\.){2,}|[\p{Lu}\p{N}][\p{L}\p{N}'’&\-]*(?:\.[\p{L}\p{N}]+)*|\([\p{L}\p{N} .]+\))"""
    private const val team = """$word(?: (?:of |de |del |la )?$word){0,6}"""

    // "X visit(s) the Y", "Y host(s) X", "X at Y", "X vs. Y", "X take(s) on Y", "X face(s) Y".
    private val opening = Regex(
        """^(?:The )?($team) (visits?|hosts?|at|vs\.?|versus|takes? on|faces?|meets?|plays?) (?:the )?($team)(?=$|[ ,.;:!])""",
    )

    private val teamsInTitle = Regex("""\s(?:vs\.?|versus|v\.|at|@)\s\p{Lu}""")

    // A description that starts with one of these is about the broadcast, not two teams.
    private val notTeams = setOf("From", "Live", "Coverage", "Action", "Highlights", "Game", "Week", "Round", "Desde")

    private val sport = Regex(
        """\b(?:Football|Basketball|Baseball|Hockey|Soccer|Volleyball|Softball|Lacrosse|Rugby|Cricket|NFL|NBA|WNBA|MLB|NHL|MLS|NWSL|""" +
            """Premier League|LaLiga|La Liga|Serie A|Bundesliga|Ligue 1|Champions League|Europa League|EFL|Liga MX)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val show = Regex(
        """\b(?:Countdown|Pregame|Pre-Game|Postgame|Post-Game|Post Match|Kickoff|Scoreboard|Leadoff|Lead-Off|Warm-Up|Warmup|Studio|Podcast|Show|Preview|Highlights|Tonight|Today|""" +
            """Inside|Insider|Report|Recap|Talk|GameDay|Nation|Weekly|Classics?|Magazine|Draft)\b""",
        RegexOption.IGNORE_CASE,
    )
}

package com.signal.sports

/** A game's league, and how sure the reading is: the surer one wins when feeds are made one row. */
data class Classified(val sport: Sport, val certainty: Int) {
    companion object {
        /** Nothing to go on. */
        const val NONE = 0
        /** The sport is known, the league assumed from it ("Basketball" on a US channel is college). */
        const val GUESSED = 1
        /** Named in the text, the group or by both teams being one pro league's. */
        const val NAMED = 2
        /** Found in ESPN's scoreboard by teams and time. */
        const val ESPN = 3
    }
}

/**
 * Which league a game is (live-sports.md → Round 2), from the words a channel name or a guide listing
 * uses. Measured on the real names and guide, 2026-11-07: ESPN+ writes "NCAA Men's Basketball:",
 * Paramount+ "English Football League:", Peacock only the teams, and the guide's second category is
 * the sport ("Volleyball", "Cricket", "Bull riding").
 */
internal object Leagues {
    private fun r(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Most specific first: the first that matches is the league. */
    private val named: List<Pair<Regex, Sport>> = listOf(
        // Sports with no filter of their own, before any word they share ("Field Hockey", "Premier League Darts").
        r("""\b(?:Field Hockey|Water Polo|Swimming|Diving|Gymnastics|Pickleball|Bull Riding|PBR|Rodeo|Darts|Snooker|Cycling|Track and Field|Cross Country|Softball|Rowing|Fencing|Skiing|Figure Skating|Equestrian|Horse Racing|Thoroughbred|Badminton|Table Tennis|Handball)\b""") to Sport.OTHER,
        r("""\b(?:Australian Rules|Aussie Rules|AFL)\b""") to Sport.AUSSIE_RULES,
        r("""\b(?:NFL|Red ?Zone|TNF|SNF|MNF|(?:Sunday|Monday|Thursday) Night Football)\b""") to Sport.NFL,
        r("""\b(?:CFL|Canadian Football)\b""") to Sport.CFL,
        r("""\b(?:High School Football|HS Football|Prep Football)\b""") to Sport.HIGH_SCHOOL_FOOTBALL,
        r("""\b(?:NCAAF|NCAA Football|College Football|CFB|FBS|FCS)\b""") to Sport.COLLEGE_FOOTBALL,
        r("""\bWNBA\b""") to Sport.WNBA,
        r("""\b(?:G[ -]League|NBA ?GL)\b""") to Sport.G_LEAGUE,
        r("""\b(?:NCAAW|WBB|(?:NCAA|College) Women['’]?s Basketball|Women['’]?s (?:NCAA |College )?Basketball|Basketball \(W\))""") to Sport.WOMENS_COLLEGE_BASKETBALL,
        r("""\b(?:NCAAM|NCAAB|MBB|(?:NCAA|College) Men['’]?s Basketball|Men['’]?s (?:NCAA |College )?Basketball|College Basketball|NCAA Basketball|Basketball \(M\))""") to Sport.MENS_COLLEGE_BASKETBALL,
        r("""\bNBA\b""") to Sport.NBA,
        r("""\b(?:NBL|EuroLeague|EuroCup|FIBA|Liga Endesa|Lega Basket|BBL Basketball)\b""") to Sport.INTL_BASKETBALL,
        r("""\b(?:College Hockey|NCAA Hockey|(?:Men|Women)['’]?s (?:College )?Hockey)\b""") to Sport.COLLEGE_HOCKEY,
        r("""\bNHL\b""") to Sport.NHL,
        r("""\b(?:KHL|SHL|AHL|ECHL|OHL|WHL|QMJHL|PWHL|IIHF|Liiga)\b""") to Sport.INTL_HOCKEY,
        r("""\b(?:College Baseball|NCAA Baseball)\b""") to Sport.COLLEGE_BASEBALL,
        r("""\b(?:MiLB|Minor League|Triple-A|Double-A|Arizona Fall League)\b""") to Sport.MILB,
        r("""\b(?:MLB|World Series)\b""") to Sport.MLB,
        // Cricket before soccer: "Indian Premier League".
        r("""\b(?:Cricket|ODI|T20I?|T10|Test Match|IPL|Indian Premier League|Big Bash|BBL|WBBL|Ashes|Sheffield Shield|Ranji)\b""") to Sport.CRICKET,
        r("""\b(?:Rugby|Six Nations|Super Rugby|Top 14|URC|NRL|State of Origin|Nations Championship|Nations Cup|Bledisloe|Premiership Rugby|NPC)\b""") to Sport.RUGBY,
        r("""\b(?:Soccer|F[uú]tbol|Premier League|EPL|La ?Liga|Serie A|Bundesliga|Ligue 1|MLS|NWSL|USL|UEFA|FIFA|Champions League|Europa League|Conference League|Liga MX|FA Cup|WSL|Women['’]?s Super League|EFL|English Football League|Carabao Cup|SPFL|Scottish Premiership|Scottish Professional Football|J\.? ?League|J1 League|K League|A-League|Eredivisie|Primeira Liga|Copa|Concacaf|CONMEBOL|Libertadores|Sudamericana|S[uü]per Lig|Saudi Pro League|Coppa Italia|DFB-Pokal|CAF|Supercup|Super Cup)\b""") to Sport.SOCCER,
        r("""\b(?:Volleyball|AVCA)\b""") to Sport.VOLLEYBALL,
        r("""\b(?:Lacrosse|PLL|NLL)\b""") to Sport.LACROSSE,
        r("""\b(?:NASCAR|IndyCar|Formula (?:1|One|2|3|E)|F[123]|Grand Prix|Auto Racing|Motorsports?|Supercross|Motocross|MotoGP|Rally|Le Mans|IMSA|WEC|Superbikes?|Supercars|Dirt Track|Super Dirt|Speedway|GNCC|USAC|Sprint Cars?|Late Models?|NHRA|Drag Racing|Motorcycle Racing)\b""") to Sport.RACING,
        r("""\b(?:Golf|PGA|LPGA|LIV|DP World Tour|Ryder Cup|Solheim Cup)\b""") to Sport.GOLF,
        r("""\b(?:Tennis|ATP|WTA|Davis Cup|Billie Jean King Cup|Wimbledon|Roland Garros)\b""") to Sport.TENNIS,
        r("""\b(?:UFC|Boxing|Boxeo|BKFC|MMA|Mixed Martial Arts|PFL|Wrestling|WWE|AEW|GCW|NJPW|ROH|TNA|Cage Warriors|ONE Championship|Bellator|Kickboxing|Muay Thai|Fight Night|Fights?|LUX)\b""") to Sport.FIGHTING,
    )

    /** "Fútbol Americano" is football, not soccer. */
    private val americano = r("""F[uú]tbol Americano""")

    private val families: List<Pair<Regex, SportFamily>> = listOf(
        r("""\b(?:Basketball|Hoops)\b""") to SportFamily.BASKETBALL,
        r("""\bFootball\b""") to SportFamily.FOOTBALL,
        r("""\bHockey\b""") to SportFamily.HOCKEY,
        r("""\bBaseball\b""") to SportFamily.BASEBALL,
    )

    private val women = r("""\bWomen|\(W\)""")

    private fun clean(text: String) = text.replace(americano, "Football")

    /** A league or sport the text names outright, or null. */
    fun named(text: String): Sport? {
        val t = clean(text)
        return named.firstOrNull { it.first.containsMatchIn(t) }?.second
    }

    private fun family(text: String): SportFamily? {
        val t = clean(text)
        return families.firstOrNull { it.first.containsMatchIn(t) }?.second
    }

    /** True when the text names any sport or league: a listing with no teams has to, to count as a game. */
    fun namesSport(text: String): Boolean = named(text) != null || family(text) != null

    /**
     * The league for a game: what the text names, then the group; both teams of one pro league; the
     * sport, with the league assumed (on US channels, a game that isn't two pro teams is college).
     * [us] is false for an international channel, where football is soccer.
     */
    fun classify(text: String, group: String = "", teams: Teams? = null, us: Boolean = true): Classified {
        val groupText = group.substringAfter('|')
        val fam = family(text) ?: family(groupText)
        val namedSport = named(text) ?: named(groupText)
        if (namedSport != null) {
            // "Basketball:" isn't a league, but a group can be ("US| NBA PPV" with "Basketball: Suns vs Magic").
            return Classified(namedSport, Classified.NAMED)
        }
        if (teams != null) {
            val within = fam?.takeUnless { it == SportFamily.FOOTBALL && !us }
            ProTeams.league(teams.first, teams.second, within)?.let { return Classified(it, Classified.NAMED) }
        }
        val guessed = when (fam) {
            SportFamily.FOOTBALL -> if (us) Sport.COLLEGE_FOOTBALL else Sport.SOCCER
            SportFamily.BASKETBALL -> when {
                women.containsMatchIn(text) -> Sport.WOMENS_COLLEGE_BASKETBALL
                us -> Sport.MENS_COLLEGE_BASKETBALL
                else -> Sport.INTL_BASKETBALL
            }
            SportFamily.HOCKEY -> if (us) Sport.COLLEGE_HOCKEY else Sport.INTL_HOCKEY
            SportFamily.BASEBALL -> if (us) Sport.COLLEGE_BASEBALL else Sport.OTHER
            else -> null
        }
        return guessed?.let { Classified(it, Classified.GUESSED) } ?: Classified(Sport.OTHER, Classified.NONE)
    }
}

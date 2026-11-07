package com.signal.sports

/**
 * Which provider groups feed Live Sports and where a channel is from (live-sports.md). A real source
 * has 918 groups, nearly all prefixed with a country ("US| NFL PPV", "UK| SPORT HD").
 */
object SportsGroups {
    private val prefix = Regex("""^([^|]{1,5})\|""")
    private val namePrefix = Regex("""^\(?(AU|NZ|UK|CA|IE|DE|FR|ES|IT|NL|SE|NO|DK|FI|PT|PL|TR|BR|MX)\)?\s*[:|(]""")
    private val sports = Regex(
        """SPORT|NFL|NBA|NHL|MLB|MILB|MLS|PPV|NCAA|ESPN|DAZN|UFC|SOCCER|RUGBY|TENNIS|GOLF|FOOTBALL|EPL|UEFA|FIFA|FORMULA|F1|SUPERCROSS|EVENT|LEAGUE""",
        RegexOption.IGNORE_CASE,
    )
    private val never = Regex("""24/7|REPLAY|ADULT|MOVIES|CINEMA""", RegexOption.IGNORE_CASE)

    /** Mostly small-college, high-school and years-old events: read only when chosen. */
    private val offByDefault = Regex("""\bFLO\b|NFHS|MATCHROOM|DIRTVISION|THE MASTERS|TEAM PPV""", RegexOption.IGNORE_CASE)

    /** US groups with no games, so the list to look through stays small. */
    private val notGames = Regex("""24/7|ADULT|MOVIES|CINEMA|KIDS|NEWS|DISNEY|HBO MAX|HULU|NETFLIX ON AIR|ORIGINAL|PRIME RAW|ROKU|TUBI|PEACOCK NETWORK|PEACOCK RAW""", RegexOption.IGNORE_CASE)

    /** The group's country prefix. "4K|" and "8K|" are qualities, not places. */
    fun country(group: String): String? = prefix.find(group)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && !it[0].isDigit() }

    /** True when this channel counts as US: in a US group, unless its name says otherwise ("AU (STAN 01) | ..."). */
    fun isUs(group: String, rawName: String): Boolean = namePrefix.find(rawName.trim()) == null && country(group) == "US"

    /** A group that can hold games, by its name. */
    fun isSports(group: String): Boolean = sports.containsMatchIn(group) && !never.containsMatchIn(group)

    /** Whether a group's channel names are read for events ([PpvNames]) unless the profile says otherwise. */
    fun readByDefault(group: String): Boolean = isSports(group) && !offByDefault.containsMatchIn(group)

    /** A group worth asking the guide about: sports anywhere, and US groups but the likes of movies. */
    fun carriesGames(group: String): Boolean = isSports(group) || (country(group) == "US" && !notGames.containsMatchIn(group))
}

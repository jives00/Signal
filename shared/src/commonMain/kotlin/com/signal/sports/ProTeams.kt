package com.signal.sports

import com.signal.match.SearchText

/**
 * The US and Canadian pro leagues' teams (live-sports.md → Round 2), so "Vikings vs. 49ers" is NFL and
 * "Performance View: OKC vs. POR" is NBA with nothing else to go on. A side is a team when its words are
 * exactly a team's nickname, its city and nickname, or one of its abbreviations; a city alone never
 * counts ("Miami vs. Notre Dame" is college). Both sides must be teams of the same league. Nicknames
 * shared across leagues ("Rangers", "Kings") are fine: the pair, and the sport when known, decide.
 */
internal object ProTeams {
    private class Entry(val league: Sport, val abbrs: Set<String>, val names: Set<String>)

    /** "ABR/ABR2 City|Nickname|Other name": the city and nickname make the full name. */
    private val raw: Map<Sport, String> = mapOf(
        Sport.NFL to """
            ARI Arizona|Cardinals; ATL Atlanta|Falcons; BAL Baltimore|Ravens; BUF Buffalo|Bills; CAR Carolina|Panthers;
            CHI Chicago|Bears; CIN Cincinnati|Bengals; CLE Cleveland|Browns; DAL Dallas|Cowboys; DEN Denver|Broncos;
            DET Detroit|Lions; GB Green Bay|Packers; HOU Houston|Texans; IND Indianapolis|Colts; JAX Jacksonville|Jaguars;
            KC Kansas City|Chiefs; LV Las Vegas|Raiders; LAC Los Angeles|Chargers; LAR Los Angeles|Rams; MIA Miami|Dolphins;
            MIN Minnesota|Vikings; NE New England|Patriots; NO New Orleans|Saints; NYG New York|Giants; NYJ New York|Jets;
            PHI Philadelphia|Eagles; PIT Pittsburgh|Steelers; SF San Francisco|49ers|Niners; SEA Seattle|Seahawks;
            TB Tampa Bay|Buccaneers|Bucs; TEN Tennessee|Titans; WSH/WAS Washington|Commanders
        """,
        Sport.NBA to """
            ATL Atlanta|Hawks; BOS Boston|Celtics; BKN/BRK Brooklyn|Nets; CHA Charlotte|Hornets; CHI Chicago|Bulls;
            CLE Cleveland|Cavaliers|Cavs; DAL Dallas|Mavericks|Mavs; DEN Denver|Nuggets; DET Detroit|Pistons;
            GS/GSW Golden State|Warriors; HOU Houston|Rockets; IND Indiana|Pacers; LAC LA|Clippers|Los Angeles Clippers;
            LAL Los Angeles|Lakers|LA Lakers; MEM Memphis|Grizzlies; MIA Miami|Heat; MIL Milwaukee|Bucks;
            MIN Minnesota|Timberwolves|Wolves; NO/NOP New Orleans|Pelicans; NY/NYK New York|Knicks; OKC Oklahoma City|Thunder;
            ORL Orlando|Magic; PHI Philadelphia|76ers|Sixers; PHX/PHO Phoenix|Suns; POR Portland|Trail Blazers|Blazers;
            SAC Sacramento|Kings; SA/SAS San Antonio|Spurs; TOR Toronto|Raptors; UTA/UTAH Utah|Jazz; WSH/WAS Washington|Wizards
        """,
        Sport.WNBA to """
            ATL Atlanta|Dream; CHI Chicago|Sky; CON/CONN Connecticut|Sun; DAL Dallas|Wings; GS/GSV Golden State|Valkyries;
            IND Indiana|Fever; LV/LVA Las Vegas|Aces; LA/LAS Los Angeles|Sparks; MIN Minnesota|Lynx; NY/NYL New York|Liberty;
            PHX/PHO Phoenix|Mercury; SEA Seattle|Storm; WSH/WAS Washington|Mystics; TOR Toronto|Tempo; POR Portland|Fire
        """,
        Sport.NHL to """
            ANA Anaheim|Ducks; BOS Boston|Bruins; BUF Buffalo|Sabres; CGY Calgary|Flames; CAR Carolina|Hurricanes|Canes;
            CHI Chicago|Blackhawks; COL Colorado|Avalanche|Avs; CBJ Columbus|Blue Jackets; DAL Dallas|Stars; DET Detroit|Red Wings;
            EDM Edmonton|Oilers; FLA Florida|Panthers; LA/LAK Los Angeles|Kings|LA Kings; MIN Minnesota|Wild;
            MTL Montreal|Canadiens|Montréal Canadiens|Habs; NSH Nashville|Predators|Preds; NJ/NJD New Jersey|Devils;
            NYI New York|Islanders; NYR New York|Rangers; OTT Ottawa|Senators|Sens; PHI Philadelphia|Flyers;
            PIT Pittsburgh|Penguins|Pens; SJ/SJS San Jose|Sharks; SEA Seattle|Kraken; STL St Louis|Blues; TB/TBL Tampa Bay|Lightning;
            TOR Toronto|Maple Leafs|Leafs; UTA/UTAH Utah|Mammoth; VAN Vancouver|Canucks; VGK/VEG Vegas|Golden Knights;
            WSH/WAS Washington|Capitals|Caps; WPG Winnipeg|Jets
        """,
        Sport.MLB to """
            ARI/AZ Arizona|Diamondbacks|D-backs; ATL Atlanta|Braves; BAL Baltimore|Orioles; BOS Boston|Red Sox; CHC Chicago|Cubs;
            CHW/CWS Chicago|White Sox; CIN Cincinnati|Reds; CLE Cleveland|Guardians; COL Colorado|Rockies; DET Detroit|Tigers;
            HOU Houston|Astros; KC Kansas City|Royals; LAA Los Angeles|Angels; LAD Los Angeles|Dodgers; MIA Miami|Marlins;
            MIL Milwaukee|Brewers; MIN Minnesota|Twins; NYM New York|Mets; NYY New York|Yankees; ATH/OAK Sacramento|Athletics;
            PHI Philadelphia|Phillies; PIT Pittsburgh|Pirates; SD San Diego|Padres; SF San Francisco|Giants; SEA Seattle|Mariners;
            STL St Louis|Cardinals; TB Tampa Bay|Rays; TEX Texas|Rangers; TOR Toronto|Blue Jays; WSH/WAS Washington|Nationals
        """,
        // MLS names are mostly whole names ("Atlanta United"), so the city and nickname are written as one.
        Sport.SOCCER to """
            ATL |Atlanta United; ATX |Austin FC; CLT |Charlotte FC; CHI |Chicago Fire; CIN |FC Cincinnati; COL |Colorado Rapids|Rapids;
            CLB |Columbus Crew|Crew; DAL |FC Dallas; DC |DC United|D.C. United; HOU |Houston Dynamo|Dynamo; LA/LAG |LA Galaxy|Galaxy;
            LAFC |Los Angeles FC|LAFC; MIA |Inter Miami|Inter Miami CF; MIN |Minnesota United; MTL |CF Montréal|CF Montreal;
            NSH |Nashville SC; NE |New England Revolution|Revolution; RBNY/NY |New York Red Bulls|Red Bulls; NYC |New York City FC|NYCFC;
            ORL |Orlando City; PHI |Philadelphia Union|Union; POR |Portland Timbers|Timbers; RSL |Real Salt Lake; SD |San Diego FC;
            SJ |San Jose Earthquakes|Earthquakes; SEA |Seattle Sounders|Sounders; SKC |Sporting Kansas City|Sporting KC;
            STL |St Louis City SC|St Louis City; TOR |Toronto FC; VAN |Vancouver Whitecaps|Whitecaps
        """,
    )

    private val entries: List<Entry> = raw.flatMap { (league, text) ->
        text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
            val abbrs = item.substringBefore(' ').split('/').toSet()
            val parts = item.substringAfter(' ').split('|').map { it.trim() }
            val city = parts[0]
            val nick = parts.getOrNull(1).orEmpty()
            val names = buildSet {
                if (city.isNotEmpty()) add(key("$city $nick"))
                parts.drop(1).forEach { add(key(it)) }
            }
            Entry(league, abbrs, names)
        }
    }

    private val byName: Map<String, List<Entry>> = entries.flatMap { e -> e.names.map { it to e } }.groupBy({ it.first }, { it.second })
    private val byAbbr: Map<String, List<Entry>> = entries.flatMap { e -> e.abbrs.map { it to e } }.groupBy({ it.first }, { it.second })

    private val rank = Regex("""^\s*(?:#\d+|No\. ?\d+)\s+""")
    private val abbr = Regex("""^[A-Z]{2,4}$""")

    private fun key(name: String): String = SearchText.words(name.replace("'", "")).joinToString(" ")

    /** The leagues [side] could be a team of. */
    private fun leaguesOf(side: String): Set<Sport> {
        val text = side.replace(rank, "").trim()
        val found = if (abbr.matches(text)) byAbbr[text] else byName[key(text)]
        return found.orEmpty().mapTo(HashSet()) { it.league }
    }

    /** The one pro league both sides play in, within [family] when that's known; null when there isn't exactly one. */
    fun league(first: String, second: String, family: SportFamily? = null): Sport? {
        val both = leaguesOf(first).intersect(leaguesOf(second)).filter { family == null || it.family == family }
        return both.singleOrNull()
    }
}

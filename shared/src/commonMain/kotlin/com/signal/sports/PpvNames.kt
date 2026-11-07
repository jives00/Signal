package com.signal.sports

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

/**
 * Reads the game out of an event channel's name (live-sports.md). These channels have no guide; the
 * provider renames them for what's on, in a different format per group:
 *
 *     NFL  | 03 - 1pm Cowboys at Colts
 *     NHL 01: 7pm Mammoth @ Canadiens
 *     UEFA | 01 - AC Milan vs Ferencváros 17:45pm
 *     LIVE | ADELAIDE UNITED VS. WELLINGTON PHOENIX | Fri 06 Nov 02:55 EST (US) | 8K EXCLUSIVE | US: ESPN+ PPV 1
 *     Phoenix Suns vs Orlando Magic @ Nov 5 9:00 PM :NBA  01
 *     US (ESPN+ 121) | Chicago State vs. Incarnate Word Nov 05 7:00PM ET (2026-11-05 19:00:00)
 *     UFC event: 1 name: Some Fight Night start:2026-10-31 21:55:00 stop:2026-11-01 02:00:00
 *     PPV EVENT 01: The Dirt Track at Charlotte (11.5 1:15 PM ET)
 *     US (ESPN+ 392) | NCAA Men's Basketball: MORE vs. #11 LOU • UND vs. WIN (MORE vs. #11 LOU) (2026-11-07 12:01:55)
 *
 * The last is a channel carrying several games: each is read, and the one the brackets name is the
 * one on it now (live-sports.md → Round 2).
 *
 * Anything that doesn't read as an event gives null: empty slots, "NO EVENT", finished ones, and
 * channel names like "US: NFL NETWORK ᴴᴰ".
 */
object PpvNames {
    data class Parsed(
        val title: String,
        val teams: Teams?,
        val sport: Sport,
        val start: Long,
        val stop: Long?,
        /** True when only a time of day was given, so [start]'s day is the best guess. */
        val dayGuessed: Boolean = false,
        /** How sure [sport] is ([Classified]). */
        val certainty: Int = Classified.NAMED,
        /** One of several games the name lists, and not the one the brackets say is on. */
        val sharedWithOthers: Boolean = false,
        /** For [Sport.OTHER], the sport the name gave ("Swimming and Diving"). */
        val sportName: String? = null,
    )

    private val eastern = TimeZone.of("America/New_York")
    private val london = TimeZone.of("Europe/London")
    private val sydney = TimeZone.of("Australia/Sydney")

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val weekdays = mapOf(
        "Mon" to DayOfWeek.MONDAY, "Tue" to DayOfWeek.TUESDAY, "Wed" to DayOfWeek.WEDNESDAY, "Thu" to DayOfWeek.THURSDAY,
        "Fri" to DayOfWeek.FRIDAY, "Sat" to DayOfWeek.SATURDAY, "Sun" to DayOfWeek.SUNDAY,
    )

    private val spaces = Regex("""\s+""")
    /** Empty slots, and the two-zone names ("// UK Sat 3 Feb // ET Sat 3 Feb") nothing has updated in years. */
    private val empty = Regex("""NO EVENT|^#|\b2098-| // """, RegexOption.IGNORE_CASE)

    private val pipes = Regex("""^(LIVE|NEXT|END|UPCOMING)\s*\|\s*(.+?)\s*\|(.*)$""", RegexOption.IGNORE_CASE)
    private val pipeDate = Regex("""(\d{1,2}) ([A-Za-z]{3}) (\d{1,2}):(\d{2})""")
    private val pipeNumericDate = Regex("""(\d{2})-(\d{2})-(\d{4})\s*\|\s*(\d{1,2}):(\d{2})\s*\(GMT\)""")

    private val startStop = Regex("""start:(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):\d{2}(?:\s+stop:(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):\d{2})?""")
    private val namedEvent = Regex("""\bname:\s*(.*)$""", RegexOption.IGNORE_CASE)

    private val parenDate = Regex("""^(.*?)\|\s*(.*?)\s*\((\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):\d{2}\)\s*$""")
    private val trailingWhen = Regex("""\s+[A-Z][a-z]{2} \d{1,2} \d{1,2}:\d{2}\s?[AP]M ET\s*$""")
    private val lastBrackets = Regex("""\s*\([^()]*\)\s*$""")

    private val atDate = Regex("""^(.*?)\s@\s([A-Z][a-z]{2}) (\d{1,2}) (\d{1,2}):(\d{2}) ?([AP]M)\b""")
    private val dotDate = Regex("""^(.*?)\((\d{1,2})\.(\d{1,2}) (\d{1,2}):(\d{2}) ([AP]M) ET\)""")

    private val slotLabel = Regex("""^(?:[\p{L}+]+(?: [\p{L}+]+)*\s*\|\s*\w+\s*-|[\p{L}+]+(?: [\p{L}+]+)*\s*\d+\w*\s*[:|-])\s*""")
    private val clock = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*([ap])m\b""", RegexOption.IGNORE_CASE)
    private val broadcast = Regex("""\b(TNF|SNF|MNF)\b""")
    private val dayWord = Regex("""\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\b""")
    private val ukWord = Regex("""\bUK\b""")
    private val ukTime = Regex("""\bUK \d""")

    /** Writers' tags before the game: "UEFA Europa League: ", "NCAAM: ", "Football: ". */
    private val tag = Regex("""^[\p{L}0-9 ()+.'-]{2,32}:\s+""")
    private val teamSplit = Regex("""\s+(?:vs?\.?|@|x)\s+""", RegexOption.IGNORE_CASE)
    private val atSplit = Regex("""\s+at\s+""", RegexOption.IGNORE_CASE)
    private val cityCode = Regex("""^[A-Z]{2,4} - """)

    /** Shows the provider runs on the same channels. */
    private val show = Regex(
        """\b(?:Live$|Studio|Morning|Countdown|Pregame|Postgame|Highlights|Podcast|Press Conference|Recap|Replay|On The Fly|Episode|Tonight|Show)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** What a named event says it is, with no teams and no sport to go on. */
    private val eventWord = Regex("""\b(?:Final|Finals|Semifinal|Championship|Championships|Tournament|Open|Cup|Grand Prix|Nationals|Race|Round)\b""", RegexOption.IGNORE_CASE)
    private val shortForms = setOf("fc", "cf", "afc", "sc", "ac", "fk", "kff", "usa", "lsu", "ucla", "usc")

    private val soccerV = Regex("""\s+v\.?\s+""")

    /** Between the games of a channel carrying several. */
    private val gameSeparator = Regex("""\s*•\s*""")

    /**
     * What an event channel's name says is on, game or not, for showing the channel itself
     * (backlog → PPV channels): a short title, the start, and the stop or a guess at it. Null when
     * the name gives no time. [Event.ended] is set for names the provider marks as over ("End | ...").
     */
    data class Event(
        val title: String,
        /** The writer's tag before the title ("NCAA Men's Basketball"), if any. */
        val tag: String?,
        /** The other games a channel showing several lists. */
        val others: List<String>,
        val start: Long,
        val stop: Long,
        val stopGuessed: Boolean,
        val dayGuessed: Boolean,
        val ended: Boolean,
        /** The game, when the name reads as one. */
        val game: Parsed?,
    ) {
        fun isOver(now: Long) = ended || stop <= now
    }

    fun event(group: String, raw: String, now: Long): Event? {
        val name = normalize(raw)
        if (name.length < 6 || empty.containsMatchIn(name)) return null
        val f = found(group, name, now).firstOrNull() ?: return null
        val games = if (f.ended) emptyList() else build(group, f.text, f.start, f.stop, f.dayGuessed, f.label).orEmpty()
        val game = games.firstOrNull { !it.sharedWithOthers } ?: games.firstOrNull()
        var body = f.text.trim().trimEnd(':', '-', ' ', '|')
        var tagText: String? = null
        // "Football: NFL Football: Prime Vision ..." has two.
        repeat(2) {
            val m = tag.find(body) ?: return@repeat
            val rest = body.substring(m.range.last + 1)
            if (' ' !in rest.trim()) return@repeat
            tagText = body.substring(0, m.range.last + 1).trim().trimEnd(':').trim().takeIf { '+' !in it }
            body = rest.trim()
        }
        body = withoutRepeat(body.substringBefore(" _ ").trim())
        val bracket = lastBrackets.find(body)?.value?.trim()?.removeSurrounding("(", ")")?.trim()
        val bare = body.replace(lastBrackets, "").trim()
        val listed = bare.split(gameSeparator).map { it.trim() }.filter { it.isNotEmpty() }
        val title: String
        var others = emptyList<String>()
        if (listed.size > 1) {
            val on = listed.indexOfFirst { it.equals(bracket, ignoreCase = true) }.coerceAtLeast(0)
            title = listed[on]
            others = listed.filterIndexed { i, _ -> i != on }
        } else {
            // Brackets that repeat the name go; others say more ("(Semifinal)", "(Matchday #12)").
            // The repeat can keep a tag the name lost ("(NFL Football: Prime Vision ...)").
            title = if (bracket != null && (bracket.contains(bare, ignoreCase = true) || bare.contains(bracket, ignoreCase = true))) bare else body
        }
        if (title.length < 3) return null
        val shown = if (title.none { it.isLowerCase() }) titleCase(title).replace(versus, " vs. ") else title
        val stop = f.stop ?: (f.start + typicalLength(game?.sport ?: Sport.OTHER))
        return Event(shown, tagText, others, f.start, stop, f.stop == null, f.dayGuessed, f.ended, game)
    }

    /** "Union (NJ) vs. George Mason (Union (NJ) vs. George Mason)" → the name once. Brackets inside the repeat are why [lastBrackets] can't. */
    private fun withoutRepeat(text: String): String {
        if (!text.endsWith(")")) return text
        var i = text.indexOf('(')
        while (i > 0) {
            val before = text.substring(0, i).trim()
            if (before.isNotEmpty() && text.substring(i).trim().equals("($before)", ignoreCase = true)) return before
            i = text.indexOf('(', i + 1)
        }
        return text
    }

    /**
     * An event channel's slot with nothing in it ("NFL | 01 -", "MLB 03 :", "NO EVENT"), or one whose
     * name nothing has updated in years. They don't play.
     */
    fun isEmptySlot(raw: String): Boolean {
        val name = normalize(raw)
        if (name.startsWith("#")) return false // a heading the provider puts at the top of a group
        if (empty.containsMatchIn(name)) return true
        val label = slotLabel.find(name)
        if (label != null && name.substring(label.range.last + 1).trim(' ', ':', '-', '|').isEmpty()) return true
        return openSlot.matches(name)
    }

    /** How long a game usually runs, for names that give only a start. */
    fun typicalLength(sport: Sport): Long = when (sport.family) {
        SportFamily.FOOTBALL, SportFamily.RACING, SportFamily.GOLF, SportFamily.FIGHTING, SportFamily.CRICKET -> 4 * HOUR_MS
        SportFamily.BASEBALL, SportFamily.BASKETBALL, SportFamily.HOCKEY -> 3 * HOUR_MS
        else -> 5 * HOUR_MS / 2
    }

    private const val HOUR_MS = 3_600_000L
    private val versus = Regex("""\s+Vs\.?\s+""")
    /** "US (ESPN+ 123) |" with nothing after it. */
    private val openSlot = Regex("""^[^|]{1,30}\|\s*$""")

    private fun normalize(raw: String) = raw.replace('`', '\'').replace(spaces, " ").trim()

    /** The game the channel shows, or the first it lists. */
    fun parse(group: String, raw: String, now: Long): Parsed? = parseAll(group, raw, now).let { all -> all.firstOrNull { !it.sharedWithOthers } ?: all.firstOrNull() }

    /** Every game the name lists: one, or several for a channel that shows them in turn. */
    fun parseAll(group: String, raw: String, now: Long): List<Parsed> {
        val name = normalize(raw)
        if (name.length < 6 || empty.containsMatchIn(name)) return emptyList()
        for (f in found(group, name, now)) {
            if (f.ended) continue
            build(group, f.text, f.start, f.stop, f.dayGuessed, f.label)?.let { return it }
        }
        return emptyList()
    }

    /** What a name's date and time were read from: the text around them, and when. */
    private class Found(val text: String, val start: Long, val stop: Long?, val dayGuessed: Boolean, val label: String = "", val ended: Boolean = false)

    /** Each way of reading a date out of [name] that fits it, in the order they're trusted. */
    private fun found(group: String, name: String, now: Long): Sequence<Found> {
        val zone = zoneFor(group, name)
        return sequenceOf<() -> Found?>(
            { fromPipes(name, now) },
            { fromStartStop(name) },
            { fromParenDate(name, zone) },
            { fromAtDate(name, now) },
            { fromDotDate(name, now) },
            { fromClock(group, name, now, zone) },
        ).mapNotNull { it() }
    }

    /** UEFA and rugby slots are written in UK time, and AU channels in Sydney's. */
    private fun zoneFor(group: String, name: String): TimeZone = when {
        name.startsWith("AU ") -> sydney
        group.startsWith("UK|") || group.contains("UEFA") || group.contains("RUGBY") || ukTime.containsMatchIn(name) -> london
        else -> eastern
    }

    private fun fromPipes(name: String, now: Long): Found? {
        val m = pipes.find(name) ?: return null
        val status = m.groupValues[1].uppercase()
        val rest = m.groupValues[3]
        val written = pipeDate.find(rest)?.let { d ->
            val month = months.indexOf(d.groupValues[2].lowercase())
            if (month < 0) null else closest(now, month + 1, d.groupValues[1].toInt(), d.groupValues[3].toInt(), d.groupValues[4].toInt(), eastern)
        } ?: pipeNumericDate.find(rest)?.let { d ->
            at(d.groupValues[3].toInt(), d.groupValues[2].toInt(), d.groupValues[1].toInt(), d.groupValues[4].toInt(), d.groupValues[5].toInt(), TimeZone.UTC)
        }
        // "Live | ... " with no date is on, as of the last channel list.
        val start = written ?: if (status == "LIVE" || status == "END") now else return null
        return Found(m.groupValues[2], start, null, false, ended = status == "END")
    }

    private fun fromStartStop(name: String): Found? {
        val m = startStop.find(name) ?: return null
        val v = m.groupValues
        val start = at(v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt(), v[5].toInt(), eastern)
        val stop = if (v[6].isEmpty()) null else at(v[6].toInt(), v[7].toInt(), v[8].toInt(), v[9].toInt(), v[10].toInt(), eastern)
        val before = name.substring(0, m.range.first).trim()
        // "UFC event: 1 name: ...", or a slot label ("MLB 01 | Brewers x Rays").
        val text = namedEvent.find(before)?.groupValues?.get(1) ?: before.substringAfter(':', "").ifBlank { slotLabel.replace(before, "") }
        return Found(text, start, stop, false)
    }

    private fun fromParenDate(name: String, zone: TimeZone): Found? {
        val v = (parenDate.find(name) ?: return null).groupValues
        val start = at(v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt(), v[7].toInt(), zone)
        return Found(v[2].replace(trailingWhen, ""), start, null, false)
    }

    private fun fromAtDate(name: String, now: Long): Found? {
        val v = (atDate.find(name) ?: return null).groupValues
        val month = months.indexOf(v[2].lowercase())
        if (month < 0) return null
        return Found(v[1], closest(now, month + 1, v[3].toInt(), hour24(v[4].toInt(), v[6]), v[5].toInt(), eastern), null, false)
    }

    private fun fromDotDate(name: String, now: Long): Found? {
        val v = (dotDate.find(name) ?: return null).groupValues
        val text = slotLabel.replace(v[1].trim(), "")
        return Found(text, closest(now, v[2].toInt(), v[3].toInt(), hour24(v[4].toInt(), v[6]), v[5].toInt(), eastern), null, false)
    }

    /** "NFL | 03 - 1pm Cowboys at Colts": a slot number and a time of day, and usually no date. */
    private fun fromClock(group: String, name: String, now: Long, zone: TimeZone): Found? {
        if (!slotLabel.containsMatchIn(name)) return null
        val body = slotLabel.replace(name, "")
        val time = clock.find(body) ?: return null
        val written = time.groupValues[1].toInt()
        val hour = hour24(written, time.groupValues[3].uppercase() + "M")
        val minute = time.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        val text = body.removeRange(time.range).replace(broadcast, "").replace(dayWord, "").replace(ukWord, "").replace(spaces, " ").trim()
        val day = when (broadcast.find(body)?.value) {
            "TNF" -> DayOfWeek.THURSDAY
            "MNF" -> DayOfWeek.MONDAY
            "SNF" -> DayOfWeek.SUNDAY
            else -> dayWord.find(body)?.value?.let { weekdays[it] } ?: DayOfWeek.SUNDAY.takeIf { sportFor(group, text, null).sport == Sport.NFL }
        }
        return Found(text, nearest(now, day, hour, minute, zone), null, true, name)
    }

    private fun build(group: String, text: String, start: Long, stop: Long?, dayGuessed: Boolean, label: String = ""): List<Parsed>? {
        var body = text.trim().trimEnd(':', '-', ' ')
        // A slot's label can name the sport ("Boxing 3 | Canelo vs Mbilli"), and so can a tag.
        val whole = "$body $label"
        val named = sportFor(group, whole, null)
        // A tag isn't part of the game, though it can name the sport. One word left isn't a name
        // ("SÃO PAULO: RACE"), so then the tag stays.
        tag.find(body)?.let { m -> body.substring(m.range.last + 1).takeIf { ' ' in it.trim() }?.let { body = it } }
        // The last brackets repeat the name or give a language; " _ " starts a note ("_ Women's").
        body = body.substringBefore(" _ ")
        val bracket = lastBrackets.find(body)?.value?.trim()?.removeSurrounding("(", ")")?.trim()
        body = body.replace(lastBrackets, "").trim()
        val games = body.split(gameSeparator).map { it.trim() }.filter { it.isNotEmpty() }
        if (games.size > 1) {
            // Several games on one channel: the brackets name the one on now.
            val on = games.indexOfFirst { it.equals(bracket, ignoreCase = true) }.coerceAtLeast(0)
            return games.mapIndexedNotNull { i, g -> game(group, g, whole, named, start, stop, dayGuessed)?.copy(sharedWithOthers = i != on) }.ifEmpty { null }
        }
        return game(group, body, whole, named, start, stop, dayGuessed)?.let { listOf(it) }
    }

    private fun game(group: String, body: String, whole: String, named: Classified, start: Long, stop: Long?, dayGuessed: Boolean): Parsed? {
        if (body.length < 4 || show.containsMatchIn(body)) return null
        val shouting = body.none { it.isLowerCase() }
        fun cased(s: String) = if (shouting) titleCase(s) else s
        val teams = teams(body, named.sport, group)?.let { Teams(cased(it.first), cased(it.second)) }
        // Named outright beats the teams; otherwise the teams can say (both one pro league's).
        var sport = if (teams != null && named.certainty < Classified.NAMED) sportFor(group, whole, teams) else named
        // US writers put "v." only between soccer teams ("Arsenal v. Hull City" on Peacock); event groups' fights use it too.
        if (teams != null && sport.sport == Sport.OTHER && "EVENT" !in group && soccerV.containsMatchIn(body)) sport = Classified(Sport.SOCCER, Classified.GUESSED)
        if (teams != null) return Parsed("${teams.first} vs. ${teams.second}", teams, sport.sport, start, stop, dayGuessed, sport.certainty, sportName = sport.label)
        // No teams: a race, a fight or a tournament, but not a team game with no name for it, or a show.
        val event = eventWord.containsMatchIn(body)
        if ((sport.sport.teamSport && !event) || (sport.sport == Sport.OTHER && "EVENT" !in group && !event)) return null
        return Parsed(cased(body), null, sport.sport, start, stop, dayGuessed, sport.certainty, sportName = sport.label)
    }

    /** The name's league; US channels' unless the group or the name says another country. */
    private fun sportFor(group: String, text: String, teams: Teams?): Classified =
        Leagues.classify(text, group, teams, us = SportsGroups.country(group).let { it == null || it == "US" } && !text.trimStart().startsWith("AU "))

    private fun teams(text: String, sport: Sport, group: String): Teams? {
        val parts = text.split(teamSplit, limit = 2).takeIf { it.size == 2 }
            // "at" also ends place names ("The Dirt Track at Charlotte"), which event groups have.
            ?: text.split(atSplit, limit = 2).takeIf { it.size == 2 && (sport.teamSport || "EVENT" !in group) }
            ?: return null
        val a = parts[0].replace(cityCode, "").trim()
        val b = parts[1].replace(cityCode, "").trim()
        return if (a.length < 2 || b.length < 2) null else Teams(a, b)
    }

    /** "ADELAIDE UNITED FC" → "Adelaide United FC". Only for names written all in capitals. */
    private fun titleCase(text: String): String {
        return text.lowercase().split(' ').joinToString(" ") { w -> if (w in shortForms) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
    }

    private fun hour24(hour: Int, half: String): Int = when {
        hour > 12 -> hour
        half == "AM" -> if (hour == 12) 0 else hour
        else -> if (hour == 12) 12 else hour + 12
    }

    /** A date with no year: the year that puts it closest to [now]. */
    private fun closest(now: Long, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long {
        val year = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).year
        return listOf(year - 1, year, year + 1).mapNotNull { y -> runCatching { at(y, month, day, hour, minute, zone) }.getOrNull() }
            .minByOrNull { abs(it - now) } ?: now
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone): Long =
        LocalDateTime(year, month, day, hour.coerceAtMost(23), minute).toInstant(zone).toEpochMilliseconds()

    /** The time of day on [day], or on any day, closest to [now]. */
    private fun nearest(now: Long, day: DayOfWeek?, hour: Int, minute: Int, zone: TimeZone): Long {
        val today: LocalDate = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        return (-3..3).map { today.plus(it, DateTimeUnit.DAY) }
            .filter { day == null || it.dayOfWeek == day }
            .map { it.atTime(hour, minute).toInstant(zone).toEpochMilliseconds() }
            .minByOrNull { abs(it - now) } ?: now
    }
}

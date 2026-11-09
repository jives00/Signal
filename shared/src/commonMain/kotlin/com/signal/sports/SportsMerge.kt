package com.signal.sports

import com.signal.match.SearchText
import kotlin.math.abs

/** A game as one feed found it, before games are made one row each. */
internal data class Found(
    val sport: Sport,
    val title: String,
    val detail: String?,
    val teams: Teams?,
    val start: Long,
    val stop: Long,
    val stopGuessed: Boolean,
    /** Only a time of day was given, so it can match a game on another day. */
    val dayGuessed: Boolean,
    val channels: List<EventChannel>,
    /** How sure [sport] is ([Classified]): the surest feed's league wins on the row. */
    val certainty: Int = Classified.NAMED,
    /** For [Sport.OTHER], the sport a feed named ("Field Hockey"). */
    val sportName: String? = null,
)

/**
 * One row per game (live-sports.md): "New York Jets vs. Kansas City Chiefs" in the guide, "Jets at
 * Chiefs" on an event channel and "Chiefs vs. Jets" on its backup are the same game. Two teams match
 * when one's words are all in the other's. Games with no teams match by title and start.
 */
internal object SportsMerge {
    /** The same two teams this close together is one game; feeds' start times differ by their pre-shows. */
    private const val SAME_GAME_MS = 4 * 3_600_000L
    private const val GUESSED_DAY_MS = 4 * 24 * 3_600_000L

    private val filler = setOf("fc", "cf", "afc", "sc", "the", "of", "university", "univ", "club")

    private class Row(val first: Found, val firstWords: Set<String>, val secondWords: Set<String>) {
        val channels = LinkedHashMap<String, EventChannel>().apply { first.channels.forEach { put(it.streamId, it) } }
        var stop = first.stop
        var start = first.start
        var stopGuessed = first.stopGuessed
        var dayGuessed = first.dayGuessed
        var sport = first.sport
        var certainty = first.certainty
        var sportName = first.sportName
        var teams = first.teams
    }

    fun merge(found: List<Found>, maxChannels: Int = 40): List<SportEvent> {
        val byWord = HashMap<String, MutableList<Row>>()
        val byTitle = HashMap<String, MutableList<Row>>()
        val rows = ArrayList<Row>()
        // Sure times first, so a guess joins them and not the reverse; then the English title.
        val ordered = found.sortedWith(compareBy({ it.dayGuessed }, { it.stopGuessed }, { foreign(it.title) }))
        for (f in ordered) {
            val first = f.teams?.let { words(it.first) }.orEmpty()
            val second = f.teams?.let { words(it.second) }.orEmpty()
            val row = if (first.isNotEmpty() && second.isNotEmpty()) {
                val within = if (f.dayGuessed) GUESSED_DAY_MS else SAME_GAME_MS
                first.flatMap { byWord[it].orEmpty() }.distinct()
                    .filter { abs(it.start - f.start) <= within && sameGame(it, first, second) }
                    .minByOrNull { abs(it.start - f.start) }
            } else {
                byTitle[titleKey(f)]?.firstOrNull { abs(it.start - f.start) <= SAME_GAME_MS }
            }
            if (row != null) {
                f.channels.forEach { row.channels.getOrPut(it.streamId) { it } }
                // A listing's end beats a guess at one.
                if (row.stopGuessed && !f.stopGuessed) { row.stop = f.stop; row.stopGuessed = false }
                if (row.dayGuessed && !f.dayGuessed) { row.start = f.start; row.dayGuessed = false }
                // The surest league, and ESPN's teams with their logos (live-sports.md → Round 2).
                if (f.certainty > row.certainty) { row.sport = f.sport; row.certainty = f.certainty; row.sportName = f.sportName ?: row.sportName }
                if (row.sportName == null) row.sportName = f.sportName
                if (f.teams?.hasLogos == true && row.teams?.hasLogos != true) row.teams = f.teams
            } else {
                val added = Row(f, first, second)
                rows += added
                (first + second).forEach { byWord.getOrPut(it) { ArrayList(2) } += added }
                if (first.isEmpty() || second.isEmpty()) byTitle.getOrPut(titleKey(f)) { ArrayList(1) } += added
            }
        }
        return rows.map { r ->
            val f = r.first
            SportEvent(
                key = "${SearchText.fold(f.title)}|${f.detail.orEmpty().take(24)}|${r.start}",
                sport = r.sport, sportName = r.sportName.takeIf { r.sport == Sport.OTHER }, title = f.title, detail = f.detail, teams = r.teams,
                start = r.start, stop = r.stop, stopGuessed = r.stopGuessed,
                channels = trim(r.channels.values.sortedWith(best), maxChannels),
            )
        }
    }

    /**
     * The best [max] channels, keeping event channels: a national game is on every affiliate, and 40
     * NBC stations with the listing pushed both NFL PPV channels off Sunday Night Football (2026-11-08).
     * Up to half the places go to channels whose name says they carry this game; the rest keep [best]'s order.
     */
    private fun trim(sorted: List<EventChannel>, max: Int): List<EventChannel> {
        if (sorted.size <= max) return sorted
        val named = sorted.filter { it.isEvent }.take(max / 2).toSet()
        val rest = sorted.filter { it !in named }.take(max - named.size).toSet()
        return sorted.filter { it in named || it in rest }
    }

    /**
     * A favorite first, then an event channel for this game (named for it, so made for it, where a
     * national game is also on every affiliate), then a channel showing only this game, then a visible
     * one, then one with a guide, then the provider's order.
     */
    private val best = compareBy<EventChannel>({ !it.favorite }, { !it.isEvent }, { it.sharedWithOthers }, { it.hidden }, { it.program == null }, { it.order })

    private val EventChannel.isEvent get() = program == null && !sharedWithOthers

    private fun foreign(title: String) = title.startsWith("Fútbol") || title.startsWith("Futbol") || title.startsWith("Boxeo")

    /** A title with no teams is the same for every game that day, so its detail tells them apart. */
    private fun titleKey(f: Found) = SearchText.fold(f.title) + "|" + f.detail.orEmpty().take(60)

    private fun words(team: String): Set<String> = SearchText.words(team).filterNotTo(LinkedHashSet()) { it in filler }

    private fun sameGame(row: Row, first: Set<String>, second: Set<String>): Boolean =
        (sameTeam(row.firstWords, first) && sameTeam(row.secondWords, second)) ||
            (sameTeam(row.firstWords, second) && sameTeam(row.secondWords, first))

    private fun sameTeam(a: Set<String>, b: Set<String>): Boolean = a.isNotEmpty() && b.isNotEmpty() && (a.containsAll(b) || b.containsAll(a))
}

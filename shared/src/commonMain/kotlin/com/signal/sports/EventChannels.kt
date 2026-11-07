package com.signal.sports

import com.signal.data.synchronized
import com.signal.model.Program
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Event channels (PPV and the like) shown for what they carry (backlog → PPV channels). Their names
 * hold the event and its time, and they have no guide, so the name is read ([PpvNames.event]):
 *
 * - the channel's shown name is the event's short title, not the whole name;
 * - with no guide, the event is its program, so the guide, info bar and recordings have it;
 * - empty slots and events that are over are hidden from the channel lists.
 *
 * When ESPN has the game ([update]), its word wins over the name's guesses: a game in progress stays
 * however long it runs, a final one goes, and the start is ESPN's.
 *
 * Only channels in groups that can hold games are read ([SportsGroups.isSports]), and only while
 * the profile's name cleanup has [com.signal.match.NameCleanup.events] on. The device and the
 * companion both use this.
 */
object EventChannels {
    /** Marks the programs made here; real guide programs come from an EPG source. */
    const val EPG_SOURCE = "event-name"

    private val lock = Any()
    private var espn = EspnMatch.Index(emptyList())
    private var states: Map<String, GameState?> = emptyMap()
    private val changed = MutableStateFlow(0)

    /** Goes up when ESPN says a game started or ended, so channel lists look again. */
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** ESPN's latest games: the TV's [SportsScores] and the companion's schedule keep this current. */
    fun update(games: List<EspnGame>) {
        val next = games.associate { it.key to it.state }
        val index = EspnMatch.Index(games)
        val moved = synchronized(lock) {
            espn = index
            (next != states).also { if (it) states = next }
        }
        if (moved) changed.value++
    }

    /** The event [rawName] carries, if its group is one that holds events. */
    fun event(group: String, rawName: String, now: Long): PpvNames.Event? =
        if (!SportsGroups.isSports(group)) null else PpvNames.event(group, rawName, now)

    /** The name to show: the event's title, or null to keep the usual name. */
    fun shortName(group: String, rawName: String, now: Long): String? = event(group, rawName, now)?.title

    /** ESPN's game for [e], when its teams and time match one, and whether its home team is written first. */
    fun espnGame(e: PpvNames.Event): Pair<EspnGame, Boolean>? {
        val game = e.game ?: return null
        val teams = game.teams ?: return null
        val index = synchronized(lock) { espn }
        if (index.games.isEmpty()) return null
        return EspnMatch.game(index, teams, game.sport, game.certainty, e.start, e.dayGuessed)
    }

    /** True for an event channel with nothing on it, or nothing still to come: an empty slot, or an event that's over. */
    fun hides(group: String, rawName: String, now: Long): Boolean {
        if (!SportsGroups.isSports(group)) return false
        if (PpvNames.isEmptySlot(rawName)) return true
        val e = PpvNames.event(group, rawName, now) ?: return false
        if (e.ended) return true
        // ESPN knows: hidden once it's final, kept while it's on however long it runs.
        espnGame(e)?.let { (g, _) -> return g.state == GameState.POST }
        return e.isOver(now)
    }

    /** The event as a program on the channel [streamId], or null when its name doesn't give one or it's over. */
    fun program(streamId: String, group: String, rawName: String, now: Long): Program? {
        val e = event(group, rawName, now)?.takeIf { !it.ended } ?: return null
        val found = espnGame(e)?.first
        if (found?.state == GameState.POST) return null
        val start = found?.start ?: e.start
        val stop = found?.let { EspnMatch.stopFor(it, it.start + (e.stop - e.start), now) } ?: e.stop
        val about = buildList {
            if (e.others.isNotEmpty()) add("Also on this channel: ${e.others.joinToString(", ")}.")
            if (found == null && e.dayGuessed) add("The channel's name gives only the time, so the day is a guess.")
            if (found?.state != GameState.IN && e.stopGuessed) add("The end time is a guess.")
        }.joinToString(" ").ifEmpty { null }
        val league = e.tag ?: e.game?.sportName ?: (found?.sport ?: e.game?.sport)?.takeIf { it != Sport.OTHER }?.label
        return Program(
            // Negative, so never a guide.db row's.
            id = -1L - abs(streamId.hashCode().toLong()),
            epgSourceId = EPG_SOURCE,
            guideChannelId = streamId,
            start = start,
            stop = stop,
            title = e.title,
            // "NFL · Q3 5:12" while it's on; the score is beside the teams' logos.
            subTitle = listOfNotNull(league, found?.detail?.takeIf { found.state == GameState.IN }).joinToString(" · ").ifEmpty { null },
            description = about,
            categories = if (e.game != null) listOf("Sports") else emptyList(),
            isLive = found?.state == GameState.IN,
        )
    }
}

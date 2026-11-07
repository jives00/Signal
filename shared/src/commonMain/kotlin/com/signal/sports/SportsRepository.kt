package com.signal.sports

import com.signal.data.Settings
import com.signal.db.guide.GuideDatabase
import com.signal.db.user.UserDatabase
import com.signal.guide.GameTitles
import com.signal.guide.GuideBuilder
import com.signal.guide.GuideRepository
import com.signal.guide.GuideStore
import com.signal.model.Program
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Live Sports page's games (live-sports.md): guide listings on any channel in reach, hidden or
 * not, plus event channels read by name. Hidden sources stay out (the groups query joins
 * active_source). Channels and listings are kept between looks: a game's progress changes every
 * minute and they don't.
 */
class SportsRepository(
    private val db: UserDatabase,
    private val settings: Settings,
    private val store: GuideStore,
    private val guide: GuideRepository,
    private val io: CoroutineDispatcher,
    private val guideIo: CoroutineDispatcher,
    /** Goes up with every change to channels, so what's kept here knows it's stale. */
    private val channelsVersion: () -> Int,
    /** ESPN's games, for leagues, team names and logos (live-sports.md → Round 2); null in tests that don't need them. */
    private val scores: SportsScores? = null,
    private val log: ((String) -> Unit)? = null,
) {
    private class Row(val channel: EventChannel, val rawName: String, val tvgId: String?, val userChannelId: String?, val read: Boolean)

    private class Listing(val program: Program, val categories: List<String>, val teams: Teams?, val title: String, val detail: String?)

    private val lock = Mutex()
    private var rows: List<Row>? = null
    private var rowsState: Any? = null
    private var byGuide: Map<String, List<Row>>? = null
    private var byGuideState: Any? = null
    private var listings: List<Listing>? = null
    private var listingsState: Any? = null

    /** The page's place, kept here because it's rebuilt on the way back from full screen. */
    var lastEvent: String? = null
    var lastEvents: List<SportEvent>? = null

    /** The profile's Live Sports settings. */
    fun settings(): SportsSettings = SportsSettings.parse(settings.raw(Settings.Key.SPORTS))

    fun save(value: SportsSettings) = settings.put(Settings.Key.SPORTS, value.encode())

    /** This TV's filters. */
    fun filters(): SportsFilters = SportsFilters.parse(settings.raw(Settings.Key.SPORTS_FILTERS))

    fun save(value: SportsFilters) = settings.put(Settings.Key.SPORTS_FILTERS, value.encode())

    /** Every game for this TV's region from now on. Slow the first time; quick after that until something changes. */
    suspend fun events(now: Long): List<SportEvent> = lock.withLock {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val s = settings()
        val all = rows(s, filters().region)
        val map = guideMap(all)
        val found = ArrayList<Found>()
        guideGames(now, map, found)
        val espn = scores?.index()
        withContext(guideIo) {
            nameGames(all, now, found)
            val named = espn?.let { EspnMatch.apply(found, it, now) } ?: found
            SportsMerge.merge(named).filter { it.stop > now && s.shows(it) }
        }.also { log?.invoke("Live Sports: ${it.size} games from ${all.size} channels in ${started.elapsedNow().inWholeMilliseconds} ms") }
    }

    /** The groups Live Sports can read in this TV's region, with whether the profile does. For Settings. */
    suspend fun groups(): List<Pair<String, Boolean>> = withContext(io) {
        val s = settings()
        val region = filters().region
        db.streamQueries.groups().executeAsList().map { it.group_name }.distinct()
            .filter { SportsGroups.isSports(it) && inRegion(it, region) }
            .map { it to s.reads(it) }
    }

    private fun inRegion(group: String, region: SportsRegion) = region == SportsRegion.WORLDWIDE || SportsGroups.country(group) == "US"

    private suspend fun rows(s: SportsSettings, region: SportsRegion): List<Row> {
        val state = listOf(s, region, settings.profileId, channelsVersion())
        rows?.takeIf { rowsState == state }?.let { return it }
        val built = withContext(io) {
            val out = ArrayList<Row>()
            var order = 0
            for (g in db.streamQueries.groups().executeAsList()) {
                val group = g.group_name
                if (!inRegion(group, region)) continue
                val read = s.reads(group)
                if (!read && !SportsGroups.carriesGames(group)) continue
                for (r in db.sportsQueries.sportsChannels(settings.profileId, g.source_id, group).executeAsList()) {
                    if (region == SportsRegion.US && !SportsGroups.isUs(group, r.raw_name)) continue
                    val channel = EventChannel(
                        streamId = r.id, sourceId = r.source_id, name = r.custom_name ?: r.display_name, group = group,
                        hidden = r.hidden == 1L || r.group_hidden == 1L, favorite = r.is_favorite, order = order++,
                    )
                    out += Row(channel, r.raw_name, r.tvg_id, r.user_channel_id, read)
                }
            }
            out
        }
        rows = built
        rowsState = state
        byGuide = null
        return built
    }

    private suspend fun guideMap(all: List<Row>): Map<String, List<Row>> {
        val state = guide.state.value
        byGuide?.takeIf { byGuideState == state }?.let { return it }
        val built = withContext(guideIo) {
            val out = HashMap<String, MutableList<Row>>()
            for (r in all) {
                val ref = guide.resolve(r.channel.streamId, r.userChannelId, r.tvgId, r.rawName) ?: continue
                out.getOrPut("${ref.epgSourceId}|${ref.guideChannelId}") { ArrayList(2) } += r
            }
            out
        }
        byGuide = built
        byGuideState = state
        return built
    }

    private suspend fun guideGames(now: Long, map: Map<String, List<Row>>, into: MutableList<Found>) {
        val db = store.db ?: return
        val state = guide.state.value
        val all = listings?.takeIf { listingsState == state } ?: withContext(guideIo) { readListings(db, now, state) }.also {
            listings = it
            listingsState = state
        }
        withContext(guideIo) {
            val channels = LinkedHashMap<String, MutableList<EventChannel>>()
            val firsts = HashMap<String, Listing>()
            for (l in all) {
                if (l.program.stop + OVERTIME_MS <= now) continue // ESPN can say it runs on; the rest go after merging
                val on = map["${l.program.epgSourceId}|${l.program.guideChannelId}"] ?: continue
                val key = "${l.title}|${l.program.start}|${l.detail}"
                firsts.getOrPut(key) { l }
                val list = channels.getOrPut(key) { ArrayList(2) }
                on.forEach { list += it.channel.copy(program = l.program) }
            }
            channels.forEach { (key, list) ->
                val l = firsts.getValue(key)
                // Football on a channel from abroad is soccer.
                val us = list.any { SportsGroups.country(it.group).let { c -> c == null || c == "US" } }
                val c = Leagues.classify(l.title + " " + l.categories.filter { it != "Sports" }.joinToString(" "), teams = l.teams, us = us)
                into += Found(c.sport, l.title, l.detail, l.teams, l.program.start, l.program.stop, stopGuessed = false, dayGuessed = false, channels = list, certainty = c.certainty, sportName = c.label)
            }
        }
    }

    private fun readListings(db: GuideDatabase, now: Long, state: GuideRepository.State): List<Listing> {
        val shifts = state.sources.associate { it.id to it.timeShiftMs }
        return db.sportsQueries.liveListings(now - DAY_MS, now + WEEK_MS).executeAsList().mapNotNull { r ->
            val categories = r.categories?.split(GuideBuilder.SEPARATOR).orEmpty()
            if (!GuideGames.isLiveGame(r.title, categories)) return@mapNotNull null
            val shift = shifts[r.epg_source_id] ?: 0
            val title = GuideGames.cleanTitle(r.title)
            val matchup = GameTitles.matchup(title)
            // Without teams the title is the same for every game that day, so the description tells them apart.
            val detail = if (matchup == null) r.description?.substringBefore(". ")?.trim()?.take(DETAIL_LENGTH) else null
            val program = Program(
                id = r.id, epgSourceId = r.epg_source_id, guideChannelId = r.guide_channel_id, start = r.start + shift, stop = r.stop + shift,
                title = r.title, description = r.description, categories = categories,
            )
            Listing(program, categories, matchup?.let { Teams(it.away, it.home) }, title, detail)
        }
    }

    private fun nameGames(all: List<Row>, now: Long, into: MutableList<Found>) {
        for (r in all) {
            if (!r.read) continue
            for (p in PpvNames.parseAll(r.channel.group, r.rawName, now)) {
                val stop = p.stop ?: (p.start + PpvNames.typicalLength(p.sport))
                if (stop + OVERTIME_MS <= now || p.start > now + WEEK_MS) continue
                val channel = if (p.sharedWithOthers) r.channel.copy(sharedWithOthers = true) else r.channel
                into += Found(p.sport, p.title, null, p.teams, p.start, stop, stopGuessed = p.stop == null, dayGuessed = p.dayGuessed, channels = listOf(channel), certainty = p.certainty, sportName = p.sportName)
            }
        }
    }

    private var imagesRaw: String? = null
    private var images: Map<String, String> = emptyMap()

    /** The image for a league or sport, from the household setting sport_images; null draws a badge. Read during composition, so from memory. */
    fun image(sport: Sport): String? {
        val raw = settings.raw(Settings.Key.SPORT_IMAGES)
        if (raw != imagesRaw) { images = SportImages.parse(raw); imagesRaw = raw }
        return SportImages.url(sport, images)
    }

    /**
     * The teams, with ESPN's logos, status and score, of the game on [streamId] now, for the info bar
     * and zap banner: ESPN found by the listing's teams (a guide game, or an event channel's own
     * program), else the page's own row for the channel. Null for anything that isn't a game ESPN knows.
     */
    suspend fun teamsOn(streamId: String, program: Program?, now: Long): Teams? {
        val sides = program?.let { p ->
            when {
                p.epgSourceId == EventChannels.EPG_SOURCE -> eventTeams.find(p.title)?.let { it.groupValues[1] to it.groupValues[2] }
                GuideGames.isLiveGame(p.title, p.categories) -> GameTitles.matchup(GuideGames.cleanTitle(p.title))?.let { it.away to it.home }
                else -> null
            }
        }
        if (sides != null) {
            val index = scores?.index(waitMs = 3_000)
            val found = index?.let { withContext(guideIo) { EspnMatch.find(it, sides.first, sides.second, program.start) } }?.takeIf { it.hasLogos }
            if (found != null) return found
        }
        return lastEvents?.firstOrNull { e -> e.isOn(now) && e.teams?.hasLogos == true && e.channels.any { it.streamId == streamId } }?.teams
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val WEEK_MS = 8 * DAY_MS
        /** How long past its listed or guessed end a game is kept for ESPN to say whether it is still on. */
        const val OVERTIME_MS = 3 * HOUR_MS
        const val DETAIL_LENGTH = 90
        /** An event channel's title as two sides: "Cowboys at Colts", "#7 TEX vs. #18 MIZ". */
        val eventTeams = Regex("""^(.+?)\s+(?:vs\.?|at|@|v\.?)\s+(.+)$""", RegexOption.IGNORE_CASE)
    }
}

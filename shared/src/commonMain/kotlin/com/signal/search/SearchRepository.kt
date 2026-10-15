package com.signal.search

import app.cash.sqldelight.coroutines.asFlow
import com.signal.data.ChannelRepository
import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import com.signal.guide.GuideRepository
import com.signal.match.SearchText
import com.signal.data.VoiceNames
import com.signal.voice.VoiceCommand
import com.signal.model.Channel
import com.signal.model.Program
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Search (PLAN §4.3.14) for channels and programs. Movies and Series join with VOD (milestone 4).
 *
 * Channels are searched in memory: every visible channel's names (display, custom and raw, so a
 * cleanup rule never hides a match), folded once. A 57k-channel list is a few MB and a scan is a
 * few milliseconds, with no full-text table to keep in step with each source refresh. Programs use
 * guide.db's full-text index, and each is shown on a visible channel that takes its guide from there.
 */
class SearchRepository(
    private val db: UserDatabase,
    private val settings: Settings,
    private val channels: ChannelRepository,
    private val guide: GuideRepository,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    /** For timings. */
    private val log: ((String) -> Unit)? = null,
) {
    private class Entry(
        val streamId: String,
        val userChannelId: String?,
        val tvgId: String?,
        val rawName: String,
        /** Folded names, most specific first: the custom name, the displayed name, the raw name. */
        val names: List<String>,
        val favorite: Boolean,
        val order: Int,
        /** Words the names write in capitals and say as letters ("NBC" → "nbc"), which voice commands spell out. */
        val initials: List<String>,
        /** The voice names the user gave it, folded (PLAN §4.3.14 → Voice names). */
        val voiceNames: List<String>,
    )

    private val lock = Mutex()
    private var entries: List<Entry>? = null
    /** "epgSourceId|guideChannelId" → the visible channels using it, favorites first. */
    private var byGuideChannel: Map<String, List<Entry>>? = null
    private var byGuideState: Any? = null

    private val rebuild = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Goes up with every change to the visible channels, so a cache built from them knows it's stale. */
    val versions: kotlinx.coroutines.flow.StateFlow<Int> get() = _versions
    val version: Int get() = _versions.value
    private val _versions = kotlinx.coroutines.flow.MutableStateFlow(0)

    init {
        // Any change to channels, favorites or hidden flags: rebuild. Straight away once something
        // has searched (after a pause, for bursts like a sync), so the next search or voice command
        // doesn't wait seconds for it on a large list.
        scope.launch {
            db.streamQueries.searchIndex(settings.profileId).asFlow().collect {
                val wasBuilt = lock.withLock { (entries != null).also { entries = null; byGuideChannel = null; _versions.value++ } }
                if (wasBuilt) rebuild.tryEmit(Unit)
            }
        }
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            rebuild.debounce(2_000).collect { lock.withLock { index() } }
        }
    }

    /** A name's folded form and initials, kept between rebuilds: folding every name took 8–11 s on the Shield. */
    private class Folded(val folded: String, val initials: List<String>)
    private var foldedNames = HashMap<String, Folded>()

    private fun fold(name: String, cache: HashMap<String, Folded>, previous: Map<String, Folded>): Folded = cache.getOrPut(name) {
        previous[name] ?: Folded(SearchText.fold(name), capitals.findAll(name).map { it.value.lowercase() }.filter(VoiceCommand::isInitials).toList())
    }

    private suspend fun index(): List<Entry> = entries ?: withContext(io) {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val previous = foldedNames
        val cache = HashMap<String, Folded>(previous.size)
        val built = db.streamQueries.searchIndex(settings.profileId).executeAsList().mapIndexedNotNull { i, r ->
            if (r.hidden == 1L || r.group_hidden == 1L) return@mapIndexedNotNull null
            val raw = listOfNotNull(r.custom_name, r.display_name, r.raw_name).map { fold(it, cache, previous) }
            val names = raw.map { it.folded }.distinct()
            val voice = VoiceNames.parse(r.voice_names)
            // Initials: capitals in the names, and any letters-only word in a voice name ("fs1" → "fs").
            val initials = (raw.flatMap { it.initials } + voice.flatMap { v -> letterRuns.findAll(v).map { it.value.lowercase() } }.filter(VoiceCommand::isInitials)).distinct()
            Entry(r.id, r.user_channel_id, r.tvg_id, r.raw_name, names, r.is_favorite, i, initials, voice.map(SearchText::fold).filter { it.isNotEmpty() })
        }
        // Only names still in use, so renamed and removed channels don't pile up.
        foldedNames = cache
        log?.invoke("Search index: ${built.size} channels in ${started.elapsedNow().inWholeMilliseconds} ms (${cache.size - cache.keys.count { it in previous }} names new)")
        built
    }.also { entries = it }

    /**
     * Channels matching every word as a word prefix: favorites first, then exact, prefix, contains
     * (PLAN §4.3.14). A channel with the query as one of its voice names comes before all of them.
     */
    suspend fun channels(query: String, limit: Int = 60): List<Channel> = channelHits(query, limit).map { it.channel }

    /**
     * A channel search hit and how it matched, for voice commands to choose between acting and
     * asking (PLAN §4.3.14 → Voice commands, phase 2). [exact] is a voice name or the whole name.
     */
    data class ChannelHit(val channel: Channel, val exact: Boolean, val favorite: Boolean)

    /** [channels], with how each matched. */
    suspend fun channelHits(query: String, limit: Int = 60): List<ChannelHit> {
        val words = SearchText.words(query)
        if (words.isEmpty()) return emptyList()
        val folded = words.joinToString(" ")
        // Spaces don't count for voice names: "fs1" is heard as "f s one", which parses to "fs 1".
        val compact = words.joinToString("")
        val hits = lock.withLock {
            withContext(io) {
                index().mapNotNull { e ->
                    val voice = e.voiceNames.any { it.replace(" ", "") == compact }
                    if (!voice && !words.all { w -> e.names.any { n -> hasWordPrefix(n, w) } }) return@mapNotNull null
                    val rank = when {
                        voice -> -1
                        e.names.any { it == folded } -> 0
                        e.names.any { it.startsWith(folded) } -> 1
                        else -> 2
                    }
                    Triple(e, rank, e.order)
                }.sortedWith(compareBy({ it.second != -1 }, { !it.first.favorite }, { it.second }, { it.third })).take(limit)
                    .map { Triple(it.first.streamId, it.second <= 0, it.first.favorite) }
            }
        }
        return hits.mapNotNull { (id, exact, favorite) -> channels.channelByStream(id)?.let { ChannelHit(it, exact, favorite) } }
    }

    /**
     * What a voice command can name (PLAN §4.3.14): the visible channels' names, folded, and the
     * initials they're written with ("NBC" → "nbc"), which people spell out when they say them.
     * From the search index, so building the voice vocabulary also readies the search it ends in.
     */
    suspend fun voiceNames(): Pair<Set<String>, Set<String>> = lock.withLock {
        withContext(io) {
            val all = index()
            all.flatMapTo(HashSet()) { it.names + it.voiceNames } to all.flatMapTo(HashSet()) { it.initials }
        }
    }

    private val capitals = Regex("""\b\p{Lu}{2,5}\b""")
    private val letterRuns = Regex("""\p{L}{2,5}""")

    data class ProgramHit(val program: Program, val channel: Channel)

    /** Programs from now to 7 days out: on now first, then by start (PLAN §4.3.14), each on a visible channel. */
    suspend fun programs(query: String, now: Long, limit: Int = 60): List<ProgramHit> {
        val words = SearchText.words(query)
        if (words.isEmpty()) return emptyList()
        val programs = guide.search(words, now, limit * 4)
        if (programs.isEmpty()) return emptyList()
        val map = lock.withLock { guideChannelMap() }
        val hits = programs.mapNotNull { p -> map["${p.epgSourceId}|${p.guideChannelId}"]?.first()?.let { p to it.streamId } }
            .sortedWith(compareBy({ !it.first.isOn(now) }, { it.first.start }))
            .take(limit)
        val channelCache = HashMap<String, Channel?>()
        return hits.mapNotNull { (p, streamId) -> channelCache.getOrPut(streamId) { channels.channelByStream(streamId) }?.let { ProgramHit(p, it) } }
    }

    private suspend fun guideChannelMap(): Map<String, List<Entry>> {
        val state = guide.state.value
        byGuideChannel?.takeIf { byGuideState == state }?.let { return it }
        val built = withContext(io) {
            val out = HashMap<String, MutableList<Entry>>()
            for (e in index()) {
                val ref = guide.resolve(e.streamId, e.userChannelId, e.tvgId, e.rawName) ?: continue
                out.getOrPut("${ref.epgSourceId}|${ref.guideChannelId}") { ArrayList(2) } += e
            }
            out.mapValues { (_, list) -> list.sortedBy { !it.favorite } }
        }
        byGuideChannel = built
        byGuideState = state
        return built
    }

    /** How many visible channels have guide data, and a sample of those without (name, tvg-id). For the log. */
    suspend fun guideCoverage(sample: Int = 15): Triple<Int, Int, List<Pair<String, String?>>> = lock.withLock {
        withContext(io) {
            val all = index()
            val unmatched = all.filter { guide.resolve(it.streamId, it.userChannelId, it.tvgId, it.rawName) == null }
            Triple(all.size - unmatched.size, all.size, unmatched.shuffled().take(sample).map { it.rawName to it.tvgId })
        }
    }

    /** True when [word] starts [name] or one of its words. */
    private fun hasWordPrefix(name: String, word: String): Boolean {
        var i = name.indexOf(word)
        while (i >= 0) {
            if (i == 0 || name[i - 1] == ' ') return true
            i = name.indexOf(word, i + 1)
        }
        return false
    }
}

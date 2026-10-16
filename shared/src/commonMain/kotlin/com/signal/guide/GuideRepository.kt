package com.signal.guide

import com.signal.data.synchronized
import com.signal.data.Settings
import com.signal.db.guide.GuideDatabase
import com.signal.model.Channel
import com.signal.model.EpgSource
import com.signal.model.GuideRef
import com.signal.model.Program
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Reads the guide for channels (PLAN §6): which EPG source and guide channel each channel uses,
 * and its programs with that source's time shift applied. Mappings, overrides, the default and
 * time shifts are applied here, at read time, so changing them never needs a rebuild.
 */
class GuideRepository(
    private val store: GuideStore,
    private val epg: EpgRepository,
    settings: Settings,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    /** For timings: building a source's index is the slow part of a guide's first rows. */
    private val log: ((String) -> Unit)? = null,
) {
    /** Everything resolution depends on. A new value means cached guide data is stale. */
    data class State(val version: Int, val sources: List<EpgSource>, val defaultId: String?, val mappings: Map<String, EpgRepository.Mapping>)

    // Settings are in it for the default EPG source.
    val state: StateFlow<State> = combine(store.version, epg.epgSources(), epg.mappings(), settings.changes()) { v, sources, mappings, _ ->
        State(v, sources, epg.defaultId(sources), mappings)
    }.stateIn(scope, SharingStarted.Eagerly, State(0, emptyList(), null, emptyMap()))

    private var indexVersion = -1
    private val indexes = HashMap<String, GuideResolver.SourceIndex>()
    private val refs = HashMap<String, GuideRef?>()
    private var refsState: State? = null

    /** Where [channel]'s guide comes from, or null when no EPG source has data for it. */
    suspend fun resolve(channel: Channel): GuideRef? = withContext(io) { resolveNow(channel) }

    // Readers run on several IO threads at once; the caches are guarded by one lock.
    private val lock = Any()

    private fun resolveNow(channel: Channel): GuideRef? =
        resolve(channel.stream.id, channel.userChannelId, channel.stream.tvgId, channel.stream.rawName)

    /** Resolution from a channel's raw fields, for search's map from guide channels back to channels. */
    internal fun resolve(streamId: String, userChannelId: String?, tvgId: String?, rawName: String): GuideRef? = synchronized(lock) {
        val s = state.value
        val db = store.db ?: return null
        if (refsState != s) {
            refs.clear()
            refsState = s
            refsSignature = signature(s)
            // The resolutions saved by the last run, when they're for this same guide and matches.
            loadSaved()?.takeIf { it.first == refsSignature }?.let { refs.putAll(it.second) }
        }
        if (indexVersion != s.version) { indexes.clear(); indexVersion = s.version }
        refs[streamId]?.let { return it }
        if (streamId in refs) return null
        return compute(userChannelId, tvgId, rawName, s, db).also {
            refs[streamId] = it
            unsaved.tryEmit(Unit)
        }
    }

    // ---- Saved between runs (user, 2026-10-16: 5-10 s before the guide's rows after opening) ----
    // Resolving a channel that isn't mapped by hand builds its EPG sources' indexes, 8 s on the Shield
    // for four guides. Each channel's answer is saved beside the guide, so the next start uses it at
    // once; it counts only for the same guide build, EPG sources and mappings.

    private var refsSignature = ""
    private var saved: Pair<String, Map<String, GuideRef?>>? = null
    private var savedLoaded = false
    private val unsaved = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            unsaved.debounce(SAVE_AFTER_MS).collect { withContext(io) { save() } }
        }
    }

    /** What the resolutions depend on: the guide build, the EPG sources in order, the default, the mappings. */
    private fun signature(s: State): String = listOf(
        store.builtAt()?.toString().orEmpty(),
        s.sources.joinToString(",") { "${it.id}:${it.enabled}:${it.timeShiftMs}" },
        s.defaultId.orEmpty(),
        s.mappings.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.epgSourceId}/${it.value.guideChannelId}" }.hashCode().toString(),
    ).joinToString("|")

    private fun loadSaved(): Pair<String, Map<String, GuideRef?>>? {
        if (savedLoaded) return saved
        savedLoaded = true
        saved = runCatching {
            val lines = GuideFiles.readText(store.resolvedPath())?.lines() ?: return null
            val sig = lines.firstOrNull() ?: return null
            sig to lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
                val f = line.split('\t')
                f[0] to if (f.size < 5) null else GuideRef(f[1], f[2], f[3].toLong(), f[4] == "1")
            }
        }.getOrNull()
        return saved
    }

    private fun save() {
        val (sig, snapshot) = synchronized(lock) { refsSignature to HashMap(refs) }
        if (sig.isEmpty() || snapshot.isEmpty()) return
        val text = buildString {
            append(sig).append('\n')
            for ((id, ref) in snapshot) {
                append(id)
                if (ref != null) append('\t').append(ref.epgSourceId).append('\t').append(ref.guideChannelId).append('\t').append(ref.timeShiftMs).append('\t').append(if (ref.isFallback) "1" else "0")
                append('\n')
            }
        }
        runCatching { GuideFiles.writeText(store.resolvedPath(), text) }.onFailure { log?.invoke("Guide: couldn't save resolutions: ${it.message}") }
        synchronized(lock) { saved = sig to snapshot }
    }

    private fun compute(userChannelId: String?, rawTvgId: String?, rawName: String, s: State, db: GuideDatabase): GuideRef? {
        val mapping = userChannelId?.let { s.mappings[it] }?.let { GuideResolver.Mapping(it.epgSourceId, it.guideChannelId) }
        return GuideResolver.resolve(mapping, rawTvgId, rawName, s.sources, s.defaultId) { id ->
            indexes.getOrPut(id) {
                val started = kotlin.time.TimeSource.Monotonic.markNow()
                GuideResolver.buildIndex(db, id).also { log?.invoke("Guide: index for $id built in ${started.elapsedNow().inWholeMilliseconds} ms") }
            }
        }
    }

    /** [channel]'s programs overlapping [from, to), in shifted (displayed) time. */
    suspend fun programs(channel: Channel, from: Long, to: Long): List<Program> = withContext(io) {
        val ref = resolveNow(channel) ?: return@withContext emptyList()
        val db = store.db ?: return@withContext emptyList()
        val all = db.guideQueries.programsBetween(ref.epgSourceId, ref.guideChannelId, from - ref.timeShiftMs, to - ref.timeShiftMs)
            .executeAsList().map { it.toModel(ref.timeShiftMs) }
        withoutOverlaps(all)
    }

    // Some guides list two programs in one slot (merged feeds, or a correction beside the original). The grid
    // can show one per slot: keep the earlier, and drop anything that starts before it ends.
    private fun withoutOverlaps(sorted: List<Program>): List<Program> {
        val out = ArrayList<Program>(sorted.size)
        var end = Long.MIN_VALUE
        for (p in sorted) {
            if (p.start < end) continue
            out += p
            end = p.stop
        }
        return out
    }

    /** The program on now and the one after it. */
    suspend fun nowAndNext(channel: Channel, now: Long): Pair<Program?, Program?> {
        val list = programs(channel, now, now + 12 * 60 * 60_000L)
        val current = list.firstOrNull { it.isOn(now) }
        val next = list.firstOrNull { it.start >= (current?.stop ?: now) }
        return current to next
    }

    /**
     * Programs whose title or episode title has every word in [words] as a word prefix, not ended
     * by shifted time (PLAN §4.3.14). [words] are already folded (SearchText).
     */
    suspend fun search(words: List<String>, now: Long, limit: Int): List<Program> = withContext(io) {
        val db = store.db ?: return@withContext emptyList()
        if (words.isEmpty()) return@withContext emptyList()
        val shifts = state.value.sources.associate { it.id to it.timeShiftMs }
        // FTS4 prefix query; the words are letters and digits only, so nothing needs escaping.
        val match = words.joinToString(" ") { "$it*" }
        db.guideQueries.searchPrograms(match, now - 12 * 60 * 60_000L, limit.toLong() * 2).executeAsList()
            .map { it.toModel(shifts[it.epg_source_id] ?: 0) }
            .filter { it.stop > now }
    }

    /**
     * Titles of games with their teams named, airing between [now] and [hours] ahead, for the team
     * names voice commands know (PLAN §4.3.14 → Voice commands, phase 2). Guide time, so a time
     * shift moves the window by a few hours at most, which a day's window absorbs.
     */
    suspend fun gameTitles(now: Long, hours: Int = 24): List<String> = withContext(io) {
        val db = store.db ?: return@withContext emptyList()
        db.guideQueries.gameTitles(now, now + hours * 60 * 60_000L).executeAsList()
    }

    /** For diagnostics: whether any guide lists [tvgId], and with how many programs. */
    suspend fun describeTvgId(tvgId: String): String = withContext(io) {
        val row = store.db?.guideQueries?.guideChannelByIdAnyCase(tvgId)?.executeAsOneOrNull() ?: return@withContext "not in any guide"
        if (row.programs == 0L) "listed as ${row.id}, no programs" else "listed as ${row.id}, ${row.programs} programs"
    }

    /** Per EPG source: guide channels and programs in the live guide (PLAN §4.3.15 → EPG sources). */
    suspend fun counts(): Map<String, Pair<Long, Long>> = withContext(io) {
        store.db?.guideQueries?.countsBySource()?.executeAsList()?.associate { it.epg_source_id to (it.channels to it.programs) }.orEmpty()
    }

    suspend fun program(id: Long): Program? = withContext(io) {
        val db = store.db ?: return@withContext null
        val row = db.guideQueries.programById(id).executeAsOneOrNull() ?: return@withContext null
        val shift = state.value.sources.firstOrNull { it.id == row.epg_source_id }?.timeShiftMs ?: 0
        row.toModel(shift)
    }

    private fun com.signal.db.guide.Program.toModel(shift: Long) = Program(
        id = id,
        epgSourceId = epg_source_id,
        guideChannelId = guide_channel_id,
        start = start + shift,
        stop = stop + shift,
        title = title,
        subTitle = sub_title,
        description = description,
        categories = categories?.split(GuideBuilder.SEPARATOR).orEmpty(),
        season = season?.toInt(),
        episode = episode?.toInt(),
        episodeLabel = episode_label,
        icon = icon,
        rating = rating,
        year = year?.toInt(),
        isNew = flags and 1L != 0L,
        isLive = flags and 2L != 0L,
        credits = credits?.split(GuideBuilder.SEPARATOR).orEmpty(),
    )
}

/** New resolutions are saved this long after the last one, so a screenful saves once. */
private const val SAVE_AFTER_MS = 3_000L

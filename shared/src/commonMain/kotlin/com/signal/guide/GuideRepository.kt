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
import kotlinx.coroutines.delay
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
    private val scope: CoroutineScope,
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
        if (refsState != s) reset(s)
        if (indexVersion != s.version) { indexes.clear(); indexVersion = s.version }
        inputs[streamId] = Input(userChannelId, tvgId, rawName)
        if (streamId in refs) {
            val ref = refs[streamId]
            if (checked[streamId] == builtAt) return ref
            // Saved against an earlier guide build: still right while its guide channel has programs.
            // One seek, where working it out again could mean building every source's index.
            if (ref == null) return null // looked at again in the background (recheckEmpty)
            if (db.guideQueries.hasPrograms(ref.epgSourceId, ref.guideChannelId).executeAsOne()) {
                checked[streamId] = builtAt
                unsaved.tryEmit(Unit)
                return ref
            }
            refs.remove(streamId)
        }
        return compute(userChannelId, tvgId, rawName, s, db).also {
            refs[streamId] = it
            checked[streamId] = builtAt
            unsaved.tryEmit(Unit)
        }
    }

    // ---- Saved between runs (user, 2026-10-16: 5-10 s before the guide's rows after opening) ----
    // Resolving a channel that isn't mapped by hand builds its EPG sources' indexes, 8 s on the Shield
    // for four guides. Each channel's answer is saved beside the guide, so the next start uses it at
    // once. It holds while the EPG sources, the default and the mappings are the same. A new guide
    // build (nightly, from the NAS) doesn't throw it away: each answer is checked as its channel is
    // first resolved, and channels that had no guide are worked out again in the background.

    /** What a channel's resolution was worked out from, so it can be worked out again without the channel. */
    private data class Input(val userChannelId: String?, val tvgId: String?, val rawName: String)

    private class Saved(val config: String, val refs: Map<String, GuideRef?>, val checked: Map<String, String>, val inputs: Map<String, Input>)

    private var config = ""
    private var builtAt = ""
    /** The guide build each saved resolution was last confirmed against. */
    private val checked = HashMap<String, String>()
    private val inputs = HashMap<String, Input>()
    private var saved: Saved? = null
    private var savedLoaded = false
    private val unsaved = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            unsaved.debounce(SAVE_AFTER_MS).collect { withContext(io) { save() } }
        }
    }

    /** A new state: the saved resolutions for these EPG sources, default and mappings, if any. Called holding the lock. */
    private fun reset(s: State) {
        refs.clear()
        checked.clear()
        refsState = s
        config = config(s)
        builtAt = store.builtAt()?.toString().orEmpty()
        val last = loadSaved()?.takeIf { it.config == config } ?: return
        refs.putAll(last.refs)
        checked.putAll(last.checked)
        for ((id, input) in last.inputs) inputs.putIfAbsent(id, input)
        // Channels that had no guide may have one in a new build: worked out again, off the screen's path.
        if (last.refs.any { (id, ref) -> ref == null && checked[id] != builtAt }) {
            scope.launch(io) { delay(RECHECK_AFTER_MS); recheckEmpty(s) }
        }
    }

    /** Channels saved with no guide, worked out again against the new guide build. */
    private fun recheckEmpty(s: State) {
        val ids = synchronized(lock) { refs.filter { (id, ref) -> ref == null && checked[id] != builtAt }.keys.toList() }
        var found = 0
        for (id in ids) synchronized(lock) {
            if (refsState != s) return
            val db = store.db ?: return
            val input = inputs[id] ?: return@synchronized
            compute(input.userChannelId, input.tvgId, input.rawName, s, db).also {
                if (it != null) found++
                refs[id] = it
                checked[id] = builtAt
            }
        }
        if (ids.isNotEmpty()) {
            log?.invoke("Guide: rechecked ${ids.size} channels without a guide, $found have one now")
            unsaved.tryEmit(Unit)
        }
    }

    /** What the resolutions depend on besides the guide build: the EPG sources in order, the default, the mappings. */
    private fun config(s: State): String = listOf(
        s.sources.joinToString(",") { "${it.id}:${it.enabled}:${it.timeShiftMs}" },
        s.defaultId.orEmpty(),
        s.mappings.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.epgSourceId}/${it.value.guideChannelId}" }.hashCode().toString(),
    ).joinToString("|")

    // One channel per line: stream, user channel, tvg-id, raw name, the build it was confirmed against,
    // then the guide channel's source, ID, time shift and fallback flag when it has one.
    private fun loadSaved(): Saved? {
        if (savedLoaded) return saved
        savedLoaded = true
        saved = runCatching {
            val lines = GuideFiles.readText(store.resolvedPath())?.lines() ?: return null
            val header = lines.firstOrNull() ?: return null
            if (!header.startsWith(FORMAT)) return null // the first format, keyed on the build: worked out again
            val refs = HashMap<String, GuideRef?>()
            val checked = HashMap<String, String>()
            val inputs = HashMap<String, Input>()
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                if (f.size < 5) continue
                val id = f[0]
                inputs[id] = Input(f[1].ifEmpty { null }, f[2].ifEmpty { null }, f[3])
                checked[id] = f[4]
                refs[id] = if (f.size < 9) null else GuideRef(f[5], f[6], f[7].toLong(), f[8] == "1")
            }
            Saved(header.removePrefix(FORMAT), refs, checked, inputs)
        }.getOrNull()
        return saved
    }

    private fun save() {
        val snapshot = synchronized(lock) {
            if (config.isEmpty() || refs.isEmpty()) return
            Saved(config, HashMap(refs), HashMap(checked), HashMap(inputs))
        }
        fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')
        val text = buildString {
            append(FORMAT).append(snapshot.config).append('\n')
            for ((id, ref) in snapshot.refs) {
                val input = snapshot.inputs[id] ?: continue
                append(clean(id)).append('\t').append(clean(input.userChannelId)).append('\t').append(clean(input.tvgId)).append('\t')
                append(clean(input.rawName)).append('\t').append(snapshot.checked[id].orEmpty())
                if (ref != null) append('\t').append(ref.epgSourceId).append('\t').append(ref.guideChannelId).append('\t').append(ref.timeShiftMs).append('\t').append(if (ref.isFallback) "1" else "0")
                append('\n')
            }
        }
        runCatching { GuideFiles.writeText(store.resolvedPath(), text) }.onFailure { log?.invoke("Guide: couldn't save resolutions: ${it.message}") }
        synchronized(lock) { saved = snapshot }
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
    /** Programs on now with every word in [words] as a word prefix ([search], for what's airing). */
    suspend fun searchOnNow(words: List<String>, now: Long, limit: Int): List<Program> = withContext(io) {
        val db = store.db ?: return@withContext emptyList()
        if (words.isEmpty()) return@withContext emptyList()
        val shifts = state.value.sources.associate { it.id to it.timeShiftMs }
        val match = words.joinToString(" ") { "$it*" }
        db.guideQueries.searchProgramsOnNow(match, now, now, limit.toLong()).executeAsList()
            .map { it.toModel(shifts[it.epg_source_id] ?: 0) }
            .filter { it.isOn(now) }
    }

    suspend fun search(words: List<String>, now: Long, limit: Int): List<Program> = withContext(io) {
        val db = store.db ?: return@withContext emptyList()
        if (words.isEmpty()) return@withContext emptyList()
        val shifts = state.value.sources.associate { it.id to it.timeShiftMs }
        // FTS4 prefix query; the words are letters and digits only, so nothing needs escaping.
        val match = words.joinToString(" ") { "$it*" }
        // Shown stop = stored stop + shift, so a program on now has a stored stop after now minus the
        // largest shift. A flat 12 hours back let a day's earlier airings fill the limit (2026-10-16).
        val back = (shifts.values.maxOrNull() ?: 0L).coerceAtLeast(0L)
        db.guideQueries.searchPrograms(match, now - back, limit.toLong() * 2).executeAsList()
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

/** Channels without a guide are looked at again this long after opening, once the screen has its rows. */
private const val RECHECK_AFTER_MS = 60_000L

/** The saved file's first line: this format, then what the resolutions hold for. */
private const val FORMAT = "v2 "

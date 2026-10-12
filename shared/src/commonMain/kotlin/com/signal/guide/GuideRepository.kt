package com.signal.guide

import com.signal.data.synchronized
import com.signal.data.Settings
import com.signal.db.guide.GuideDatabase
import com.signal.match.GuideNames
import com.signal.model.Channel
import com.signal.model.EpgSource
import com.signal.model.GuideRef
import com.signal.model.Program
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) {
    /** Everything resolution depends on. A new value means cached guide data is stale. */
    data class State(val version: Int, val sources: List<EpgSource>, val defaultId: String?, val mappings: Map<String, EpgRepository.Mapping>)

    // Settings are in it for the default EPG source.
    val state: StateFlow<State> = combine(store.version, epg.epgSources(), epg.mappings(), settings.changes()) { v, sources, mappings, _ ->
        State(v, sources, epg.defaultId(sources), mappings)
    }.stateIn(scope, SharingStarted.Eagerly, State(0, emptyList(), null, emptyMap()))

    /** Per EPG source, built once per guide version: guide channel IDs that have programs, and their normalized names. */
    private class SourceIndex(val ids: Set<String>, val byName: Map<String, String>)

    private var indexVersion = -1
    private val indexes = HashMap<String, SourceIndex>()
    private val refs = HashMap<String, GuideRef?>()
    private var refsState: State? = null

    /** Where [channel]'s guide comes from, or null when no EPG source has data for it. */
    suspend fun resolve(channel: Channel): GuideRef? = withContext(io) { resolveNow(channel) }

    // Readers run on several IO threads at once; the caches are guarded by one lock.
    private val lock = Any()

    private fun resolveNow(channel: Channel): GuideRef? = synchronized(lock) {
        val s = state.value
        val db = store.db ?: return null
        if (refsState != s) {
            refs.clear()
            refsState = s
        }
        if (indexVersion != s.version) {
            indexes.clear()
            indexVersion = s.version
        }
        return refs.getOrPut(channel.stream.id) { compute(channel.userChannelId, channel.stream.tvgId, channel.stream.rawName, s, db) }
    }

    /** Resolution from a channel's raw fields, for search's map from guide channels back to channels. */
    internal fun resolve(streamId: String, userChannelId: String?, tvgId: String?, rawName: String): GuideRef? = synchronized(lock) {
        val s = state.value
        val db = store.db ?: return null
        if (refsState != s) { refs.clear(); refsState = s }
        if (indexVersion != s.version) { indexes.clear(); indexVersion = s.version }
        return refs.getOrPut(streamId) { compute(userChannelId, tvgId, rawName, s, db) }
    }

    private fun compute(userChannelId: String?, rawTvgId: String?, rawName: String, s: State, db: GuideDatabase): GuideRef? {
        val enabled = s.sources.filter { it.enabled }
        if (enabled.isEmpty()) return null
        val mapping = userChannelId?.let { s.mappings[it] }
        val chosenId = mapping?.epgSourceId?.takeIf { id -> enabled.any { it.id == id } } ?: s.defaultId
        val chosen = enabled.firstOrNull { it.id == chosenId }
        val order = listOfNotNull(chosen) + enabled.filter { it.id != chosenId }
        val name = GuideNames.normalize(rawName)
        val tvgId = rawTvgId?.takeIf { it.isNotBlank() }
        for ((i, src) in order.withIndex()) {
            val index = indexes.getOrPut(src.id) { buildIndex(db, src.id) }
            val guideChannel = when {
                // 1. A manual mapping names both the EPG source and the guide channel: the strongest override.
                i == 0 && mapping?.guideChannelId != null && mapping.epgSourceId == src.id -> mapping.guideChannelId
                // 2. tvg-id in this EPG source.
                tvgId != null && tvgId in index.ids -> tvgId
                // 3. The normalized name in this EPG source.
                else -> index.byName[name]
            } ?: continue
            return GuideRef(src.id, guideChannel, src.timeShiftMs, isFallback = i > 0)
        }
        return null
    }

    private fun buildIndex(db: GuideDatabase, epgSourceId: String): SourceIndex {
        val withPrograms = db.guideQueries.channelsWithPrograms(epgSourceId).executeAsList().toHashSet()
        val byName = HashMap<String, String>()
        for (c in db.guideQueries.channelsForSource(epgSourceId).executeAsList()) {
            if (c.id in withPrograms && c.normalized_name.isNotEmpty()) byName.putIfAbsent(c.normalized_name, c.id)
        }
        return SourceIndex(withPrograms, byName)
    }

    /** [channel]'s programs overlapping [from, to), in shifted (displayed) time. */
    suspend fun programs(channel: Channel, from: Long, to: Long): List<Program> = withContext(io) {
        val ref = resolveNow(channel) ?: return@withContext emptyList()
        val db = store.db ?: return@withContext emptyList()
        db.guideQueries.programsBetween(ref.epgSourceId, ref.guideChannelId, from - ref.timeShiftMs, to - ref.timeShiftMs)
            .executeAsList().map { it.toModel(ref.timeShiftMs) }
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

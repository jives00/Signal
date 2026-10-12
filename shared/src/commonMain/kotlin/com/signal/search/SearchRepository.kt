package com.signal.search

import app.cash.sqldelight.coroutines.asFlow
import com.signal.data.ChannelRepository
import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import com.signal.guide.GuideRepository
import com.signal.match.SearchText
import com.signal.model.Channel
import com.signal.model.Program
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
    )

    private val lock = Mutex()
    private var entries: List<Entry>? = null
    /** "epgSourceId|guideChannelId" → the visible channels using it, favorites first. */
    private var byGuideChannel: Map<String, List<Entry>>? = null
    private var byGuideState: Any? = null

    init {
        // Any change to channels, favorites or hidden flags: rebuild on the next search.
        scope.launch {
            db.streamQueries.searchIndex(settings.profileId).asFlow().collect { lock.withLock { entries = null; byGuideChannel = null } }
        }
    }

    private suspend fun index(): List<Entry> = entries ?: withContext(io) {
        db.streamQueries.searchIndex(settings.profileId).executeAsList().mapIndexedNotNull { i, r ->
            if (r.hidden == 1L || r.group_hidden == 1L) return@mapIndexedNotNull null
            val names = listOfNotNull(r.custom_name, r.display_name, r.raw_name).map(SearchText::fold).distinct()
            Entry(r.id, r.user_channel_id, r.tvg_id, r.raw_name, names, r.is_favorite, i)
        }
    }.also { entries = it }

    /** Channels matching every word as a word prefix: favorites first, then exact, prefix, contains (PLAN §4.3.14). */
    suspend fun channels(query: String, limit: Int = 60): List<Channel> {
        val words = SearchText.words(query)
        if (words.isEmpty()) return emptyList()
        val folded = words.joinToString(" ")
        val hits = lock.withLock {
            withContext(io) {
                index().mapNotNull { e ->
                    if (!words.all { w -> e.names.any { n -> hasWordPrefix(n, w) } }) return@mapNotNull null
                    val rank = when {
                        e.names.any { it == folded } -> 0
                        e.names.any { it.startsWith(folded) } -> 1
                        else -> 2
                    }
                    Triple(e, rank, e.order)
                }.sortedWith(compareBy({ !it.first.favorite }, { it.second }, { it.third })).take(limit).map { it.first.streamId }
            }
        }
        return hits.mapNotNull { channels.channelByStream(it) }
    }

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

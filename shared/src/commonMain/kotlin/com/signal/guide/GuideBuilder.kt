package com.signal.guide

import com.signal.data.Log
import com.signal.data.SourceRepository
import com.signal.db.guide.GuideDatabase
import com.signal.demo.DemoData
import com.signal.match.GuideNames
import com.signal.model.CredentialMasking
import com.signal.model.EpgKind
import com.signal.model.EpgSource
import com.signal.parse.XmltvChannel
import com.signal.parse.XmltvParser
import com.signal.parse.XmltvProgram
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The in-app guide build (PLAN §6): downloads each enabled EPG source and writes it into a fresh
 * guide.db. Programs that have already ended, or start more than 7 days out, are dropped, both by
 * shifted time. A source that fails keeps its data from the previous guide, so one bad URL never
 * empties the grid.
 */
class GuideBuilder(
    private val http: HttpClient,
    private val sources: SourceRepository,
    private val io: CoroutineDispatcher,
    private val log: Log,
) {
    /** What one EPG source contributed: counts on success, or the error. */
    data class Outcome(val channels: Int, val programs: Int, val error: String? = null, val carriedOver: Boolean = false)

    suspend fun build(epgSources: List<EpgSource>, fresh: GuideDatabase, previous: GuideDatabase?, now: Long): Map<String, Outcome> = withContext(io) {
        val out = LinkedHashMap<String, Outcome>()
        for (epg in epgSources.filter { it.enabled }) {
            val result = runCatching { load(epg, fresh, now) }
            out[epg.id] = result.getOrElse { e ->
                val message = e.message ?: e::class.simpleName ?: "Failed"
                log.warn("Guide ${epg.name} failed: $message")
                val carried = previous?.let { carryOver(epg, it, fresh, now) } ?: 0
                Outcome(0, carried, error = message, carriedOver = carried > 0)
            }
        }
        fresh.guideQueries.indexAllForSearch()
        out
    }

    private suspend fun load(epg: EpgSource, db: GuideDatabase, now: Long): Outcome {
        val writer = Writer(db, epg, now)
        when (epg.kind) {
            EpgKind.DEMO -> demo(writer, now)
            EpgKind.XMLTV -> readXmltv(http, epg.url, emptyMap()) { XmltvParser(writer::channel, writer::program).parse(it) }
            EpgKind.XTREAM -> {
                val source = epg.sourceId?.let { sources.byId(it) } ?: error("Its source was removed")
                val url = source.url.trimEnd('/') + "/xmltv.php?username=" + source.username.encodeURLParameter() +
                    "&password=" + source.password.encodeURLParameter()
                runCatching {
                    readXmltv(http, url, source.headers) { XmltvParser(writer::channel, writer::program).parse(it) }
                }.getOrElse { e ->
                    // The URL is never logged whole: it embeds the credentials (PLAN §1).
                    throw IllegalStateException("${e.message} from ${CredentialMasking.forLog(url, source)}")
                }
            }
        }
        writer.flush()
        if (writer.programs == 0) error("No programs")
        log.info("Guide ${epg.name}: ${writer.channels} channels, ${writer.programs} programs")
        return Outcome(writer.channels, writer.programs)
    }

    /** The Demo source's synthetic guide (PLAN §7.4): every Demo channel, 7 days. */
    private fun demo(writer: Writer, now: Long) {
        DemoData.channels().forEachIndexed { i, ch ->
            writer.channel(XmltvChannel(ch.tvgId, listOf(ch.baseName), null))
            for (p in DemoData.programs(i, now)) {
                writer.program(
                    XmltvProgram(
                        channel = ch.tvgId, start = p.startMs, stop = p.endMs, title = p.title,
                        subTitle = p.episodeTitle, description = p.description, categories = listOf(p.category),
                        season = p.season, episode = p.episode, isNew = p.isNew, isLive = p.isLive,
                    ),
                )
            }
        }
    }

    private fun carryOver(epg: EpgSource, from: GuideDatabase, to: GuideDatabase, now: Long): Int {
        var count = 0
        to.transaction {
            from.guideQueries.guideChannelsForSource(epg.id).executeAsList().forEach {
                to.guideQueries.insertChannel(it.epg_source_id, it.id, it.display_name, it.normalized_name, it.icon)
            }
            from.guideQueries.programsForSource(epg.id, now - epg.timeShiftMs).executeAsList().forEach { p ->
                to.guideQueries.insertProgram(
                    p.epg_source_id, p.guide_channel_id, p.start, p.stop, p.title, p.sub_title, p.description, p.categories,
                    p.season, p.episode, p.episode_label, p.icon, p.rating, p.year, p.flags, p.credits,
                )
                count++
            }
        }
        return count
    }

    /** Batches inserts into transactions: one per row would be far too slow for 300k programs. */
    private class Writer(private val db: GuideDatabase, private val epg: EpgSource, now: Long) {
        // Pruning works in guide time: a program is shown at time + shift.
        private val keepAfter = now - epg.timeShiftMs
        private val keepBefore = now + 7 * 24 * 60 * 60_000L - epg.timeShiftMs
        private val pendingChannels = ArrayList<XmltvChannel>()
        private val pendingPrograms = ArrayList<XmltvProgram>(BATCH)
        var channels = 0; private set
        var programs = 0; private set

        fun channel(c: XmltvChannel) {
            pendingChannels += c
            channels++
            if (pendingChannels.size >= BATCH) flush()
        }

        fun program(p: XmltvProgram) {
            if (p.stop <= keepAfter || p.start >= keepBefore) return
            pendingPrograms += p
            programs++
            if (pendingPrograms.size >= BATCH) flush()
        }

        fun flush() {
            if (pendingChannels.isEmpty() && pendingPrograms.isEmpty()) return
            db.transaction {
                for (c in pendingChannels) {
                    val name = c.displayNames.firstOrNull() ?: c.id
                    db.guideQueries.insertChannel(epg.id, c.id, name, GuideNames.normalize(name), c.icon)
                }
                for (p in pendingPrograms) {
                    val flags = (if (p.isNew) 1L else 0) or (if (p.isLive) 2L else 0) or (if (p.previouslyShown) 4L else 0)
                    db.guideQueries.insertProgram(
                        epg.id, p.channel, p.start, p.stop, p.title, p.subTitle, p.description,
                        p.categories.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR),
                        p.season?.toLong(), p.episode?.toLong(), p.episodeLabel, p.icon, p.rating, p.year?.toLong(), flags,
                        p.credits.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR),
                    )
                }
            }
            pendingChannels.clear()
            pendingPrograms.clear()
        }
    }

    companion object {
        private const val BATCH = 2000
        /** Joins list fields (categories, credits) in one column; a character no guide text uses. */
        const val SEPARATOR = "\u001F"
    }
}

package com.signal.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.guide.GuideDatabase
import com.signal.guide.GuideResolver
import com.signal.match.GuideNames
import com.signal.model.EpgSource
import com.signal.model.GuideRef
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Properties

/** A program, briefly, in displayed (shifted) time. */
@Serializable
data class ProgramBrief(val title: String, val start: Long, val stop: Long)

/** A channel in a guide, for the mapping queue and search (PLAN §4.3.18 → Guide mapping). */
@Serializable
data class GuideChannelInfo(
    val epgSourceId: String,
    val epgSourceName: String,
    val id: String,
    val displayName: String,
    val score: Double = 0.0,
    val now: ProgramBrief? = null,
    val next: ProgramBrief? = null,
)

/**
 * Reads the published guide build for the web admin: which guide channel each channel uses (the
 * app's own GuideResolver), now and next, and the guide's channel list for mapping. It reopens the
 * file when a new build is published.
 */
class GuideReader(private val builds: () -> File?) {
    private var openFile: File? = null
    private var driver: JdbcSqliteDriver? = null
    private var db: GuideDatabase? = null
    private val indexes = HashMap<String, GuideResolver.SourceIndex>()
    private var channels: List<Named>? = null

    private class Named(val epgSourceId: String, val id: String, val displayName: String, val names: Set<String>, val tokens: Set<String>, val compact: List<String>)

    /** The build's version, so callers can cache what they derive from it. */
    @Synchronized fun version(): String? = current()?.let { openFile?.name }

    @Synchronized private fun current(): GuideDatabase? {
        val file = builds()
        if (file != openFile) {
            driver?.close()
            driver = null
            db = null
            indexes.clear()
            channels = null
            openFile = file
            if (file != null && file.isFile) {
                driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("open_mode", "1") })
                db = GuideDatabase(driver!!)
            }
        }
        return db
    }

    @Synchronized fun resolve(mapping: GuideResolver.Mapping?, tvgId: String?, rawName: String, sources: List<EpgSource>, defaultId: String?): GuideRef? {
        val db = current() ?: return null
        return GuideResolver.resolve(mapping, tvgId, rawName, sources, defaultId) { id -> indexes.getOrPut(id) { GuideResolver.buildIndex(db, id) } }
    }

    /** The program on now and the next one on a guide channel; [shiftMs] is its EPG source's time shift. */
    @Synchronized fun nowAndNext(epgSourceId: String, guideChannelId: String, shiftMs: Long, now: Long): Pair<ProgramBrief?, ProgramBrief?> {
        val db = current() ?: return null to null
        val list = db.guideQueries.programsBetween(epgSourceId, guideChannelId, now - shiftMs, now - shiftMs + 12 * 60 * 60_000L).executeAsList()
            .map { ProgramBrief(it.title, it.start + shiftMs, it.stop + shiftMs) }
        val on = list.firstOrNull { now >= it.start && now < it.stop }
        val next = list.firstOrNull { it.start >= (on?.stop ?: now) }
        return on to next
    }

    @Synchronized private fun named(): List<Named> {
        val db = current() ?: return emptyList()
        channels?.let { return it }
        val built = db.guideQueries.countsBySource().executeAsList().flatMap { source ->
            val withPrograms = db.guideQueries.channelsWithPrograms(source.epg_source_id).executeAsList().toHashSet()
            val extra = db.guideQueries.channelNamesForSource(source.epg_source_id).executeAsList().groupBy({ it.guide_channel_id }, { it.normalized_name })
            db.guideQueries.guideChannelsForSource(source.epg_source_id).executeAsList().filter { it.id in withPrograms }.map { c ->
                val names = (listOf(c.normalized_name) + extra[c.id].orEmpty()).filter { it.isNotEmpty() }.toSet()
                Named(c.epg_source_id, c.id, c.display_name, names, names.flatMap { it.split(' ') }.toSet(), names.map { it.replace(" ", "") })
            }
        }
        channels = built
        return built
    }

    /**
     * Likely guide channels for a channel from every enabled EPG source (PLAN §4.3.18 → Guide mapping):
     * an exact normalized name or tvg-id first, then the most shared words.
     */
    fun suggest(rawName: String, tvgId: String?, sources: List<EpgSource>, now: Long, limit: Int = 3): List<GuideChannelInfo> {
        val name = GuideNames.normalize(rawName)
        val words = name.split(' ').filter { it.isNotEmpty() }.toSet()
        val enabled = sources.filter { it.enabled }.associateBy { it.id }
        val scored = named().filter { it.epgSourceId in enabled }.mapNotNull { c ->
            val score = when {
                tvgId != null && c.id.equals(tvgId, ignoreCase = true) -> 1.0
                name in c.names -> 0.95
                words.isEmpty() -> 0.0
                else -> {
                    val shared = words.count { it in c.tokens }
                    if (shared == 0) 0.0 else shared.toDouble() / (words.size + c.tokens.size - shared)
                }
            }
            if (score >= MIN_SCORE) c to score else null
        }.sortedByDescending { it.second }.take(limit)
        return scored.map { (c, score) -> info(c, enabled.getValue(c.epgSourceId), now, score) }
    }

    /** Guide channels whose name or ID contains [query], spaces aside ("hbo 2" finds "HBO2 HD"), from every enabled EPG source. */
    fun search(query: String, sources: List<EpgSource>, now: Long, limit: Int = 30): List<GuideChannelInfo> {
        val q = GuideNames.normalize(query).replace(" ", "")
        val raw = query.trim().lowercase()
        if (q.isEmpty() && raw.isEmpty()) return emptyList()
        val enabled = sources.filter { it.enabled }.associateBy { it.id }
        return named().asSequence().filter { it.epgSourceId in enabled }
            .filter { c -> (q.isNotEmpty() && c.compact.any { q in it }) || c.id.lowercase().contains(raw) || c.displayName.lowercase().contains(raw) }
            .take(limit).map { info(it, enabled.getValue(it.epgSourceId), now, 0.0) }.toList()
    }

    @Synchronized fun displayName(epgSourceId: String, id: String): String? = named().firstOrNull { it.epgSourceId == epgSourceId && it.id == id }?.displayName

    private fun info(c: Named, source: EpgSource, now: Long, score: Double): GuideChannelInfo {
        val (on, next) = nowAndNext(c.epgSourceId, c.id, source.timeShiftMs, now)
        return GuideChannelInfo(c.epgSourceId, source.name, c.id, c.displayName, score, on, next)
    }

    companion object {
        private const val MIN_SCORE = 0.34
    }
}

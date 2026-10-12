package com.signal.guide

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.data.FractionalIndex
import com.signal.data.Settings
import com.signal.data.newId
import com.signal.db.user.UserDatabase
import com.signal.model.EpgKind
import com.signal.model.EpgSource
import com.signal.model.Source
import com.signal.model.SourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * EPG sources and guide mappings (PLAN §6), in user.db. The default EPG source is a household
 * setting; the rest, in position order, are the fallback list.
 */
class EpgRepository(private val db: UserDatabase, private val settings: Settings, private val io: CoroutineDispatcher) {
    fun epgSources(): Flow<List<EpgSource>> = db.guideQueries.allEpgSources().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<EpgSource> = withContext(io) { db.guideQueries.allEpgSources().executeAsList().map { it.toModel() } }

    suspend fun byId(id: String): EpgSource? = withContext(io) { db.guideQueries.epgSourceById(id).executeAsOneOrNull()?.toModel() }

    /** The default EPG source's ID: the setting, else the first enabled source. */
    fun defaultId(sources: List<EpgSource>): String? =
        settings.raw(Settings.Key.DEFAULT_EPG_SOURCE)?.takeIf { id -> sources.any { it.id == id && it.enabled } }
            ?: sources.firstOrNull { it.enabled }?.id

    fun setDefault(id: String) = settings.put(Settings.Key.DEFAULT_EPG_SOURCE, id)

    /**
     * Xtream and Demo sources bring their own guide (PLAN §4.3.15 → EPG sources: "An Xtream source's
     * own xmltv.php guide is added automatically"). Returns true when one was added.
     */
    suspend fun ensureForSource(source: Source): Boolean = withContext(io) {
        val kind = when (source.kind) {
            SourceKind.XTREAM -> EpgKind.XTREAM
            SourceKind.DEMO -> EpgKind.DEMO
            SourceKind.M3U -> return@withContext false
        }
        if (db.guideQueries.epgSourceForSource(source.id).executeAsOneOrNull() != null) return@withContext false
        // Named after the source, so every device that adds it agrees on the ID (PLAN §9).
        save(EpgSource(id = "epg:${source.id}", name = "${source.name} guide", kind = kind, sourceId = source.id, position = nextPosition()))
        true
    }

    suspend fun removeForSource(sourceId: String) = withContext(io) {
        db.transaction {
            db.guideQueries.epgSourceForSource(sourceId).executeAsOneOrNull()?.let { db.guideQueries.removeMappingsForEpgSource(it.id) }
            db.guideQueries.deleteEpgSourceForSource(sourceId)
        }
    }

    suspend fun addXmltv(name: String, url: String): EpgSource = withContext(io) {
        EpgSource(id = newId(), name = name.trim().ifEmpty { "Guide" }, kind = EpgKind.XMLTV, url = url.trim(), position = nextPosition())
            .also(::save)
    }

    suspend fun update(source: EpgSource) = withContext(io) { save(source) }

    suspend fun delete(id: String) = withContext(io) {
        db.transaction {
            db.guideQueries.removeMappingsForEpgSource(id)
            db.guideQueries.deleteEpgSource(id)
        }
    }

    /** Moves an EPG source to [toIndex] in the fallback order. */
    suspend fun move(id: String, toIndex: Int) = withContext(io) {
        val list = db.guideQueries.allEpgSources().executeAsList().filter { it.id != id }
        val i = toIndex.coerceIn(0, list.size)
        db.guideQueries.setEpgPosition(FractionalIndex.between(list.getOrNull(i - 1)?.position, list.getOrNull(i)?.position), id)
    }

    fun markBuilt(id: String, at: Long) = db.guideQueries.markEpgBuilt(at, id)
    fun markFailed(id: String, error: String) = db.guideQueries.markEpgFailed(error, id)

    // ---- Mappings ------------------------------------------------------------------------

    data class Mapping(val epgSourceId: String, val guideChannelId: String?)

    fun mappings(): Flow<Map<String, Mapping>> = db.guideQueries.allMappings().asFlow().mapToList(io)
        .map { rows -> rows.associate { it.user_channel_id to Mapping(it.epg_source_id, it.guide_channel_id) } }

    suspend fun setMapping(userChannelId: String, epgSourceId: String, guideChannelId: String?) = withContext(io) {
        db.guideQueries.setMapping(userChannelId, epgSourceId, guideChannelId)
    }

    suspend fun removeMapping(userChannelId: String) = withContext(io) { db.guideQueries.removeMapping(userChannelId) }

    private fun nextPosition(): String =
        FractionalIndex.after(db.guideQueries.allEpgSources().executeAsList().lastOrNull()?.position)

    private fun save(s: EpgSource) = db.guideQueries.upsertEpgSource(
        com.signal.db.user.Epg_source(
            id = s.id,
            name = s.name,
            kind = s.kind.name,
            url = s.url,
            source_id = s.sourceId,
            enabled = if (s.enabled) 1 else 0,
            time_shift_minutes = s.timeShiftMinutes.toLong(),
            position = s.position,
            last_built_at = s.lastBuiltAt,
            last_error = s.lastError,
            created_at = Clock.System.now().toEpochMilliseconds(),
        ),
    )

    private fun com.signal.db.user.Epg_source.toModel() = EpgSource(
        id = id,
        name = name,
        kind = runCatching { EpgKind.valueOf(kind) }.getOrDefault(EpgKind.XMLTV),
        url = url,
        sourceId = source_id,
        enabled = enabled == 1L,
        timeShiftMinutes = time_shift_minutes.toInt(),
        position = position,
        lastBuiltAt = last_built_at,
        lastError = last_error,
    )
}

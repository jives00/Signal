package com.signal.guide

import com.signal.db.guide.GuideDatabase
import com.signal.match.GuideNames
import com.signal.model.EpgSource
import com.signal.model.GuideRef

/**
 * Which EPG source and guide channel a channel uses (PLAN §6 → Channel ↔ guide mapping), shared by
 * the app and the companion's web admin so both always agree. Pure: the caller caches the indexes.
 */
object GuideResolver {
    /** Per EPG source, built once per guide build: guide channel IDs that have programs, and their names. */
    class SourceIndex(
        /** Lowercased tvg-id → the guide's own ID: XMLTV IDs often differ from the M3U's only in case. */
        val byId: Map<String, String>,
        val byName: Map<String, String>,
    )

    data class Mapping(val epgSourceId: String, val guideChannelId: String?)

    fun buildIndex(db: GuideDatabase, epgSourceId: String): SourceIndex {
        val withPrograms = db.guideQueries.channelsWithPrograms(epgSourceId).executeAsList().toHashSet()
        val byId = HashMap<String, String>()
        for (id in withPrograms) byId.putIfAbsent(id.lowercase(), id)
        val byName = HashMap<String, String>()
        for (c in db.guideQueries.channelsForSource(epgSourceId).executeAsList()) {
            if (c.id in withPrograms && c.normalized_name.isNotEmpty()) byName.putIfAbsent(c.normalized_name, c.id)
        }
        // Then every other name each channel goes by; the first display-name wins a tie.
        for (n in db.guideQueries.channelNamesForSource(epgSourceId).executeAsList()) {
            if (n.guide_channel_id in withPrograms) byName.putIfAbsent(n.normalized_name, n.guide_channel_id)
        }
        return SourceIndex(byId, byName)
    }

    /**
     * The guide for a channel: its mapping's EPG source (else the default) first, then the others in
     * fallback order. In each: a manual guide channel, else the tvg-id, else the normalized name.
     */
    fun resolve(
        mapping: Mapping?,
        rawTvgId: String?,
        rawName: String,
        sources: List<EpgSource>,
        defaultId: String?,
        index: (epgSourceId: String) -> SourceIndex,
    ): GuideRef? {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) return null
        val chosenId = mapping?.epgSourceId?.takeIf { id -> enabled.any { it.id == id } } ?: defaultId
        val chosen = enabled.firstOrNull { it.id == chosenId }
        val order = listOfNotNull(chosen) + enabled.filter { it.id != chosenId }
        val name = GuideNames.normalize(rawName)
        val tvgId = rawTvgId?.takeIf { it.isNotBlank() }
        for ((i, src) in order.withIndex()) {
            val idx = index(src.id)
            val guideChannel = when {
                // 1. A manual mapping names both the EPG source and the guide channel: the strongest override.
                i == 0 && mapping?.guideChannelId != null && mapping.epgSourceId == src.id -> mapping.guideChannelId
                // 2. tvg-id in this EPG source.
                tvgId != null && tvgId.lowercase() in idx.byId -> idx.byId.getValue(tvgId.lowercase())
                // 3. The normalized name in this EPG source.
                else -> idx.byName[name]
            } ?: continue
            return GuideRef(src.id, guideChannel, src.timeShiftMs, isFallback = i > 0)
        }
        return null
    }

    /** The default EPG source's ID: the setting when it names an enabled source, else the first enabled one. */
    fun defaultId(setting: String?, sources: List<EpgSource>): String? =
        setting?.takeIf { id -> sources.any { it.id == id && it.enabled } } ?: sources.firstOrNull { it.enabled }?.id
}

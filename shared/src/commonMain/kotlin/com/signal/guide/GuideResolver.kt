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
        /** Normalized name → every guide channel going by it, best-known name first. */
        val byName: Map<String, List<String>>,
        /** Guide channel → when its last program starts: how far its listings go. */
        val lastStart: Map<String, Long> = emptyMap(),
    ) {
        /** Local stations by call sign: "WLS-DT.us_locals1". Their names are no use, since "WLS-DT" normalizes to "dt". */
        val byCallSign: Map<String, String> by lazy {
            val m = HashMap<String, String>()
            for ((id, original) in byId) stationSuffix.find(id)?.let { m.putIfAbsent(it.groupValues[1], original) }
            m
        }
        /** The same names with the spaces taken out: guides write "HBO2" where providers write "HBO 2". */
        val byCompact: Map<String, List<String>> by lazy {
            val m = HashMap<String, MutableList<String>>()
            for ((name, ids) in byName) {
                val list = m.getOrPut(name.replace(" ", "")) { ArrayList(1) }
                for (id in ids) if (id !in list) list += id
            }
            m
        }
    }

    data class Mapping(val epgSourceId: String, val guideChannelId: String?)

    /** Goes in anything cached from [resolve], so a change to the rules doesn't leave old answers behind. */
    const val RULES = 2

    /** A later guide must go this much further to win, so channels don't swap over a few hours' difference. */
    private const val FURTHER_MS = 12 * 60 * 60_000L
    private val stationSuffix = Regex("""^([kw][a-z]{2,3})-(?:dt|tv)\.""")

    fun buildIndex(db: GuideDatabase, epgSourceId: String): SourceIndex {
        val furthest = db.guideQueries.channelsFurthest(epgSourceId).executeAsList()
        val withPrograms = furthest.mapNotNullTo(HashSet()) { it.guide_channel_id }
        val lastStart = HashMap<String, Long>()
        for (row in furthest) {
            val id = row.guide_channel_id ?: continue
            lastStart[id] = row.last_start ?: 0
        }
        val byId = HashMap<String, String>()
        for (id in withPrograms) byId.putIfAbsent(id.lowercase(), id)
        val byName = HashMap<String, MutableList<String>>()
        fun add(name: String, id: String) {
            val list = byName.getOrPut(name) { ArrayList(1) }
            if (id !in list) list += id
        }
        for (c in db.guideQueries.channelsForSource(epgSourceId).executeAsList()) {
            if (c.id in withPrograms && c.normalized_name.isNotEmpty()) add(c.normalized_name, c.id)
        }
        // Then every other name each channel goes by.
        for (n in db.guideQueries.channelNamesForSource(epgSourceId).executeAsList()) {
            if (n.guide_channel_id in withPrograms) add(n.normalized_name, n.guide_channel_id)
        }
        return SourceIndex(byId, byName, lastStart)
    }

    /**
     * The guide for a channel: its mapping's EPG source (else the default) first, then the others in
     * fallback order. In each: a manual guide channel, else the tvg-id, else the normalized name.
     *
     * A name often matches several guide channels: "hbo" is HBO's Pacific feed in one guide, and HBO
     * Hungary and Poland in another (2026-10-14, the user's guides). Name matches are ranked: the
     * channel's own country first ("US: …" → `….us`, `….us2`), then not a Pacific or West feed; a
     * Pacific or West best match also tries the name + " east". A clean name match in a later source
     * beats a flawed one in an earlier source, which is used only when nothing better exists.
     *
     * With no mapping, every source gets a say, and the one whose listings go well past the others
     * wins: a provider's guide may stop hours from now where another has days (2026-11-06, Fox 32's
     * ended at 4 AM). A mapping's source is the user's choice, so it's taken as soon as it matches.
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
        val country = GuideNames.country(rawName)
        // The best flawed name match so far (wrong country, or a Pacific or West feed), used only if nothing clean turns up.
        var flawed: Pair<GuideRef, Int>? = null
        var best: GuideRef? = null
        var bestLast = Long.MIN_VALUE
        val callSign = GuideNames.callSign(rawName, tvgId)
        val us = country == "us" || tvgId?.substringAfterLast('.', "")?.lowercase()?.startsWith("us") == true
        val alternatives = if (us) GuideNames.otherNames(name, tvgId) else emptyList()
        for ((i, src) in order.withIndex()) {
            // 1. A manual mapping names both the EPG source and the guide channel: the strongest override.
            // Checked before the source's index is built: a mapped channel never needs it, and building
            // it first held every mapped row behind it (5.5 s for one guide on the Shield, 2026-10-16).
            if (i == 0 && mapping?.guideChannelId != null && mapping.epgSourceId == src.id) {
                return GuideRef(src.id, mapping.guideChannelId, src.timeShiftMs, isFallback = false)
            }
            val idx = index(src.id)
            fun ref(guideChannel: String) = GuideRef(src.id, guideChannel, src.timeShiftMs, isFallback = i > 0)
            // 2. tvg-id in this EPG source.
            var clean = tvgId?.let { idx.byId[it.lowercase()] }
            // 3. The normalized name in this EPG source, ranked.
            if (clean == null) bestByName(idx, name, country)?.let { (guideChannel, penalty) ->
                if (penalty == 0) clean = guideChannel
                else if (flawed == null || penalty < flawed!!.second) flawed = ref(guideChannel) to penalty
            }
            // 4. A US channel by other names: its call sign, then how else guides write it.
            if (clean == null && us) clean = callSign?.let { idx.byCallSign[it] } ?: otherName(idx, alternatives, country)
            val found = clean ?: continue
            if (mapping != null) return ref(found)
            val last = idx.lastStart[found] ?: 0
            if (best == null || last > bestLast + FURTHER_MS) {
                best = ref(found)
                bestLast = last
            }
        }
        return best ?: flawed?.first
    }

    private fun otherName(idx: SourceIndex, names: List<String>, country: String?): String? {
        for (name in names) bestByName(idx, name, country)?.takeIf { it.second == 0 }?.let { return it.first }
        return null
    }

    private fun bestByName(idx: SourceIndex, name: String, country: String?): Pair<String, Int>? {
        val exact = bestOf(idx.byName, name, " east", country)
        if (exact != null && exact.second == 0) return exact
        // Then ignoring spaces, so "HBO 2" finds "HBO2 HD"; the exact name wins a tie.
        val compact = bestOf(idx.byCompact, name.replace(" ", ""), "east", country)
        return listOfNotNull(exact, compact).minByOrNull { it.second }
    }

    private fun bestOf(byName: Map<String, List<String>>, name: String, east: String, country: String?): Pair<String, Int>? {
        fun ranked(key: String) = byName[key].orEmpty().map { it to penalty(it, country) }.minByOrNull { it.second }
        val best = ranked(name)
        if (best != null && best.second and REGIONAL == 0) return best
        // "HBO" is only HBO's Pacific feed by that name; the East one is "HBO East".
        val eastBest = ranked("$name$east")
        return if (eastBest != null && (best == null || eastBest.second < best.second)) eastBest else best
    }

    private const val REGIONAL = 1
    private const val OTHER_COUNTRY = 2
    private val regional = Regex("""\b(?:pacific|west)\b""")

    /** How far a guide channel ID is from right for a channel of [country]: 0 is clean. */
    private fun penalty(guideChannelId: String, country: String?): Int {
        val words = guideChannelId.lowercase().replace('.', ' ').replace('_', ' ').replace('(', ' ').replace(')', ' ')
        var p = if (regional.containsMatchIn(words)) REGIONAL else 0
        // XMLTV IDs end in a country, often with a list suffix: "HBO.us", "HBO.Drama.us2", "WBBM-DT.us_locals1".
        val idCountry = guideChannelId.substringAfterLast('.', "").lowercase().takeWhile { it.isLetter() }.takeIf { it.length in 2..3 }
        if (country != null && idCountry != null && idCountry != country) p += OTHER_COUNTRY
        return p
    }

    /** The default EPG source's ID: the setting when it names an enabled source, else the first enabled one. */
    fun defaultId(setting: String?, sources: List<EpgSource>): String? =
        setting?.takeIf { id -> sources.any { it.id == id && it.enabled } } ?: sources.firstOrNull { it.enabled }?.id
}

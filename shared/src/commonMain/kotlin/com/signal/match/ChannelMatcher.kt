package com.signal.match

/**
 * Matches channels across providers (PLAN §4.5 → Matching): is this stream on the new source the
 * same channel as the user's channel on the old one? Shared, so the NAS's provider switch review
 * and the TV's simpler one score the same way.
 *
 * Signals: the same tvg-id (strong), the same normalized name or a known alias (strong), shared
 * words (medium), the same logo or group (tie-breakers only). Numbers must agree, so Fox Sports 1
 * and 2 never merge, and the regional trap holds: when either name names a city or a callsign,
 * both must name the same one before a match is more than a suggestion.
 */
object ChannelMatcher {
    enum class Confidence { CONFIDENT, SUGGESTED }

    /** A stream, as the matcher sees it. */
    data class Stream(
        val id: String,
        val sourceId: String,
        val rawName: String,
        val tvgId: String?,
        val logoUrl: String? = null,
        val groupName: String = "",
    )

    data class Match(val stream: Stream, val score: Double, val confidence: Confidence, val reasons: List<String>)

    /** Built-in aliases (normalized → canonical); the user's confirmations add more (PLAN §4.5). */
    val builtInAliases: Map<String, String> = mapOf(
        "fs1" to "fox sports 1",
        "fs2" to "fox sports 2",
        "fsn" to "fox sports net",
        "nfl rz" to "nfl redzone",
        "redzone" to "nfl redzone",
        "nfl red zone" to "nfl redzone",
        "bein" to "bein sports",
        "espn u" to "espnu",
        "nbcsn" to "nbc sports network",
        "tnt usa" to "tnt",
        "tbs usa" to "tbs",
        "a and e" to "aande",
        "a e" to "aande",
        "hgtv usa" to "hgtv",
        "comedy central usa" to "comedy central",
        "cbssn" to "cbs sports network",
        "cbs sports net" to "cbs sports network",
        "mlbn" to "mlb network",
        "nhln" to "nhl network",
        "nba tv usa" to "nba tv",
    )

    private val dropWords = setOf("backup", "alt", "alternative", "feed", "us", "usa", "uk", "ca")
    private val callsign = Regex("^[kw][a-z]{2,3}$")
    private val regions = setOf(
        "new york", "ny", "nyc", "philadelphia", "philly", "chicago", "los angeles", "la", "boston", "detroit", "atlanta", "dallas",
        "houston", "miami", "seattle", "denver", "phoenix", "san francisco", "sf", "bay area", "washington", "dc", "minneapolis",
        "cleveland", "pittsburgh", "baltimore", "tampa", "orlando", "st louis", "kansas city", "sacramento", "portland", "charlotte",
        "san diego", "las vegas", "nashville", "milwaukee", "cincinnati", "indianapolis", "columbus", "salt lake", "raleigh",
        "new orleans", "buffalo", "hartford", "austin", "san antonio", "albany", "richmond", "memphis", "louisville", "oklahoma",
        "east", "west", "pacific", "mountain", "central",
    )

    /** The name compared: the guide's normalization, minus words that never tell two channels apart, then aliases. */
    fun canonical(name: String, aliases: Map<String, String> = builtInAliases): String {
        val normalized = GuideNames.normalize(name)
        aliases[normalized]?.let { return it }
        val joined = normalized.split(' ').filter { it.isNotEmpty() && it !in dropWords }.joinToString(" ")
        return aliases[joined] ?: joined
    }

    private fun numbers(words: List<String>) = words.filter { w -> w.any { it.isDigit() } }.toSet()

    /** City, region and callsign tokens in a normalized name (PLAN §4.5 → the regional trap). */
    fun regionTokens(canonicalName: String): Set<String> {
        val words = canonicalName.split(' ')
        val out = HashSet<String>()
        words.filter { callsign.matches(it) }.forEach { out += it }
        for (r in regions) {
            val parts = r.split(' ')
            if (parts.size == 1) { if (r in words) out += r } else if (" $canonicalName ".contains(" $r ")) out += r
        }
        return out
    }

    /** Scores [a] against [b]; null when they're clearly different channels. */
    fun score(a: Stream, b: Stream, aliases: Map<String, String> = builtInAliases): Match? {
        val ca = canonical(a.rawName, aliases)
        val cb = canonical(b.rawName, aliases)
        val wa = ca.split(' ').filter { it.isNotEmpty() }
        val wb = cb.split(' ').filter { it.isNotEmpty() }
        if (wa.isEmpty() || wb.isEmpty()) return null
        val reasons = ArrayList<String>()
        var score = 0.0
        val sameTvg = !a.tvgId.isNullOrBlank() && a.tvgId.equals(b.tvgId, ignoreCase = true)
        if (sameTvg) { score += 0.6; reasons += "same tvg-id" }
        val sameName = ca == cb
        if (sameName) {
            score += 0.6
            reasons += "same name"
        } else {
            val shared = wa.toSet().intersect(wb.toSet()).size
            val similarity = shared.toDouble() / (wa.toSet() + wb.toSet()).size
            if (similarity > 0) { score += 0.5 * similarity; reasons += "similar name" }
        }
        // Numbers tell channels apart: Fox Sports 1 vs 2, ESPN vs ESPN 2.
        if (numbers(wa) != numbers(wb) && !sameTvg) return null
        if (!a.logoUrl.isNullOrBlank() && a.logoUrl == b.logoUrl) { score += 0.05; reasons += "same logo" }
        if (a.groupName.isNotBlank() && GuideNames.normalize(a.groupName) == GuideNames.normalize(b.groupName)) { score += 0.05; reasons += "same group" }
        score = score.coerceAtMost(1.0)
        if (score < SUGGEST_AT) return null
        val ra = regionTokens(ca)
        val rb = regionTokens(cb)
        val regionalOk = (ra.isEmpty() && rb.isEmpty()) || ra == rb
        if (!regionalOk && !sameTvg) reasons += "different region"
        val confident = score >= CONFIDENT_AT && (sameTvg || sameName) && (regionalOk || sameTvg && sameName)
        return Match(b, score, if (confident) Confidence.CONFIDENT else Confidence.SUGGESTED, reasons)
    }

    /**
     * An index of one source's streams for fast lookups: by tvg-id, canonical name, and words that
     * aren't too common to mean anything.
     */
    class Index(streams: List<Stream>, private val aliases: Map<String, String> = builtInAliases) {
        private val byTvg = streams.filter { !it.tvgId.isNullOrBlank() }.groupBy { it.tvgId!!.lowercase() }
        private val byName = streams.groupBy { canonical(it.rawName, aliases) }
        private val byWord: Map<String, List<Stream>> = HashMap<String, MutableList<Stream>>().also { m ->
            for (s in streams) for (w in canonical(s.rawName, aliases).split(' ').toSet()) if (w.length > 1) m.getOrPut(w) { ArrayList() } += s
        }.filterValues { it.size <= COMMON_WORD }

        /** The best matches for [a], most likely first: confident ones, then suggestions. */
        fun matches(a: Stream, limit: Int = 3, exclude: (Stream) -> Boolean = { false }): List<Match> {
            val ca = canonical(a.rawName, aliases)
            val candidates = LinkedHashSet<Stream>()
            a.tvgId?.takeIf { it.isNotBlank() }?.let { byTvg[it.lowercase()]?.let(candidates::addAll) }
            byName[ca]?.let(candidates::addAll)
            for (w in ca.split(' ').toSet()) byWord[w]?.let(candidates::addAll)
            return candidates.asSequence().filter { it.id != a.id && !exclude(it) }.mapNotNull { score(a, it, aliases) }
                .sortedWith(compareBy<Match> { it.confidence }.thenByDescending { it.score })
                .take(limit).toList()
        }
    }

    /** Learned aliases (pairs of names the user confirmed as the same channel) added to the built-in ones. */
    fun aliases(learned: List<Pair<String, String>>): Map<String, String> {
        val out = HashMap(builtInAliases)
        for ((x, y) in learned) {
            val cx = canonical(x)
            val cy = canonical(y)
            if (cx != cy && cx.isNotEmpty() && cy.isNotEmpty()) out[cx] = cy
        }
        return out
    }

    const val CONFIDENT_AT = 0.6
    const val SUGGEST_AT = 0.3
    private const val COMMON_WORD = 1500
}

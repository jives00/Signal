package com.signal.match

import com.signal.data.VodRepository
import com.signal.model.VodItem
import com.signal.model.VodKind

/**
 * Catalog names as titles (PLAN §4.3.12: "normalized title and year"). The companion searches TMDB
 * with [query]; the app compares titles across sources (carry-over, Play from…) and against the
 * guide (Find in Movies/Series) with [key].
 */
object VodTitles {
    private val prefix = Regex("""^\s*(?:[A-Z]{2,3}|4K|UHD|FHD|HD|VOD)\s*[-|:•]\s*""")
    private val bracketed = Regex("""[(\[][^)\]]*[)\]]""")
    private val yearIn = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]|\b((?:19|20)\d{2})\s*$""")
    private val quality = Regex("""\b(?:4K|UHD|FHD|HD|SD|HDR|HEVC|x265|H\.?265|1080p|720p|2160p|MULTI|SUB|DUB|VOSTFR)\b""", RegexOption.IGNORE_CASE)
    private val trailingYear = Regex("""\s+(?:19|20)\d{2}\s*$""")
    private val spaces = Regex("""\s+""")

    /** The title alone: language and quality tags, bracketed parts and a trailing year removed. */
    fun query(name: String): String {
        var s = VodRepository.displayName(VodItem(id = "", sourceId = "", kind = VodKind.MOVIE, providerId = "", name = name, categoryId = ""))
        repeat(2) { s = s.replace(prefix, "") }
        s = s.replace(bracketed, " ").replace(quality, " ").replace(trailingYear, " ")
        return s.replace(spaces, " ").trim().ifEmpty { name.trim() }
    }

    fun year(item: VodItem): Int? = item.year ?: year(item.name)

    fun year(name: String): Int? = yearIn.find(name)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }

    /** For comparing titles: lower case letters and digits only, no accents, "&" as "and". */
    fun key(s: String): String = stripAccents(s.lowercase().replace("&", "and")).filter { it.isLetterOrDigit() }

    /** A catalog name's comparison key: [key] of its [query]. */
    fun nameKey(name: String): String = key(query(name))
}

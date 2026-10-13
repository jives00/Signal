package com.signal.server

import com.signal.match.VodTitles
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.nas.EpisodeMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What the companion keeps of a TMDB title (V4's tmdb_title.body): only the fields the app shows,
 * so a large catalog stays a few kilobytes a title rather than TMDB's full responses.
 */
@Serializable
data class TmdbTitle(
    val id: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMin: Int? = null,
    val rating: Double? = null,
    val contentRating: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val trailerKey: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    /** Series: TMDB's season numbers, specials (season 0) left out. */
    val seasons: List<Int> = emptyList(),
)

/** A search result, for matching and the web admin's Fix match. */
@Serializable
data class TmdbCandidate(val id: Long, val title: String, val originalTitle: String? = null, val year: Int? = null, val poster: String? = null, val overview: String? = null)

object TmdbParse {
    fun path(kind: String) = if (kind == VodKind.SERIES.name) "tv" else "movie"

    fun details(kind: String): String = if (kind == VodKind.SERIES.name) "credits,videos,content_ratings" else "credits,videos,release_dates"

    fun title(kind: String, o: JsonObject): TmdbTitle? {
        val id = o.long("id") ?: return null
        val series = kind == VodKind.SERIES.name
        val name = (if (series) o.str("name") else o.str("title")) ?: return null
        val crew = o.obj("credits")?.arr("crew").orEmpty().map { it.jsonObject }
        return TmdbTitle(
            id = id,
            title = name,
            originalTitle = (if (series) o.str("original_name") else o.str("original_title"))?.takeIf { it != name },
            year = yearOf(if (series) o.str("first_air_date") else o.str("release_date")),
            overview = o.str("overview")?.takeIf { it.isNotBlank() },
            genres = o.arr("genres").orEmpty().mapNotNull { it.jsonObject.str("name") },
            runtimeMin = if (series) o.arr("episode_run_time")?.firstOrNull()?.jsonPrimitive?.intOrNull else o.int("runtime")?.takeIf { it > 0 },
            rating = o["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
            contentRating = if (series) usRating(o.obj("content_ratings")) { it.str("rating") }
            else o.obj("release_dates")?.arr("results").orEmpty().map { it.jsonObject }.firstOrNull { it.str("iso_3166_1") == "US" }
                ?.arr("release_dates").orEmpty().mapNotNull { it.jsonObject.str("certification")?.takeIf { c -> c.isNotBlank() } }.firstOrNull(),
            poster = o.str("poster_path"),
            backdrop = o.str("backdrop_path"),
            trailerKey = o.obj("videos")?.arr("results").orEmpty().map { it.jsonObject }
                .filter { it.str("site") == "YouTube" }
                .sortedBy { if (it.str("type") == "Trailer") 0 else 1 }
                .firstOrNull()?.str("key"),
            cast = o.obj("credits")?.arr("cast").orEmpty().take(10).mapNotNull { it.jsonObject.str("name") },
            director = if (series) o.arr("created_by").orEmpty().mapNotNull { it.jsonObject.str("name") }.joinToString(", ").ifEmpty { null }
            else crew.filter { it.str("job") == "Director" }.mapNotNull { it.str("name") }.joinToString(", ").ifEmpty { null },
            seasons = if (series) o.arr("seasons").orEmpty().mapNotNull { it.jsonObject.int("season_number") }.filter { it > 0 }.sorted() else emptyList(),
        )
    }

    private fun usRating(o: JsonObject?, value: (JsonObject) -> String?): String? =
        o?.arr("results").orEmpty().map { it.jsonObject }.firstOrNull { it.str("iso_3166_1") == "US" }?.let(value)?.takeIf { it.isNotBlank() }

    fun season(o: JsonObject): List<EpisodeMeta> = o.arr("episodes").orEmpty().mapNotNull { e ->
        val ep = e.jsonObject
        EpisodeMeta(
            season = ep.int("season_number") ?: return@mapNotNull null,
            episode = ep.int("episode_number") ?: return@mapNotNull null,
            title = ep.str("name"),
            overview = ep.str("overview")?.takeIf { it.isNotBlank() },
            still = ep.str("still_path"),
            airDate = ep.str("air_date")?.takeIf { it.isNotBlank() },
            runtimeMin = ep.int("runtime"),
        )
    }

    fun results(kind: String, o: JsonObject): List<TmdbCandidate> = o.arr("results").orEmpty().mapNotNull { r ->
        val x = r.jsonObject
        val series = kind == VodKind.SERIES.name
        TmdbCandidate(
            id = x.long("id") ?: return@mapNotNull null,
            title = (if (series) x.str("name") else x.str("title")) ?: return@mapNotNull null,
            originalTitle = if (series) x.str("original_name") else x.str("original_title"),
            year = yearOf(if (series) x.str("first_air_date") else x.str("release_date")),
            poster = x.str("poster_path"),
            overview = x.str("overview"),
        )
    }

    fun ids(o: JsonObject): List<Long> = o.arr("results").orEmpty().mapNotNull { it.jsonObject.long("id") }

    private fun yearOf(date: String?) = date?.take(4)?.toIntOrNull()
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray)?.jsonArray
}

/** Catalog names into TMDB search queries (PLAN §4.3.12: "normalized title and year"); the rules are shared with the app in [VodTitles]. */
object TitleNormalizer {
    fun query(name: String): String = VodTitles.query(name)

    fun year(item: VodItem): Int? = VodTitles.year(item)

    fun key(s: String): String = VodTitles.key(s)

    /**
     * The search result to take, or null: an exact title match within a year either way, else the
     * first result within a year whose title contains the query or the other way round.
     */
    fun pick(query: String, year: Int?, results: List<TmdbCandidate>): TmdbCandidate? {
        val q = key(query)
        if (q.isEmpty()) return null
        fun yearOk(c: TmdbCandidate) = year == null || c.year == null || kotlin.math.abs(c.year - year) <= 1
        fun keys(c: TmdbCandidate) = listOfNotNull(c.title, c.originalTitle).map(::key)
        results.firstOrNull { c -> yearOk(c) && keys(c).any { it == q } }?.let { return it }
        if (year == null) return null
        return results.firstOrNull { c -> yearOk(c) && c.year != null && keys(c).any { k -> k.isNotEmpty() && (k.contains(q) || q.contains(k)) } }
    }
}

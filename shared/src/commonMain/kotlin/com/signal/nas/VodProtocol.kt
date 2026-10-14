package com.signal.nas

import kotlinx.serialization.Serializable

/** VOD enrichment from the companion (PLAN §4.3.12). Device endpoints, all with the device's key. */
object NasVodPaths {
    /** GET → [VodIndex], all in one body. Kept for app versions before [INDEX_PAGE]. */
    const val INDEX = "/device/vod/index"
    /**
     * GET → [VodIndexPage]: the index a page at a time, since at the real catalog's size one body is tens
     * of MB (PLAN §8 → milestone 5). `page` from 0; page 0 takes `have` (304 when unchanged), and the
     * later pages take page 0's `version` (409 when the index changed in between: start again).
     */
    const val INDEX_PAGE = "/device/vod/index/page"
    /** GET → [VodMeta] for one catalog title; 404 when it isn't matched. */
    fun meta(itemId: String) = "/device/vod/meta/${itemId.encodeURLPath()}"
    /** Images the companion caches and resizes for TV: `/images/tmdb/<size>/<file>`. Open on the home network and Tailscale. */
    const val IMAGES = "/images/tmdb"
    /** A provider's poster for a title TMDB has none for, cached and resized: `/images/provider/<item id>`. Open like [IMAGES]. */
    const val PROVIDER_IMAGES = "/images/provider"
    fun providerImage(itemId: String) = "$PROVIDER_IMAGES/${itemId.encodeURLPath()}"

    private fun String.encodeURLPath() = buildString {
        for (b in this@encodeURLPath.encodeToByteArray()) {
            val ch = b.toInt().toChar()
            if (ch.isLetterOrDigit() || ch in "-._~") append(ch) else append('%').append((b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * What the browse pages need from enrichment: each matched title's TMDB ID and poster, the TMDB lists
 * and the genres, all as the catalog's own item IDs (only titles the sources have, PLAN §4.3.6).
 */
@Serializable
data class VodIndex(
    /** Bumped when matches or lists change, so the TV can skip an unchanged index. */
    val version: Long,
    val tmdbIds: Map<String, Long> = emptyMap(),
    /** TMDB poster file paths ("/abc.jpg"), by item ID. */
    val posters: Map<String, String> = emptyMap(),
    /** "MOVIE:trending" → item IDs in TMDB's order. Lists: trending, popular, new, airing, top_rated. */
    val lists: Map<String, List<String>> = emptyMap(),
    /** "MOVIE" → genre → item IDs. */
    val genres: Map<String, Map<String, List<String>>> = emptyMap(),
)

/** One page of the index: some of its matches, then shelf rows, in the order [VodIndexPager] lays them out. */
@Serializable
data class VodIndexPage(
    val version: Long,
    val page: Int,
    val pages: Int,
    val matches: List<VodIndexMatch> = emptyList(),
    val shelves: List<VodShelfRow> = emptyList(),
)

/** A catalog title's TMDB ID, and its poster's file path when TMDB has one. */
@Serializable
data class VodIndexMatch(val item: String, val tmdbId: Long, val poster: String? = null)

/** A title's place on a shelf. [shelf] is the device's key: "list:trending", "genre:Action". */
@Serializable
data class VodShelfRow(val kind: String, val shelf: String, val position: Int, val item: String)

/**
 * [index] as flat rows, matches first, then lists, then genres, cut into pages of [pageSize] rows.
 * Built once per index version on the companion; the device stores each page as it arrives.
 */
class VodIndexPager(val index: VodIndex, private val pageSize: Int = PAGE_ROWS) {
    private val matches: List<VodIndexMatch> by lazy {
        index.tmdbIds.map { (item, id) -> VodIndexMatch(item, id, index.posters[item]) }
    }
    private val shelves: List<VodShelfRow> by lazy {
        val rows = ArrayList<VodShelfRow>()
        for ((key, items) in index.lists) {
            val kind = key.substringBefore(':')
            items.forEachIndexed { i, item -> rows += VodShelfRow(kind, LIST + key.substringAfter(':'), i, item) }
        }
        for ((kind, genres) in index.genres) for ((genre, items) in genres) {
            items.forEachIndexed { i, item -> rows += VodShelfRow(kind, GENRE + genre, i, item) }
        }
        rows
    }

    val pages: Int get() = (matches.size + shelves.size).let { if (it == 0) 1 else (it - 1) / pageSize + 1 }

    /** Null past the last page. */
    fun page(page: Int): VodIndexPage? {
        if (page !in 0 until pages) return null
        val from = page * pageSize
        val to = from + pageSize
        return VodIndexPage(
            version = index.version,
            page = page,
            pages = pages,
            matches = matches.subList(from.coerceAtMost(matches.size), to.coerceAtMost(matches.size)),
            shelves = shelves.subList((from - matches.size).coerceIn(0, shelves.size), (to - matches.size).coerceIn(0, shelves.size)),
        )
    }

    companion object {
        /** About 1 MB of JSON at most: a few seconds' parse on the Shield at worst, off the UI thread. */
        const val PAGE_ROWS = 10_000
        /** The device's shelf keys (`VodEnrichment`'s): a TMDB list, or a genre. */
        const val LIST = "list:"
        const val GENRE = "genre:"
    }
}

/** One title's enriched metadata (PLAN §4.3.8 → Information). Image fields are TMDB file paths. */
@Serializable
data class VodMeta(
    val itemId: String,
    val tmdbId: Long,
    val title: String,
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
    /** The director for movies, the creators for series. */
    val director: String? = null,
    /** Series: TMDB's episodes for the seasons the source has (PLAN §4.3.9). */
    val episodes: List<EpisodeMeta> = emptyList(),
)

@Serializable
data class EpisodeMeta(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    /** "2026-10-01". */
    val airDate: String? = null,
    val runtimeMin: Int? = null,
)

/** TMDB image sizes the companion serves (PLAN §4.3.12: posters about 500 wide, backdrops about 1920). */
object TmdbImages {
    const val POSTER = "w500"
    const val BACKDROP = "backdrop"
    const val STILL = "w780"

    /** TMDB's own CDN, for when the NAS isn't reachable. */
    fun direct(size: String, path: String) = "https://image.tmdb.org/t/p/${if (size == BACKDROP) "w1280" else size}$path"
}

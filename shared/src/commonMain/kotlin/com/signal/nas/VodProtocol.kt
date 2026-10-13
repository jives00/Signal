package com.signal.nas

import kotlinx.serialization.Serializable

/** VOD enrichment from the companion (PLAN §4.3.12). Device endpoints, all with the device's key. */
object NasVodPaths {
    /** GET → [VodIndex]. */
    const val INDEX = "/device/vod/index"
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

package com.signal.model

/** Movies and series from a source's Xtream VOD APIs (PLAN §2 "VOD", §4.3.6–4.3.7). */
enum class VodKind { MOVIE, SERIES }

/** A movie or a series in a source's catalog: cached source data, rebuilt on refresh like streams. */
data class VodItem(
    /** `<source>:v:<id>` for movies, `<source>:s:<id>` for series: stable across refreshes (PLAN §5.4). */
    val id: String,
    val sourceId: String,
    val kind: VodKind,
    val providerId: String,
    val name: String,
    val categoryId: String,
    val posterUrl: String? = null,
    /** The provider's poster, when [posterUrl] is the companion's: shown if that one fails to load. */
    val fallbackPosterUrl: String? = null,
    val year: Int? = null,
    /** The provider's 0–10 rating. */
    val rating: Double? = null,
    /** Epoch seconds, from Xtream's `added` or `last_modified`; drives Recently added. */
    val addedAt: Long = 0,
    val tmdbId: Long? = null,
    /** Movies: the file extension Xtream serves (mkv, mp4). */
    val containerExt: String? = null,
    val plot: String? = null,
    /** A direct URL, for the Demo source; Xtream URLs are built at play time. */
    val url: String? = null,
    /** Order in the provider's list. */
    val position: Int = 0,
)

data class VodCategory(val sourceId: String, val kind: VodKind, val categoryId: String, val name: String, val position: Int)

object VodIds {
    fun movie(sourceId: String, providerId: String) = "$sourceId:v:$providerId"
    fun series(sourceId: String, providerId: String) = "$sourceId:s:$providerId"
}

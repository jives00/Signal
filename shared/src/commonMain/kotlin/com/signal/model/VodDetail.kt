package com.signal.model

/** A movie's detail (PLAN §4.3.8), from the provider's get_vod_info until the NAS enriches it. */
data class MovieDetail(
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val cast: String? = null,
    val director: String? = null,
    val durationSec: Int? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    /** A YouTube video ID (PLAN §4.3.8 → Trailer). */
    val trailerKey: String? = null,
    val tmdbId: Long? = null,
    val containerExt: String? = null,
)

/** One episode (PLAN §4.3.9). IDs are `<source>:e:<provider id>`. */
data class Episode(
    val id: String,
    val providerId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String? = null,
    val durationSec: Int? = null,
    val stillUrl: String? = null,
    val containerExt: String? = null,
    /** A direct URL, for the Demo source. */
    val url: String? = null,
) {
    val code: String get() = "S$season E$episode"
}

/** A series' detail and episodes (PLAN §4.3.9), from get_series_info. */
data class SeriesDetail(
    val plot: String? = null,
    val genres: List<String> = emptyList(),
    val cast: String? = null,
    val director: String? = null,
    val rating: Double? = null,
    val backdropUrl: String? = null,
    val trailerKey: String? = null,
    val tmdbId: Long? = null,
    val episodes: List<Episode> = emptyList(),
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
    fun inSeason(season: Int) = episodes.filter { it.season == season }.sortedBy { it.episode }

    /** The episode after [season]·[episode], into the next season when needed, or null at the end. */
    fun after(season: Int, episode: Int): Episode? =
        episodes.sortedWith(compareBy({ it.season }, { it.episode })).firstOrNull { it.season > season || (it.season == season && it.episode > episode) }
}

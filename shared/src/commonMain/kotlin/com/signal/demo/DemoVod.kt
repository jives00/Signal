package com.signal.demo

import com.signal.model.VodCategory
import com.signal.model.VodIds
import com.signal.model.VodItem
import com.signal.model.VodKind
import kotlin.random.Random

/**
 * The Demo source's movies and series (PLAN §7.4): seeded, synthetic titles that play public test
 * videos, so Movies and Series work without a provider. Names carry the junk real catalogs do
 * (language prefixes, the year in brackets), so clean-up is exercised.
 */
object DemoVod {
    private const val SEED = DemoData.SEED + 4

    /** Public on-demand test videos (Big Buck Bunny, Apple's bipbop, Tears of Steel). */
    val testVideos = listOf(
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        "https://test-streams.mux.dev/tos_ismc/main.m3u8",
    )

    private val movieCategories = listOf("Action", "Comedy", "Drama", "Documentary", "Family", "Sci-Fi", "EN | Thriller", "4K Movies")
    private val seriesCategories = listOf("Drama Series", "Comedy Series", "Kids Series", "Docuseries", "EN | Crime")

    private val first = listOf("The", "Last", "Silent", "Broken", "Golden", "Hidden", "Midnight", "Electric", "Lost", "Northern", "Crimson", "Final", "Distant", "Wild", "Paper")
    private val second = listOf("River", "Empire", "Signal", "Harbor", "Frontier", "Garden", "Machine", "Kingdom", "Echo", "Summer", "Horizon", "Station", "Orchard", "Voyage", "Archive")
    private val decorations = listOf<(String, Int) -> String>(
        { n, y -> "$n ($y)" },
        { n, _ -> "EN - $n" },
        { n, y -> "$n [$y]" },
        { n, _ -> "$n 4K" },
        { n, _ -> n },
        { n, _ -> n },
    )

    private fun title(rnd: Random): String = "${first[rnd.nextInt(first.size)]} ${second[rnd.nextInt(second.size)]}"

    fun categories(sourceId: String, kind: VodKind): List<VodCategory> =
        (if (kind == VodKind.MOVIE) movieCategories else seriesCategories).mapIndexed { i, name -> VodCategory(sourceId, kind, "${i + 1}", name, i) }

    /** Recently added is relative to a fixed day, so the order is the same on every run. */
    private const val BASE_ADDED = 1_790_000_000L // 2026-09-21

    fun movies(sourceId: String): List<VodItem> {
        val rnd = Random(SEED)
        return (0 until 360).map { i ->
            val year = 1975 + rnd.nextInt(51)
            val name = decorations[rnd.nextInt(decorations.size)](title(rnd), year)
            VodItem(
                id = VodIds.movie(sourceId, "${1000 + i}"),
                sourceId = sourceId,
                kind = VodKind.MOVIE,
                providerId = "${1000 + i}",
                name = name,
                categoryId = "${i % movieCategories.size + 1}",
                year = year,
                rating = (40 + rnd.nextInt(55)) / 10.0,
                addedAt = BASE_ADDED - rnd.nextLong(180L * 24 * 60 * 60),
                containerExt = "m3u8",
                plot = "A synthetic film from the Demo source, for development only. It stands in for a real catalog's plot summary, which usually runs to a few sentences.",
                url = testVideos[i % testVideos.size],
                position = i,
            )
        }
    }

    /** A Demo movie's detail: synthetic, with the test video's real length roughly. */
    fun movieDetail(item: VodItem): com.signal.model.MovieDetail = com.signal.model.MovieDetail(
        plot = item.plot, genres = listOf(movieCategories[(item.categoryId.toIntOrNull() ?: 1) - 1].substringAfter("| ")),
        cast = "Alex Doe, Sam Roe, Jordan Poe", director = "Casey Moe", durationSec = 596, releaseDate = "${item.year}-01-01",
        rating = item.rating, containerExt = "m3u8",
    )

    /** A Demo series: 2 to 4 seasons of 6 to 10 episodes, seeded by the series. */
    fun seriesDetail(item: VodItem): com.signal.model.SeriesDetail {
        val rnd = Random(SEED + item.providerId.hashCode())
        val episodes = (1..(2 + rnd.nextInt(3))).flatMap { season ->
            (1..(6 + rnd.nextInt(5))).map { n ->
                val pid = "${item.providerId}${season.toString().padStart(2, '0')}${n.toString().padStart(2, '0')}"
                com.signal.model.Episode(
                    id = "${item.sourceId}:e:$pid", providerId = pid, season = season, episode = n,
                    title = "${first[rnd.nextInt(first.size)]} ${second[rnd.nextInt(second.size)]}",
                    plot = "A synthetic episode from the Demo source.", durationSec = 596,
                    containerExt = "m3u8", url = testVideos[(season + n) % testVideos.size],
                )
            }
        }
        return com.signal.model.SeriesDetail(plot = item.plot, genres = listOf("Drama"), cast = "Alex Doe, Sam Roe", rating = item.rating, episodes = episodes)
    }

    fun series(sourceId: String): List<VodItem> {
        val rnd = Random(SEED + 1)
        return (0 until 60).map { i ->
            val year = 2000 + rnd.nextInt(26)
            VodItem(
                id = VodIds.series(sourceId, "${500 + i}"),
                sourceId = sourceId,
                kind = VodKind.SERIES,
                providerId = "${500 + i}",
                name = decorations[rnd.nextInt(decorations.size)](title(rnd), year),
                categoryId = "${i % seriesCategories.size + 1}",
                year = year,
                rating = (50 + rnd.nextInt(45)) / 10.0,
                addedAt = BASE_ADDED - rnd.nextLong(120L * 24 * 60 * 60),
                plot = "A synthetic series from the Demo source, for development only.",
                position = i,
            )
        }
    }
}

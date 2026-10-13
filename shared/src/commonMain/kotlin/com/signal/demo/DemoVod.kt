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

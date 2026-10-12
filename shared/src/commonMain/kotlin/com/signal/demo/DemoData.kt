package com.signal.demo

import com.signal.model.ProviderStream
import com.signal.model.StreamIds
import kotlin.random.Random

/**
 * The Demo source (PLAN §7.4): a synthetic, seeded channel list and guide, so development never
 * depends on the real provider or uses up its connection limit. Only synthetic names and public
 * test streams, so it's safe in the public repo. Debug builds only.
 *
 * Names deliberately include the junk real providers send (prefixes, quality tags, decorative
 * Unicode, flags) so character clean-up and display are exercised on every run.
 */
object DemoData {
    const val SEED = 20261011
    const val CHANNEL_COUNT = 2000

    /** Public test streams, cycled across the channels. One is live, the rest are VOD standing in for live. */
    val testStreams = listOf(
        "https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8",
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        "https://test-streams.mux.dev/tos_ismc/main.m3u8",
    )

    private val groups = listOf(
        "US Sports" to listOf("Sports Center", "Sports Net", "Golf Channel", "Tennis TV", "Racing Live", "Fight Zone", "Hoops TV", "Gridiron", "Puck Network", "Diamond TV"),
        "US News" to listOf("News 24", "World Report", "Business Now", "Weather Watch", "Capitol News", "Morning Desk"),
        "US Entertainment" to listOf("Drama Plus", "Comedy Central Park", "Reality One", "Classic TV", "Prime Stories", "Sitcom Gold"),
        "Movies" to listOf("Cinema One", "Action Max", "Romance Reels", "Thriller Zone", "Family Films", "Indie Screen", "Retro Movies"),
        "Kids" to listOf("Toon Town", "Little Explorers", "Junior Science", "Cartoon Classics", "Teen Zone"),
        "Music" to listOf("Hits Radio TV", "Rock Arena", "Country Roads", "Jazz Lounge", "Hip Hop Live"),
        "Documentary" to listOf("Planet Wild", "History Vault", "Science Channel X", "Ocean Deep", "Space Today"),
        "Local" to listOf("FOX 5 New York", "FOX 29 Philadelphia", "ABC 7 Chicago", "NBC 4 Los Angeles", "CBS 2 Boston", "FOX 13 Tampa", "ABC 13 Houston"),
        "UK" to listOf("Britannia One", "London Live", "Highland TV", "Albion Drama"),
        "Canada" to listOf("Maple Sports", "North News", "Prairie TV"),
        "Español" to listOf("Deportes Uno", "Noticias Hoy", "Novelas Plus", "Cine Latino"),
        "4K" to listOf("Ultra Nature", "Ultra Sports", "Ultra Cinema"),
    )

    private val decorations = listOf(
        { n: String -> "US: $n" },
        { n: String -> "|US| $n" },
        { n: String -> "$n HD" },
        { n: String -> "$n FHD" },
        { n: String -> "$n ᴴᴰ" },
        { n: String -> "┃US┃ $n" },
        { n: String -> "🇺🇸 $n" },
        { n: String -> "$n (Backup)" },
        { n: String -> "⭐ $n" },
        { n: String -> n },
        { n: String -> n },
        { n: String -> n },
    )

    /** [name] is decorated like a provider's; [baseName] is the clean name, which the Demo guide uses. */
    data class DemoChannel(val name: String, val group: String, val number: Int, val tvgId: String, val url: String, val baseName: String)

    fun channels(): List<DemoChannel> {
        val rnd = Random(SEED)
        val out = ArrayList<DemoChannel>(CHANNEL_COUNT)
        var n = 0
        while (out.size < CHANNEL_COUNT) {
            for ((group, names) in groups) {
                for (base in names) {
                    if (out.size >= CHANNEL_COUNT) break
                    val round = n / groups.sumOf { it.second.size }
                    // Later rounds add numbered siblings (Sports Net 2, 3...) to reach the target size.
                    val name = if (round == 0) base else "$base ${round + 1}"
                    val decorated = decorations[rnd.nextInt(decorations.size)](name)
                    val tvgId = name.lowercase().replace(Regex("[^a-z0-9]+"), ".") + ".demo"
                    out += DemoChannel(decorated, if (round == 0) group else "$group ${'A' + (round - 1) % 26}", out.size + 1, tvgId, testStreams[out.size % testStreams.size], name)
                    n++
                }
            }
        }
        return out
    }

    fun streams(sourceId: String): List<ProviderStream> = channels().mapIndexed { i, c ->
        val providerId = StreamIds.fnv1a64("demo:${c.tvgId}:$i")
        ProviderStream(
            id = StreamIds.m3u(sourceId, providerId),
            sourceId = sourceId,
            providerId = providerId,
            rawName = c.name,
            groupName = c.group,
            logoUrl = null,
            tvgId = c.tvgId,
            providerNumber = c.number,
            position = i,
            url = c.url,
        )
    }

    data class DemoProgram(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val description: String,
        val category: String,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeTitle: String? = null,
        val isNew: Boolean = false,
        val isLive: Boolean = false,
    )

    private class Show(val title: String, val category: String, val series: Boolean = false, val live: Boolean = false)

    private val shows = listOf(
        Show("Morning Report", "News", live = true), Show("Championship Final", "Sports", live = true), Show("The Late Show", "Talk"),
        Show("Nature's Edge", "Documentary", series = true), Show("Kitchen Masters", "Cooking", series = true),
        Show("Breaking Point", "Drama", series = true), Show("Highlights Tonight", "Sports"), Show("Science Uncovered", "Documentary", series = true),
        Show("The Long Road Home", "Movie"), Show("Weekend Preview", "Sports"), Show("Market Watch", "News", live = true),
        Show("Road Trip", "Reality", series = true), Show("Inside the Game", "Sports"), Show("Mystery Hour", "Drama", series = true),
        Show("Cartoon Marathon", "Kids"),
    )

    private val episodeTitles = listOf("The Beginning", "Crossroads", "Out of Time", "Homecoming", "The Storm", "Second Chances", "Finale")

    /**
     * A deterministic schedule for one channel: programs of 15 minutes to 3 hours, aligned to quarter
     * hours, from [fromMs] to [days] later. It's anchored to the UTC day before [fromMs], so a channel
     * shows the same programs whenever it's built that day. The guide spike and the Demo guide share it (PLAN §7.4).
     */
    fun programs(channelIndex: Int, fromMs: Long, days: Int = 7): List<DemoProgram> {
        val quarter = 15 * 60_000L
        val day = 24 * 60 * 60_000L
        val rnd = Random(SEED + channelIndex)
        val endMs = fromMs + days * day
        var t = fromMs - fromMs % day - day + rnd.nextInt(8) * quarter
        val out = ArrayList<DemoProgram>()
        while (t < endMs) {
            val length = quarter * listOf(1, 2, 2, 4, 4, 4, 6, 8, 12)[rnd.nextInt(9)]
            val show = shows[rnd.nextInt(shows.size)]
            val episode = if (show.series) rnd.nextInt(1, 24) else null
            val p = DemoProgram(
                title = show.title, startMs = t, endMs = t + length,
                description = "A synthetic program from the Demo source, for development only. It stands in for a real guide's synopsis, which often runs to two or three lines.",
                category = show.category,
                season = if (show.series) rnd.nextInt(1, 9) else null,
                episode = episode,
                episodeTitle = episode?.let { episodeTitles[it % episodeTitles.size] },
                isNew = show.series && rnd.nextInt(4) == 0,
                isLive = show.live,
            )
            if (p.endMs > fromMs) out += p
            t += length
        }
        return out
    }
}

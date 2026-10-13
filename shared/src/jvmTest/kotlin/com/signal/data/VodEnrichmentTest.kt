package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.model.Episode
import com.signal.model.MovieDetail
import com.signal.model.SeriesDetail
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.model.VodKind
import com.signal.nas.EpisodeMeta
import com.signal.nas.NasConnection
import com.signal.nas.NasVodPaths
import com.signal.nas.VodIndex
import com.signal.nas.VodMeta
import com.signal.platform.AppInfo
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The companion's VOD index on the device (PLAN §4.3.12): shelves, posters, and moving to TMDB keys. */
class VodEnrichmentTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    private val sources = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })
    private val vod = VodRepository(db, Dispatchers.IO) { path -> "http://nas/images/tmdb/w500$path" }
    private val watch = WatchRepository(db, settings, Dispatchers.IO)
    private var indexCalls = 0
    private val json = Json { encodeDefaults = true }
    private var served: VodIndex? = null
    private val nas = NasConnection(
        settings,
        HttpClient(MockEngine { r ->
            when {
                r.url.encodedPath == "/health" -> respond("""{"status":"ok","version":"0.4.0","database":"ok"}""")
                r.url.encodedPath == NasVodPaths.INDEX -> {
                    indexCalls++
                    val index = served!!
                    if (r.url.parameters["have"] == index.version.toString()) respond("", HttpStatusCode.NotModified)
                    else respond(json.encodeToString(VodIndex.serializer(), index))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }),
        box, AppInfo("0.4.0", "dev", isDebug = true, deviceModel = "SHIELD"), Log { _, _ -> },
    )
    private val enrichment = VodEnrichment(db, settings, vod, { nas }, Dispatchers.IO, Log { _, _ -> })

    private suspend fun demo() {
        sources.save(Source(id = "demo", name = "Demo", kind = SourceKind.DEMO, url = "demo:"))
        sources.refresh("demo").getOrThrow()
    }

    @Test fun shelvesKeepTmdbOrderAndPostersComeFromTheNas(): Unit = runBlocking {
        demo()
        val movies = vod.recentlyAdded(VodKind.MOVIE, 3)
        val (a, b, c) = movies
        enrichment.store(
            VodIndex(
                version = 7,
                tmdbIds = mapOf(a.id to 603, b.id to 27205, c.id to 603),
                posters = mapOf(a.id to "/matrix.jpg"),
                lists = mapOf("MOVIE:trending" to listOf(b.id, a.id, c.id)),
                genres = mapOf("MOVIE" to mapOf("Action" to listOf(a.id, b.id))),
            ),
        )
        assertEquals(listOf("genre:Action" to 2, "list:trending" to 3), vod.shelves(VodKind.MOVIE).first().map { it.shelf to it.count }.sortedBy { it.first })
        // c is the same TMDB title as a, from the list's later position: shown once.
        assertEquals(listOf(b.id, a.id), vod.shelfItems(VodKind.MOVIE, "list:trending").map { it.id })
        val enriched = vod.item(a.id)!!
        assertEquals(603L, enriched.tmdbId)
        assertEquals("http://nas/images/tmdb/w500/matrix.jpg", enriched.posterUrl)
        assertEquals(a.posterUrl, enriched.fallbackPosterUrl)
        assertEquals("7", settings.raw(Settings.Key.VOD_INDEX_VERSION))
    }

    @Test fun watchDataMovesToTheTmdbKeyOnceMatched(): Unit = runBlocking {
        demo()
        val movie = vod.recentlyAdded(VodKind.MOVIE, 1).single()
        val (series, other) = vod.recentlyAdded(VodKind.SERIES, 2)
        // Before the NAS matched them: keyed by the source's item IDs.
        watch.addToLibrary(movie)
        watch.savePosition(WatchRepository.Position(movie.id, "MOVIE", movie.id, null, null, null, null, "M", 120_000, 600_000, 0))
        watch.markWatched("${series.id}:s1e1", "EPISODE", series.id, series.id, 1, 1)
        watch.savePosition(WatchRepository.Position("${series.id}:s1e2", "EPISODE", series.id, "demo:e:2", series.id, 1, 2, "E", 60_000, 600_000, 0))
        // Hiding clears that series' positions, so another one.
        watch.hideSeries(other.id)

        enrichment.store(VodIndex(version = 1, tmdbIds = mapOf(movie.id to 603, series.id to 1396, other.id to 1399)))
        val state = watch.current()
        assertEquals(listOf("tmdb:movie:603"), state.library.map { it.key })
        assertTrue("tmdb:movie:603" in state.positions)
        assertTrue("tmdb:tv:1396:s1e1" in state.watched)
        assertEquals("tmdb:tv:1396", state.positions.getValue("tmdb:tv:1396:s1e2").seriesKey)
        assertTrue("tmdb:tv:1399" in state.hidden)
        // New saves use the same key, so nothing splits in two.
        assertEquals("tmdb:movie:603", ItemKeys.movie(vod.item(movie.id)!!))
    }

    @Test fun anUnchangedIndexIsNotStoredAgain(): Unit = runBlocking {
        settings.put(Settings.Key.NAS_API_KEY, "key")
        served = VodIndex(version = 3, tmdbIds = mapOf("x" to 1))
        assertTrue(enrichment.sync())
        assertFalse(enrichment.sync(), "the second ask sends have=3 and gets 304")
        assertEquals(2, indexCalls)
    }

    @Test fun tmdbMetadataLayersOverTheProviders() {
        val image = { size: String, path: String -> "nas/$size$path" }
        val movie = VodEnrichment.merge(
            MovieDetail(plot = "provider plot", genres = listOf("Sci-Fi"), durationSec = 100, containerExt = "mkv"),
            VodMeta("i", 603, "The Matrix", overview = "TMDB overview", genres = listOf("Action"), runtimeMin = 136, backdrop = "/b.jpg", contentRating = "R"),
            image,
        )!!
        assertEquals("TMDB overview", movie.plot)
        assertEquals(136 * 60, movie.durationSec)
        assertEquals("nas/backdrop/b.jpg", movie.backdropUrl)
        assertEquals("mkv", movie.containerExt, "playback details stay the provider's")
        assertNull(VodEnrichment.merge(null as SeriesDetail?, null, image))

        val series = VodEnrichment.merge(
            SeriesDetail(episodes = listOf(Episode("e1", "1", 1, 1, "Episode 1"), Episode("e2", "2", 1, 2, "The Real Title", plot = "p"))),
            VodMeta("s", 1396, "Breaking Bad", episodes = listOf(EpisodeMeta(1, 1, "Pilot", "o1", "/s1.jpg"), EpisodeMeta(1, 2, "Other", "o2"))),
            image,
        )!!
        assertEquals(listOf("Pilot", "The Real Title"), series.episodes.map { it.title })
        assertEquals(listOf("o1", "p"), series.episodes.map { it.plot })
        assertEquals("nas/w780/s1.jpg", series.episodes.first().stillUrl)
    }
}

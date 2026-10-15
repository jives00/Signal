package com.signal.server

import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.nas.NasPaths
import com.signal.nas.NasVodPaths
import com.signal.nas.PairRequest
import com.signal.nas.PairStarted
import com.signal.nas.PairStatus
import com.signal.nas.VodIndex
import com.signal.nas.VodMeta
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VOD enrichment against a fake TMDB (PLAN §4.3.12–4.3.13). */
class VodTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<String>()
    private var rateLimitOnce = true

    private val tmdbApi = MockEngine { req ->
        val path = req.url.encodedPath.removePrefix("/3")
        val q = req.url.parameters["query"]
        synchronized(requests) { requests += path + (q?.let { "?$it" } ?: "") }
        val body = when {
            path == "/movie/popular" && rateLimitOnce -> {
                rateLimitOnce = false
                return@MockEngine respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
            }
            path == "/movie/603" -> MATRIX
            path == "/movie/27205" -> INCEPTION
            path == "/tv/1396" -> BREAKING_BAD
            path == "/tv/1396/season/1" -> """{"episodes":[{"season_number":1,"episode_number":1,"name":"Pilot","still_path":"/s1.jpg","air_date":"2008-01-20","runtime":58},{"season_number":1,"episode_number":2,"name":"Cat's in the Bag..."}]}"""
            path == "/tv/1396/season/2" -> """{"episodes":[{"season_number":2,"episode_number":1,"name":"Seven Thirty-Seven"}]}"""
            path == "/search/movie" && q == "The Matrix" -> """{"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-31"}]}"""
            path == "/search/tv" && q == "Breaking Bad" -> """{"results":[{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20"}]}"""
            path == "/trending/movie/week" -> """{"results":[{"id":999},{"id":603}]}"""
            path == "/movie/top_rated" -> """{"results":[{"id":27205},{"id":603}]}"""
            path.startsWith("/movie/") || path.startsWith("/tv/") -> return@MockEngine respond("{}", HttpStatusCode.NotFound)
            else -> """{"results":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val catalog = listOf(
        item("p:v:1", VodKind.MOVIE, "EN - The Matrix (1999)", year = null),
        item("p:v:2", VodKind.MOVIE, "Inception 4K", tmdbId = 27205),
        item("p:v:3", VodKind.MOVIE, "Zzz Home Video", year = 2004),
        item("q:v:9", VodKind.MOVIE, "The Matrix [1999]", year = 1999),
        item("p:s:1", VodKind.SERIES, "Breaking Bad", year = 2008),
    )

    private val store = MemoryCompanionStore()
    private val tmdbStore = MemoryTmdbStore()
    private val companion = Companion(
        Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-vod").toFile().path, webDir = "missing"),
        deviceStore = MemoryDeviceStore(),
        companionStore = store,
        syncStoreOverride = MemorySyncStore(),
        buildGuideOnChange = false,
        tmdbHttp = HttpClient(tmdbApi),
        tmdbStoreOverride = tmdbStore,
        vodItemsOverride = { catalog },
    )

    private fun item(id: String, kind: VodKind, name: String, year: Int? = null, tmdbId: Long? = null) =
        VodItem(id = id, sourceId = id.substringBefore(':'), kind = kind, providerId = id.substringAfterLast(':'), name = name, categoryId = "1", year = year, tmdbId = tmdbId)

    private fun withKey() = store.putSetting(CompanionSettings.TMDB_KEY, companion.cipher!!.encrypt("abc123"))

    @Test fun matchesByProviderIdOrSearchAndLooksEachTitleUpOnce(): Unit = runBlocking {
        withKey()
        val line = companion.vod.run("test")
        assertTrue("matched 4, unmatched 1" in line, line)
        val matches = tmdbStore.matches().associateBy { it.vodItemId }
        assertEquals(603L, matches.getValue("p:v:1").tmdbId)
        assertEquals(603L, matches.getValue("q:v:9").tmdbId)
        assertEquals(27205L, matches.getValue("p:v:2").tmdbId)
        assertEquals(VodMatch.UNMATCHED, matches.getValue("p:v:3").status)
        assertEquals(1396L, matches.getValue("p:s:1").tmdbId)
        // The provider's ID was used as is: no search for Inception.
        assertTrue(requests.none { it.startsWith("/search/movie?Inception") })
        // The 429 was retried, and the list still came through.
        assertTrue(requests.count { it == "/movie/popular" } >= 2)

        // A second run has nothing left to do.
        requests.clear()
        assertTrue("matched 0, unmatched 0" in companion.vod.run("again"))
        assertTrue(requests.none { it.startsWith("/search") })
    }

    @Test fun theIndexKeepsOnlyWhatTheSourcesHave(): Unit = runBlocking {
        withKey()
        companion.vod.run("test")
        val index = companion.vod.index()
        assertEquals(listOf("p:v:1", "q:v:9"), index.lists["MOVIE:trending"], "TMDB's 999 isn't in any source")
        assertEquals(listOf("p:v:2", "p:v:1", "q:v:9"), index.lists["MOVIE:top_rated"])
        assertEquals("/matrix.jpg", index.posters["p:v:1"])
        assertEquals(setOf("p:v:1", "q:v:9", "p:v:2"), index.genres.getValue("MOVIE").getValue("Action").toSet())
        assertEquals(listOf("p:s:1"), index.genres.getValue("SERIES").getValue("Drama"))
        assertNull(index.tmdbIds["p:v:3"])
    }

    @Test fun aFixedMatchIsKeptAndSeriesGetTheirEpisodes() = testApplication {
        application { signalModule(companion) }
        withKey()
        companion.vod.run("test")
        val key = pair()

        val fix = client.put("/api/vod/items/p:v:3/match") { contentType(ContentType.Application.Json); setBody("""{"tmdbId":27205}""") }
        assertEquals(HttpStatusCode.NoContent, fix.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/vod/items/p:v:3/match") { contentType(ContentType.Application.Json); setBody("""{"tmdbId":42}""") }.status)
        companion.vod.run("again")
        assertEquals(VodMatch.MANUAL, tmdbStore.match("p:v:3")!!.status)
        assertEquals(27205L, tmdbStore.match("p:v:3")!!.tmdbId)

        val index: VodIndex = json.decodeFromString(client.get(NasVodPaths.INDEX) { header(HttpHeaders.Authorization, "Bearer $key") }.bodyAsText())
        assertEquals(27205L, index.tmdbIds["p:v:3"])
        assertEquals(HttpStatusCode.NotModified, client.get("${NasVodPaths.INDEX}?have=${index.version}") { header(HttpHeaders.Authorization, "Bearer $key") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasVodPaths.INDEX).status)

        // The same index a page at a time: page 0 says how many, the rest need its version.
        val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $key") }
        val first: com.signal.nas.VodIndexPage = json.decodeFromString(client.get("${NasVodPaths.INDEX_PAGE}?page=0", auth).bodyAsText())
        assertEquals(index.version, first.version)
        assertEquals(1, first.pages, "a small index is one page")
        assertEquals(27205L, first.matches.single { it.item == "p:v:3" }.tmdbId)
        assertTrue(first.shelves.any { it.shelf == "list:trending" && it.kind == "MOVIE" })
        assertEquals(HttpStatusCode.NotModified, client.get("${NasVodPaths.INDEX_PAGE}?page=0&have=${index.version}", auth).status)
        assertEquals(HttpStatusCode.Conflict, client.get("${NasVodPaths.INDEX_PAGE}?page=1&version=${index.version + 1}", auth).status)
        assertEquals(HttpStatusCode.NotFound, client.get("${NasVodPaths.INDEX_PAGE}?page=1&version=${index.version}", auth).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("${NasVodPaths.INDEX_PAGE}?page=0").status)

        val meta: VodMeta = json.decodeFromString(client.get(NasVodPaths.meta("p:s:1")) { header(HttpHeaders.Authorization, "Bearer $key") }.bodyAsText())
        assertEquals("Breaking Bad", meta.title)
        assertEquals("Vince Gilligan", meta.director)
        assertEquals("TV-MA", meta.contentRating)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), meta.episodes.map { it.season to it.episode })
        assertEquals("/s1.jpg", meta.episodes.first().still)
        val movie: VodMeta = json.decodeFromString(client.get(NasVodPaths.meta("p:v:1")) { header(HttpHeaders.Authorization, "Bearer $key") }.bodyAsText())
        assertEquals("R", movie.contentRating)
        assertEquals("trailer1", movie.trailerKey)
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne"), movie.cast)

        // The web admin's list.
        val rows = client.get("/api/vod/items?status=manual").bodyAsText()
        assertTrue("\"tmdbTitle\":\"Inception\"" in rows, rows)

        // Sorted by a column, empty values last in either direction.
        fun ids(query: String) = json.decodeFromString<VodRows>(runBlocking { client.get("/api/vod/items?$query").bodyAsText() }).rows.map { it.itemId }
        assertEquals(listOf("p:s:1", "p:v:1", "p:v:2", "q:v:9", "p:v:3"), ids("sort=title"))
        assertEquals(listOf("p:v:3", "q:v:9", "p:v:2", "p:v:1", "p:s:1"), ids("sort=title&dir=desc"))
        assertEquals(listOf("q:v:9", "p:v:3", "p:s:1"), ids("sort=year").take(3))
        assertEquals(listOf("p:s:1", "p:v:3", "q:v:9"), ids("sort=year&dir=desc").take(3))
        // No such category: nothing.
        assertEquals(emptyList(), ids("category=Nope"))
    }

    @Test fun withoutAKeyNothingIsAsked(): Unit = runBlocking {
        assertTrue("no TMDB API key" in companion.vod.run("test"))
        assertTrue(requests.isEmpty())
    }

    @Test fun theKeyIsStoredEncryptedAndNeverShown() = testApplication {
        application { signalModule(companion) }
        val r = client.put("/api/settings/companion") {
            contentType(ContentType.Application.Json)
            setBody("""{"buildTime":"03:00","timeZone":"America/Chicago","snapshotsToKeep":30,"tombstoneDays":90,"newDeviceProfile":"main","tmdbKey":"abc123","imageCacheGb":5}""")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)
        assertTrue(store.setting(CompanionSettings.TMDB_KEY) != "abc123")
        assertEquals("abc123", companion.settings.tmdbKey)
        val view = client.get("/api/settings").bodyAsText()
        assertTrue("\"tmdbKeySet\":true" in view && "abc123" !in view, view)
        assertEquals(5, companion.settings.imageCacheGb)
    }

    @Test fun titlesNormalize() {
        assertEquals("The Matrix", TitleNormalizer.query("EN - The Matrix (1999)"))
        assertEquals("Inception", TitleNormalizer.query("Inception 4K"))
        assertEquals("Amélie", TitleNormalizer.query("FR | Amélie [2001] MULTI"))
        assertEquals(1999, TitleNormalizer.year(item("x", VodKind.MOVIE, "The Matrix (1999)")))
        val results = listOf(TmdbCandidate(1, "The Matrix Reloaded", year = 2003), TmdbCandidate(2, "The Matrix", year = 1999))
        assertEquals(2L, TitleNormalizer.pick("The Matrix", 1999, results)?.id)
        assertNull(TitleNormalizer.pick("The Matrix", 1980, results), "no year within one either way")
    }

    @Test fun backdropsAreResizedAndTheCacheStaysUnderItsLimit(): Unit = runBlocking {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        var fetched = 0
        val cdn = HttpClient(MockEngine { fetched++; respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) })
        val dir = Files.createTempDirectory("signal-images").toFile()
        var limit = 1L shl 30
        val cache = ImageCache(dir, cdn, { limit })
        val backdrop = assertNotNull(cache.get("backdrop", "b.jpg"))
        assertEquals(1920, ImageIO.read(backdrop).width)
        assertNotNull(cache.get("backdrop", "b.jpg"))
        assertEquals(1, fetched, "the second ask is served from disk")
        assertNull(cache.get("backdrop", "..%2Fx.jpg"))
        assertNull(cache.get("w92", "b.jpg"), "only the sizes the app uses")
        cache.get("w500", "p.png")
        backdrop.setLastModified(System.currentTimeMillis() - 60_000)
        limit = cache.size() - 1
        cache.get("w780", "s.png")
        assertTrue(!backdrop.exists(), "the least recently used file goes first")
        assertTrue(cache.size() <= limit)
    }

    @Test fun providerPostersAreCachedAsTvSizedJpegs(): Unit = runBlocking {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1000, 1500, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        var fetched = 0
        val provider = HttpClient(MockEngine { fetched++; respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) })
        val cache = ImageCache(Files.createTempDirectory("signal-provider").toFile(), provider, { 1L shl 30 })
        val poster = assertNotNull(cache.provider("http://provider.example/covers/7.png"))
        assertEquals(500, ImageIO.read(poster).width)
        assertNotNull(cache.provider("http://provider.example/covers/7.png"))
        assertEquals(1, fetched, "the second ask is served from disk")
        assertNull(cache.provider("file:///etc/passwd"), "only http and https")
        assertTrue(cache.size() > 0, "provider posters count toward the cache limit")
    }

    @Test fun theWebAdminListsAddsAndRemovesLibraryTitles() = testApplication {
        application { signalModule(companion) }
        withKey()
        companion.vod.run("test")
        // Added under the key a TV would use: the TMDB ID when matched, else the item ID.
        val add = client.post("/api/library") { contentType(ContentType.Application.Json); setBody("""{"itemIds":["p:v:1","p:v:3"],"profiles":[]}""") }
        assertEquals(HttpStatusCode.OK, add.status, add.bodyAsText())
        val rows = client.get("/api/library").bodyAsText()
        assertTrue("\"key\":\"tmdb:movie:603\"" in rows && "\"key\":\"p:v:3\"" in rows, rows)
        assertTrue("\"available\":false" !in rows, rows)
        // The same title again is skipped.
        val again = client.post("/api/library") { contentType(ContentType.Application.Json); setBody("""{"itemIds":["q:v:9"],"profiles":[]}""") }.bodyAsText()
        assertTrue("\"changed\":0" in again, again)
        assertEquals(HttpStatusCode.OK, client.delete("/api/library/p:v:3").status)
        assertTrue("p:v:3" !in client.get("/api/library").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/library") { contentType(ContentType.Application.Json); setBody("""{"itemIds":["nope"]}""") }.status)
    }

    private suspend fun ApplicationTestBuilder.pair(): String {
        val started: PairStarted = json.decodeFromString(client.post(NasPaths.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest("tv-1", "Living room", "SHIELD", "0.4.0")))
        }.bodyAsText())
        client.post("/api/devices/approve") { contentType(ContentType.Application.Json); setBody("""{"code":"${started.code}"}""") }
        return json.decodeFromString<PairStatus>(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText()).apiKey!!
    }

    companion object Fixtures {
        val MATRIX = """{"id":603,"title":"The Matrix","release_date":"1999-03-31","overview":"A hacker learns the truth.","genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science Fiction"}],
            "runtime":136,"vote_average":8.2,"poster_path":"/matrix.jpg","backdrop_path":"/matrix-b.jpg",
            "videos":{"results":[{"site":"YouTube","type":"Teaser","key":"teaser1"},{"site":"YouTube","type":"Trailer","key":"trailer1"}]},
            "credits":{"cast":[{"name":"Keanu Reeves"},{"name":"Laurence Fishburne"}],"crew":[{"job":"Director","name":"Lana Wachowski"},{"job":"Director","name":"Lilly Wachowski"}]},
            "release_dates":{"results":[{"iso_3166_1":"DE","release_dates":[{"certification":"16"}]},{"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"R"}]}]}}"""
        val INCEPTION = """{"id":27205,"title":"Inception","release_date":"2010-07-15","genres":[{"name":"Action"}],"poster_path":"/inception.jpg"}"""
        val BREAKING_BAD = """{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","genres":[{"name":"Drama"}],"episode_run_time":[47],
            "created_by":[{"name":"Vince Gilligan"}],"seasons":[{"season_number":0},{"season_number":1},{"season_number":2}],
            "content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}}"""
    }
}

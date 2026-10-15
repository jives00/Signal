package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.parse.XtreamParser
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Movies and series (PLAN §4.3.6–4.3.7): parsing, caching on refresh, and the browse sorts. */
class VodRepositoryTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    private val vod = VodRepository(db, Dispatchers.IO)
    private val xtream = Source(id = "x", name = "Provider", kind = SourceKind.XTREAM, url = "http://p.example", username = "u", password = "p")
    private fun sources() = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })

    @Test fun parsesXtreamMoviesAndSeriesLeniently() {
        val movies = XtreamParser.vodStreams(
            xtream,
            """[{"stream_id":"7","name":"Heat (1995)","stream_icon":"http://img/7.jpg","rating":"8.3","added":"1700000000","category_id":"3","container_extension":"mkv","tmdb":"949"},
               {"stream_id":8,"name":"No Year","rating":"","added":null,"category_id":3},
               {"stream_id":"7","name":"duplicate"}]""",
        )
        assertEquals(2, movies.size)
        val heat = movies[0]
        assertEquals("x:v:7", heat.id)
        assertEquals(1995, heat.year)
        assertEquals(8.3, heat.rating)
        assertEquals(949L, heat.tmdbId)
        assertEquals("mkv", heat.containerExt)
        assertEquals(null, movies[1].rating)

        val series = XtreamParser.series(xtream, """[{"series_id":"45","name":"The Wire","cover":"http://img/45.jpg","releaseDate":"2002-06-02","last_modified":"1690000000","category_id":"9","plot":"Baltimore."}]""")
        assertEquals("x:s:45", series.single().id)
        assertEquals(2002, series.single().year)
        assertEquals("Baltimore.", series.single().plot)
    }

    @Test fun theDemoSourceBringsMoviesAndSeriesAndRemovingItTakesThemAway(): Unit = runBlocking {
        val sources = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })
        sources.save(Source(id = "demo", name = "Demo", kind = SourceKind.DEMO, url = "demo:"))
        sources.refresh("demo").getOrThrow()
        assertEquals(360L, vod.count(VodKind.MOVIE).first())
        assertEquals(60L, vod.count(VodKind.SERIES).first())
        val cats = vod.categories(VodKind.MOVIE).first()
        assertEquals(8, cats.size)
        assertEquals(360, cats.sumOf { it.count })
        val recent = vod.recentlyAdded(VodKind.MOVIE, 20)
        assertEquals(recent.sortedByDescending { it.addedAt }, recent)

        // A disabled source's titles leave the pages.
        sources.save(sources.byId("demo")!!.copy(enabled = false))
        assertEquals(0L, vod.count(VodKind.MOVIE).first())
        sources.save(sources.byId("demo")!!.copy(enabled = true))
        sources.delete("demo")
        assertEquals(0L, db.vodQueries.countForKind("MOVIE").executeAsOne())
    }

    @Test fun aVodFailureKeepsTheChannelsAndTheOldCatalog(): Unit = runBlocking {
        var vodWorks = true
        val http = HttpClient(MockEngine { req ->
            when (req.url.parameters["action"]) {
                null -> respond("""{"user_info":{"auth":1}}""")
                "get_live_categories" -> respond("""[{"category_id":"1","category_name":"News"}]""")
                "get_live_streams" -> respond("""[{"stream_id":1,"name":"News 24","category_id":"1"}]""")
                "get_vod_categories" -> if (vodWorks) respond("""[{"category_id":"3","category_name":"Action"}]""") else respondError(HttpStatusCode.InternalServerError)
                "get_vod_streams" -> respond("""[{"stream_id":7,"name":"Heat (1995)","category_id":"3"}]""")
                "get_series_categories" -> respond("[]")
                "get_series" -> respond("[]")
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        val sources = SourceRepository(db, http, box, Dispatchers.IO, Log { _, _ -> })
        sources.save(xtream)
        assertEquals(1, sources.refresh("x").getOrThrow())
        assertEquals(1L, vod.count(VodKind.MOVIE).first())
        vodWorks = false
        assertEquals(1, sources.refresh("x").getOrThrow(), "live channels still refresh")
        assertEquals(1L, vod.count(VodKind.MOVIE).first(), "the cached movies stay")
    }

    @Test fun emptyListsKeepTheOldCatalog(): Unit = runBlocking {
        var empty = false
        val http = HttpClient(MockEngine { req ->
            when (req.url.parameters["action"]) {
                null -> respond("""{"user_info":{"auth":1}}""")
                "get_live_categories", "get_vod_categories", "get_series_categories" -> respond("[]")
                "get_live_streams" -> respond("""[{"stream_id":1,"name":"News 24"}]""")
                "get_vod_streams" -> respond(if (empty) "[]" else """[{"stream_id":7,"name":"Heat (1995)"}]""")
                "get_series" -> respond(if (empty) "[]" else """[{"series_id":5,"name":"The Wire"}]""")
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        val sources = SourceRepository(db, http, box, Dispatchers.IO, Log { _, _ -> })
        sources.save(xtream)
        sources.refresh("x").getOrThrow()
        assertEquals(1L, vod.count(VodKind.MOVIE).first())
        // The provider sends `[]` for both lists, but the live channels as usual.
        empty = true
        assertEquals(1, sources.refresh("x").getOrThrow(), "live channels still refresh")
        assertEquals(1L, vod.count(VodKind.MOVIE).first(), "the movies stay")
        assertEquals(1L, vod.count(VodKind.SERIES).first(), "and the series")
    }

    @Test fun aStreamedListThatBreaksPartWayKeepsTheOldCatalog(): Unit = runBlocking {
        var broken = false
        val http = HttpClient(MockEngine { req ->
            when (req.url.parameters["action"]) {
                null -> respond("""{"user_info":{"auth":1}}""")
                "get_live_categories", "get_series_categories" -> respond("[]")
                "get_live_streams" -> respond("""[{"stream_id":1,"name":"News 24"}]""")
                "get_vod_categories" -> respond("""[{"category_id":"3","category_name":"Action"}]""")
                // Streamed, so the movies have been staged by the time get_series fails.
                "get_vod_streams" -> respond("""[{"stream_id":7,"name":"Heat (1995)","category_id":"3"},{"stream_id":7,"name":"twice"},{"stream_id":8,"name":"Alien {Director's Cut} \"Final\"","category_id":"3"}]""")
                "get_series" -> if (broken) respondError(HttpStatusCode.BadGateway) else respond("""[{"series_id":5,"name":"The Wire"}]""")
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        val sources = SourceRepository(db, http, box, Dispatchers.IO, Log { _, _ -> })
        sources.save(xtream)
        sources.refresh("x").getOrThrow()
        assertEquals(2L, vod.count(VodKind.MOVIE).first(), "the same ID twice is one movie")
        assertEquals("Heat (1995)", vod.item("x:v:7")?.name, "the first wins")
        assertEquals("Alien {Director's Cut} \"Final\"", vod.item("x:v:8")?.name)
        assertEquals(listOf("x:s:5"), vod.search("wire", VodKind.SERIES).map { it.id }, "Search finds the swapped-in titles")
        broken = true
        sources.refresh("x").getOrThrow()
        assertEquals(1L, vod.count(VodKind.SERIES).first(), "the old catalog stays")
        assertEquals(0L, db.vodQueries.needsKeys(10).executeAsList().size.toLong())
    }

    @Test fun aSourceWhoseMoviesFailedGetsThemOnLaunch(): Unit = runBlocking {
        var vodWorks = false
        val http = HttpClient(MockEngine { req ->
            when (req.url.parameters["action"]) {
                null -> respond("""{"user_info":{"auth":1}}""")
                "get_live_categories", "get_series_categories", "get_series" -> respond("[]")
                "get_live_streams" -> respond("""[{"stream_id":1,"name":"News 24"}]""")
                "get_vod_categories" -> respond("[]")
                "get_vod_streams" -> if (vodWorks) respond("""[{"stream_id":7,"name":"Heat (1995)"}]""") else respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        val sources = SourceRepository(db, http, box, Dispatchers.IO, Log { _, _ -> })
        sources.save(xtream)
        sources.refresh("x").getOrThrow()
        assertEquals(0L, vod.count(VodKind.MOVIE).first(), "the channels refreshed, the movies didn't")
        vodWorks = true
        assertEquals(1 to 0, sources.refreshVodIfMissing("x"))
        assertEquals(1L, vod.count(VodKind.MOVIE).first())
        assertEquals(null, sources.refreshVodIfMissing("x"), "nothing to do once it has titles")
    }

    @Test fun titlesCachedBeforeTheKeysGetThem(): Unit = runBlocking {
        sources().save(xtream)
        db.vodQueries.insertItem(com.signal.db.user.Vod_item("x:v:1", "x", "MOVIE", "1", "EN - Heat (1995)", "1", null, 1995, null, 0, null, null, null, null, 0, "", ""))
        assertTrue(vod.search("heat", VodKind.MOVIE).isEmpty())
        assertEquals(1, vod.fillMissingKeys())
        assertEquals(listOf("x:v:1"), vod.search("heat", VodKind.MOVIE).map { it.id })
        assertEquals("heat", vod.item("x:v:1")?.titleKey)
        assertEquals(0, vod.fillMissingKeys())
    }

    @Test fun hiddenCategoriesAreLeftOutDroppedAndBroughtBack(): Unit = runBlocking {
        var filter = com.signal.match.VodCategoryFilter.NONE
        val http = HttpClient(MockEngine { req ->
            when (req.url.parameters["action"]) {
                null -> respond("""{"user_info":{"auth":1}}""")
                "get_live_categories", "get_live_streams" -> respond("[]")
                "get_vod_categories" -> respond("""[{"category_id":"3","category_name":"EN - ACTION"},{"category_id":"4","category_name":"ES - PELÍCULAS"}]""")
                "get_vod_streams" -> respond("""[{"stream_id":7,"name":"EN - Heat (1995)","category_id":"3"},{"stream_id":8,"name":"ES - Calor","category_id":"4"}]""")
                "get_series_categories" -> respond("""[{"category_id":"9","category_name":"ESPAÑA SERIES"}]""")
                "get_series" -> respond("""[{"series_id":5,"name":"La Casa","category_id":"9"}]""")
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        val sources = SourceRepository(db, http, box, Dispatchers.IO, Log { _, _ -> }, vodFilter = { filter })
        sources.save(xtream)
        sources.refresh("x").getOrThrow()
        assertEquals(2L, vod.count(VodKind.MOVIE).first())

        // Set on a catalog loaded before it: the hidden titles go at once, from Search too.
        val englishOnly = com.signal.match.VodCategoryFilter(showOnly = listOf("EN - *"))
        assertEquals(2, vod.dropHidden(englishOnly))
        assertEquals(listOf("x:v:7"), vod.recentlyAdded(VodKind.MOVIE).map { it.id })
        assertEquals(0L, vod.count(VodKind.SERIES).first())
        assertEquals(listOf("EN - ACTION"), vod.categories(VodKind.MOVIE).first().map { it.name }, "an emptied category leaves the column")
        assertEquals(0, db.vodQueries.countInCategory("x", "MOVIE", "4").executeAsOne().toInt())

        // A refresh never keeps them.
        filter = englishOnly
        sources.refresh("x").getOrThrow()
        assertEquals(1L, vod.count(VodKind.MOVIE).first())
        assertEquals(0L, vod.count(VodKind.SERIES).first())

        // Shown again: the source needs a refresh to bring them back.
        val alsoSeries = com.signal.match.VodCategoryFilter(showOnly = listOf("EN - *", "ESPAÑA *"))
        assertEquals(setOf("x"), vod.sourcesUnhidden(englishOnly, alsoSeries))
        assertEquals(emptySet(), vod.sourcesUnhidden(alsoSeries, englishOnly))
        filter = alsoSeries
        sources.refresh("x").getOrThrow()
        assertEquals(1L, vod.count(VodKind.SERIES).first())
    }

    @Test fun titlesAndCategoriesShowTheCleanupRules(): Unit = runBlocking {
        var rules = com.signal.match.NameCleanup(presets = setOf(com.signal.match.NameCleanup.Preset.VOD_PREFIX, com.signal.match.NameCleanup.Preset.VOD_COUNTRY))
        val cleaned = VodRepository(db, Dispatchers.IO, cleanup = { rules })
        db.vodQueries.insertCategory(com.signal.db.user.Vod_category("x", "SERIES", "1", "EN - DRAMA", 0))
        sources().save(xtream)
        db.vodQueries.insertItem(com.signal.db.user.Vod_item("x:s:1", "x", "SERIES", "1", "EN - Lupin (2021) (FR)", "1", null, 2021, null, 0, null, null, null, null, 0, "", ""))
        val item = cleaned.item("x:s:1")!!
        assertEquals("Lupin", VodRepository.displayName(item), "the tag, the country, then the year")
        assertEquals("EN - Lupin (2021) (FR)", item.name, "matching keeps the provider's name")
        assertEquals("EN - DRAMA", cleaned.categories(VodKind.SERIES).first().single().name)
        rules = rules.copy(groups = true)
        assertEquals("DRAMA", cleaned.categories(VodKind.SERIES).first().single().name)
        assertEquals("EN - Lupin (2021) (FR)", VodRepository.displayName(vod.item("x:s:1")!!), "no rules: the year isn't last")
    }

    @Test fun sortsAndDisplayNames() {
        fun item(name: String, year: Int?, rating: Double?, added: Long) = VodItem("x:v:$name", "x", VodKind.MOVIE, name, name, "1", year = year, rating = rating, addedAt = added)
        val items = listOf(item("Beta (2001)", 2001, 6.0, 30), item("alpha", 1999, 9.0, 10), item("Gamma", null, null, 20))
        assertEquals(listOf("Beta (2001)", "Gamma", "alpha"), VodRepository.sorted(items, VodSort.RECENT).map { it.name })
        assertEquals(listOf("alpha", "Beta (2001)", "Gamma"), VodRepository.sorted(items, VodSort.A_Z).map { it.name })
        assertEquals(listOf("Beta (2001)", "alpha", "Gamma"), VodRepository.sorted(items, VodSort.YEAR).map { it.name })
        assertEquals(listOf("alpha", "Beta (2001)", "Gamma"), VodRepository.sorted(items, VodSort.RATING).map { it.name })
        assertEquals("Beta", VodRepository.displayName(items[0]))
        assertTrue(VodRepository.displayName(item("EN - Heat 4K", null, null, 0)).contains("Heat"))
    }
}

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

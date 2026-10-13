package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.demo.DemoVod
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.model.VodKind
import com.signal.parse.XtreamParser
import com.signal.platform.SecretBox
import com.signal.ui.screens.seriesNext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Library, resume, watched and Continue watching (PLAN §4.3.1, §4.3.8–4.3.11). */
class WatchRepositoryTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    private val sources = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })
    private val vod = VodRepository(db, Dispatchers.IO)
    private val details = VodDetailRepository(db, sources, Dispatchers.IO, Log { _, _ -> })
    private val watch = WatchRepository(db, settings, Dispatchers.IO)
    private val now = Clock.System.now().toEpochMilliseconds()

    private suspend fun demo() {
        sources.save(Source(id = "demo", name = "Demo", kind = SourceKind.DEMO, url = "demo:"))
        sources.refresh("demo").getOrThrow()
    }

    private fun position(key: String, pos: Long, dur: Long = 600_000, kind: String = "MOVIE", item: String = "x", seriesKey: String? = null, episodeId: String? = null, season: Int? = null, episode: Int? = null) =
        WatchRepository.Position(key, kind, item, episodeId, seriesKey, season, episode, "T", pos, dur, 0)

    @Test fun positionsWatchedAndTheMinimum(): Unit = runBlocking {
        watch.savePosition(position("m1", 5_000))
        assertTrue(watch.current().positions.isEmpty(), "under 10 seconds isn't saved")
        watch.savePosition(position("m1", 120_000))
        assertEquals(120_000, watch.current().positions.getValue("m1").positionMs)
        // At 90% it's watched, and leaves Continue watching (PLAN §4.3.8).
        watch.savePosition(position("m1", 545_000))
        assertTrue("m1" in watch.current().watched)
        assertTrue(watch.current().positions.isEmpty())
        watch.markUnwatched("m1")
        assertTrue(watch.current().watched.isEmpty())
    }

    @Test fun continueWatchingShowsStartedItemsAndNextUp(): Unit = runBlocking {
        demo()
        val movie = vod.recentlyAdded(VodKind.MOVIE, 1).single()
        val series = vod.recentlyAdded(VodKind.SERIES, 1).single()
        val detail = details.series(series)!!
        val seriesKey = ItemKeys.series(series)
        watch.savePosition(position(ItemKeys.movie(movie), 60_000, item = movie.id))
        // Finished S1 E1: next up is S1 E2, from the start.
        val e1 = detail.inSeason(1).first()
        watch.markWatched(ItemKeys.episode(seriesKey, e1), "EPISODE", series.id, seriesKey, 1, 1)
        val cards = ContinueWatching.build(watch.current(), vod, details, now)
        assertEquals(2, cards.size)
        val next = cards.single { it.nextUp }
        assertEquals(detail.inSeason(1)[1].id, next.episode?.id)
        assertTrue(cards.single { !it.nextUp }.fraction!! > 0f)

        // Removing the series from the row stops next up until another episode is watched (PLAN §4.3.1).
        watch.hideSeries(seriesKey)
        assertTrue(ContinueWatching.build(watch.current(), vod, details, now).none { it.nextUp })
        val e2 = detail.inSeason(1)[1]
        watch.markWatched(ItemKeys.episode(seriesKey, e2), "EPISODE", series.id, seriesKey, 1, 2)
        assertEquals(detail.inSeason(1)[2].id, ContinueWatching.build(watch.current(), vod, details, now).single { it.nextUp }.episode?.id)

        // Older than 60 days drops off.
        assertTrue(ContinueWatching.build(watch.current(), vod, details, now + 61L * 24 * 60 * 60_000).isEmpty())
    }

    @Test fun theSeriesPageOffersContinueNextUpOrStart(): Unit = runBlocking {
        demo()
        val series = vod.recentlyAdded(VodKind.SERIES, 1).single()
        val detail = details.series(series)!!
        val key = ItemKeys.series(series)
        assertEquals("Play S1 E1", seriesNext(key, detail, watch.current())?.label)
        val e1 = detail.inSeason(1).first()
        watch.markWatched(ItemKeys.episode(key, e1), "EPISODE", series.id, key, 1, 1)
        assertEquals("Next up S1 E2", seriesNext(key, detail, watch.current())?.label)
        val e2 = detail.inSeason(1)[1]
        watch.savePosition(position(ItemKeys.episode(key, e2), 65_000, kind = "EPISODE", item = series.id, seriesKey = key, episodeId = e2.id, season = 1, episode = 2))
        assertEquals("Continue S1 E2 · 1:05", seriesNext(key, detail, watch.current())?.label)
        // The last season's last episode leads nowhere.
        val last = detail.episodes.maxWith(compareBy({ it.season }, { it.episode }))
        assertNull(detail.after(last.season, last.episode))
    }

    @Test fun libraryAddAndRemove(): Unit = runBlocking {
        demo()
        val movie = vod.recentlyAdded(VodKind.MOVIE, 1).single()
        watch.addToLibrary(movie)
        assertEquals(listOf(ItemKeys.movie(movie)), watch.current().library.map { it.key })
        watch.removeFromLibrary(ItemKeys.movie(movie))
        assertTrue(watch.current().library.isEmpty())
    }

    @Test fun parsesVodAndSeriesInfo() {
        val m = XtreamParser.movieDetail(
            """{"info":{"plot":"P","genre":"Action, Sci-Fi","duration":"02:16:00","rating":"8.7","backdrop_path":["http://img/b.jpg"],"youtube_trailer":"https://www.youtube.com/watch?v=abc12345","tmdb_id":"603"},
               "movie_data":{"stream_id":603,"container_extension":"mkv"}}""",
        )
        assertEquals(listOf("Action", "Sci-Fi"), m.genres)
        assertEquals(8160, m.durationSec)
        assertEquals("abc12345", m.trailerKey)
        assertEquals(603L, m.tmdbId)
        assertEquals("mkv", m.containerExt)
        val source = Source(id = "x", name = "P", kind = SourceKind.XTREAM, url = "http://p")
        val asMap = XtreamParser.seriesDetail(source, """{"info":{"plot":"S"},"episodes":{"1":[{"id":"11","episode_num":1,"title":"Pilot","season":1,"container_extension":"mp4","info":{"duration_secs":2700}}],"2":[{"id":"21","episode_num":1,"season":2}]}}""")
        assertEquals(listOf(1, 2), asMap.seasons)
        assertEquals("x:e:11", asMap.episodes.first().id)
        assertEquals("Episode 1", asMap.inSeason(2).single().title)
        val asList = XtreamParser.seriesDetail(source, """{"episodes":[[{"id":"11","episode_num":1,"season":1}],[{"id":"12","episode_num":2,"season":1}]]}""")
        assertEquals(2, asList.episodes.size)
        assertEquals("x:e:12", asList.after(1, 1)?.id)
    }

    @Test fun demoSeriesHaveSeasonsAndPlayableEpisodes(): Unit = runBlocking {
        demo()
        val d = DemoVod.seriesDetail(vod.recentlyAdded(VodKind.SERIES, 1).single())
        assertTrue(d.seasons.size in 2..4)
        assertTrue(d.episodes.all { it.url != null })
    }
}

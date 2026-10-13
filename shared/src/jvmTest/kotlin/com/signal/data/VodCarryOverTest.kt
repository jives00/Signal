package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.db.user.Vod_item
import com.signal.match.VodTitles
import com.signal.model.Episode
import com.signal.model.Program
import com.signal.model.SeriesDetail
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.model.VodKind
import com.signal.parse.XtreamParser
import com.signal.platform.SecretBox
import com.signal.ui.screens.programKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4 of VOD (PLAN §8 → milestone 4): titles across sources (carry-over, Play from…), VOD in
 * Search, Find in Movies/Series, and the New episode badge.
 */
class VodCarryOverTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    private val sources = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })
    private val vod = VodRepository(db, Dispatchers.IO)
    private val watch = WatchRepository(db, settings, Dispatchers.IO)

    private var position = 0L

    /** Two Xtream sources; "a" was added first, so it's preferred. */
    private suspend fun twoSources() {
        sources.save(Source(id = "a", name = "First", kind = SourceKind.XTREAM, url = "http://a.example"))
        Thread.sleep(5)
        sources.save(Source(id = "b", name = "Second", kind = SourceKind.XTREAM, url = "http://b.example"))
    }

    private fun item(source: String, kind: VodKind, id: String, name: String, year: Int? = null, tmdb: Long? = null) {
        val itemId = if (kind == VodKind.MOVIE) "$source:v:$id" else "$source:s:$id"
        val (titleKey, searchName) = VodRepository.keys(name)
        db.vodQueries.insertItem(Vod_item(itemId, source, kind.name, id, name, "1", null, year?.toLong(), null, 0, tmdb, null, null, null, position++, titleKey, searchName))
        db.vodQueries.indexFts(itemId)
    }

    @Test fun titlesNormalizeAcrossProviders() {
        assertEquals("amelie", VodTitles.nameKey("FR | Amélie [2001] MULTI"))
        assertEquals(VodTitles.nameKey("EN - The Matrix (1999)"), VodTitles.nameKey("The Matrix 4K"))
        assertEquals("tomandjerry", VodTitles.nameKey("Tom & Jerry"))
    }

    @Test fun copiesArePerSourcePreferredFirst(): Unit = runBlocking {
        twoSources()
        item("b", VodKind.MOVIE, "9", "EN - Heat (1995)", 1995)
        item("a", VodKind.MOVIE, "1", "Heat [1995] 4K", 1995)
        item("a", VodKind.MOVIE, "2", "Heat", 1986)
        // The same TMDB ID beats different names.
        item("a", VodKind.MOVIE, "3", "The Thing", 1982, tmdb = 1091)
        item("b", VodKind.MOVIE, "4", "John Carpenter's The Thing", 1982, tmdb = 1091)

        val heat = vod.item("b:v:9")!!
        assertEquals(listOf("a:v:1", "b:v:9"), vod.copies(heat).map { it.id }, "the 1986 Heat is another title")
        assertEquals(listOf("a:v:3", "b:v:4"), vod.copies(vod.item("b:v:4")!!).map { it.id })
        assertEquals(listOf("a:v:2"), vod.copies(vod.item("a:v:2")!!).map { it.id })
    }

    @Test fun savedKeysResolveToWhateverSourceHasTheTitle(): Unit = runBlocking {
        twoSources()
        item("b", VodKind.MOVIE, "4", "The Thing", 1982, tmdb = 1091)
        item("b", VodKind.SERIES, "7", "The Wire", 2002)
        // A TMDB key plays from any source with that ID, even when the row came from a source that's gone.
        assertEquals("b:v:4", vod.resolve("tmdb:movie:1091", VodKind.MOVIE, "gone:v:1", "The Thing", 1982)?.id)
        // A source's own key, then the same title and year.
        assertEquals("b:s:7", vod.resolve("b:s:7", VodKind.SERIES, "b:s:7", null, null)?.id)
        assertEquals("b:s:7", vod.resolve("a:s:3", VodKind.SERIES, "a:s:3", "EN - The Wire", 2002)?.id)
        assertNull(vod.resolve("a:s:3", VodKind.SERIES, "a:s:3", "The Wire", 1999), "a different year is another title")
        assertNull(vod.resolve("a:v:9", VodKind.MOVIE, "a:v:9", "Nothing Like It", null))
    }

    @Test fun carryOverMovesRowsWhenTheSourceGoes(): Unit = runBlocking {
        twoSources()
        item("a", VodKind.SERIES, "3", "The Wire", 2002)
        item("b", VodKind.SERIES, "7", "EN - The Wire (2002)", 2002)
        val wire = vod.item("a:s:3")!!
        watch.addToLibrary(wire, "a:s:3")
        watch.markWatched("a:s:3:s1e1", "EPISODE", "a:s:3", "a:s:3", 1, 1)
        assertEquals(0, watch.carryOver(vod), "nothing moves while the source has it")

        // The first source is removed: the rows move to the second's copy, episodes keeping their ending.
        sources.delete("a")
        assertEquals(1, watch.carryOver(vod))
        val state = watch.current()
        assertEquals(listOf("b:s:7"), state.library.map { it.key })
        assertTrue("b:s:7:s1e1" in state.watched)
        assertEquals("b:s:7", state.watched.getValue("b:s:7:s1e1").seriesKey)
    }

    @Test fun searchFindsEachTitleOnceRankedExactThenPrefix(): Unit = runBlocking {
        twoSources()
        item("a", VodKind.MOVIE, "1", "Alien (1979)", 1979)
        item("b", VodKind.MOVIE, "2", "EN - Alien (1979)", 1979)
        item("a", VodKind.MOVIE, "3", "Aliens", 1986)
        item("a", VodKind.MOVIE, "4", "The Alienist Movie", 2001)
        item("a", VodKind.SERIES, "5", "Alien Nation", 1989)
        val movies = vod.search("alien", VodKind.MOVIE).map { it.id }
        assertEquals("a:v:1", movies.first(), "the exact title, from the preferred source")
        assertFalse("b:v:2" in movies, "the second source's copy shows once")
        assertTrue("a:v:3" in movies && "a:v:4" in movies)
        assertEquals(listOf("a:s:5"), vod.search("alien nat", VodKind.SERIES).map { it.id })
        assertTrue(vod.search("lien", VodKind.MOVIE).isEmpty(), "words match by prefix")
    }

    @Test fun findNeedsAConfidentMatch(): Unit = runBlocking {
        twoSources()
        item("a", VodKind.MOVIE, "1", "Heat", 1995)
        item("a", VodKind.MOVIE, "2", "Heat", 1986)
        item("a", VodKind.SERIES, "3", "The Office (UK)", 2001)
        item("a", VodKind.SERIES, "4", "The Office", 2005)
        item("a", VodKind.SERIES, "5", "The Office", 2001)
        item("b", VodKind.SERIES, "6", "Severance", 2022)
        assertEquals("a:v:1", vod.find("Heat", 1996, VodKind.MOVIE)?.id, "a year within one")
        assertNull(vod.find("Heat", null, VodKind.MOVIE), "two movies by that name and no year to tell them apart")
        assertNull(vod.find("The Office", null, VodKind.SERIES))
        assertEquals("b:s:6", vod.find("Severance", null, VodKind.SERIES)?.id)
        assertNull(vod.find("Nothing", 2000, VodKind.MOVIE))
    }

    @Test fun theGuideSaysWhatKindAProgramIs() {
        fun program(categories: List<String> = emptyList(), season: Int? = null) = Program(1, "e", "c", 0, 1, "T", categories = categories, season = season)
        assertEquals(VodKind.MOVIE, programKind(program(listOf("Movie", "Drama"))))
        assertEquals(VodKind.SERIES, programKind(program(season = 2)))
        assertNull(programKind(program(listOf("News"))))
    }

    @Test fun newEpisodesAreThoseAddedSinceTheUserLastWatched() {
        val key = "tmdb:tv:1"
        fun ep(s: Int, e: Int, added: Long) = Episode("x:e:$s$e", "$s$e", s, e, "E$e", addedAt = added)
        val detail = SeriesDetail(episodes = listOf(ep(1, 1, 1_000), ep(1, 2, 1_000), ep(1, 3, 5_000)))
        val watchedE1 = WatchRepository.Watched("$key:s1e1", "EPISODE", "x:s:1", key, 1, 1, 2_000_000)
        val state = WatchRepository.State(watched = mapOf(watchedE1.key to watchedE1))
        val followed = state.followedSeries().getValue(key)
        assertEquals(2_000_000, followed.lastActivity)
        // E3 was added (at 5,000 s) after the last watch (2,000 s).
        assertTrue(NewEpisodes.isNew(followed, detail, state))
        // Watching E3 clears it.
        val watchedE3 = WatchRepository.Watched("$key:s1e3", "EPISODE", "x:s:1", key, 1, 3, 6_000_000)
        val after = WatchRepository.State(watched = mapOf(watchedE1.key to watchedE1, watchedE3.key to watchedE3))
        assertFalse(NewEpisodes.isNew(after.followedSeries().getValue(key), detail, after))
        // An old episode uploaded again, before the last one watched, isn't new.
        val reuploaded = SeriesDetail(episodes = listOf(ep(1, 1, 9_000), ep(1, 2, 1_000)))
        val watchedE2 = WatchRepository.Watched("$key:s1e2", "EPISODE", "x:s:1", key, 1, 2, 2_000_000)
        val s2 = WatchRepository.State(watched = mapOf(watchedE2.key to watchedE2))
        assertFalse(NewEpisodes.isNew(s2.followedSeries().getValue(key), reuploaded, s2))
    }

    @Test fun episodesCarryTheirAddedDate() {
        val source = Source(id = "x", name = "P", kind = SourceKind.XTREAM, url = "http://p.example")
        val detail = XtreamParser.seriesDetail(source, """{"info":{},"episodes":{"1":[{"id":"11","season":1,"episode_num":1,"title":"Pilot","added":"1700000000"}]}}""")
        assertEquals(1_700_000_000, detail.episodes.single().addedAt)
    }

    @Test fun continueWatchingPlaysFromTheSourceThatHasTheTitleNow(): Unit = runBlocking {
        twoSources()
        item("b", VodKind.MOVIE, "4", "The Thing", 1982, tmdb = 1091)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        // Saved while playing a copy in a source that's since gone.
        watch.savePosition(WatchRepository.Position("tmdb:movie:1091", "MOVIE", "gone:v:1", null, null, null, null, "The Thing", 60_000, 600_000, 0))
        val details = VodDetailRepository(db, sources, Dispatchers.IO, Log { _, _ -> })
        assertEquals("b:v:4", ContinueWatching.build(watch.current(), vod, details, now).single().item.id)
    }
}

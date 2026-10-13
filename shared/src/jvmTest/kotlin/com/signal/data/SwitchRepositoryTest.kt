package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.match.ChannelMatcher
import com.signal.model.ChannelGroup
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two providers on one TV (PLAN §4.5): the switch review, learned aliases and the Stream picker. */
class SwitchRepositoryTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    private val sources = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })
    private val channels = ChannelRepository(db, settings, Dispatchers.IO)
    private val switches = SwitchRepository(db, settings, Dispatchers.IO)

    private suspend fun demo(id: String) {
        // The Demo source makes the same channels under each source's own stream IDs: two providers in miniature.
        sources.save(Source(id = id, name = id, kind = SourceKind.DEMO, url = "demo:"))
        sources.refresh(id).getOrThrow()
    }

    private suspend fun firstChannels(sourceId: String, n: Int) =
        channels.groupsNow().map { it.group }.filterIsInstance<ChannelGroup.Provider>().first { it.sourceId == sourceId }.let { channels.channelsNow(it).take(n) }

    @Test fun favoritesLinkToTheNewSourceAndKeepPlayingAfterTheOldOneIsOff(): Unit = runBlocking {
        demo("old")
        val favs = firstChannels("old", 3)
        favs.forEach { channels.setFavorite(it, true) }
        demo("new")

        val report = switches.report("new")
        assertEquals(3, report.confident.size)
        switches.acceptAll(report.confident)
        assertEquals(3, switches.report("new").alreadyLinked)

        // Both sources on: each favorite now has two streams, so the Stream picker offers a choice.
        val linked = channels.multiLinked().first()
        assertEquals(3, linked.size)
        val uc = channels.channelsNow(ChannelGroup.Favorites).first().userChannelId!!
        val newStream = linked.getValue(uc).first { it.sourceName == "new" }
        channels.choosePreferred(uc, newStream.streamId)
        assertEquals(newStream.streamId, channels.channelByUserChannel(uc)?.stream?.id)
        // Favorites show the preferred stream too, not whichever link SQLite happens to group on.
        assertEquals(newStream.streamId, channels.channelsNow(ChannelGroup.Favorites).first { it.userChannelId == uc }.stream.id)
        val oldStream = linked.getValue(uc).first { it.sourceName == "old" }
        channels.choosePreferred(uc, oldStream.streamId)
        assertEquals(oldStream.streamId, channels.channelsNow(ChannelGroup.Favorites).first { it.userChannelId == uc }.stream.id)
        channels.choosePreferred(uc, newStream.streamId)

        // The old source goes: favorites stay, playing from the new source.
        sources.save(sources.byId("old")!!.copy(enabled = false))
        val after = channels.channelsNow(ChannelGroup.Favorites)
        assertEquals(3, after.size)
        assertTrue(after.all { it.stream.sourceId == "new" })
    }

    @Test fun aConfirmedSuggestionTeachesAnAlias(): Unit = runBlocking {
        demo("old")
        val fav = firstChannels("old", 1).single()
        channels.setFavorite(fav, true)
        demo("new")
        val p = switches.report("new").confident.single()
        val from = p.userChannel.streams.first()
        // Pretend the user confirmed a differently named stream.
        val other = p.best.copy(stream = p.best.stream.copy(rawName = "Totally Different Name"), confidence = ChannelMatcher.Confidence.SUGGESTED)
        switches.accept(p.userChannel.id, from, other)
        val alias = db.matchQueries.allAliases().executeAsList().single()
        assertEquals(ChannelMatcher.canonical("Totally Different Name"), alias.a)
    }

    @Test fun rejectedMatchesStayAway(): Unit = runBlocking {
        demo("old")
        channels.setFavorite(firstChannels("old", 1).single(), true)
        demo("new")
        val p = switches.report("new").confident.single()
        p.matches.forEach { switches.reject(p.userChannel.id, it.stream.id) }
        val again = switches.report("new")
        assertTrue((again.confident + again.suggested).none { q -> q.userChannel.id == p.userChannel.id && q.matches.any { m -> m.stream.id in p.matches.map { it.stream.id } } })
    }
}

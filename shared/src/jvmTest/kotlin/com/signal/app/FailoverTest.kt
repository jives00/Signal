package com.signal.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.ChannelRepository
import com.signal.data.Log
import com.signal.data.Settings
import com.signal.data.SourceRepository
import com.signal.data.SwitchRepository
import com.signal.db.user.UserDatabase
import com.signal.model.ChannelGroup
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.platform.SecretBox
import com.signal.player.PlaybackState
import com.signal.ui.TestPlayer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Automatic failover to a channel's next linked stream (PLAN §4.5, milestone 5). */
class FailoverTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    private val sources = SourceRepository(db, HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), box, Dispatchers.IO, Log { _, _ -> })
    private val channels = ChannelRepository(db, settings, Dispatchers.IO)
    private val switches = SwitchRepository(db, settings, Dispatchers.IO)
    private val player = TestPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var links: Map<String, List<ChannelRepository.LinkedStream>> = emptyMap()
    private val playback = PlaybackController(player, channels, sources, settings, scope, Log { _, _ -> }, linkedStreams = { links })

    @AfterTest fun stop() = scope.cancel()

    private suspend fun demo(id: String) {
        sources.save(Source(id = id, name = id, kind = SourceKind.DEMO, url = "demo:"))
        sources.refresh(id).getOrThrow()
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what (playing ${playback.current.value?.stream?.sourceId}, ${player.state.value.status})")
            delay(10)
        }
    }

    /** A favorite carried by sources "a" and "b", linked, preferring "a". */
    private suspend fun linkedFavorite(): String {
        demo("a")
        val ch = channels.groupsNow().map { it.group }.filterIsInstance<ChannelGroup.Provider>().first { it.sourceId == "a" }.let { channels.channelsNow(it).first() }
        channels.setFavorite(ch, true)
        demo("b")
        switches.acceptAll(switches.report("b").confident)
        links = channels.multiLinked().first()
        val uc = channels.channelsNow(ChannelGroup.Favorites).single().userChannelId!!
        channels.choosePreferred(uc, links.getValue(uc).first { it.sourceName == "a" }.streamId)
        return uc
    }

    private val playingSource get() = playback.current.value?.stream?.sourceId

    @Test fun aFailedStreamMovesToTheNextLinkOnceThenShowsTheError(): Unit = runBlocking {
        val uc = linkedFavorite()
        playback.tune(channels.channelByUserChannel(uc)!!, fullScreen = true)
        until("playing a") { player.state.value.status == PlaybackState.Status.PLAYING && playingSource == "a" }

        player.fail()
        until("failed over to b") { playingSource == "b" && player.state.value.status == PlaybackState.Status.PLAYING }
        assertEquals(2, player.played.size)

        // Every link tried: the error stays, with no loop back to a.
        player.fail()
        delay(300)
        assertEquals(PlaybackState.Status.ERROR, player.state.value.status)
        assertEquals("b", playingSource)
        assertEquals(2, player.played.size)

        // The preferred stream wasn't changed: tuning again starts on a.
        assertEquals("a", channels.channelByUserChannel(uc)!!.stream.sourceId)
        playback.stop()
        playback.tune(channels.channelByUserChannel(uc)!!, fullScreen = true)
        until("a again") { playingSource == "a" && player.state.value.status == PlaybackState.Status.PLAYING }
    }

    @Test fun aChannelWithOneStreamJustShowsTheError(): Unit = runBlocking {
        demo("a")
        val ch = channels.groupsNow().map { it.group }.filterIsInstance<ChannelGroup.Provider>().first().let { channels.channelsNow(it).first() }
        playback.tune(ch, fullScreen = true)
        until("playing") { player.state.value.status == PlaybackState.Status.PLAYING }
        player.fail()
        delay(300)
        assertEquals(PlaybackState.Status.ERROR, player.state.value.status)
        assertEquals(1, player.played.size)
    }
}

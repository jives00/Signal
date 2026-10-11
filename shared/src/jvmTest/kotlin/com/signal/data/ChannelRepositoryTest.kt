package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.demo.DemoData
import com.signal.model.ChannelGroup
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChannelRepositoryTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) }
    private val db = UserDatabase(driver)
    private val settings = Settings(db, Dispatchers.IO)
    private val log = Log { _, _ -> }
    private val reversingBox = object : SecretBox {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(cipher: String) = cipher.removePrefix("enc:").reversed()
    }
    private val m3uBody = """
        #EXTM3U
        #EXTINF:-1 tvg-id="a" group-title="Sports",US: Alpha HD
        http://h/joe/pw/1.ts
        #EXTINF:-1 tvg-id="b" group-title="Sports",Bravo
        http://h/joe/pw/2.ts
        #EXTINF:-1 tvg-id="c" group-title="News",Charlie ᴴᴰ
        http://h/joe/pw/3.ts
    """.trimIndent()
    private val http = HttpClient(MockEngine { respond(m3uBody, HttpStatusCode.OK) })
    private val sources = SourceRepository(db, http, reversingBox, Dispatchers.IO, log)
    private val channels = ChannelRepository(db, settings, Dispatchers.IO)

    private fun setup(): Source = runBlocking {
        val s = Source(id = "s1", name = "Provider A", kind = SourceKind.M3U, url = "http://h/list.m3u", username = "joe", password = "pw")
        sources.save(s)
        assertEquals(3, sources.refresh("s1").getOrThrow())
        s
    }

    @Test
    fun passwordsAreStoredEncrypted() = runBlocking {
        setup()
        val stored = db.sourceQueries.byId("s1").executeAsOne().password_enc
        assertFalse(stored.contains("pw") && !stored.startsWith("enc:"))
        assertEquals("enc:wp", stored)
        assertEquals("pw", sources.byId("s1")!!.password)
    }

    @Test
    fun groupsComeInProviderOrderAfterFavorites() = runBlocking {
        setup()
        val groups = channels.groups().first()
        assertEquals(listOf("Favorites", "Sports", "News"), groups.map { it.group.title })
        assertEquals(2, groups[1].count)
    }

    @Test
    fun displayNamesAreCleanedButRawNamesKept() = runBlocking {
        setup()
        val news = channels.channelsNow(ChannelGroup.Provider("s1", "News", "News"))
        assertEquals("Charlie HD", news.single().displayName)
        assertEquals("Charlie ᴴᴰ", news.single().stream.rawName)
    }

    @Test
    fun aUserChannelIsCreatedOnceAndOnlyWhenNeeded() = runBlocking {
        setup()
        val sports = ChannelGroup.Provider("s1", "Sports", "Sports")
        val alpha = channels.channelsNow(sports).first()
        assertEquals(null, alpha.userChannelId)
        channels.setFavorite(alpha, true)
        channels.rememberAspect(alpha, "FILL")
        channels.rename(alpha, "Alpha")
        assertEquals(1L, db.userChannelQueries.userChannelForStream(alpha.stream.id).executeAsList().size.toLong())
        val reloaded = channels.channelsNow(sports).first()
        assertNotNull(reloaded.userChannelId)
        assertTrue(reloaded.isFavorite)
        assertEquals("Alpha", reloaded.displayName)
    }

    @Test
    fun customizationsSurviveARefresh() = runBlocking {
        setup()
        val sports = ChannelGroup.Provider("s1", "Sports", "Sports")
        channels.rename(channels.channelsNow(sports)[1], "Bravo Renamed")
        sources.refresh("s1").getOrThrow()
        assertEquals("Bravo Renamed", channels.channelsNow(sports)[1].displayName)
    }

    @Test
    fun favoritesKeepTheirManualOrderAndMove() = runBlocking {
        setup()
        val all = channels.channelsNow(ChannelGroup.Provider("s1", "Sports", "Sports")) +
            channels.channelsNow(ChannelGroup.Provider("s1", "News", "News"))
        all.forEach { channels.setFavorite(it, true) }
        assertEquals(listOf("US: Alpha HD", "Bravo", "Charlie HD"), channels.channelsNow(ChannelGroup.Favorites).map { it.displayName })
        val charlie = channels.channelsNow(ChannelGroup.Favorites).last()
        channels.moveFavorite(charlie.userChannelId!!, 0)
        assertEquals(listOf("Charlie HD", "US: Alpha HD", "Bravo"), channels.channelsNow(ChannelGroup.Favorites).map { it.displayName })
    }

    @Test
    fun hidingRemovesFromListsAndProfilesAreSeparate() = runBlocking {
        setup()
        val sports = ChannelGroup.Provider("s1", "Sports", "Sports")
        channels.setHidden(channels.channelsNow(sports).first(), true)
        assertEquals(listOf("Bravo"), channels.channelsNow(sports).map { it.displayName })
        assertEquals(1, channels.hiddenChannels().first().size)
        // Another profile on the same device database doesn't see the hide (PLAN §5.4).
        settings.put(Settings.Key.PROFILE_ID, "kids")
        assertEquals(2, channels.channelsNow(sports).size)
    }

    @Test
    fun hiddenGroupsLeaveTheList() = runBlocking {
        setup()
        channels.setGroupHidden(ChannelGroup.Provider("s1", "News", "News"), true)
        assertEquals(listOf("Favorites", "Sports"), channels.groups().first().map { it.group.title })
        assertEquals(3, channels.groups(includeHidden = true).first().size)
    }

    @Test
    fun playlistsHoldChannelsInOrder() = runBlocking {
        setup()
        val id = channels.createPlaylist("My Sports")
        val sports = channels.channelsNow(ChannelGroup.Provider("s1", "Sports", "Sports"))
        sports.reversed().forEach { channels.addToPlaylist(it, id) }
        assertEquals(listOf("Bravo", "US: Alpha HD"), channels.channelsNow(ChannelGroup.Playlist(id, "My Sports")).map { it.displayName })
        assertTrue(channels.groups().first().any { it.group.title == "My Sports" })
    }

    @Test
    fun demoSourceLoadsTwoThousandChannels() = runBlocking {
        sources.save(Source(id = "demo", name = "Demo", kind = SourceKind.DEMO, url = "demo:"))
        assertEquals(DemoData.CHANNEL_COUNT, sources.refresh("demo").getOrThrow())
        val groups = channels.groups().first()
        assertTrue(groups.size > 10)
        assertEquals(DemoData.CHANNEL_COUNT, groups.drop(1).sumOf { it.count })
    }
}

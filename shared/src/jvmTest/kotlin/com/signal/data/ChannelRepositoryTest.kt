package com.signal.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.user.UserDatabase
import com.signal.demo.DemoData
import com.signal.match.NameCleanup
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

    /** Sources per profile (PLAN §9.1): a source the profile leaves out drops from the lists, on this profile only. */
    @Test
    fun aSourceHiddenOnTheProfileLeavesTheLists() = runBlocking {
        setup()
        sources.save(Source(id = "s2", name = "Provider B", kind = SourceKind.M3U, url = "http://h/b.m3u"))
        sources.refresh("s2").getOrThrow()
        fun providerGroups(gs: List<GroupEntry>) = gs.mapNotNull { (it.group as? ChannelGroup.Provider)?.sourceId }.distinct()
        assertEquals(listOf("s1", "s2"), providerGroups(channels.groups().first()))

        settings.put(Settings.Key.HIDDEN_SOURCES, HiddenSources.encode(setOf("s2")))
        // Settings writes go through a queue on io, so wait for the lists to follow.
        val hidden = kotlinx.coroutines.withTimeout(5_000) { channels.groups().first { providerGroups(it) == listOf("s1") } }
        assertTrue(hidden.none { it.sourceName != null }, "with one source showing, groups don't name it")
        assertTrue(sources.byId("s2")!!.hiddenHere)
        assertEquals(listOf("s1"), sources.active().map { it.id })
        assertEquals(3, db.streamQueries.searchIndex("main").executeAsList().size, "Search leaves out its channels")

        // Another profile has its own choice: nothing hidden there.
        settings.put(Settings.Key.PROFILE_ID, "kids")
        kotlinx.coroutines.withTimeout(5_000) { channels.groups().first { providerGroups(it) == listOf("s1", "s2") } }
        assertFalse(sources.byId("s2")!!.hiddenHere)
    }

    @Test
    fun hiddenSourcesRoundTrip() {
        assertEquals(null, HiddenSources.encode(emptySet()))
        assertEquals("a,b", HiddenSources.encode(setOf("b", "a")))
        assertEquals(setOf("a", "b"), HiddenSources.parse(" a, b ,,"))
        assertEquals(emptySet(), HiddenSources.parse(null))
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
    fun nameCleanupRulesChangeDisplayedNamesButARenameWins() = runBlocking {
        setup()
        val sports = ChannelGroup.Provider("s1", "Sports", "Sports")
        settings.put(Settings.Key.NAME_CLEANUP, NameCleanup(setOf(NameCleanup.Preset.COUNTRY, NameCleanup.Preset.QUALITY)).encode())
        assertEquals(listOf("Alpha", "Bravo"), channels.channelsNow(sports).map { it.displayName })
        assertEquals("US: Alpha HD", channels.channelsNow(sports).first().stream.rawName)
        assertEquals("Charlie", channels.channelsNow(ChannelGroup.Provider("s1", "News", "News")).single().displayName)

        channels.rename(channels.channelsNow(sports).first(), "US: My Alpha HD")
        assertEquals("US: My Alpha HD", channels.channelsNow(sports).first().displayName)
        // Group names only change when asked.
        assertEquals(listOf("Favorites", "Sports", "News"), channels.groups().first().map { it.group.title })
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
    fun favoritesSortLiveAndManualComesBack() = runBlocking {
        setup()
        val all = channels.channelsNow(ChannelGroup.Provider("s1", "Sports", "Sports")) +
            channels.channelsNow(ChannelGroup.Provider("s1", "News", "News"))
        all.forEach { channels.setFavorite(it, true) }
        channels.moveFavorite(channels.channelsNow(ChannelGroup.Favorites).last().userChannelId!!, 0)
        val manual = listOf("Charlie HD", "US: Alpha HD", "Bravo")
        assertEquals(manual, channels.channelsNow(ChannelGroup.Favorites).map { it.displayName })
        settings.put(Settings.Key.FAVORITES_SORT, "A_Z")
        assertEquals(listOf("Bravo", "Charlie HD", "US: Alpha HD"), channels.channelsNow(ChannelGroup.Favorites).map { it.displayName })
        // Manual again: the order underneath was never rewritten.
        settings.put(Settings.Key.FAVORITES_SORT, null)
        assertEquals(manual, channels.channelsNow(ChannelGroup.Favorites).map { it.displayName })
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
    fun playlistSortIsLiveAndManualComesBack() = runBlocking {
        setup()
        val id = channels.createPlaylist("Mix")
        val all = channels.channelsNow(ChannelGroup.Provider("s1", "Sports", "Sports")) + channels.channelsNow(ChannelGroup.Provider("s1", "News", "News"))
        all.reversed().forEach { channels.addToPlaylist(it, id) }
        val playlist = ChannelGroup.Playlist(id, "Mix")
        val manual = listOf("Charlie HD", "Bravo", "US: Alpha HD")
        assertEquals(manual, channels.channelsNow(playlist).map { it.displayName })
        channels.setPlaylistSort(id, com.signal.model.ChannelSort.A_Z)
        assertEquals(listOf("Bravo", "Charlie HD", "US: Alpha HD"), channels.channelsNow(playlist).map { it.displayName })
        // Menus read the sort without the database, and the group list carries it.
        assertEquals(com.signal.model.ChannelSort.A_Z, channels.playlistSort(id))
        assertEquals(com.signal.model.ChannelSort.A_Z, channels.groups().first().first { it.group == playlist }.channelSort)
        channels.setPlaylistSort(id, null)
        assertEquals(manual, channels.channelsNow(playlist).map { it.displayName })
        assertEquals(null, channels.playlistSort(id))
    }

    @Test
    fun connectionLimitNeverExceedsTheProvider() {
        val xtream = Source(id = "x", name = "X", kind = SourceKind.XTREAM, url = "http://h")
        assertEquals(null, xtream.copy(connectionLimit = 0).connections) // not refreshed yet
        assertEquals(4, xtream.copy(connectionLimit = 0, providerConnections = 4).connections)
        assertEquals(2, xtream.copy(connectionLimit = 2, providerConnections = 4).connections)
        assertEquals(4, xtream.copy(connectionLimit = 6, providerConnections = 4).connections)
        assertEquals(2, Source(id = "m", name = "M", kind = SourceKind.M3U, url = "http://h/l.m3u").connections)
    }

    @Test
    fun aRefreshKeepsTheUsersConnectionLimit() = runBlocking {
        setup()
        sources.save(sources.byId("s1")!!.copy(connectionLimit = 1))
        sources.refresh("s1").getOrThrow()
        // M3U reports no limit, so the user's stays and there's no provider's.
        assertEquals(1, sources.byId("s1")!!.connectionLimit)
        assertEquals(null, sources.byId("s1")!!.providerConnections)
    }

    @Test
    fun playbackSettingsReadTheirDefaultsAndChoices() {
        val defaults = settings.playerSettings()
        assertEquals(com.signal.player.PlayerSettings(), defaults)
        settings.put(Settings.Key.AUDIO_LANGUAGES, "es, en")
        settings.put(Settings.Key.SUBTITLES, "ALWAYS")
        settings.put(Settings.Key.LIVE_BUFFER, "STABLE")
        settings.put(Settings.Key.STREAM_TIMEOUT, "30")
        settings.put(Settings.Key.STREAM_RETRIES, "9") // out of range: the default
        settings.put(Settings.Key.VIDEO_DECODER, "NOT_A_MODE")
        settings.put(Settings.Key.AUDIO_PASSTHROUGH, "false")
        val s = settings.playerSettings()
        assertEquals(listOf("es", "en"), s.audioLanguages)
        assertEquals(com.signal.player.SubtitleMode.ALWAYS, s.subtitles)
        assertEquals(com.signal.player.LiveBuffer.STABLE, s.liveBuffer)
        assertEquals(30, s.timeoutSeconds)
        assertEquals(1, s.retries)
        assertEquals(com.signal.player.DecoderMode.HARDWARE_FALLBACK, s.decoder)
        assertFalse(s.passthrough)
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

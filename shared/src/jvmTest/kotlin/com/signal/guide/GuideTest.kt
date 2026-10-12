package com.signal.guide

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.ChannelRepository
import com.signal.data.Log
import com.signal.data.Settings
import com.signal.data.SourceRepository
import com.signal.db.user.UserDatabase
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.model.EpgSource
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.platform.SecretBox
import com.signal.ui.TestPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideTest {
    private val hour = 3_600_000L
    private val now = 1_791_828_000_000L // 2026-10-12T18:00:00Z
    private val platform = TestPlatform()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Log { _, _ -> }
    private val db = UserDatabase(platform.createUserDatabaseDriver())
    private val settings = Settings(db, platform.io)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }

    /** Serves XMLTV per URL; a missing URL answers 404. */
    private val served = HashMap<String, ByteArray>()
    private val http = HttpClient(MockEngine { req ->
        served[req.url.toString()]?.let { respond(it) } ?: respondError(HttpStatusCode.NotFound)
    })

    private val sources = SourceRepository(db, http, box, platform.io, log)
    private val channels = ChannelRepository(db, settings, platform.io)
    private val epg = EpgRepository(db, settings, platform.io)
    private val store = GuideStore(platform, Dispatchers.IO, scope, log)
    private val guide = GuideRepository(store, epg, settings, Dispatchers.IO, scope)
    private val builder = GuideBuilder(http, sources, Dispatchers.IO, log)

    @AfterTest fun tearDown() = scope.cancel()

    private fun xmltv(vararg programs: Triple<String, String, Pair<Long, Long>>, channels: List<Pair<String, String>> = emptyList()) = buildString {
        fun t(ms: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)) + " +0000"
        append("<tv>")
        channels.forEach { (id, name) -> append("""<channel id="$id"><display-name>$name</display-name></channel>""") }
        programs.forEach { (ch, title, time) -> append("""<programme channel="$ch" start="${t(time.first)}" stop="${t(time.second)}"><title>$title</title></programme>""") }
        append("</tv>")
    }.toByteArray()

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(bytes) } }.toByteArray()

    private suspend fun m3u(body: String): List<Channel> {
        served["http://p/list.m3u"] = body.toByteArray()
        sources.save(Source(id = "m3u", name = "P", kind = SourceKind.M3U, url = "http://p/list.m3u"))
        sources.refresh("m3u").getOrThrow()
        return channels.groupsNow().filter { it.group is ChannelGroup.Provider }.flatMap { channels.channelsNow(it.group) }
    }

    private suspend fun build(): Boolean {
        val list = epg.all()
        return store.build(now) { fresh, previous -> builder.build(list, fresh, previous, now).values.any { it.programs > 0 } }
    }

    /** Waits for the repository's state to see the latest EPG sources, mappings and guide. */
    private suspend fun settle() {
        val expected = epg.all()
        waitState("sources and guide") { it.sources == expected && it.version == store.version.value }
    }

    private suspend fun waitState(what: String, p: (GuideRepository.State) -> Boolean) {
        withTimeoutOrNull(10_000) { guide.state.first(p) } ?: error("Timed out waiting for $what: ${guide.state.value}")
    }

    @Test fun demoGuideBuildsAndMapsByTvgId() = runBlocking<Unit> {
        sources.save(Source(id = "demo", name = "Demo", kind = SourceKind.DEMO, url = "demo:"))
        sources.refresh("demo").getOrThrow()
        assertTrue(epg.ensureForSource(sources.byId("demo")!!))
        assertFalse(epg.ensureForSource(sources.byId("demo")!!), "only once per source")
        assertTrue(build())
        settle()
        val first = channels.channelsNow(channels.groupsNow().first { it.group is ChannelGroup.Provider }.group).first()
        val (on, next) = guide.nowAndNext(first, now)
        assertNotNull(on)
        assertTrue(on.isOn(now))
        assertNotNull(next)
        assertEquals(on.stop, next.start)
        assertNotNull(store.builtAt())
    }

    @Test fun xmltvPlainAndGzippedWithTimeShiftAndPruning() = runBlocking<Unit> {
        val list = m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.us\" group-title=\"G\",US: Alpha HD\nhttp://p/1.ts\n#EXTINF:-1 group-title=\"G\",Bravo ᴴᴰ\nhttp://p/2.ts\n")
        served["http://g/one.xml"] = xmltv(
            Triple("a.us", "Ended", now - 3 * hour to now - 2 * hour),
            Triple("a.us", "On now", now - hour to now + hour),
            Triple("a.us", "Far future", now + 8 * 24 * hour to now + 8 * 24 * hour + hour),
        )
        served["http://g/two.xml.gz"] = gzip(xmltv(Triple("bravo", "Bravo show", now - hour to now + hour), channels = listOf("bravo" to "Bravo")))
        epg.addXmltv("One", "http://g/one.xml")
        epg.addXmltv("Two", "http://g/two.xml.gz")
        assertTrue(build())
        settle()

        val alpha = list.first { it.stream.tvgId == "a.us" }
        assertEquals(listOf("On now"), guide.programs(alpha, now - 24 * hour, now + 9 * 24 * hour).map { it.title }, "ended and >7-day programs are pruned")

        // Bravo has no tvg-id: matched by normalized name in the second source, as a fallback.
        val bravo = list.first { it.stream.tvgId == null }
        val ref = guide.resolve(bravo)!!
        assertEquals("bravo", ref.guideChannelId)
        assertTrue(ref.isFallback)

        // Time shift applies at read time, with no rebuild.
        val one = epg.all().first { it.name == "One" }
        epg.update(one.copy(timeShiftMinutes = 60))
        settle()
        assertEquals(now, guide.programs(alpha, now, now + 2 * hour).single().start)
    }

    @Test fun manualMappingAndOverrides() = runBlocking<Unit> {
        val list = m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.us\" group-title=\"G\",Alpha\nhttp://p/1.ts\n")
        served["http://g/one.xml"] = xmltv(Triple("a.us", "From one", now to now + hour))
        served["http://g/two.xml"] = xmltv(Triple("a.us", "From two", now to now + hour), Triple("other", "Other channel", now to now + hour))
        val one = epg.addXmltv("One", "http://g/one.xml")
        val two = epg.addXmltv("Two", "http://g/two.xml")
        build()
        settle()
        val alpha = list.single()
        assertEquals("From one", guide.nowAndNext(alpha, now).first?.title, "the first source is the default")

        epg.setDefault(two.id)
        waitState("default") { it.defaultId == two.id }
        assertEquals("From two", guide.nowAndNext(alpha, now).first?.title)

        val uc = channels.ensureUserChannel(alpha)
        val mapped = channels.channelByKey(uc)!!
        epg.setMapping(uc, one.id, null)
        waitState("override") { uc in it.mappings }
        assertEquals("From one", guide.nowAndNext(mapped, now).first?.title, "an EPG override")

        epg.setMapping(uc, two.id, "other")
        waitState("manual mapping") { it.mappings[uc]?.guideChannelId == "other" }
        assertEquals("Other channel", guide.nowAndNext(mapped, now).first?.title, "a manual mapping")
    }

    @Test fun aFailedSourceKeepsItsPreviousData() = runBlocking<Unit> {
        val list = m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.us\" group-title=\"G\",Alpha\nhttp://p/1.ts\n")
        served["http://g/one.xml"] = xmltv(Triple("a.us", "Kept", now to now + hour))
        epg.addXmltv("One", "http://g/one.xml")
        assertTrue(build())
        served.remove("http://g/one.xml")
        assertTrue(build(), "carried-over programs still make a guide worth swapping in")
        settle()
        assertEquals("Kept", guide.nowAndNext(list.single(), now).first?.title)
    }

    @Test fun nothingBuiltKeepsTheOldGuide() = runBlocking<Unit> {
        epg.addXmltv("Missing", "http://g/missing.xml")
        assertFalse(build())
        assertNull(store.db)
    }

    @Test fun storeReopensTheNewestGuide() = runBlocking<Unit> {
        m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.us\" group-title=\"G\",Alpha\nhttp://p/1.ts\n")
        served["http://g/one.xml"] = xmltv(Triple("a.us", "Show", now to now + hour))
        epg.addXmltv("One", "http://g/one.xml")
        build()
        val reopened = GuideStore(platform, Dispatchers.IO, scope, log).apply { open() }
        assertEquals(now, reopened.builtAt())
    }

    @Test fun overlapsKeepTheEarlierProgram() = runBlocking<Unit> {
        val list = m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.us\" group-title=\"G\",Alpha\nhttp://p/1.ts\n")
        served["http://g/one.xml"] = xmltv(
            Triple("a.us", "First", now to now + 2 * hour),
            Triple("a.us", "Same slot", now to now + 2 * hour),
            Triple("a.us", "Starts inside", now + hour to now + 3 * hour),
            Triple("a.us", "After", now + 2 * hour to now + 3 * hour),
        )
        epg.addXmltv("One", "http://g/one.xml")
        build()
        settle()
        assertEquals(listOf("First", "After"), guide.programs(list.single(), now, now + 4 * hour).map { it.title })
    }

    @Test fun tvgIdMatchesInAnyCaseAndNamesMatchAnyDisplayName() = runBlocking<Unit> {
        val list = m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"ESPN2.us\" group-title=\"G\",Two\nhttp://p/1.ts\n#EXTINF:-1 group-title=\"G\",US: ESPN U HD\nhttp://p/2.ts\n")
        val xml = xmltv(
            Triple("espn2.us", "Deuce", now to now + hour),
            Triple("espnu", "College", now to now + hour),
            channels = listOf("espn2.us" to "ESPN2", "espnu" to "ESPNU"),
        ).toString(Charsets.UTF_8).replace("<display-name>ESPNU</display-name>", "<display-name>ESPNU</display-name><display-name>ESPN U</display-name>")
        served["http://g/one.xml"] = xml.toByteArray()
        epg.addXmltv("One", "http://g/one.xml")
        build()
        settle()
        assertEquals("Deuce", guide.nowAndNext(list.first { it.stream.tvgId != null }, now).first?.title)
        assertEquals("College", guide.nowAndNext(list.first { it.stream.tvgId == null }, now).first?.title, "matched by its second display-name")
    }

    @Test fun aGuideFromAnotherSchemaIsDeletedOnOpen() = runBlocking<Unit> {
        m3u("#EXTM3U\n#EXTINF:-1 tvg-id=\"a.us\" group-title=\"G\",Alpha\nhttp://p/1.ts\n")
        served["http://g/one.xml"] = xmltv(Triple("a.us", "Show", now to now + hour))
        epg.addXmltv("One", "http://g/one.xml")
        build()
        store.db!!.guideQueries.putMeta(GuideStore.META_SCHEMA, "1")
        val reopened = GuideStore(platform, Dispatchers.IO, scope, log).apply { open() }
        assertNull(reopened.db, "an older guide is deleted, so a fresh one builds")
    }

    @Test fun epgSourcesMoveInTheFallbackOrder() = runBlocking<Unit> {
        val a = epg.addXmltv("A", "http://a")
        val b = epg.addXmltv("B", "http://b")
        val c = epg.addXmltv("C", "http://c")
        epg.move(c.id, 0)
        assertEquals(listOf(c.id, a.id, b.id), epg.all().map(EpgSource::id))
        epg.delete(a.id)
        assertEquals(listOf(c.id, b.id), epg.all().map(EpgSource::id))
    }
}

class UserDatabaseMigrationTest {
    /** A 0.1.x user.db (schema version 1) gains the milestone 2 tables and keeps its data. */
    @Test fun version1MigratesToTheGuideTables() {
        val file = java.io.File.createTempFile("user-v1", ".db").apply { delete(); deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath}"
        JdbcSqliteDriver(url).use { d ->
            UserDatabase.Schema.create(d)
            listOf("guide_mapping", "reminder", "epg_source").forEach { d.execute(null, "DROP TABLE $it", 0) }
            d.execute(null, "DROP INDEX IF EXISTS reminder_start", 0)
            d.execute(null, "INSERT INTO source(id, name, kind, url, created_at) VALUES ('s', 'Kept', 'M3U', 'http://x', 1)", 0)
            d.execute(null, "PRAGMA user_version = 1", 0)
        }
        JdbcSqliteDriver(url, java.util.Properties(), UserDatabase.Schema).use { d ->
            val db = UserDatabase(d)
            assertEquals("Kept", db.sourceQueries.byId("s").executeAsOne().name)
            assertTrue(db.guideQueries.allEpgSources().executeAsList().isEmpty())
            assertTrue(db.reminderQueries.remindersFor("main").executeAsList().isEmpty())
        }
    }
}

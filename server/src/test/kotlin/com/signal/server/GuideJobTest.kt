package com.signal.server

import com.signal.guide.GuideStore
import com.signal.model.EpgKind
import com.signal.model.EpgSource
import com.signal.nas.NasGuideMeta
import com.signal.nas.NasGuidePaths
import com.signal.nas.NasPaths
import com.signal.nas.PairRequest
import com.signal.nas.PairStarted
import com.signal.nas.PairStatus
import com.signal.nas.bearer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import com.signal.sync.BUNDLE_SCHEMA
import com.signal.sync.PushRequest
import com.signal.sync.SyncFields
import com.signal.sync.SyncItem
import com.signal.sync.SyncPaths
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.model.Source
import com.signal.model.SourceKind
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.server.testing.testApplication
import io.ktor.test.dispatcher.runTestWithRealTime
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretCipherTest {
    @Test fun roundTripsAndRefusesTheWrongKey() {
        val cipher = SecretCipher("one")
        val sealed = cipher.encrypt("hunter2")
        assertNotEquals("hunter2", sealed)
        assertNotEquals(sealed, cipher.encrypt("hunter2"), "a fresh IV every time")
        assertEquals("hunter2", cipher.decrypt(sealed))
        assertFailsWith<Exception> { SecretCipher("two").decrypt(sealed) }
    }
}

class GuideScheduleTest {
    private val chicago = ZoneId.of("America/Chicago")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, chicago).toInstant().toEpochMilli()

    @Test fun nextRunIsTonightOrTomorrowInLocalTime() {
        val s = GuideSchedule("03:00", "America/Chicago")
        assertEquals(at(2026, 10, 13, 3), s.nextAfter(at(2026, 10, 12, 15)))
        assertEquals(at(2026, 10, 13, 3), s.nextAfter(at(2026, 10, 13, 2, 59)))
        assertEquals(at(2026, 10, 14, 3), s.nextAfter(at(2026, 10, 13, 3)))
    }

    @Test fun followsDaylightSavingTime() {
        // Clocks go back on 1 November 2026: 3:00 AM local is still 3:00 AM local.
        val s = GuideSchedule("03:00", "America/Chicago")
        val next = s.nextAfter(at(2026, 10, 31, 12))
        assertEquals(3, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), chicago).hour)
    }

    @Test fun validates() {
        assertTrue(GuideSchedule().isValid())
        assertEquals(false, GuideSchedule("25:00").isValid())
        assertEquals(false, GuideSchedule("03:00", "Mars/Olympus").isValid())
    }
}

class GuideJobTest {
    // These wait for real guide builds. testApplication allows 60 s in all, which a busy CI runner
    // ran out of during a release, while CI and the image build shared it.
    private fun guideBuildTest(block: suspend ApplicationTestBuilder.() -> Unit) =
        runTestWithRealTime(timeout = 3.minutes) { runTestApplication(block = block) }

    private val json = Json { ignoreUnknownKeys = true }
    private val dataDir = Files.createTempDirectory("signal-data").toFile()

    private fun companion(secretKey: String? = "test-key") = Companion(
        Config(port = 0, db = null, secretKey = secretKey, dataDir = dataDir.path, webDir = "missing"),
        deviceStore = MemoryDeviceStore(),
        companionStore = MemoryCompanionStore(),
        syncStoreOverride = MemorySyncStore(),
    )

    private suspend fun HttpClient.pair(): String {
        val started: PairStarted = json.decodeFromString(post(NasPaths.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest("dev-1", "TV", "SHIELD", "0.3.0")))
        }.bodyAsText())
        post("/api/devices/approve") { contentType(ContentType.Application.Json); setBody("""{"code":"${started.code}"}""") }
        return json.decodeFromString<PairStatus>(get(NasPaths.pairStatus(started.pairingId)).bodyAsText()).apiKey!!
    }

    // What a TV with the Demo source syncs: its EPG source, as a household item.
    private val demoPush = PushRequest(
        BUNDLE_SCHEMA,
        listOf(SyncItem(SyncType.EPG_SOURCE, "epg-demo", SyncScope.HOUSEHOLD, fields = SyncFields.epgSource(EpgSource(id = "epg-demo", name = "Demo guide", kind = EpgKind.DEMO, sourceId = "demo"), 0))),
    )

    @Test fun aDevicesInputsBuildAGuideItCanDownload() = guideBuildTest {
        val companion = companion()
        application { signalModule(companion) }
        val key = client.pair()
        assertEquals(HttpStatusCode.NotFound, client.get(NasGuidePaths.META) { header(HttpHeaders.Authorization, bearer(key)) }.status)

        val push = client.post(SyncPaths.PUSH) {
            header(HttpHeaders.Authorization, bearer(key))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PushRequest.serializer(), demoPush))
        }
        assertEquals(HttpStatusCode.OK, push.status)

        // A synced EPG source starts a build at once.
        var meta: NasGuideMeta? = null
        var tries = 0
        while (meta == null && tries++ < 600) {
            val r = client.get(NasGuidePaths.META) { header(HttpHeaders.Authorization, bearer(key)) }
            if (r.status == HttpStatusCode.OK) meta = json.decodeFromString<NasGuideMeta>(r.bodyAsText()) else delay(100)
        }
        val m = meta ?: error("No guide was built")
        assertEquals(GuideStore.SCHEMA, m.schema)
        assertTrue(m.programs > 0)
        assertEquals(null, m.outcomes.getValue("epg-demo").error)

        val gz = client.get(NasGuidePaths.FILE) { header(HttpHeaders.Authorization, bearer(key)) }.bodyAsBytes()
        assertEquals(m.sizeBytes, gz.size.toLong())
        assertEquals(m.sha256, MessageDigest.getInstance("SHA-256").digest(gz).joinToString("") { "%02x".format(it) })
        val db = Files.createTempFile("guide", ".db").toFile()
        GZIPInputStream(gz.inputStream()).use { input -> db.outputStream().use { input.copyTo(it) } }
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT value FROM meta WHERE key = 'origin'").use { it.next(); assertEquals(GuideStore.ORIGIN_NAS, it.getString(1)) }
                s.executeQuery("SELECT COUNT(*) FROM program WHERE epg_source_id = 'epg-demo'").use { it.next(); assertEquals(m.programs.toLong(), it.getLong(1)) }
                // Stamped like the app's own files, so Android's driver opens it without re-creating tables.
                s.executeQuery("PRAGMA user_version").use { it.next(); assertTrue(it.getInt(1) > 0) }
            }
        }

        val status = client.get("/api/guide").bodyAsText()
        assertTrue(status.contains("\"epgSources\":[\"Demo guide\"]"), status)
        assertTrue(status.contains("\"inputsFrom\":\"TV\""), status)
        assertEquals(m.builtAt, m.outcomes.getValue("epg-demo").refreshedAt)

        // Sources → Refresh now: one EPG source, by ID.
        assertEquals(HttpStatusCode.NotFound, client.post("/api/guide/build?epg=nope").status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/guide/build?epg=epg-demo").status)
        var again: NasGuideMeta? = null
        tries = 0
        while (again == null && tries++ < 600) {
            val r = json.decodeFromString<NasGuideMeta>(client.get(NasGuidePaths.META) { header(HttpHeaders.Authorization, bearer(key)) }.bodyAsText())
            if (r.builtAt != m.builtAt) again = r else delay(100)
        }
        val a = again ?: error("Refresh now didn't rebuild")
        assertEquals(a.builtAt, a.outcomes.getValue("epg-demo").refreshedAt)
    }

    @Test fun refreshingOneEpgSourceCopiesTheOthers() = guideBuildTest {
        val companion = companion()
        application { signalModule(companion) }
        val key = client.pair()
        val two = PushRequest(BUNDLE_SCHEMA, listOf("epg-a", "epg-b").map { id ->
            SyncItem(SyncType.EPG_SOURCE, id, SyncScope.HOUSEHOLD, fields = SyncFields.epgSource(EpgSource(id = id, name = id, kind = EpgKind.DEMO, sourceId = "demo"), 0))
        })
        client.post(SyncPaths.PUSH) {
            header(HttpHeaders.Authorization, bearer(key))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PushRequest.serializer(), two))
        }
        suspend fun builtAfter(at: Long): NasGuideMeta {
            repeat(600) {
                val r = client.get(NasGuidePaths.META) { header(HttpHeaders.Authorization, bearer(key)) }
                if (r.status == HttpStatusCode.OK) json.decodeFromString<NasGuideMeta>(r.bodyAsText()).let { if (it.builtAt > at && !companion.guide.running) return it }
                delay(100)
            }
            error("No guide was built")
        }
        val first = builtAfter(0)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/guide/build?epg=epg-b").status)
        val second = builtAfter(first.builtAt)
        val a = second.outcomes.getValue("epg-a")
        assertEquals(first.outcomes.getValue("epg-a").refreshedAt, a.refreshedAt, "copied, not downloaded")
        assertTrue(a.programs > 0 && a.channels > 0)
        assertEquals(second.builtAt, second.outcomes.getValue("epg-b").refreshedAt)
        assertEquals(second.programs, a.programs + second.outcomes.getValue("epg-b").programs)
    }

    @Test fun guideEndpointsNeedAKey() = testApplication {
        application { signalModule(companion()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasGuidePaths.META).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasGuidePaths.FILE) { header(HttpHeaders.Authorization, bearer("nope")) }.status)
    }

    @Test fun withoutTheSecretKeyPasswordsAreRefused() = testApplication {
        application { signalModule(companion(secretKey = null)) }
        val key = client.pair()
        val source = Source(id = "x", name = "Provider", kind = SourceKind.XTREAM, url = "http://p.example", username = "me", password = "pw")
        val push = client.post(SyncPaths.PUSH) {
            header(HttpHeaders.Authorization, bearer(key))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PushRequest.serializer(), PushRequest(BUNDLE_SCHEMA, listOf(SyncItem(SyncType.SOURCE, "x", SyncScope.HOUSEHOLD, fields = SyncFields.source(source, 0))))))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, push.status)
    }

    @Test fun runNowNeedsInputsAndTheScheduleIsValidated() = testApplication {
        application { signalModule(companion()) }
        assertEquals(HttpStatusCode.Conflict, client.post("/api/guide/build").status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/guide/schedule") { contentType(ContentType.Application.Json); setBody("""{"time":"3am","timeZone":"America/Chicago"}""") }.status)
        assertEquals(HttpStatusCode.NoContent, client.put("/api/guide/schedule") { contentType(ContentType.Application.Json); setBody("""{"time":"04:30","timeZone":"America/New_York"}""") }.status)
        assertTrue(client.get("/api/guide").bodyAsText().contains("\"time\":\"04:30\""))
    }
}

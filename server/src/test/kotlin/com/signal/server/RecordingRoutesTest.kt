package com.signal.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingRoutesTest {
    private val store = MemoryRecordingStore()

    private fun companion(recordingsDir: String?, viewerPort: Int = 3017) = Companion(
        Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-rec").toFile().path, webDir = "missing", viewerPort = viewerPort, recordingsDir = recordingsDir),
        deviceStore = MemoryDeviceStore(),
        companionStore = MemoryCompanionStore(),
        syncStoreOverride = MemorySyncStore(),
        buildGuideOnChange = false,
        recordingStoreOverride = store,
    )

    private fun unfinished() = Recording("r1", "main", "s:x:1", "Show", "Channel", 1, Long.MAX_VALUE, RecordingState.RECORDING, updatedAt = 1)

    @Test fun theDeployGuardSaysBusyOnlyWhileARecordingIsUnfinished() = testApplication {
        application { signalModule(companion(null)) }
        assertEquals("idle", client.get("/health/recording").bodyAsText())
        store.save(unfinished())
        assertEquals("busy", client.get("/health/recording").bodyAsText())
        store.save(unfinished().copy(state = RecordingState.DONE))
        assertEquals("idle", client.get("/health/recording").bodyAsText())
    }

    @Test fun theDeployGuardAnswersOnTheViewerPortToo() = testApplication {
        application { signalModule(companion(null, viewerPort = 80)) }
        assertEquals("idle", client.get("/health/recording").bodyAsText())
        assertFalse(client.get("/api/recordings").bodyAsText().contains("recordings"), "the viewer's port has no web admin")
    }

    @Test fun withoutTheMountThePageSaysSoAndNothingStarts() = testApplication {
        application { signalModule(companion(null)) }
        assertTrue(client.get("/api/recordings").bodyAsText().contains("RECORDINGS_DIR"))
    }

    @Test fun refusesARecordingTooLongOrOnAnUnknownChannel() = testApplication {
        val dir = Files.createTempDirectory("recordings").toFile()
        application { signalModule(companion(dir.path)) }
        suspend fun start(minutes: Int) = client.post("/api/recordings") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"profile":"main","streamId":"nope","minutes":$minutes}""")
        }
        assertEquals(HttpStatusCode.BadRequest, start(0).status)
        assertEquals(HttpStatusCode.BadRequest, start(13 * 60).status)
        assertEquals(HttpStatusCode.NotFound, start(30).status)
    }

    @Test fun schedulingNeedsAChannelInTheGuideAndKeepRecordingNeedsARealChoice() = testApplication {
        val dir = Files.createTempDirectory("recordings").toFile()
        application { signalModule(companion(dir.path)) }
        val json = ContentType.Application.Json.toString()
        val scheduled = client.post("/api/recordings/schedule") {
            header(HttpHeaders.ContentType, json)
            setBody("""{"profile":"main","streamId":"nope","start":1}""")
        }
        assertEquals(HttpStatusCode.NotFound, scheduled.status)
        suspend fun extend(minutes: Int) = client.post("/api/recordings/r1/extend") {
            header(HttpHeaders.ContentType, json)
            setBody("""{"minutes":$minutes}""")
        }
        assertEquals(HttpStatusCode.BadRequest, extend(5).status)
        assertEquals(HttpStatusCode.Conflict, extend(15).status)
        store.save(unfinished().copy(state = RecordingState.SCHEDULED, stopMs = 1_000))
        assertEquals(HttpStatusCode.NoContent, extend(15).status)
        assertEquals(1_000 + 15 * 60_000L, store.get("r1")!!.stopMs)
        assertEquals("[]", client.get("/api/recordings/programs?streamId=nope").bodyAsText())
    }

    @Test fun recordingSettingsSaveAndComeBack() = testApplication {
        val c = companion(null)
        application { signalModule(c) }
        val saved = c.settings.save(c.settings.view().copy(recordBeforeMinutes = 3, recordAfterMinutes = 20, recordKeepDays = 14, recordWatchedDays = 2, recordCapGb = 900))
        assertEquals(null, saved)
        c.settings.view().let {
            assertEquals(3, it.recordBeforeMinutes)
            assertEquals(20, it.recordAfterMinutes)
            assertEquals(14, it.recordKeepDays)
            assertEquals(2, it.recordWatchedDays)
            assertEquals(900, it.recordCapGb)
        }
    }

    @Test fun aDeviceSeesOnlyItsOwnProfilesRecordingsAndNeedsItsKey() = testApplication {
        val dir = Files.createTempDirectory("recordings").toFile()
        val devices = MemoryDeviceStore()
        val key = "device-key"
        devices.save(Device("d1", "Shield", "SHIELD", "1.0", 1, 1, "main"), DeviceKeys.hash(key))
        val c = Companion(
            Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-rec").toFile().path, webDir = "missing", viewerPort = 3017, recordingsDir = dir.path),
            deviceStore = devices, companionStore = MemoryCompanionStore(), syncStoreOverride = MemorySyncStore(), buildGuideOnChange = false, recordingStoreOverride = store,
        )
        val files = RecordingFiles(dir.resolve("main/r1"))
        TsSegmenter(files).apply { feed(Ts.stream(4)); close() }
        files.end()
        store.save(unfinished().copy(state = RecordingState.DONE, endedAt = 2, expiresAt = 3, durationMs = 3_500))
        store.save(unfinished().copy(id = "r2", profileId = "parents", state = RecordingState.DONE, endedAt = 2, expiresAt = 3))
        application { signalModule(c) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/device/recordings").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/device/recordings/r1/files/index.m3u8").status)
        suspend fun get(path: String) = client.get(path) { header(HttpHeaders.Authorization, "Bearer $key") }
        val list = get("/device/recordings").bodyAsText()
        assertTrue(list.contains("r1"))
        assertFalse(list.contains("r2"))
        assertEquals(HttpStatusCode.OK, get("/device/recordings/r1/files/index.m3u8").status)
        assertEquals(HttpStatusCode.OK, get("/device/recordings/r1/files/seg-000001.ts").status)
        assertEquals(HttpStatusCode.NotFound, get("/device/recordings/r2/files/index.m3u8").status)
        val progress = client.post("/device/recordings/r1/progress") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"positionMs":1000}""")
        }
        assertEquals(HttpStatusCode.NoContent, progress.status)
        assertEquals(1_000, store.get("r1")!!.positionMs)
        assertEquals(HttpStatusCode.NotFound, client.delete("/device/recordings/r2") { header(HttpHeaders.Authorization, "Bearer $key") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/device/recordings/r1") { header(HttpHeaders.Authorization, "Bearer $key") }.status)
    }

    @Test fun aDeviceOnlyTouchesItsOwnRunningRecordingsAndCantSetNonsense() = testApplication {
        val devices = MemoryDeviceStore()
        val key = "device-key"
        devices.save(Device("d1", "Shield", "SHIELD", "1.0", 1, 1, "main"), DeviceKeys.hash(key))
        val c = Companion(
            Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-rec").toFile().path, webDir = "missing", viewerPort = 3017, recordingsDir = Files.createTempDirectory("recordings").toFile().path),
            deviceStore = devices, companionStore = MemoryCompanionStore(), syncStoreOverride = MemorySyncStore(), buildGuideOnChange = false, recordingStoreOverride = store,
        )
        store.save(unfinished().copy(id = "theirs", profileId = "parents"))
        application { signalModule(c) }
        suspend fun post(path: String, body: String = "{}") = client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        val program = """{"streamId":"nope","start":1000,"stop":2000,"title":"Show"}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/device/recordings") { setBody(program) }.status)
        assertEquals(HttpStatusCode.NotFound, post("/device/recordings", program).status, "a channel it doesn't have")
        assertEquals(HttpStatusCode.BadRequest, post("/device/recordings", """{"streamId":"s","start":2000,"stop":1000,"title":"Show"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/device/recordings", """{"streamId":"s","start":1000,"stop":2000,"title":" "}""").status)
        assertEquals(HttpStatusCode.NotFound, post("/device/recordings/theirs/stop").status)
        assertEquals(HttpStatusCode.NotFound, post("/device/recordings/theirs/extend", """{"minutes":30}""").status)
    }

    @Test fun servesAFinishedRecordingsFilesAndDeletesThem() = testApplication {
        val dir = Files.createTempDirectory("recordings").toFile()
        val files = RecordingFiles(dir.resolve("main/r1"))
        val segmenter = TsSegmenter(files)
        segmenter.feed(Ts.stream(4))
        segmenter.close()
        files.end()
        store.save(unfinished().copy(state = RecordingState.DONE, endedAt = 2))
        application { signalModule(companion(dir.path)) }

        val playlist = client.get("/api/recordings/r1/files/index.m3u8")
        assertEquals("application/vnd.apple.mpegurl", playlist.headers[HttpHeaders.ContentType])
        assertTrue(playlist.bodyAsText().contains("seg-000001.ts"))
        assertEquals(HttpStatusCode.OK, client.get("/api/recordings/r1/files/seg-000001.ts").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/recordings/r1/files/result.txt").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/recordings/r1").status)
        assertFalse(dir.resolve("main/r1").exists())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/recordings/r1").status)
    }
}

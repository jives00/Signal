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

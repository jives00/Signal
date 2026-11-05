package com.signal.server

import com.signal.model.Source
import com.signal.model.SourceAccount
import com.signal.model.SourceKind
import com.signal.nas.PairRequest
import com.signal.parse.XtreamParser
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The viewer's port (web-player.md). The test client always arrives on port 80, so a viewerPort of 80 stands for "on the viewer's port". */
class ViewerTest {
    private val devices = MemoryDeviceStore()
    private fun companion(viewerPort: Int, webDir: String = "missing") = Companion(
        Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-viewer").toFile().path, webDir = webDir, viewerPort = viewerPort),
        deviceStore = devices,
        companionStore = MemoryCompanionStore(),
        syncStoreOverride = MemorySyncStore(),
        buildGuideOnChange = false,
    )

    private fun paired(companion: Companion, profile: String? = null): String {
        val started = companion.pairings.start(PairRequest("d-1", "Phone", "Web viewer", "web"))!!
        companion.pairings.approve(started.code, devices, profile)
        return companion.pairings.status(started.pairingId).apiKey!!
    }

    @Test fun theViewerPortHasNoWebAdmin() = testApplication {
        val dir = Files.createTempDirectory("web").toFile()
        dir.resolve("index.html").writeText("admin page")
        dir.resolve("viewer.html").writeText("viewer page")
        dir.resolve("assets").mkdir()
        dir.resolve("assets/a.js").writeText("js")
        application { signalModule(companion(viewerPort = 80, webDir = dir.path)) }
        assertEquals("viewer page", client.get("/").bodyAsText())
        assertEquals("viewer page", client.get("/sources").bodyAsText())
        assertEquals("viewer page", client.get("/index.html").bodyAsText())
        assertEquals("js", client.get("/assets/a.js").bodyAsText())
        assertFalse(client.get("/api/status").bodyAsText().contains("database"))
        assertFalse(client.get("/api/sources").bodyAsText().contains("sources"))
        assertEquals(HttpStatusCode.NotFound, client.get("/viewer/nothing").status)
    }

    @Test fun theViewerApiIsOnlyOnItsPort() = testApplication {
        application { signalModule(companion(viewerPort = 3017)) }
        assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/viewer/lists").status)
    }

    @Test fun theViewerNeedsItsKeyAndShowsItsProfile() = testApplication {
        val companion = companion(viewerPort = 80)
        val parents = companion.sync.createProfile("Parents", null)
        val key = paired(companion, parents.id)
        application { signalModule(companion) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/viewer/lists").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/viewer/lists") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/viewer/lists") { header(HttpHeaders.Authorization, "Bearer $key") }.status)
        assertEquals(parents.id, devices.all().single().profileId)
        assertEquals(HttpStatusCode.NotFound, client.get("/viewer/watch/nope") { header(HttpHeaders.Authorization, "Bearer $key") }.status)
    }

    // ---- Which login plays ----
    private val second = SourceAccount("b", "Second", "u2", "p2")
    private val third = SourceAccount("c", "Third", "u3", "p3")
    private val source = Source("s", "Provider", SourceKind.XTREAM, "http://host/", "u1", "p1", accounts = listOf(second, third))

    private class Counts(val busy: Set<String>) {
        var asked = 0
        fun check(s: Source): XtreamParser.AccountInfo {
            asked++
            return XtreamParser.AccountInfo(authenticated = true, status = "Active", maxConnections = 1, activeConnections = if (s.username in busy) 1 else 0, expiresAt = null)
        }
    }

    @Test fun aViewerPlaysFromItsHomeLoginWhenFree() = runBlocking {
        val counts = Counts(emptySet())
        assertEquals("b", ViewerLogins({ counts.check(it) }).pick("d", source, "b").id)
    }

    @Test fun aViewerBorrowsWhenItsHomeLoginIsBusy() = runBlocking {
        val counts = Counts(setOf("u2"))
        assertEquals(SourceAccount.MAIN, ViewerLogins({ counts.check(it) }).pick("d", source, "b").id)
    }

    @Test fun everyLoginBusyPlaysFromHome() = runBlocking {
        val counts = Counts(setOf("u1", "u2", "u3"))
        assertEquals("b", ViewerLogins({ counts.check(it) }).pick("d", source, "b").id)
    }

    @Test fun aChannelChangeKeepsTheLoginWithoutAsking() = runBlocking {
        var time = 0L
        val counts = Counts(setOf("u2"))
        val logins = ViewerLogins({ counts.check(it) }, { time })
        val first = logins.pick("d", source, "b").id
        val asked = counts.asked
        time += 50_000
        logins.playing("d")
        time += 50_000
        assertEquals(first, logins.pick("d", source, "b").id)
        assertEquals(asked, counts.asked)
        // Idle past the hold: it asks again.
        time += 61_000
        logins.pick("d", source, "b")
        assertTrue(counts.asked > asked)
    }
}

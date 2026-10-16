package com.signal.server

import com.signal.nas.DeviceInfo
import com.signal.nas.NasPaths
import com.signal.nas.PairRequest
import com.signal.nas.PairStarted
import com.signal.nas.PairState
import com.signal.nas.PairStatus
import com.signal.nas.bearer
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkTrustTest {
    @Test fun lanAndTailscaleAreTrusted() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.105", "100.115.171.80", "::1", "fd7a:115c:a1e0::1", "::ffff:192.168.0.5")
            .forEach { assertTrue(NetworkTrust.isTrustedAddress(it), it) }
    }

    @Test fun theInternetIsNot() {
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "2001:4860:4860::8888", "synology", "")
            .forEach { assertFalse(NetworkTrust.isTrustedAddress(it), it) }
    }

    @Test fun cloudflareHeadersAreNeverTrusted() {
        assertFalse(NetworkTrust.isTrusted("192.168.0.10", listOf("CF-Connecting-IP")))
        assertTrue(NetworkTrust.isTrusted("192.168.0.10", listOf("User-Agent")))
    }
}

class ServerTest {
    private fun companion(webDir: String = "missing") = Companion(Config(port = 0, db = null, secretKey = null, dataDir = "build/tmp/data", webDir = webDir), database = null)

    @Test fun healthReportsTheDatabase() = testApplication {
        application { signalModule(companion()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"database\":\"not configured\""))
    }

    @Test fun theAdminRefusesCloudflare() = testApplication {
        application { signalModule(companion()) }
        // The test client connects from localhost, which is trusted...
        assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
        // ...unless the request came through Cloudflare.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/status") { header("CF-Ray", "abc") }.status)
    }

    @Test fun webAdminServesFilesAndFallsBackToIndex() = testApplication {
        val dir = Files.createTempDirectory("web").toFile()
        dir.resolve("index.html").writeText("<html>admin</html>")
        dir.resolve("app.js").writeText("console.log(1)")
        application { signalModule(companion(dir.path)) }
        assertEquals("console.log(1)", client.get("/app.js").bodyAsText())
        assertEquals("<html>admin</html>", client.get("/channels/123").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nothing").status)
        // Nothing outside the web directory, however the path is written.
        assertFalse(client.get("/../build.gradle.kts").bodyAsText().contains("plugins"))
    }
}

class PairingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = MemoryDeviceStore()
    private fun companion() = Companion(Config(port = 0, db = null, secretKey = null, dataDir = "build/tmp/data", webDir = "missing"), database = null, deviceStore = store)

    private suspend fun HttpClient.startPairing(deviceId: String = "dev-1"): PairStarted =
        json.decodeFromString(post(NasPaths.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest(deviceId, "Living room Shield", "SHIELD Android TV", "0.3.0")))
        }.bodyAsText())

    private suspend fun HttpClient.approve(code: String) = post("/api/devices/approve") {
        contentType(ContentType.Application.Json)
        setBody("""{"code":"$code"}""")
    }

    @Test fun aDevicePairsWithTheCodeAndUsesItsKey() = testApplication {
        application { signalModule(companion()) }
        val started = client.startPairing()
        assertEquals(6, started.code.length)
        val pending: PairStatus = json.decodeFromString(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText())
        assertEquals(PairState.PENDING, pending.state)

        assertEquals(HttpStatusCode.NotFound, client.approve("000000x").status)
        assertEquals(HttpStatusCode.OK, client.approve(started.code).status)

        val approved: PairStatus = json.decodeFromString(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText())
        assertEquals(PairState.APPROVED, approved.state)
        val key = assertNotNull(approved.apiKey)
        // The key is handed over once.
        val again: PairStatus = json.decodeFromString(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText())
        assertEquals(PairState.EXPIRED, again.state)
        assertNull(again.apiKey)

        val me = client.get(NasPaths.ME) { header(HttpHeaders.Authorization, bearer(key)) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("Living room Shield", json.decodeFromString<DeviceInfo>(me.bodyAsText()).name)
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasPaths.ME) { header(HttpHeaders.Authorization, bearer("wrong")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasPaths.ME).status)
        // Only the hash is stored.
        assertEquals(1, store.all().size)
    }

    @Test fun aDevicesLogsAreKeptForTheWebAdmin() = testApplication {
        application { signalModule(companion()) }
        val started = client.startPairing("dev-logs")
        client.approve(started.code)
        val key = json.decodeFromString<PairStatus>(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText()).apiKey!!
        assertEquals(HttpStatusCode.Unauthorized, client.post(NasPaths.LOGS) { setBody("no key") }.status)
        assertEquals(HttpStatusCode.NoContent, client.post(NasPaths.LOGS) { header(HttpHeaders.Authorization, bearer(key)); setBody("I Tune ESPN") }.status)
        val logs = client.get("/api/devices/dev-logs/logs")
        assertEquals(HttpStatusCode.OK, logs.status)
        assertEquals("I Tune ESPN", logs.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/devices/someone-else/logs").status)
        // Device IDs never become paths.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/devices/..%2F..%2Fbuild.gradle/logs").status)
    }

    @Test fun forgettingADeviceRevokesItsKey() = testApplication {
        application { signalModule(companion()) }
        val started = client.startPairing()
        client.approve(started.code)
        val key = json.decodeFromString<PairStatus>(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText()).apiKey!!
        assertEquals(HttpStatusCode.NoContent, client.patch("/api/devices/dev-1") { contentType(ContentType.Application.Json); setBody("""{"name":"Bedroom"}""") }.status)
        assertEquals("Bedroom", store.all().single().name)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/devices/dev-1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasPaths.ME) { header(HttpHeaders.Authorization, bearer(key)) }.status)
    }

    @Test fun pairingAgainReplacesTheOldKey() = testApplication {
        application { signalModule(companion()) }
        suspend fun pair(): String {
            val s = client.startPairing()
            client.approve(s.code)
            return json.decodeFromString<PairStatus>(client.get(NasPaths.pairStatus(s.pairingId)).bodyAsText()).apiKey!!
        }
        val first = pair()
        val second = pair()
        assertEquals(1, store.all().size)
        assertEquals(HttpStatusCode.Unauthorized, client.get(NasPaths.ME) { header(HttpHeaders.Authorization, bearer(first)) }.status)
        assertEquals(HttpStatusCode.OK, client.get(NasPaths.ME) { header(HttpHeaders.Authorization, bearer(second)) }.status)
    }

    @Test fun devicePairingIsRefusedThroughCloudflare() = testApplication {
        application { signalModule(companion()) }
        assertEquals(HttpStatusCode.Forbidden, client.post(NasPaths.PAIR) { header("CF-Ray", "x") }.status)
    }

    @Test fun codesExpire() {
        var now = 0L
        val pairings = Pairings { now }
        val started = pairings.start(PairRequest("d", "n", "m", "v"))!!
        now += Pairings.TTL_MS
        assertEquals(PairState.EXPIRED, pairings.status(started.pairingId).state)
        assertNull(pairings.approve(started.code, store))
    }

    @Test fun withoutTheDatabaseDevicesWait() = testApplication {
        application { signalModule(Companion(Config(port = 0, db = null, secretKey = null, dataDir = "build/tmp/data", webDir = "missing"))) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/devices").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(NasPaths.ME).status)
    }
}

package com.signal.server

import io.ktor.client.request.get
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

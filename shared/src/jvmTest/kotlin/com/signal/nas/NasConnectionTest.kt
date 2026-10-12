package com.signal.nas

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.Log
import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import com.signal.platform.AppInfo
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NasConnectionTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(cipher: String) = cipher.removePrefix("enc:").reversed()
    }
    private val info = AppInfo("0.3.0", "dev", isDebug = true, deviceModel = "SHIELD Android TV")
    private val requests = mutableListOf<HttpRequestData>()
    private val health = """{"status":"ok","version":"0.3.0","database":"ok"}"""

    private fun nas(handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        NasConnection(settings, HttpClient(MockEngine { requests += it; handler(it) }), box, info, Log { _, _ -> }, pollMs = 10)

    /** The home address is down (Tailscale only, away from home). */
    private fun MockRequestHandleScope.homeDown(request: HttpRequestData) {
        if (request.url.host == "192.168.0.105") throw java.io.IOException("unreachable")
    }

    @Test fun findsWhicheverAddressAnswers() = runBlocking {
        val nas = nas { homeDown(it); respond(health) }
        assertEquals(NasConnection.Via.TAILSCALE to "http://synology:3013", nas.findAddress())
    }

    @Test fun neitherAnsweringMeansUnreachable() = runBlocking {
        val nas = nas { throw java.io.IOException("down") }
        assertEquals(null, nas.findAddress())
    }

    @Test fun pairsAndKeepsTheKeyEncrypted() = runBlocking {
        var polls = 0
        val nas = nas { r ->
            homeDown(r)
            when {
                r.url.encodedPath == NasPaths.HEALTH -> respond(health)
                r.method == HttpMethod.Post && r.url.encodedPath == NasPaths.PAIR -> respond("""{"pairingId":"p1","code":"123456","expiresInSeconds":600}""")
                r.url.encodedPath == NasPaths.pairStatus("p1") ->
                    respond(if (++polls < 3) """{"state":"PENDING"}""" else """{"state":"APPROVED","apiKey":"secret-key","deviceName":"Living room"}""")
                r.url.encodedPath == NasPaths.ME ->
                    if (r.headers["Authorization"] == "Bearer secret-key") respond("""{"id":"d","name":"Living room"}""") else respond("", HttpStatusCode.Unauthorized)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val steps = nas.pair().toList()
        assertEquals(NasConnection.Pairing.Searching, steps.first())
        assertEquals(NasConnection.Pairing.ShowCode("123456", NasConnection.Via.TAILSCALE, "http://synology:3013"), steps[1])
        assertEquals(NasConnection.Pairing.Paired("Living room"), steps.last())
        assertEquals("enc:yek-terces", settings.raw(Settings.Key.NAS_API_KEY))
        assertTrue(nas.isPaired)

        nas.check()
        val status = assertIs<NasConnection.Status.Connected>(nas.status.value)
        assertEquals(NasConnection.Via.TAILSCALE, status.via)
        // Every device request says which version it runs.
        assertTrue(requests.filter { it.url.encodedPath == NasPaths.ME }.all { it.headers[APP_VERSION_HEADER] == "0.3.0" })
    }

    @Test fun anExpiredCodeIsReplaced() = runBlocking {
        var starts = 0
        val nas = nas { r ->
            when {
                r.url.encodedPath == NasPaths.HEALTH -> respond(health)
                r.url.encodedPath == NasPaths.PAIR -> { starts++; respond("""{"pairingId":"p$starts","code":"00000$starts","expiresInSeconds":600}""") }
                r.url.encodedPath == NasPaths.pairStatus("p1") -> respond("""{"state":"EXPIRED"}""")
                else -> respond("""{"state":"APPROVED","apiKey":"k","deviceName":"TV"}""")
            }
        }
        val codes = nas.pair().toList().filterIsInstance<NasConnection.Pairing.ShowCode>().map { it.code }
        assertEquals(listOf("000001", "000002"), codes)
    }

    @Test fun aRemovedDeviceForgetsItsKey() = runBlocking {
        settings.put(Settings.Key.NAS_API_KEY, box.encrypt("old"))
        val nas = nas { r -> if (r.url.encodedPath == NasPaths.HEALTH) respond(health) else respond("", HttpStatusCode.Unauthorized) }
        nas.check()
        assertEquals(NasConnection.Status.Removed, nas.status.value)
        assertFalse(nas.isPaired)
    }

    @Test fun aRequestRetriesOnTheOtherAddress() = runBlocking {
        settings.put(Settings.Key.NAS_API_KEY, box.encrypt("k"))
        var homeUp = true
        val nas = nas { r ->
            if (r.url.host == "192.168.0.105" && !homeUp) throw java.io.IOException("left home")
            if (r.url.encodedPath == NasPaths.HEALTH) respond(health) else respond("""{"id":"d","name":"TV"}""")
        }
        nas.check()
        // Both answer; whichever won, take the home network away and check again.
        homeUp = false
        nas.check()
        assertEquals(NasConnection.Via.TAILSCALE, assertIs<NasConnection.Status.Connected>(nas.status.value).via)
    }

    @Test fun anUnreachableNasNeverFailsPairingSilently(): Unit = runBlocking {
        val nas = nas { throw java.io.IOException("down") }
        assertIs<NasConnection.Pairing.Failed>(nas.pair().last())
    }

    @Test fun addressesBecomeUrls() {
        assertEquals("http://192.168.0.105:3013", NasConnection.baseUrl("192.168.0.105:3013"))
        assertEquals("https://nas.local", NasConnection.baseUrl("https://nas.local/"))
    }
}

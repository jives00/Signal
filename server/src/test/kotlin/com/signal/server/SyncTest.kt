package com.signal.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.Log
import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import com.signal.nas.NasPaths
import com.signal.nas.PairRequest
import com.signal.nas.PairStarted
import com.signal.nas.PairStatus
import com.signal.nas.bearer
import com.signal.platform.SecretBox
import com.signal.sync.BUNDLE_SCHEMA
import com.signal.sync.PullResult
import com.signal.sync.PushRequest
import com.signal.sync.SyncItem
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.signal.sync.PushResult
import com.signal.sync.SyncEngine
import com.signal.sync.SyncPaths
import com.signal.sync.SyncTables
import com.signal.sync.SyncTransport
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings sync end to end (PLAN §5.5, §9): real user.db files and SyncEngines on two or three
 * "TVs", syncing through the companion's real routes.
 */
class SyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val syncStore = MemorySyncStore()
    private val companion = Companion(
        Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-data").toFile().path, webDir = "missing"),
        deviceStore = MemoryDeviceStore(),
        companionStore = MemoryCompanionStore(),
        syncStoreOverride = syncStore,
    )

    /** One TV: its own user.db and Keystore stand-in, so passwords must be re-encrypted on arrival. */
    inner class Tv(val name: String) {
        val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
        val settings = Settings(db, Dispatchers.IO)
        val box = object : SecretBox {
            override fun encrypt(plain: String) = "$name:" + plain.reversed()
            override fun decrypt(cipher: String) = cipher.removePrefix("$name:").reversed()
        }
        val engine = SyncEngine(db, settings, SyncTables(db, settings, box), Dispatchers.IO, Log { _, _ -> })
        lateinit var key: String
        lateinit var client: HttpClient

        val transport = object : SyncTransport {
            override suspend fun push(request: PushRequest): PushResult? {
                val r = client.post(SyncPaths.PUSH) {
                    header(HttpHeaders.Authorization, bearer(key))
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PushRequest.serializer(), request))
                }
                check(r.status == HttpStatusCode.OK) { r.bodyAsText() }
                return json.decodeFromString(PushResult.serializer(), r.bodyAsText())
            }

            override suspend fun pull(since: Long): PullResult? {
                val r = client.get("${SyncPaths.PULL}?since=$since&schema=$BUNDLE_SCHEMA") { header(HttpHeaders.Authorization, bearer(key)) }
                check(r.status == HttpStatusCode.OK) { r.bodyAsText() }
                return json.decodeFromString(PullResult.serializer(), r.bodyAsText())
            }
        }

        suspend fun sync() = assertNotNull(engine.sync(transport), "$name's sync failed: ${engine.state.value.error}")

        fun addSource(id: String, password: String) = db.sourceQueries.upsert(
            com.signal.db.user.Source(id, "Provider", "XTREAM", "http://provider.example", "me", box.encrypt(password), null, null, 1, "TS", 2, null, null, 5, null, 100),
        )

        fun addChannel(streamId: String): String {
            val uc = "ch:$streamId"
            db.userChannelQueries.insertUserChannel(uc, 1)
            db.userChannelQueries.insertLink(uc, streamId, "manual", 1)
            return uc
        }

        fun favorites() = db.syncQueries.allFavorites(settings.profileId).executeAsList().map { it.user_channel_id }
        fun channelRow(uc: String) = db.userChannelQueries.profileRow(uc, settings.profileId).executeAsOneOrNull()
    }

    /** [placement] adds fields to the approval, like `"profile":"own"`. */
    private suspend fun ApplicationTestBuilder.paired(tv: Tv, placement: String = ""): Tv {
        val started: PairStarted = json.decodeFromString(client.post(NasPaths.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest(tv.settings.deviceId, tv.name, "SHIELD", "0.3.0")))
        }.bodyAsText())
        client.post("/api/devices/approve") { contentType(ContentType.Application.Json); setBody("""{"code":"${started.code}"${if (placement.isEmpty()) "" else ",$placement"}}""") }
        tv.key = json.decodeFromString<PairStatus>(client.get(NasPaths.pairStatus(started.pairingId)).bodyAsText()).apiKey!!
        tv.client = client
        return tv
    }

    @Test fun aSecondTvGetsTheFirstOnesSettings() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("living-room"))
        a.addSource("src-1", "hunter2")
        val uc = a.addChannel("src-1:x:42")
        a.db.listsQueries.addFavorite(a.settings.profileId, uc, "a0")
        a.db.userChannelQueries.ensureProfileRow(uc, a.settings.profileId)
        a.db.userChannelQueries.setCustomName("Local news", uc, a.settings.profileId)
        a.settings.put(Settings.Key.CLOCK_24H, "true")
        a.settings.put(Settings.Key.RECENT_SEARCHES, "news")
        a.settings.put(Settings.Key.NAS_HOME_ADDRESS, "10.0.0.2:3013")
        a.sync()

        // The password is encrypted at rest on the companion.
        val stored = syncStore.all().first { it.type == "source" }
        assertFalse("hunter2" in stored.fields, stored.fields)

        val b = paired(Tv("bedroom"))
        val report = b.sync()
        assertEquals(listOf("src-1"), report.sourcesToRefresh)
        val source = b.db.sourceQueries.byId("src-1").executeAsOne()
        assertEquals("hunter2", b.box.decrypt(source.password_enc), "re-encrypted with this TV's own key")
        assertEquals(0L, source.last_refreshed_at, "refresh results stay per device")
        assertEquals(listOf(uc), b.favorites())
        assertEquals("Local news", b.channelRow(uc)?.custom_name)
        assertEquals("true", b.settings.raw(Settings.Key.CLOCK_24H))
        // Device scope stays with its device, and the NAS connection never syncs.
        assertNull(b.settings.raw(Settings.Key.RECENT_SEARCHES))
        assertNull(b.settings.raw(Settings.Key.NAS_HOME_ADDRESS))
        assertTrue(syncStore.all().none { it.type == "setting" && it.id == "nas_home_address" })
        assertTrue(syncStore.all().any { it.type == "setting" && it.id == "recent_searches" && it.scope == "device" })
    }

    @Test fun editsToDifferentFieldsBothSurvive() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        val uc = a.addChannel("s:x:1")
        a.db.userChannelQueries.ensureProfileRow(uc, "main")
        a.sync()
        b.sync()

        a.db.userChannelQueries.setCustomName("Renamed on A", uc, "main")
        b.db.userChannelQueries.setHidden(1, uc, "main")
        a.sync()
        b.sync()
        a.sync()
        for (tv in listOf(a, b)) {
            assertEquals("Renamed on A", tv.channelRow(uc)?.custom_name, tv.name)
            assertEquals(1L, tv.channelRow(uc)?.hidden, tv.name)
        }
    }

    /**
     * A field from a newer app version (voice names did this, 2026-10-15) is left alone by a TV
     * that doesn't know it. 0.2.16 and earlier sent it back as a null, which wiped it everywhere.
     */
    @Test fun aFieldThisVersionDoesntKnowSurvivesItsSync() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        val uc = a.addChannel("s:x:1")
        a.db.userChannelQueries.ensureProfileRow(uc, "main")
        a.sync()

        // A newer app version (A, say) sets a field this version has no column for.
        a.transport.push(PushRequest(BUNDLE_SCHEMA, listOf(SyncItem(SyncType.CHANNEL_PROFILE, uc, SyncScope.PROFILE, "main", JsonObject(mapOf("from_a_newer_app" to JsonPrimitive("kept")))))))
        // B receives it, then changes something else on that channel and syncs.
        b.sync()
        b.db.userChannelQueries.setHidden(1, uc, "main")
        b.sync()
        a.db.userChannelQueries.setCustomName("Renamed", uc, "main")
        a.sync()

        val c = paired(Tv("c"))
        c.sync()
        val fields = json.decodeFromString<JsonObject>(c.db.syncQueries.allBase().executeAsList().first { it.type == SyncType.CHANNEL_PROFILE && it.id == uc }.fields)
        assertEquals(JsonPrimitive("kept"), fields["from_a_newer_app"])
        assertEquals("Renamed", c.channelRow(uc)?.custom_name)
        assertEquals(1L, c.channelRow(uc)?.hidden)
    }

    /**
     * 0.2.27: voice names kept vanishing. A TV that tuned a channel before it had pulled that
     * channel's settings had an empty profile row of its own, and its next sync (which pushes before
     * it pulls) sent the row whole, nulls and all, over the voice names another TV had set.
     */
    @Test fun anEmptyRowFromTuningNeverBlanksAnotherTvsSettings() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        a.sync()
        b.sync()

        val uc = a.addChannel("s:x:1")
        a.db.userChannelQueries.ensureProfileRow(uc, "main")
        a.db.userChannelQueries.setVoiceNames("fox, local fox", uc, "main")
        a.db.userChannelQueries.setCustomName("FOX 32", uc, "main")
        a.sync()

        // B tunes the same channel before it has pulled A's settings: an empty row of its own.
        b.addChannel("s:x:1")
        b.db.userChannelQueries.ensureProfileRow(uc, "main")
        b.sync()
        b.sync()
        a.sync()

        for (tv in listOf(a, b)) {
            assertEquals("fox, local fox", tv.channelRow(uc)?.voice_names, tv.name)
            assertEquals("FOX 32", tv.channelRow(uc)?.custom_name, tv.name)
        }
        val c = paired(Tv("c"))
        c.sync()
        assertEquals("fox, local fox", c.channelRow(uc)?.voice_names)
    }

    @Test fun anEditNotYetSentWinsOverOneThatArrivedEarlier() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        a.sync()
        b.sync()
        b.settings.put(Settings.Key.GROUP_SORT, "A_Z")
        b.sync()
        // A edited the same setting while B's change was on its way: A's arrives last, so it wins (PLAN §5.5).
        a.settings.put(Settings.Key.GROUP_SORT, "MANUAL")
        a.sync()
        b.sync()
        assertEquals("MANUAL", a.settings.raw(Settings.Key.GROUP_SORT))
        assertEquals("MANUAL", b.settings.raw(Settings.Key.GROUP_SORT))
    }

    @Test fun deletesReachOtherTvs() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        val uc = a.addChannel("s:x:7")
        a.db.listsQueries.addFavorite("main", uc, "a0")
        a.sync()
        b.sync()
        assertEquals(listOf(uc), b.favorites())

        b.db.listsQueries.removeFavorite("main", uc)
        b.sync()
        a.sync()
        assertEquals(emptyList(), a.favorites())
        assertTrue(syncStore.all().first { it.type == "favorite" }.deleted, "kept as a tombstone")
    }

    @Test fun aTvThatAlreadyHasSettingsKeepsThemAndAddsTheNasOnes() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        a.settings.put(Settings.Key.CLOCK_24H, "true")
        a.settings.put(Settings.Key.GROUP_SORT, "A_Z")
        a.sync()

        // C ran standalone: its values win, nothing of its own is lost, and A's other settings arrive.
        val c = Tv("c")
        c.settings.put(Settings.Key.CLOCK_24H, "false")
        c.addSource("src-c", "pw")
        paired(c).sync()
        assertEquals("false", c.settings.raw(Settings.Key.CLOCK_24H))
        assertEquals("A_Z", c.settings.raw(Settings.Key.GROUP_SORT))
        assertNotNull(c.db.sourceQueries.byId("src-c").executeAsOneOrNull())
        a.sync()
        assertEquals("false", a.settings.raw(Settings.Key.CLOCK_24H))

        // ...and the companion kept a snapshot from before, which restores.
        val snapshot = syncStore.snapshots().single { it.reason == "Before c joined" }
        companion.sync.restore(snapshot.id)
        a.sync()
        assertEquals("true", a.settings.raw(Settings.Key.CLOCK_24H))
        assertNull(a.db.sourceQueries.byId("src-c").executeAsOneOrNull(), "restoring removes what came after")
    }

    @Test fun profilesKeepTheirOwnSettings() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        val uc = a.addChannel("s:x:1")
        a.db.listsQueries.addFavorite("main", uc, "a0")
        a.settings.put(Settings.Key.DEFAULT_EPG_SOURCE, "epg:s")
        a.sync()
        b.sync()

        val kids = companion.sync.createProfile("Kids", copyFrom = "main")
        companion.sync.assignDevice(b.settings.deviceId, kids.id)
        val report = b.sync()
        assertTrue(report.profileChanged)
        assertEquals(kids.id, b.settings.profileId)
        assertEquals("Kids", b.engine.state.value.profileName)
        assertEquals(listOf(uc), b.favorites(), "copied from Main")

        // A favorite on Kids stays on Kids; household settings still reach both.
        val uc2 = b.addChannel("s:x:2")
        b.db.listsQueries.addFavorite(kids.id, uc2, "a1")
        b.sync()
        a.settings.put(Settings.Key.DEFAULT_EPG_SOURCE, "epg:other")
        a.sync()
        b.sync()
        assertEquals(listOf(uc), a.favorites())
        assertEquals(listOf(uc, uc2), b.favorites())
        assertEquals("epg:other", b.settings.raw(Settings.Key.DEFAULT_EPG_SOURCE))

        // Deleting Kids sends B back to Main.
        companion.sync.deleteProfile(kids.id)
        assertTrue(b.sync().profileChanged)
        assertEquals("main", b.settings.profileId)
        assertEquals(listOf(uc), b.favorites())
    }

    /** Approve device can choose the profile (PLAN §9.1); the first sync is already in it. */
    @Test fun approvalPlacesTheDeviceBeforeItsFirstSync() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val uc = a.addChannel("s:x:1")
        a.db.listsQueries.addFavorite("main", uc, "a0")
        a.sync()

        val kids = companion.sync.createProfile("Kids", copyFrom = null)
        val b = paired(Tv("b"), """"profile":"${kids.id}"""")
        b.sync()
        assertEquals(kids.id, b.settings.profileId)
        assertEquals(emptyList(), b.favorites(), "Kids started empty, and nothing of Main's came first")

        val c = paired(Tv("c"), """"newProfile":"Den","copyFrom":"main"""")
        c.sync()
        assertEquals("Den", c.engine.state.value.profileName)
        assertEquals(listOf(uc), c.favorites(), "a copy of Main")

        val d = paired(Tv("Bedroom"), """"profile":"own"""")
        d.sync()
        assertEquals("Bedroom", d.engine.state.value.profileName)
        assertEquals(listOf(uc), d.favorites(), "its own profile starts as a copy of Main")

        // No choice: Main, as Settings → Companion says by default.
        val e = paired(Tv("e"))
        e.sync()
        assertEquals("main", e.settings.profileId)
    }

    @Test fun approvalToAProfileThatsGoneLeavesTheCodeWaiting() = testApplication {
        application { signalModule(companion) }
        val started: PairStarted = json.decodeFromString(client.post(NasPaths.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest("dev-x", "X", "SHIELD", "0.3.0")))
        }.bodyAsText())
        val refused = client.post("/api/devices/approve") { contentType(ContentType.Application.Json); setBody("""{"code":"${started.code}","profile":"gone"}""") }
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertEquals("X", companion.pairings.waiting(started.code), "still waiting, so it can be approved again")
    }

    @Test fun aSettingsFileRoundTrips() = testApplication {
        val a = Tv("a")
        a.addSource("src-1", "pw1")
        val uc = a.addChannel("src-1:x:9")
        a.db.listsQueries.addFavorite("main", uc, "a0")
        a.settings.put(Settings.Key.SHOW_LOGOS, "false")
        val file = a.engine.exportFile()
        assertTrue("pw1" in file, "the file carries passwords in plain text, as the screen warns")

        val b = Tv("b")
        b.settings.put(Settings.Key.SHOW_NAMES, "false")
        assertEquals(true, b.engine.importFile(file).isSuccess)
        assertEquals("pw1", b.box.decrypt(b.db.sourceQueries.byId("src-1").executeAsOne().password_enc))
        assertEquals(listOf(uc), b.favorites())
        assertEquals("false", b.settings.raw(Settings.Key.SHOW_LOGOS))
        assertEquals("false", b.settings.raw(Settings.Key.SHOW_NAMES), "nothing already here is removed")
        assertTrue(b.engine.importFile("""{"format":"something-else","exportedAt":0,"items":[]}""").isFailure)
    }

    @Test fun refusesAnUnknownSchemaAndUnpairedDevices() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val newer = client.get("${SyncPaths.PULL}?since=0&schema=${BUNDLE_SCHEMA + 1}") { header(HttpHeaders.Authorization, bearer(a.key)) }
        assertEquals(HttpStatusCode.Conflict, newer.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("${SyncPaths.PULL}?since=0&schema=$BUNDLE_SCHEMA").status)
    }

    @Test fun aPullDuringAWriteNeverSkipsTheItemsBeingWritten(): Unit = runBlocking {
        // A store whose write can be held open, to land a pull between "revisions handed out" and "written".
        val gate = java.util.concurrent.CountDownLatch(1)
        val writing = java.util.concurrent.CountDownLatch(1)
        val inner = MemorySyncStore()
        var hold = false
        val slow = object : SyncStore by inner {
            override fun write(items: List<StoredItem>) {
                if (hold) { writing.countDown(); gate.await() }
                inner.write(items)
            }
        }
        val c = Companion(
            Config(port = 0, db = null, secretKey = "k", dataDir = Files.createTempDirectory("race").toFile().path, webDir = "missing"),
            deviceStore = MemoryDeviceStore(), companionStore = MemoryCompanionStore(), syncStoreOverride = slow,
        )
        val device = Device("d1", "TV", "SHIELD", "0.3.0", 0, 0)
        c.sync.edit(listOf(AdminOp("setting", "a", "household", "", com.signal.sync.fields("value" to "1"))))
        val before = c.sync.pull(device, 0, BUNDLE_SCHEMA).rev

        hold = true
        val edit = launch(Dispatchers.IO) { c.sync.edit(listOf(AdminOp("setting", "b", "household", "", com.signal.sync.fields("value" to "2")))) }
        writing.await()
        // Mid-write: the pull must not claim the new revision.
        val during = try {
            c.sync.pull(device, before, BUNDLE_SCHEMA)
        } finally {
            hold = false
            gate.countDown()
        }
        edit.join()
        assertEquals(before, during.rev)
        val after = c.sync.pull(device, during.rev, BUNDLE_SCHEMA)
        assertEquals(listOf("b"), after.items.map { it.id })
    }

    @Test fun aFullSyncHealsATvThatMissedADelete() = testApplication {
        application { signalModule(companion) }
        val a = paired(Tv("a"))
        val b = paired(Tv("b"))
        val uc = a.addChannel("s:x:5")
        a.db.listsQueries.addFavorite("main", uc, "a0")
        a.sync()
        b.sync()
        b.db.listsQueries.removeFavorite("main", uc)
        b.sync()
        // A "misses" the delete: its saved revision jumps past it without applying it.
        a.db.syncQueries.putState("rev", companion.sync.currentRev().toString())
        a.sync()
        assertEquals(listOf(uc), a.favorites(), "an ordinary sync can't see it any more")
        assertNotNull(a.engine.sync(a.transport, full = true))
        assertEquals(emptyList(), a.favorites())
    }

    // On a real Netty server: Ktor's in-memory test client only hands over a response body once it ends.
    @Test fun theEventStreamSaysHelloThenAnnouncesChanges(): Unit = runBlocking {
        val server = embeddedServer(Netty, port = 0) { signalModule(companion) }.start()
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(CIO) { defaultRequest { url("http://127.0.0.1:$port") } }
        try {
            val a = Tv("a").also { pairWith(http, it) }
            val b = Tv("b").also { pairWith(http, it) }
            withTimeout(10_000) {
                http.prepareGet(SyncPaths.EVENTS) { header(HttpHeaders.Authorization, bearer(a.key)) }.execute { response ->
                    val channel = response.bodyAsChannel()
                    suspend fun nextEvent(): String {
                        while (true) {
                            val line = channel.readLine() ?: error("stream closed")
                            if (line.startsWith("data:")) return line
                        }
                    }
                    assertTrue("\"hello\"" in nextEvent())
                    b.settings.put(Settings.Key.SHOW_LOGOS, "false")
                    b.sync()
                    assertTrue("\"settings\"" in nextEvent())
                }
            }
        } finally {
            http.close()
            server.stop(0, 0)
        }
    }

    private suspend fun pairWith(http: HttpClient, tv: Tv) {
        val started: PairStarted = json.decodeFromString(http.post(NasPaths.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PairRequest.serializer(), PairRequest(tv.settings.deviceId, tv.name, "SHIELD", "0.3.0")))
        }.bodyAsText())
        http.post("/api/devices/approve") { contentType(ContentType.Application.Json); setBody("""{"code":"${started.code}"}""") }
        tv.key = json.decodeFromString<PairStatus>(http.get(NasPaths.pairStatus(started.pairingId)).bodyAsText()).apiKey!!
        tv.client = http
    }
}

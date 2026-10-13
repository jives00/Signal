package com.signal.server

import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The web admin's pages against a real companion with the Demo source (PLAN §4.3.18). */
class AdminTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val syncStore = MemorySyncStore()
    private val companion = Companion(
        Config(port = 0, db = null, secretKey = "test-key", dataDir = Files.createTempDirectory("signal-admin").toFile().path, webDir = "missing"),
        deviceStore = MemoryDeviceStore(),
        companionStore = MemoryCompanionStore(),
        syncStoreOverride = syncStore,
    )

    private suspend fun HttpClient.send(method: String, path: String, body: String? = null): String {
        val r = when (method) {
            "POST" -> post(path) { contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            "PUT" -> put(path) { contentType(ContentType.Application.Json); setBody(body ?: "{}") }
            "DELETE" -> delete(path)
            else -> get(path)
        }
        check(r.status.value < 300) { "$method $path: ${r.status} ${r.bodyAsText()}" }
        return r.bodyAsText()
    }

    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    private suspend fun HttpClient.demo(): JsonObject {
        companion.startCatalog()
        send("POST", "/api/sources", """{"name":"Demo","kind":"DEMO","url":"demo:"}""")
        repeat(200) {
            // What the pages read: the catalog's snapshot, swapped in just after the refresh commits.
            if (channels("limit=1")["total"]!!.jsonPrimitive.int > 0) return obj(send("GET", "/api/sources"))
            delay(50)
        }
        error("The catalog never loaded the Demo source")
    }

    private suspend fun HttpClient.channels(query: String) = obj(send("GET", "/api/channels?$query"))

    private fun JsonObject.rows() = this["rows"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(name: String) = this[name]?.jsonPrimitive?.content

    @Test fun sourcesBringTheirGuideAndTheCatalogLoads() = testApplication {
        application { signalModule(companion) }
        val sources = client.demo()
        val epg = sources["epgSources"]!!.jsonArray.single().jsonObject
        assertEquals("Demo guide", epg.s("name"))
        assertEquals("true", epg.s("isDefault"))
        val page = client.channels("limit=5")
        assertEquals(2000, page["total"]!!.jsonPrimitive.int)
        assertEquals(5, page.rows().size)
        assertTrue(client.channels("q=sports&limit=500").rows().all { "sports" in it.s("name")!!.lowercase() || "sports" in it.s("rawName")!!.lowercase() })
    }

    @Test fun favoritesApplyToSeveralProfilesAndUndo() = testApplication {
        application { signalModule(companion) }
        client.demo()
        val kids = companion.sync.createProfile("Kids", null)
        val first = client.channels("limit=3").rows().map { it.s("streamId")!! }
        val result = obj(client.send("POST", "/api/channels/edit", """{"streamIds":${Json.encodeToString(first)},"profiles":["main","${kids.id}"],"action":"favorite"}"""))
        for (p in listOf("main", kids.id)) {
            val favs = obj(client.send("GET", "/api/lists?profile=$p"))["favorites"]!!.jsonArray.map { it.jsonObject.s("streamId") }
            assertEquals(first, favs, p)
        }
        // A user channel was made once per stream, shared by both profiles.
        assertEquals(3, syncStore.all().count { it.type == SyncType.USER_CHANNEL && !it.deleted })

        // Drag the third to the top; only its key changes.
        val uc3 = client.channels("favorites=true&limit=10").rows().first { it.s("streamId") == first[2] }.s("userChannelId")!!
        client.send("POST", "/api/favorites/$uc3/move", """{"profile":"main","toIndex":0}""")
        val order = obj(client.send("GET", "/api/lists?profile=main"))["favorites"]!!.jsonArray.map { it.jsonObject.s("streamId") }
        assertEquals(listOf(first[2], first[0], first[1]), order)

        // Undo the first edit: favorites and user channels go again.
        client.send("POST", "/api/edits/undo", """{"ops":${result["undo"]}}""")
        assertEquals(0, obj(client.send("GET", "/api/lists?profile=${kids.id}"))["favorites"]!!.jsonArray.size)
        assertEquals(0, syncStore.all().count { it.type == SyncType.USER_CHANNEL && !it.deleted })
    }

    @Test fun renameHideResetAndFilters() = testApplication {
        application { signalModule(companion) }
        client.demo()
        val s = client.channels("limit=1").rows().single().s("streamId")!!
        client.send("POST", "/api/channels/edit", """{"streamIds":["$s"],"profiles":["main"],"action":"set","values":{"customName":"My channel","customNumber":"501"}}""")
        val row = obj(client.send("GET", "/api/channels/$s?profile=main"))
        assertEquals("My channel", row.s("name"))
        assertEquals("501", row.s("number"))
        assertEquals(1, client.channels("customized=true").rows().size)
        client.send("POST", "/api/channels/edit", """{"streamIds":["$s"],"profiles":["main"],"action":"hide"}""")
        assertEquals(1, client.channels("hidden=hidden").rows().size)
        client.send("POST", "/api/channels/edit", """{"streamIds":["$s"],"profiles":["main"],"action":"reset"}""")
        assertEquals(0, client.channels("customized=true").rows().size)
    }

    @Test fun groupsHideRenameAndMoveSwitchesToManual() = testApplication {
        application { signalModule(companion) }
        client.demo()
        val groups = obj(client.send("GET", "/api/groups?profile=main"))["groups"]!!.jsonArray.map { it.jsonObject }
        assertTrue(groups.size > 2)
        val g = groups[2]
        client.send("POST", "/api/groups/move", """{"sourceId":"${g.s("sourceId")}","group":"${g.s("name")}","profile":"main","toIndex":0}""")
        val after = obj(client.send("GET", "/api/groups?profile=main"))
        assertEquals("MANUAL", after.s("groupSort"))
        assertEquals(g.s("name"), after["groups"]!!.jsonArray.first().jsonObject.s("name"))
        client.send("POST", "/api/groups/edit", """{"sourceId":"${g.s("sourceId")}","group":"${g.s("name")}","profiles":["main"],"hidden":true,"name":"Renamed"}""")
        val hidden = obj(client.send("GET", "/api/groups?profile=main"))["groups"]!!.jsonArray.map { it.jsonObject }.first { it.s("name") == g.s("name") }
        assertEquals("true", hidden.s("hidden"))
        assertEquals("Renamed", hidden.s("title"))
    }

    @Test fun mappingQueueAndManualMappings() = testApplication {
        application { signalModule(companion) }
        val sources = client.demo()
        val epgId = sources["epgSources"]!!.jsonArray.single().jsonObject.s("id")!!
        // Until the guide build (started by the new EPG source) lands, every channel is in the queue.
        val queue = obj(client.send("POST", "/api/mapping/next", """{"profile":"main"}"""))
        val stream = queue["channel"]!!.jsonObject.s("streamId")!!
        val skipped = obj(client.send("POST", "/api/mapping/next", """{"profile":"main","skip":["$stream"]}"""))
        assertTrue(skipped["channel"]!!.jsonObject.s("streamId") != stream, "a skipped channel isn't offered again")

        client.send("POST", "/api/mapping", """{"streamId":"$stream","epgSourceId":"$epgId","guideChannelId":"demo.1"}""")
        val manual = json.parseToJsonElement(client.send("GET", "/api/mapping/manual")).jsonArray.single().jsonObject
        assertEquals("demo.1", manual.s("guideChannelId"))
        client.send("DELETE", "/api/mapping/${manual.s("userChannelId")}")
        assertEquals(0, json.parseToJsonElement(client.send("GET", "/api/mapping/manual")).jsonArray.size)
    }

    @Test fun settingsCompanionSettingsAndCopyTo() = testApplication {
        application { signalModule(companion) }
        client.demo()
        client.send("PUT", "/api/settings", """{"scope":"profile","key":"clock_24h","value":"true","profiles":["main"]}""")
        assertEquals("true", obj(client.send("GET", "/api/settings?profile=main"))["profile"]!!.jsonObject.s("clock_24h"))
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/settings") { contentType(ContentType.Application.Json); setBody("""{"scope":"profile","key":"nas_api_key","value":"x"}""") }.status)

        val view = obj(client.send("GET", "/api/settings"))["companion"]!!.jsonObject
        assertEquals("30", view.s("snapshotsToKeep"))
        client.send("PUT", "/api/settings/companion", """{"buildTime":"04:00","timeZone":"America/Chicago","snapshotsToKeep":10,"tombstoneDays":60,"newDeviceProfile":"own"}""")
        assertEquals(10, companion.settings.snapshotsToKeep)
        assertEquals("04:00", companion.guideJob.schedule().time)

        val kids = companion.sync.createProfile("Kids", null)
        val s = client.channels("limit=1").rows().single().s("streamId")!!
        client.send("POST", "/api/channels/edit", """{"streamIds":["$s"],"profiles":["main"],"action":"favorite"}""")
        client.send("POST", "/api/profiles/copy", """{"from":"main","to":["${kids.id}"],"sections":["favorites","settings"]}""")
        assertEquals(1, obj(client.send("GET", "/api/lists?profile=${kids.id}"))["favorites"]!!.jsonArray.size)
        assertEquals("true", obj(client.send("GET", "/api/settings?profile=${kids.id}"))["profile"]!!.jsonObject.s("clock_24h"))
    }

    @Test fun restoreOneProfileAndImportAFile() = testApplication {
        application { signalModule(companion) }
        client.demo()
        val s = client.channels("limit=2").rows().map { it.s("streamId")!! }
        client.send("POST", "/api/channels/edit", """{"streamIds":["${s[0]}"],"profiles":["main"],"action":"favorite"}""")
        client.send("PUT", "/api/settings", """{"scope":"household","key":"default_epg_source","value":"x"}""")
        val snap = companion.sync.snapshot("Test")
        client.send("POST", "/api/channels/edit", """{"streamIds":["${s[1]}"],"profiles":["main"],"action":"favorite"}""")
        client.send("PUT", "/api/settings", """{"scope":"household","key":"default_epg_source","value":"y"}""")

        val summary = obj(client.send("GET", "/api/snapshots/${snap.id}/summary"))
        assertEquals(1, summary["counts"]!!.jsonObject["favorite/profile"]!!.jsonPrimitive.int)
        client.send("POST", "/api/snapshots/${snap.id}/restore-part", """{"part":"profile","id":"main"}""")
        assertEquals(1, obj(client.send("GET", "/api/lists?profile=main"))["favorites"]!!.jsonArray.size)
        // Household settings weren't part of it.
        assertEquals("y", obj(client.send("GET", "/api/settings"))["household"]!!.jsonObject.s("default_epg_source"))

        val file = client.send("GET", "/api/sync/export")
        val kids = companion.sync.createProfile("Kids", null)
        client.send("POST", "/api/sync/import", """{"profile":"${kids.id}","file":$file}""")
        assertEquals(1, obj(client.send("GET", "/api/lists?profile=${kids.id}"))["favorites"]!!.jsonArray.size)
    }

    @Test fun aProviderSwitchLinksFavoritesToTheNewSourceBeforeTheOldOneGoes() = testApplication {
        application { signalModule(companion) }
        client.demo()
        val oldId = obj(client.send("GET", "/api/sources"))["sources"]!!.jsonArray.single().jsonObject.s("id")!!
        val favs = client.channels("limit=3").rows().map { it.s("streamId")!! }
        client.send("POST", "/api/channels/edit", """{"streamIds":${Json.encodeToString(favs)},"profiles":["main"],"action":"favorite"}""")

        // The new provider: the same channels under new stream IDs.
        client.send("POST", "/api/sources", """{"name":"Demo Two","kind":"DEMO","url":"demo:"}""")
        val newId = obj(client.send("GET", "/api/sources"))["sources"]!!.jsonArray.map { it.jsonObject }.first { it.s("name") == "Demo Two" }.s("id")!!
        repeat(200) { if (companion.catalog.streams.count { it.sourceId == newId } > 0) return@repeat; delay(50) }

        val report = obj(client.send("GET", "/api/switch?source=$newId"))
        val confident = report["confident"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, confident.size, report.toString())
        assertEquals(0, report["unmatched"]!!.jsonArray.size)
        assertTrue(obj(client.send("GET", "/api/overview"))["switchReview"]!!.jsonPrimitive.int >= 3)

        val links = confident.map { p -> """{"userChannelId":"${p.s("userChannelId")}","streamId":"${p["matches"]!!.jsonArray.first().jsonObject.s("streamId")}","confident":true}""" }
        client.send("POST", "/api/switch/accept", """{"links":[${links.joinToString(",")}]}""")
        val after = obj(client.send("GET", "/api/switch?source=$newId"))
        assertEquals(3, after["alreadyLinked"]!!.jsonPrimitive.int)
        assertEquals(0, after["confident"]!!.jsonArray.size)

        // The final step: the old source goes, and favorites play from the new one.
        client.send("POST", "/api/sources/$oldId/enabled", """{"enabled":false}""")
        val lists = obj(client.send("GET", "/api/lists?profile=main"))["favorites"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, lists.size)
        assertTrue(lists.all { it.s("sourceId") == newId && it.s("unavailable") == "false" }, lists.toString())
    }

    @Test fun rejectingASuggestionKeepsItAway() = testApplication {
        application { signalModule(companion) }
        client.demo()
        val s = client.channels("limit=1").rows().single().s("streamId")!!
        client.send("POST", "/api/channels/edit", """{"streamIds":["$s"],"profiles":["main"],"action":"favorite"}""")
        client.send("POST", "/api/sources", """{"name":"Demo Two","kind":"DEMO","url":"demo:"}""")
        val newId = obj(client.send("GET", "/api/sources"))["sources"]!!.jsonArray.map { it.jsonObject }.first { it.s("name") == "Demo Two" }.s("id")!!
        repeat(200) { if (companion.catalog.streams.count { it.sourceId == newId } > 0) return@repeat; delay(50) }
        val p = obj(client.send("GET", "/api/switch?source=$newId"))["confident"]!!.jsonArray.single().jsonObject
        val ids = p["matches"]!!.jsonArray.map { "\"${it.jsonObject.s("streamId")}\"" }
        client.send("POST", "/api/switch/reject", """{"userChannelId":"${p.s("userChannelId")}","streamIds":[${ids.joinToString(",")}]}""")
        val after = obj(client.send("GET", "/api/switch?source=$newId"))
        assertTrue(after["confident"]!!.jsonArray.none { it.jsonObject.s("userChannelId") == p.s("userChannelId") && it.jsonObject["matches"]!!.jsonArray.any { m -> "\"${m.jsonObject.s("streamId")}\"" in ids } })
    }

    @Test fun sourcesEditKeepsThePasswordAndRemoveTakesTheGuideToo() = testApplication {
        application { signalModule(companion) }
        companion.startCatalog()
        val id = run {
            client.send("POST", "/api/sources", """{"name":"Provider","kind":"XTREAM","url":"http://p.invalid","username":"me","password":"secret"}""")
            obj(client.send("GET", "/api/sources"))["sources"]!!.jsonArray.single().jsonObject.s("id")!!
        }
        assertTrue(syncStore.all().none { "secret" in it.fields }, "sealed at rest")
        client.send("PUT", "/api/sources/$id", """{"name":"Renamed","kind":"XTREAM","url":"http://p.invalid","username":"me"}""")
        assertEquals("secret", obj(client.send("GET", "/api/sources/$id/password")).s("password"))
        assertNotNull(syncStore.all().firstOrNull { it.type == SyncType.EPG_SOURCE && it.id == "epg:$id" && !it.deleted })
        client.send("DELETE", "/api/sources/$id")
        assertTrue(syncStore.all().filter { it.scope == SyncScope.HOUSEHOLD && it.type in setOf(SyncType.SOURCE, SyncType.EPG_SOURCE) }.all { it.deleted })
    }
}

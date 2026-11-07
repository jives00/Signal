package com.signal.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ReleasesTest {
    private fun release(version: String, stable: Boolean, notes: String) =
        ReleaseView("apk-v$version", "Signal $version", 0, stable, notes)

    // Newest first, as Releases.list sorts them.
    private val releases = listOf(
        release("1.2.1", false, "Live scores\n\n- Scores in Live Sports.\n- Clocks too."),
        release("1.2.0", false, "- Event channels named for their event."),
        release("1.1.9", true, "Stable one\n\n- Older."),
        release("1.1.8", false, "- Before the stable one."),
    )

    @Test fun draftNotesGatherTheTestingReleasesSinceTheLastStableOne() {
        assertEquals(
            "## 1.2.1: Live scores\n- Scores in Live Sports.\n- Clocks too.\n\n## 1.2.0\n- Event channels named for their event.",
            Releases.draftNotes(releases, "apk-v1.2.1"),
        )
        assertEquals("## 1.2.0\n- Event channels named for their event.", Releases.draftNotes(releases, "apk-v1.2.0"))
        assertEquals("## 1.1.8\n- Before the stable one.", Releases.draftNotes(releases, "apk-v1.1.8"))
    }

    @Test fun listingGivesTestingReleasesTheirDraft() = runBlocking {
        val body = """[
            {"tag_name":"apk-v1.2.0","name":"Signal 1.2.0","prerelease":true,"draft":false,"body":"- New."},
            {"tag_name":"apk-v1.1.9","name":"Signal 1.1.9","prerelease":false,"draft":false,"body":"- Old."},
            {"tag_name":"apk-v1.2.1","name":"Signal 1.2.1","prerelease":true,"draft":false,"body":"- Newer."}
        ]"""
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val list = Releases(http) { null }.list().releases
        assertEquals(listOf("apk-v1.2.1", "apk-v1.2.0", "apk-v1.1.9"), list.map { it.tag })
        assertEquals("## 1.2.1\n- Newer.\n\n## 1.2.0\n- New.", list[0].draftNotes)
        assertEquals("", list[2].draftNotes)
    }

    @Test fun markingStableCanReplaceTheNotes() = runBlocking {
        val sent = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Patch) sent += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"id":7}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val releases = Releases(http) { "token" }
        assertNull(releases.mark("apk-v1.2.1", stable = true, notes = "Big update\n\n- \"Quoted\" things."))
        assertNull(releases.mark("apk-v1.2.1", stable = false))
        val withNotes = Json.parseToJsonElement(sent[0]).jsonObject
        assertEquals("false", withNotes.getValue("prerelease").jsonPrimitive.content)
        assertEquals("Big update\n\n- \"Quoted\" things.", withNotes.getValue("body").jsonPrimitive.content)
        assertFalse("body" in Json.parseToJsonElement(sent[1]).jsonObject, "notes are left alone unless given")
    }
}

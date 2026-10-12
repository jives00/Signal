package com.signal.server

import com.signal.sync.PushRequest
import com.signal.sync.SettingsFile
import com.signal.sync.SyncEvent
import com.signal.sync.SyncPaths
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NewProfile(val name: String, val copyFrom: String? = null)

@Serializable
data class ProfileName(val name: String)

@Serializable
data class DeviceProfile(val profileId: String)

@Serializable
data class SnapshotRequest(val reason: String = "Manual")

private val eventJson = Json { encodeDefaults = true }

/** Runs [block], answering a [SyncRefused] with its status and message. */
private suspend fun RoutingCall.refusable(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: SyncRefused) {
        respondText(e.message ?: "Refused", status = HttpStatusCode.fromValue(e.status))
    }
}

/** Settings sync for paired devices (PLAN §5.5, §9.2). Every route needs the device's key. */
fun Route.syncDeviceRoutes(companion: Companion) {
    post(SyncPaths.PUSH) {
        val device = call.device(companion) ?: return@post
        val request = runCatching { call.receive<PushRequest>() }.getOrNull()
            ?: return@post call.respondText("Bad sync request", status = HttpStatusCode.BadRequest)
        call.refusable { call.respond(companion.sync.push(device, request)) }
    }

    get(SyncPaths.PULL) {
        val device = call.device(companion) ?: return@get
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
        val schema = call.request.queryParameters["schema"]?.toIntOrNull() ?: 0
        call.refusable { call.respond(companion.sync.pull(device, since, schema)) }
    }

    // Server-sent events (PLAN §9.2 → Real-time updates): a hello with the current revision, then
    // every settings change and guide build, and a comment every 20 seconds so dead links show.
    get(SyncPaths.EVENTS) {
        call.device(companion) ?: return@get
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val events = companion.events
                .onStart { emit(SyncEvent(SyncEvent.HELLO, rev = companion.sync.currentRev())) }
                .map { "data: ${eventJson.encodeToString(SyncEvent.serializer(), it)}\n\n" }
            val pings = flow {
                while (true) {
                    delay(PING_MS)
                    emit(": ping\n\n")
                }
            }
            merge(events, pings).collect {
                writeStringUtf8(it)
                flush()
            }
        }
    }
}

private const val PING_MS = 20_000L

/** The web admin's profiles, device assignment and backups (PLAN §4.3.18 → Devices & profiles, Backups). */
fun Route.syncAdminRoutes(companion: Companion) {
    get("/sync") { call.refusable { call.respond(companion.sync.status()) } }

    get("/sync/export") {
        call.refusable {
            val file = companion.sync.exportFile()
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "signal-settings.json").toString())
            call.respondText(Json.encodeToString(SettingsFile.serializer(), file), ContentType.Application.Json)
        }
    }

    post("/profiles") {
        val body = runCatching { call.receive<NewProfile>() }.getOrNull()?.takeIf { it.name.isNotBlank() }
            ?: return@post call.respondText("Give the profile a name.", status = HttpStatusCode.BadRequest)
        call.refusable { call.respond(companion.sync.createProfile(body.name.trim().take(100), body.copyFrom)) }
    }

    put("/profiles/{id}") {
        val name = runCatching { call.receive<ProfileName>() }.getOrNull()?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@put call.respondText("Give the profile a name.", status = HttpStatusCode.BadRequest)
        call.refusable {
            if (companion.sync.renameProfile(call.parameters["id"]!!, name.take(100))) call.respond(HttpStatusCode.NoContent)
            else call.respondText("No such profile", status = HttpStatusCode.NotFound)
        }
    }

    delete("/profiles/{id}") {
        call.refusable {
            if (companion.sync.deleteProfile(call.parameters["id"]!!)) call.respond(HttpStatusCode.NoContent)
            else call.respondText("No such profile", status = HttpStatusCode.NotFound)
        }
    }

    put("/devices/{id}/profile") {
        val body = runCatching { call.receive<DeviceProfile>() }.getOrNull()
            ?: return@put call.respondText("Choose a profile.", status = HttpStatusCode.BadRequest)
        call.refusable {
            if (companion.sync.assignDevice(call.parameters["id"]!!, body.profileId)) call.respond(HttpStatusCode.NoContent)
            else call.respondText("No such device", status = HttpStatusCode.NotFound)
        }
    }

    post("/snapshots") {
        val reason = runCatching { call.receive<SnapshotRequest>() }.getOrNull()?.reason?.trim()?.takeIf { it.isNotEmpty() } ?: "Manual"
        call.refusable { call.respond(companion.sync.snapshot(reason.take(100))) }
    }

    post("/snapshots/{id}/restore") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
        call.refusable { call.respond(mapOf("changes" to companion.sync.restore(id))) }
    }

    delete("/snapshots/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
        call.refusable {
            val store = companion.syncStore ?: throw SyncRefused(503, "The database isn't connected yet.")
            if (store.deleteSnapshot(id)) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
    }
}

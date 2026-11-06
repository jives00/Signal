package com.signal.server

import com.signal.model.LiveFormat
import com.signal.model.SourceAccount
import com.signal.model.SourceKind
import com.signal.model.StreamUrls
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable data class RecordingsView(val problem: String?, val freeBytes: Long, val recordings: List<Recording>)
@Serializable data class StartRecording(val profile: String, val streamId: String, val minutes: Int)

private const val MAX_MINUTES = 12 * 60

/**
 * Every login that can fetch [recording]'s channel, for [Recorder]: the channel's own source, then
 * the profile's other sources, then the rest of the household. They have to be on the same server,
 * where the provider's stream IDs are the same whichever login asks.
 */
suspend fun recordingLogins(companion: Companion, recording: Recording): List<RecordingLogin> {
    val stream = companion.catalog.stream(recording.streamId) ?: return emptyList()
    val view = AdminView(companion.sync.liveItems(), recording.profileId)
    val own = view.sources.firstOrNull { it.first.id == stream.sourceId }?.first?.takeIf { it.enabled } ?: return emptyList()
    if (own.kind != SourceKind.XTREAM) {
        // Nothing to ask a plain playlist, and no other logins.
        return listOf(RecordingLogin(own.name, own.id, StreamUrls.live(own, stream), StreamUrls.headers(own, stream), null))
    }
    val server = own.url.trimEnd('/')
    val others = view.sources.map { it.first }.filter { it.id != own.id && it.enabled && it.kind == own.kind && it.url.trimEnd('/') == server }
    return (listOf(own) + others.sortedByDescending { view.sourceShown(it.id) })
        .flatMap { source ->
            source.logins.map { login ->
                val name = if (login.id == SourceAccount.MAIN) source.name else "${source.name}: ${login.name}"
                source.withLogin(login).copy(liveFormat = LiveFormat.TS) to name
            }
        }
        .distinctBy { (source, _) -> source.username }
        .map { (source, name) -> RecordingLogin(name, source.username, StreamUrls.live(source, stream), StreamUrls.headers(source, stream), source) }
}

/** Inside /api, network-trust only, like the rest of the web admin. */
fun Route.recordingRoutes(companion: Companion) {
    val channels = AdminChannels(companion)
    val recorder = companion.recorder

    get("/recordings") {
        val profile = call.request.queryParameters["profile"]?.takeIf { it.isNotBlank() } ?: SyncService.MAIN
        call.respond(RecordingsView(recorder.problem(), recorder.freeBytes(), companion.recordingStore?.forProfile(profile).orEmpty()))
    }

    post("/recordings") {
        val body = call.receive<StartRecording>()
        if (body.minutes !in 1..MAX_MINUTES) return@post call.respondText("Choose up to $MAX_MINUTES minutes.", status = HttpStatusCode.BadRequest)
        val row = channels.channel(body.profile, body.streamId, System.currentTimeMillis())
            ?: return@post call.respondText("No such channel", status = HttpStatusCode.NotFound)
        val stopMs = System.currentTimeMillis() + body.minutes * 60_000L
        runCatching { recorder.start(body.profile, body.streamId, row.now?.title ?: row.name, row.name, stopMs) }
            .onSuccess { call.respond(it) }
            .onFailure { call.respondText(it.message ?: "Couldn't start.", status = HttpStatusCode.Conflict) }
    }

    post("/recordings/{id}/stop") {
        if (recorder.stop(call.parameters["id"]!!)) call.respond(HttpStatusCode.NoContent)
        else call.respondText("That isn't recording.", status = HttpStatusCode.Conflict)
    }

    delete("/recordings/{id}") {
        when (recorder.delete(call.parameters["id"]!!)) {
            null -> call.respondText("No such recording", status = HttpStatusCode.NotFound)
            false -> call.respondText("Stop it first.", status = HttpStatusCode.Conflict)
            true -> call.respond(HttpStatusCode.NoContent)
        }
    }

    // A browser or player reads these directly; the playlist changes while it records, so it's never cached.
    get("/recordings/{id}/files/{name}") {
        val name = call.parameters["name"]!!
        val file = recorder.file(call.parameters["id"]!!, name) ?: return@get call.respondText("No such file", status = HttpStatusCode.NotFound)
        if (name == RecordingFiles.PLAYLIST) call.respondBytes(file.readBytes(), ContentType("application", "vnd.apple.mpegurl"))
        else call.respondFile(file)
    }
}

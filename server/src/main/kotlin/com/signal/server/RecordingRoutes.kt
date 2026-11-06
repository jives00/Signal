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

/** [conflicts] are the recordings still to come that may not get a source, because more overlap than there are sources. */
@Serializable data class RecordingsView(val problem: String?, val freeBytes: Long, val recordings: List<Recording>, val conflicts: List<String> = emptyList())
@Serializable data class StartRecording(val profile: String, val streamId: String, val minutes: Int)
@Serializable data class ScheduleRecording(val profile: String, val streamId: String, val start: Long)
@Serializable data class ExtendRecording(val minutes: Int)

private const val MAX_MINUTES = 12 * 60
private const val HOUR_MS = 60 * 60_000L
/** How far a program can move in the guide and still be the same one (dvr.md). */
private const val FOLLOW_MS = 2 * HOUR_MS
/** The guide looks 7 days ahead. */
private const val AHEAD_MS = 7 * 24 * HOUR_MS
private val KEEP_RECORDING = setOf(15, 30, 60)

/** The program [title] has moved to: the same title on the channel, nearest to where it was. */
fun sameProgram(programs: List<ProgramBrief>, title: String, start: Long): ProgramBrief? =
    programs.filter { it.title.trim().equals(title.trim(), ignoreCase = true) && kotlin.math.abs(it.start - start) <= FOLLOW_MS }
        .minByOrNull { kotlin.math.abs(it.start - start) }

/**
 * Which of [upcoming] may go without a source: at some moment in its program, it and whatever else
 * is recording then add up to more than [sources] gives it. Padding doesn't count, since it's given
 * up when a source is short.
 */
suspend fun recordingConflicts(upcoming: List<Recording>, active: List<Recording>, sources: suspend (Recording) -> Int): List<String> {
    val all = upcoming + active
    fun Recording.from() = programStart ?: startMs
    fun Recording.to() = programStop?.let { it + extraMs } ?: stopMs
    return upcoming.filter { r ->
        val overlapping = all.filter { it.from() < r.to() && r.from() < it.to() }
        // The count only rises where something starts, so those are the moments to check.
        val most = overlapping.maxOf { moment -> overlapping.count { it.from() <= moment.from() && moment.from() < it.to() } }
        most > sources(r).coerceAtLeast(1)
    }.map { it.id }
}

/** After a guide build: moves scheduled recordings to where their programs are now (dvr.md). */
fun followGuide(companion: Companion) {
    val store = companion.recordingStore ?: return
    val channels = AdminChannels(companion)
    val before = companion.settings.recordBeforeMinutes * 60_000L
    val after = companion.settings.recordAfterMinutes * 60_000L
    (store.scheduled() + store.unfinished()).forEach { recording ->
        val start = recording.programStart ?: return@forEach
        val programs = channels.programs(recording.profileId, recording.streamId, start - FOLLOW_MS, start + FOLLOW_MS + 1)
        // Nothing at all there means the guide has no data for it, which says nothing about the program.
        if (programs.isNotEmpty()) companion.recorder.follow(recording, sameProgram(programs, recording.title, start), before, after)
    }
}

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
        val store = companion.recordingStore
        val upcoming = store?.scheduled().orEmpty()
        val conflicts = if (upcoming.isEmpty()) emptyList() else recordingConflicts(upcoming, store?.unfinished().orEmpty()) { recordingLogins(companion, it).size }
        call.respond(RecordingsView(recorder.problem(), recorder.freeBytes(), store?.forProfile(profile).orEmpty(), conflicts))
    }

    // What a channel has coming up, to choose from.
    get("/recordings/programs") {
        val profile = call.request.queryParameters["profile"]?.takeIf { it.isNotBlank() } ?: SyncService.MAIN
        val streamId = call.request.queryParameters["streamId"] ?: return@get call.respondText("No channel", status = HttpStatusCode.BadRequest)
        val now = System.currentTimeMillis()
        call.respond(channels.programs(profile, streamId, now, now + AHEAD_MS))
    }

    post("/recordings/schedule") {
        val body = call.receive<ScheduleRecording>()
        val now = System.currentTimeMillis()
        val row = channels.channel(body.profile, body.streamId, now) ?: return@post call.respondText("No such channel", status = HttpStatusCode.NotFound)
        val program = channels.programs(body.profile, body.streamId, body.start, body.start + 1).firstOrNull { it.start == body.start }
            ?: return@post call.respondText("The guide doesn't have that program.", status = HttpStatusCode.NotFound)
        val settings = companion.settings
        runCatching {
            recorder.schedule(body.profile, body.streamId, program.title, row.name, program.start, program.stop, settings.recordBeforeMinutes * 60_000L, settings.recordAfterMinutes * 60_000L)
        }
            .onSuccess { call.respond(it) }
            .onFailure { call.respondText(it.message ?: "Couldn't schedule that.", status = HttpStatusCode.Conflict) }
    }

    post("/recordings/{id}/extend") {
        val body = call.receive<ExtendRecording>()
        if (body.minutes !in KEEP_RECORDING) return@post call.respondText("Keep recording 15, 30 or 60 minutes.", status = HttpStatusCode.BadRequest)
        if (recorder.extend(call.parameters["id"]!!, body.minutes * 60_000L)) call.respond(HttpStatusCode.NoContent)
        else call.respondText("That recording has finished.", status = HttpStatusCode.Conflict)
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

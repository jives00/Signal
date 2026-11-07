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
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File

/** [conflicts] are the recordings still to come that may not get a source, because more overlap than there are sources. */
@Serializable data class RecordingsView(
    val problem: String?,
    val freeBytes: Long,
    val recordings: List<Recording>,
    val conflicts: List<String> = emptyList(),
)
@Serializable data class RecordingProgress(val positionMs: Long)
@Serializable data class StartRecording(val profile: String, val streamId: String, val minutes: Int)
/** [title] as well as [start]: a channel's guide can list two programs at one time. */
@Serializable data class ScheduleRecording(val profile: String, val streamId: String, val start: Long, val title: String? = null)
@Serializable data class ExtendRecording(val minutes: Int)
/** From a TV: what its guide showed, so what's set is what the user chose, whichever guide it has. */
@Serializable data class DeviceSchedule(val streamId: String, val start: Long, val stop: Long, val title: String)
@Serializable data class DeviceScheduled(val recording: Recording, val conflict: Boolean)

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

private suspend fun conflicts(companion: Companion): List<String> {
    val store = companion.recordingStore
    val upcoming = store?.scheduled().orEmpty()
    return if (upcoming.isEmpty()) emptyList() else recordingConflicts(upcoming, store?.unfinished().orEmpty()) { recordingLogins(companion, it).size }
}

private suspend fun RoutingCall.respondRecordingFile(file: File) {
    // A player re-reads the playlist while it records, so it's never cached.
    if (file.name == RecordingFiles.PLAYLIST) respondBytes(file.readBytes(), ContentType("application", "vnd.apple.mpegurl"))
    else respondFile(file)
}

/** For the TV and the viewer: the device key says whose recordings these are, and it travels as a header, never in a URL (dvr.md). */
fun Route.recordingDeviceRoutes(companion: Companion) {
    val recorder = companion.recorder
    val channels = AdminChannels(companion)

    /** The recording, when it's this device's profile's. */
    suspend fun RoutingCall.own(): Recording? {
        val device = device(companion) ?: return null
        val recording = companion.recordingStore?.get(parameters["id"]!!)?.takeIf { it.profileId == device.profileId }
        if (recording == null) respondText("No such recording", status = HttpStatusCode.NotFound)
        return recording
    }

    route("/device/recordings") {
        get {
            val device = call.device(companion) ?: return@get
            call.respond(RecordingsView(recorder.problem(), recorder.freeBytes(), companion.recordingStore?.forProfile(device.profileId).orEmpty(), conflicts(companion)))
        }
        // Set from the TV's guide. Also takes a program already on, which records from now.
        post {
            val device = call.device(companion) ?: return@post
            val body = call.receive<DeviceSchedule>()
            if (body.title.isBlank() || body.stop <= body.start) return@post call.respondText("That isn't a program.", status = HttpStatusCode.BadRequest)
            val name = channels.channelName(device.profileId, body.streamId)
                ?: return@post call.respondText("The companion doesn't have that channel.", status = HttpStatusCode.NotFound)
            val settings = companion.settings
            runCatching {
                recorder.schedule(device.profileId, body.streamId, body.title, name, body.start, body.stop, settings.recordBeforeMinutes * 60_000L, settings.recordAfterMinutes * 60_000L)
            }
                .onSuccess { call.respond(DeviceScheduled(it, it.id in conflicts(companion))) }
                .onFailure { call.respondText(it.message ?: "Couldn't set that.", status = HttpStatusCode.Conflict) }
        }
        post("/{id}/stop") {
            val recording = call.own() ?: return@post
            if (recorder.stop(recording.id)) call.respond(HttpStatusCode.NoContent)
            else call.respondText("That isn't recording.", status = HttpStatusCode.Conflict)
        }
        post("/{id}/extend") {
            val recording = call.own() ?: return@post
            val body = call.receive<ExtendRecording>()
            if (body.minutes !in KEEP_RECORDING) return@post call.respondText("Keep recording 15, 30 or 60 minutes.", status = HttpStatusCode.BadRequest)
            if (recorder.extend(recording.id, body.minutes * 60_000L)) call.respond(HttpStatusCode.NoContent)
            else call.respondText("That recording has finished.", status = HttpStatusCode.Conflict)
        }
        get("/{id}/files/{name}") {
            val recording = call.own() ?: return@get
            val file = recorder.file(recording.id, call.parameters["name"]!!) ?: return@get call.respondText("No such file", status = HttpStatusCode.NotFound)
            call.respondRecordingFile(file)
        }
        post("/{id}/progress") {
            val recording = call.own() ?: return@post
            recorder.progress(recording.id, call.receive<RecordingProgress>().positionMs)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/keep") {
            val recording = call.own() ?: return@post
            if (recorder.keepLonger(recording.id)) call.respond(HttpStatusCode.NoContent)
            else call.respondText("That recording hasn't finished.", status = HttpStatusCode.Conflict)
        }
        delete("/{id}") {
            val recording = call.own() ?: return@delete
            if (recorder.delete(recording.id) == true) call.respond(HttpStatusCode.NoContent)
            else call.respondText("Stop it first.", status = HttpStatusCode.Conflict)
        }
    }
}

/** Inside /api, network-trust only, like the rest of the web admin. */
fun Route.recordingRoutes(companion: Companion) {
    val channels = AdminChannels(companion)
    val recorder = companion.recorder

    get("/recordings") {
        val profile = call.request.queryParameters["profile"]?.takeIf { it.isNotBlank() } ?: SyncService.MAIN
        call.respond(RecordingsView(recorder.problem(), recorder.freeBytes(), companion.recordingStore?.forProfile(profile).orEmpty(), conflicts(companion)))
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
        val name = channels.channelName(body.profile, body.streamId) ?: return@post call.respondText("No such channel", status = HttpStatusCode.NotFound)
        val program = channels.programs(body.profile, body.streamId, body.start, body.start + 1)
            .firstOrNull { it.start == body.start && (body.title == null || it.title == body.title) }
            ?: return@post call.respondText("The guide doesn't have that program.", status = HttpStatusCode.NotFound)
        val settings = companion.settings
        runCatching {
            recorder.schedule(body.profile, body.streamId, program.title, name, program.start, program.stop, settings.recordBeforeMinutes * 60_000L, settings.recordAfterMinutes * 60_000L)
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
        val name = channels.channelName(body.profile, body.streamId)
            ?: return@post call.respondText("No such channel", status = HttpStatusCode.NotFound)
        val now = System.currentTimeMillis()
        val onNow = channels.programs(body.profile, body.streamId, now, now + 1).firstOrNull { it.start <= now && now < it.stop }
        val stopMs = now + body.minutes * 60_000L
        runCatching { recorder.start(body.profile, body.streamId, onNow?.title ?: name, name, stopMs) }
            .onSuccess { call.respond(it) }
            .onFailure { call.respondText(it.message ?: "Couldn't start.", status = HttpStatusCode.Conflict) }
    }

    post("/recordings/{id}/keep") {
        if (recorder.keepLonger(call.parameters["id"]!!)) call.respond(HttpStatusCode.NoContent)
        else call.respondText("That recording hasn't finished.", status = HttpStatusCode.Conflict)
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

    get("/recordings/{id}/files/{name}") {
        val file = recorder.file(call.parameters["id"]!!, call.parameters["name"]!!) ?: return@get call.respondText("No such file", status = HttpStatusCode.NotFound)
        call.respondRecordingFile(file)
    }
}

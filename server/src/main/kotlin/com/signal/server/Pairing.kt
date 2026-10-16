package com.signal.server

import com.signal.nas.APP_VERSION_HEADER
import com.signal.nas.DeviceInfo
import com.signal.nas.NasPaths
import com.signal.nas.PairRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import java.io.File
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Approve device, with where it goes (PLAN §9.1): [profile] is a profile's ID, or "own" for a new
 * profile of its own (a copy of Main, named after it); [newProfile] names a new profile, a copy of
 * [copyFrom] or empty. Neither: as Settings → Companion says for new devices, and an existing
 * device keeps its profile.
 */
@Serializable
data class ApproveRequest(val code: String, val profile: String? = null, val newProfile: String? = null, val copyFrom: String? = null)

@Serializable
data class RenameRequest(val name: String)

/** The app's side of pairing (PLAN §5.7), and the first endpoint that takes a device's key. */
fun Route.deviceRoutes(companion: Companion) {
    post(NasPaths.PAIR) {
        val request = runCatching { call.receive<PairRequest>() }.getOrNull()
        if (request == null || request.deviceId.isBlank() || request.deviceId.length > 36) {
            call.respondText("Bad pairing request", status = HttpStatusCode.BadRequest)
            return@post
        }
        val started = companion.pairings.start(request)
        if (started == null) call.respondText("Too many devices are pairing at once; try again in a few minutes.", status = HttpStatusCode.TooManyRequests)
        else call.respond(started)
    }

    get(NasPaths.pairStatus("{id}")) {
        call.respond(companion.pairings.status(call.parameters["id"].orEmpty()))
    }

    get(NasPaths.ME) {
        val device = call.device(companion) ?: return@get
        call.respond(DeviceInfo(device.id, device.name))
    }

    // Send logs (PLAN §4.3.15 → About): the app's own log lines, already masked on the device. Only the
    // latest upload per device is kept; the web admin's device settings download it.
    post(NasPaths.LOGS) {
        val device = call.device(companion) ?: return@post
        val text = call.receiveText()
        if (text.length > MAX_LOG_CHARS) return@post call.respondText("Logs too large", status = HttpStatusCode.PayloadTooLarge)
        deviceLogFile(companion, device.id).apply { parentFile.mkdirs() }.writeText(text)
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MAX_LOG_CHARS = 2_000_000

/** Where a device's last uploaded logs are kept. Device IDs are UUIDs, never a path. */
internal fun deviceLogFile(companion: Companion, deviceId: String): File =
    File(File(companion.config.dataDir, "logs"), deviceId.filter { it.isLetterOrDigit() || it == '-' } + ".txt")

/** The web admin's Devices page (PLAN §4.3.18 → Devices & profiles). */
fun Route.deviceAdminRoutes(companion: Companion) {
    get("/devices") {
        val store = call.deviceStore(companion) ?: return@get
        call.respond(store.all())
    }

    post("/devices/approve") {
        val store = call.deviceStore(companion) ?: return@post
        val r = runCatching { call.receive<ApproveRequest>() }.getOrDefault(ApproveRequest(""))
        val notShowing = "No device is showing that code. Codes last 10 minutes; check the TV."
        val name = companion.pairings.waiting(r.code) ?: return@post call.respondText(notShowing, status = HttpStatusCode.NotFound)
        // The chosen profile, made first: the device gets its key only once it's placed.
        val chosen = try {
            when {
                r.newProfile?.isNotBlank() == true -> companion.sync.createProfile(r.newProfile.trim().take(100), r.copyFrom?.takeIf { it.isNotBlank() }).id
                r.profile == CompanionSettings.OWN -> companion.sync.createProfile(name, SyncService.MAIN).id
                r.profile != null -> r.profile.takeIf { companion.sync.hasProfile(it) } ?: throw SyncRefused(404, "That profile no longer exists. Reload the page.")
                else -> null
            }
        } catch (e: SyncRefused) {
            return@post call.respondText(e.message.orEmpty(), status = HttpStatusCode.fromValue(e.status))
        }
        val device = companion.pairings.approve(r.code, store, chosen)
        if (device == null) call.respondText(notShowing, status = HttpStatusCode.NotFound)
        else if (chosen != null) call.respond(device)
        else {
            // New devices join Main, or get their own profile, as Settings → Companion says (PLAN §9.1).
            val fresh = device.lastSyncAt == 0L && device.profileId == SyncService.MAIN
            val placed = if (fresh && companion.settings.newDeviceProfile == CompanionSettings.OWN && companion.syncStore != null) {
                runCatching { companion.sync.ownProfile(device) }.map { device.copy(profileId = it.id) }.getOrDefault(device)
            } else device
            call.respond(placed)
        }
    }

    patch("/devices/{id}") {
        val store = call.deviceStore(companion) ?: return@patch
        val name = runCatching { call.receive<RenameRequest>().name.trim() }.getOrDefault("")
        when {
            name.isEmpty() || name.length > 100 -> call.respondText("Enter a name of up to 100 characters.", status = HttpStatusCode.BadRequest)
            store.rename(call.parameters["id"].orEmpty(), name) -> call.respond(HttpStatusCode.NoContent)
            else -> call.respondText("No such device", status = HttpStatusCode.NotFound)
        }
    }

    // The last logs the device sent (Settings → About → Send logs on the TV), as a download.
    get("/devices/{id}/logs") {
        val file = deviceLogFile(companion, call.parameters["id"].orEmpty())
        if (!file.isFile) return@get call.respondText("This TV hasn't sent its logs. On the TV: Settings → About & updates → Send logs.", status = HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "signal-logs-${file.nameWithoutExtension.take(8)}.txt").toString())
        call.respondFile(file)
    }

    // Forget: the device's key stops working at once, and it has to pair again.
    delete("/devices/{id}") {
        val store = call.deviceStore(companion) ?: return@delete
        if (store.delete(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.NoContent)
        else call.respondText("No such device", status = HttpStatusCode.NotFound)
    }
}

/** The store, or a 503 when the database isn't connected yet. */
private suspend fun RoutingCall.deviceStore(companion: Companion): DeviceStore? =
    companion.devices ?: null.also { respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable) }

/**
 * The device behind this request's key, or null after answering 401. Also records when it was last
 * seen and its app version, at most once a minute so polling doesn't mean a write per request.
 */
suspend fun RoutingCall.device(companion: Companion): Device? {
    val store = deviceStore(companion) ?: return null
    val key = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    val device = key?.takeIf { it.isNotEmpty() }?.let { store.byKeyHash(DeviceKeys.hash(it)) }
    if (device == null) {
        respondText("This device isn't paired, or was removed in the web admin.", status = HttpStatusCode.Unauthorized)
        return null
    }
    val now = System.currentTimeMillis()
    val version = request.headers[APP_VERSION_HEADER] ?: device.appVersion
    if (now - device.lastSeenAt > 60_000 || version != device.appVersion) store.touch(device.id, version.take(30), now)
    return device
}

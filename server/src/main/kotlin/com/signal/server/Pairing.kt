package com.signal.server

import com.signal.nas.APP_VERSION_HEADER
import com.signal.nas.DeviceInfo
import com.signal.nas.NasPaths
import com.signal.nas.PairRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class ApproveRequest(val code: String)

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
}

/** The web admin's Devices page (PLAN §4.3.18 → Devices & profiles). */
fun Route.deviceAdminRoutes(companion: Companion) {
    get("/devices") {
        val store = call.deviceStore(companion) ?: return@get
        call.respond(store.all())
    }

    post("/devices/approve") {
        val store = call.deviceStore(companion) ?: return@post
        val code = runCatching { call.receive<ApproveRequest>().code }.getOrDefault("")
        val device = companion.pairings.approve(code, store)
        if (device == null) call.respondText("No device is showing that code. Codes last 10 minutes; check the TV.", status = HttpStatusCode.NotFound)
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

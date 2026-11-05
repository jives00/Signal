package com.signal.server

import com.signal.app.ProviderAccounts
import com.signal.data.Settings
import com.signal.model.Source
import com.signal.model.SourceAccount
import com.signal.parse.XtreamParser
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.sync.str
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("Viewer")

/**
 * The viewer's API (web-player.md), on its own port. A viewer is a paired device: its key says which
 * profile it shows, so nothing here takes a profile, and its device settings give its home login.
 */
fun Route.viewerRoutes(companion: Companion) {
    val channels = AdminChannels(companion)
    val logins = ViewerLogins(check = { companion.catalog.accountInfo(it) })
    val now = { System.currentTimeMillis() }

    route("/viewer") {
        get("/lists") {
            val device = call.device(companion) ?: return@get
            call.answer { listsView(channels, device.profileId, now()) }
        }
        get("/groups") {
            val device = call.device(companion) ?: return@get
            call.answer { groupsView(companion, channels.view(device.profileId)) }
        }
        get("/channels") {
            val device = call.device(companion) ?: return@get
            val q = call.request.queryParameters
            call.answer {
                channels.page(
                    device.profileId,
                    ChannelFilter(query = q["q"]?.takeIf { it.isNotBlank() }, sourceId = q["source"]?.takeIf { it.isNotBlank() }, group = q["group"]?.takeIf { it.isNotBlank() }, hidden = "visible"),
                    offset = 0,
                    limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100,
                    now = now(),
                )
            }
        }
        get("/channels/{streamId}") {
            val device = call.device(companion) ?: return@get
            val row = channels.channel(device.profileId, call.parameters["streamId"]!!, now())
            if (row == null) call.respond(HttpStatusCode.NotFound) else call.respond(row)
        }
        get("/watch/{streamId}") {
            val device = call.device(companion) ?: return@get
            val (source, stream) = call.watchTarget(companion, device.profileId) ?: return@get
            val login = logins.pick(device.id, source, homeLogin(companion, device, source))
            log.info("Watch: ${device.name}: ${stream.rawName} (${source.name}, ${login.name})")
            call.respond(watchStream(source.withLogin(login), stream))
        }
        // Sent while a channel plays, so changing channel keeps the login in use.
        post("/playing") {
            val device = call.device(companion) ?: return@post
            logins.playing(device.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The account ID the device setting source_accounts gives [device] for [source], if any. */
private fun homeLogin(companion: Companion, device: Device, source: Source): String? {
    val raw = companion.sync.liveItems().firstOrNull {
        it.scope == SyncScope.DEVICE && it.owner == device.id && it.type == SyncType.SETTING && it.id == Settings.Key.SOURCE_ACCOUNTS.id
    }?.fields?.str("value")
    return ProviderAccounts.parseChosen(raw)[source.id]
}

/**
 * Which login a viewer plays from, by the same rules as a TV (`ProviderAccounts`): its home login,
 * or a free one if that's busy as it starts watching. It runs here because a browser shouldn't hold
 * the other logins just to ask about them.
 */
class ViewerLogins(
    private val check: suspend (Source) -> XtreamParser.AccountInfo?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Held(val sourceId: String, val accountId: String, @Volatile var usedAt: Long)

    private val held = ConcurrentHashMap<String, Held>()

    fun playing(deviceId: String) {
        held[deviceId]?.usedAt = now()
    }

    suspend fun pick(deviceId: String, source: Source, homeId: String?): SourceAccount {
        val logins = source.logins
        if (logins.size < 2) return logins.first()
        held[deviceId]?.takeIf { it.sourceId == source.id && now() - it.usedAt < ProviderAccounts.HOLD_MS }?.let { h ->
            logins.firstOrNull { it.id == h.accountId }?.let { h.usedAt = now(); return it }
        }
        val home = logins.firstOrNull { it.id == homeId } ?: logins.first()
        val use = if (free(source, home) == false) borrow(source, logins.filter { it.id != home.id }) ?: home else home
        held[deviceId] = Held(source.id, use.id, now())
        return use
    }

    private suspend fun free(source: Source, login: SourceAccount) = ProviderAccounts.isFree(check(source.withLogin(login)))

    private suspend fun borrow(source: Source, logins: List<SourceAccount>): SourceAccount? = coroutineScope {
        logins.map { async { it to free(source, it) } }.awaitAll().firstOrNull { it.second == true }?.first
    }
}

package com.signal.server

import com.signal.model.LiveFormat
import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.SourceAccount
import com.signal.model.SourceKind
import com.signal.model.StreamUrls
import com.signal.nas.NowPlaying
import com.signal.nas.PlayKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * What the web admin's Watch page plays (web-player.md): the provider's own URL, which the browser
 * fetches directly, so the companion never relays video. [hls] is false when the browser can't play
 * it (an M3U stream that isn't HLS). [headersDropped]: the source sets a User-Agent or other headers,
 * which a browser can't send, so the provider may refuse it.
 */
@Serializable data class WatchStream(val url: String, val hls: Boolean, val headersDropped: Boolean)

private val log = LoggerFactory.getLogger("Watch")

/** Inside /api, network-trust only, like the rest of the web admin. */
fun Route.watchRoutes(companion: Companion) {
    get("/watch/{streamId}") {
        val profile = call.request.queryParameters["profile"]?.takeIf { it.isNotBlank() } ?: SyncService.MAIN
        val (source, stream) = call.watchTarget(companion, profile) ?: return@get
        log.info("Watch: ${stream.rawName} (${source.name})")
        val title = AdminChannels(companion).channelName(profile, stream.id) ?: stream.rawName
        companion.activity.report(webKey(profile), SourceActivity.Who.WEB, "Web admin", profile, NowPlaying(source.id, SourceAccount.MAIN, PlayKind.LIVE, stream.id, title, since = System.currentTimeMillis()))
        call.respond(watchStream(source, stream))
    }
    // While a channel plays, and as it stops (the dashboard's cards).
    post("/watch/playing") {
        companion.activity.seen(webKey(call.request.queryParameters["profile"]))
        call.respond(HttpStatusCode.NoContent)
    }
    post("/watch/stopped") {
        companion.activity.stopped(webKey(call.request.queryParameters["profile"]), call.request.queryParameters["stream"])
        call.respond(HttpStatusCode.NoContent)
    }
}

/** One per profile: the web admin has no device to tell its tabs apart. */
private fun webKey(profile: String?) = "web:${profile?.takeIf { it.isNotBlank() } ?: SyncService.MAIN}"

/** The channel in the path with its source, or null after refusing: unknown, or its source is off for [profile]. */
internal suspend fun RoutingCall.watchTarget(companion: Companion, profile: String): Pair<Source, ProviderStream>? {
    val view = AdminView(companion.sync.liveItems(), profile)
    val stream = companion.catalog.stream(parameters["streamId"]!!)
    val source = stream?.let { s -> view.sources.firstOrNull { it.first.id == s.sourceId }?.first }
    if (stream == null || source == null) {
        respondText("No such channel", status = HttpStatusCode.NotFound)
        return null
    }
    if (!source.enabled || !view.sourceShown(source.id)) {
        respondText("That channel's source is off for this profile.", status = HttpStatusCode.Conflict)
        return null
    }
    return source to stream
}

/** Xtream serves every live channel as HLS too, whatever the source plays on the TV; browsers only play HLS. */
fun watchStream(source: Source, stream: ProviderStream): WatchStream {
    val url = when (source.kind) {
        SourceKind.XTREAM -> StreamUrls.live(source.copy(liveFormat = LiveFormat.HLS), stream)
        else -> StreamUrls.live(source, stream)
    }
    return WatchStream(url, hls = url.substringBefore('?').lowercase().endsWith(".m3u8"), headersDropped = StreamUrls.headers(source, stream).isNotEmpty())
}

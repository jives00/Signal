package com.signal.server

import com.signal.model.LiveFormat
import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.model.StreamUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
        val view = AdminView(companion.sync.liveItems(), profile)
        val stream = companion.catalog.stream(call.parameters["streamId"]!!)
        val source = stream?.let { s -> view.sources.firstOrNull { it.first.id == s.sourceId }?.first }
        if (stream == null || source == null) return@get call.respondText("No such channel", status = HttpStatusCode.NotFound)
        if (!source.enabled || !view.sourceShown(source.id)) return@get call.respondText("That channel's source is off for this profile.", status = HttpStatusCode.Conflict)
        log.info("Watch: ${stream.rawName} (${source.name})")
        call.respond(watchStream(source, stream))
    }
}

/** Xtream serves every live channel as HLS too, whatever the source plays on the TV; browsers only play HLS. */
fun watchStream(source: Source, stream: ProviderStream): WatchStream {
    val url = when (source.kind) {
        SourceKind.XTREAM -> StreamUrls.live(source.copy(liveFormat = LiveFormat.HLS), stream)
        else -> StreamUrls.live(source, stream)
    }
    return WatchStream(url, hls = url.substringBefore('?').lowercase().endsWith(".m3u8"), headersDropped = StreamUrls.headers(source, stream).isNotEmpty())
}

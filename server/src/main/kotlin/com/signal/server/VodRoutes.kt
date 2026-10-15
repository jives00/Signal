package com.signal.server

import com.signal.data.Settings
import com.signal.data.VodRepository
import com.signal.match.NameCleanup
import com.signal.model.VodKind
import com.signal.nas.NasVodPaths
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** The web admin's Movies & Series page (PLAN §5.2 → VOD). */
@Serializable
data class VodStatus(
    val keySet: Boolean,
    val titles: Int,
    val matched: Int,
    val manual: Int,
    val unmatched: Int,
    val running: Boolean,
    val progress: String?,
    val lastRun: JobRun?,
    val imageCacheBytes: Long,
)

@Serializable
data class VodRow(
    val itemId: String,
    val sourceId: String,
    val kind: String,
    /** The title as the profile's TVs show it, after title cleanup. */
    val title: String,
    /** The provider's own name for it. */
    val name: String,
    val year: Int?,
    /** matched, manual, unmatched, or pending (not tried yet). */
    val status: String,
    val tmdbId: Long?,
    val tmdbTitle: String?,
    val tmdbYear: Int?,
)

@Serializable data class VodRows(val rows: List<VodRow>, val total: Int)

/** Fix match: a TMDB ID, or null for "no match". */
@Serializable data class MatchRequest(val tmdbId: Long?)

fun Route.vodDeviceRoutes(companion: Companion) {
    get(NasVodPaths.INDEX) {
        call.device(companion) ?: return@get
        if (companion.tmdbStore == null) return@get call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
        val index = companion.vod.index()
        // The TV sends the version it has; an unchanged index is a few bytes.
        if (call.request.queryParameters["have"]?.toLongOrNull() == index.version) call.respond(HttpStatusCode.NotModified)
        else call.respond(index)
    }
    // The same index a page at a time (PLAN §8 → milestone 5): one body was tens of MB at 233k titles.
    get(NasVodPaths.INDEX_PAGE) {
        call.device(companion) ?: return@get
        if (companion.tmdbStore == null) return@get call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
        val pager = companion.vod.indexPager()
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val q = call.request.queryParameters
        when {
            page == 0 && q["have"]?.toLongOrNull() == pager.index.version -> call.respond(HttpStatusCode.NotModified)
            page > 0 && q["version"]?.toLongOrNull() != pager.index.version ->
                call.respondText("The index changed since page 0. Start again.", status = HttpStatusCode.Conflict)
            else -> pager.page(page)?.let { call.respond(it) } ?: call.respondText("No such page.", status = HttpStatusCode.NotFound)
        }
    }

    get("/device/vod/meta/{id}") {
        call.device(companion) ?: return@get
        if (companion.tmdbStore == null) return@get call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
        val item = companion.catalogItem(call.parameters["id"]!!) ?: return@get call.respondText("Not in the catalog", status = HttpStatusCode.NotFound)
        val meta = companion.vod.meta(item)
        if (meta == null) call.respondText("Not matched", status = HttpStatusCode.NotFound) else call.respond(meta)
    }
}

/** Images are open on the home network and Tailscale without a device key, so image loaders can fetch them plainly. */
fun Route.vodImageRoutes(companion: Companion) {
    get("${NasVodPaths.IMAGES}/{size}/{name}") {
        val file = runCatching { companion.images.get(call.parameters["size"]!!, call.parameters["name"]!!) }.getOrNull()
        if (file == null) return@get call.respondText("No such image", status = HttpStatusCode.NotFound)
        // TMDB file names never change content, so TVs can keep them.
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 30 * 24 * 60 * 60))
        call.respondFile(file)
    }

    // A provider's poster, only for a title in the catalog: never a URL the caller chooses.
    get("${NasVodPaths.PROVIDER_IMAGES}/{id}") {
        val url = companion.catalogItem(call.parameters["id"]!!)?.posterUrl?.takeIf { it.isNotBlank() }
        val file = url?.let { runCatching { companion.images.provider(it) }.getOrNull() }
        if (file == null) return@get call.respondText("No such image", status = HttpStatusCode.NotFound)
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 7 * 24 * 60 * 60))
        call.respondFile(file)
    }
}

fun Route.vodAdminRoutes(companion: Companion) {
    get("/vod") {
        if (companion.tmdbStore == null) return@get call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
        val c = companion.vod.counts()
        call.respond(
            VodStatus(
                companion.settings.tmdbKey != null, c.titles, c.matched, c.manual, c.unmatched, companion.vod.running, companion.vod.progress,
                companion.store?.lastJob(Companion.VOD_JOB), runCatching { companion.images.size() }.getOrDefault(0),
            ),
        )
    }

    // The catalog with each title's match: search, filter by kind and status, paged. Titles are cleaned
    // with the rules of ?profile= (PLAN §4.3.6 → Categories).
    get("/vod/items") {
        if (companion.tmdbStore == null) return@get call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
        val profile = call.request.queryParameters["profile"]?.takeIf { it.isNotBlank() } ?: SyncService.MAIN
        val rules = NameCleanup.parse(AdminView(companion.sync.liveItems(), profile).profileSettings[Settings.Key.VOD_NAME_CLEANUP.id])
        val q = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
        val kind = call.request.queryParameters["kind"]
        val status = call.request.queryParameters["status"]
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val matches = companion.tmdbStore!!.matches().associateBy { it.vodItemId }
        val rows = companion.vodItems().asSequence()
            .filter { kind == null || it.kind.name == kind }
            .map { item -> item to VodRepository.displayNameOf(item.name, rules) }
            .filter { (item, title) -> q.isEmpty() || title.lowercase().contains(q) || item.name.lowercase().contains(q) }
            .map { (item, title) ->
                val m = matches[item.id]
                val t = m?.tmdbId?.let { companion.vod.entryTitle(item.kind.name, it) }
                VodRow(item.id, item.sourceId, item.kind.name, title, item.name, item.year, m?.status ?: "pending", m?.tmdbId, t?.first, t?.second)
            }
            .filter { status == null || it.status == status }
            .toList()
        call.respond(VodRows(rows.drop(offset).take(limit), rows.size))
    }

    get("/vod/search") {
        val kind = call.request.queryParameters["kind"]?.takeIf { it == VodKind.SERIES.name } ?: VodKind.MOVIE.name
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        if (q.isEmpty()) return@get call.respond(emptyList<TmdbCandidate>())
        val year = call.request.queryParameters["year"]?.toIntOrNull()
        runCatching { companion.vod.search(kind, q, year) }
            .onSuccess { call.respond(it.take(20)) }
            .onFailure { call.respondText(it.message ?: "TMDB search failed", status = HttpStatusCode.BadGateway) }
    }

    put("/vod/items/{id}/match") {
        val item = companion.catalogItem(call.parameters["id"]!!) ?: return@put call.respondText("Not in the catalog", status = HttpStatusCode.NotFound)
        val r = call.receive<MatchRequest>()
        val error = runCatching { companion.vod.setMatch(item, r.tmdbId) }.getOrElse { it.message }
        if (error != null) return@put call.respondText(error, status = HttpStatusCode.BadRequest)
        companion.vodChanged()
        call.respond(HttpStatusCode.NoContent)
    }

    // Back to automatic: matched again on the next run.
    delete("/vod/items/{id}/match") {
        val item = companion.catalogItem(call.parameters["id"]!!) ?: return@delete call.respondText("Not in the catalog", status = HttpStatusCode.NotFound)
        companion.vod.clearMatch(item)
        companion.vodChanged()
        call.respond(HttpStatusCode.NoContent)
    }

    post("/vod/run") {
        when {
            companion.store == null -> call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
            companion.settings.tmdbKey == null -> call.respondText("Add a TMDB API key in Settings → Companion first.", status = HttpStatusCode.Conflict)
            !companion.runVodInBackground("run now") -> call.respondText("Matching is already running.", status = HttpStatusCode.Conflict)
            else -> call.respond(HttpStatusCode.Accepted)
        }
    }
}

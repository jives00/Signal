package com.signal.server

import com.signal.data.Settings
import com.signal.match.NameCleanup
import com.signal.match.VodCategoryFilter
import com.signal.data.newId
import com.signal.model.ChannelSort
import com.signal.model.GroupSort
import com.signal.model.LiveFormat
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.sync.SettingsFile
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.sync.fields
import com.signal.sync.long
import com.signal.sync.str
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---- Request bodies ---------------------------------------------------------------------------------

/** Most edits: which channels, and which profiles ("Apply to", PLAN §9.1). */
@Serializable
data class ChannelEdit(
    val streamIds: List<String>,
    val profiles: List<String>,
    /** favorite, unfavorite, hide, unhide, reset, addToPlaylist, removeFromPlaylist, set */
    val action: String,
    val playlistId: String? = null,
    /** For "set": customName, customLogo, customNumber, voiceNames; a JSON null clears one. */
    val values: Map<String, JsonElement> = emptyMap(),
)

@Serializable data class MoveRequest(val profile: String, val toIndex: Int)
@Serializable data class NameRequest(val name: String, val profiles: List<String> = emptyList())
@Serializable data class ProfileTargets(val profiles: List<String>)
@Serializable data class PlaylistItems(val profile: String, val streamIds: List<String> = emptyList(), val userChannelIds: List<String> = emptyList())
@Serializable data class GroupEdit(val sourceId: String, val group: String, val profiles: List<String>, val hidden: Boolean? = null, val name: String? = null, val clearName: Boolean = false, val sort: String? = null)
/** Movies & Series → Categories: hide, show or rename a category, by name, for the household. */
@Serializable data class VodCategoryEdit(val name: String, val hidden: Boolean? = null, val customName: String? = null, val clearName: Boolean = false)
/** Movies & Series → Categories: move a category in its kind's column, which switches the order to Manual. */
@Serializable data class VodCategoryMove(val kind: String, val name: String, val toIndex: Int, val profile: String = SyncService.MAIN)
@Serializable data class VodCategorySort(val sort: String)
@Serializable data class GroupMove(val sourceId: String, val group: String, val profile: String, val toIndex: Int)
@Serializable data class MappingRequest(val streamId: String, val epgSourceId: String, val guideChannelId: String? = null)
@Serializable data class QueueRequest(val profile: String, val skip: List<String> = emptyList())
@Serializable data class SettingRequest(val scope: String, val key: String, val value: String? = null, val profiles: List<String> = emptyList())
@Serializable data class DeviceSettingRequest(val key: String, val value: String? = null)
@Serializable data class UndoRequest(val ops: List<AdminOp>)
@Serializable data class CopyRequest(val from: String, val to: List<String>, val sections: List<String>, val replace: Boolean = false)
@Serializable data class RestoreRequest(val part: String = "all", val id: String? = null)
@Serializable data class ImportRequest(val profile: String, val file: SettingsFile)

@Serializable
data class SourceForm(
    val name: String,
    val kind: String,
    val url: String,
    val username: String = "",
    /** Empty on an edit keeps the stored password. */
    val password: String = "",
    val userAgent: String? = null,
    val referrer: String? = null,
    val enabled: Boolean = true,
    val liveFormat: String = "TS",
    val connectionLimit: Int = 2,
)

@Serializable data class EpgForm(val name: String? = null, val url: String? = null, val enabled: Boolean? = null, val timeShiftMinutes: Int? = null)
@Serializable data class EnabledRequest(val enabled: Boolean)
@Serializable data class DefaultRequest(val id: String)

// ---- Responses --------------------------------------------------------------------------------------

@Serializable
data class SourceView(
    val id: String, val name: String, val kind: String, val url: String, val username: String, val hasPassword: Boolean,
    val userAgent: String?, val referrer: String?, val enabled: Boolean, val liveFormat: String, val connectionLimit: Int,
    val lastRefreshedAt: Long, val lastError: String?, val streams: Int, val refreshing: Boolean,
    /** Xtream: what the provider allows, and when the account expires (epoch seconds), from the companion's last refresh. */
    val providerConnections: Int? = null, val expiresAt: Long? = null,
    /** Sources per profile (PLAN §9.1): the profiles that leave this source out. */
    val hiddenFor: List<String> = emptyList(),
)

@Serializable
data class EpgSourceView(
    val id: String, val name: String, val kind: String, val url: String, val sourceId: String?, val enabled: Boolean,
    val timeShiftMinutes: Int, val isDefault: Boolean, val channels: Int, val programs: Int, val error: String?,
    /** When the companion last downloaded it; null before its first good build. */
    val refreshedAt: Long?,
    /** A running guide build is downloading it; a single Refresh now leaves the others alone. */
    val refreshing: Boolean = false,
)

/** [guideBuilding]: a guide build is running, so the EPG sources' Refresh buttons wait. */
@Serializable data class SourcesView(val sources: List<SourceView>, val epgSources: List<EpgSourceView>, val guideBuilding: Boolean)

@Serializable
data class GroupView(
    val sourceId: String, val sourceName: String, val name: String, val title: String, val count: Int,
    val hidden: Boolean, val channelSort: String?, val customName: String?,
)

@Serializable data class GroupsView(val groupSort: String, val channelSort: String, val groups: List<GroupView>)

/**
 * A provider movie or series category (PLAN §4.3.6 → Categories). [title] is what the profile's TVs
 * show: the household's rename, or the name after title cleanup. A hidden category's titles are
 * dropped, so its [count] is 0.
 */
@Serializable
data class VodCategoryView(
    val sourceId: String, val sourceName: String, val kind: String, val categoryId: String,
    val name: String, val title: String, val count: Int, val hidden: Boolean, val customName: String?,
)

/** Every category, movies then series, each in the column's order ([sort], a GroupSort name), hidden ones included. */
@Serializable data class VodCategoriesView(val sort: String, val categories: List<VodCategoryView>)

/** [sort] is "A_Z", "NUMBER", or null for the manual order (the TV's playlist menu → Sort). */
@Serializable data class PlaylistView(val id: String, val name: String, val channels: List<ChannelRow>, val sort: String? = null)
@Serializable data class SortRequest(val sort: String? = null)
@Serializable data class ListsView(
    val favorites: List<ChannelRow>,
    val playlists: List<PlaylistView>,
    /** The profile's Favorites sort: "A_Z", "NUMBER", or null for the manual order. */
    val favoritesSort: String? = null,
)

@Serializable data class QueueView(val remaining: Int, val channel: ChannelRow?, val suggestions: List<GuideChannelInfo>)

@Serializable
data class ManualMapping(val userChannelId: String, val channelName: String, val epgSourceId: String, val epgSourceName: String, val guideChannelId: String?, val guideChannelName: String?)

@Serializable data class ReminderView(val id: String, val title: String, val start: Long, val stop: Long, val channelName: String)
/** One Library title (PLAN §4.3.11). [available] is false when no enabled source has it now. */
@Serializable data class LibraryRow(val key: String, val kind: String, val title: String, val year: Int?, val addedAt: Long, val available: Boolean)
@Serializable data class LibraryAdd(val itemIds: List<String>, val profiles: List<String> = emptyList())

@Serializable data class SettingsView(val household: Map<String, String>, val profile: Map<String, String>, val companion: CompanionSettingsView)

@Serializable data class DeviceSettingsView(val device: Device, val settings: Map<String, String>)

@Serializable
data class Overview(
    val sources: List<SourceView>,
    val coverage: Coverage,
    val unmappedFavorites: Int,
    val unavailableFavorites: Int,
    val failedJobs: List<JobRun>,
    val devices: List<Device>,
    val latestAppVersion: String,
    /** Channels the provider switch review has for you, across sources (PLAN §4.3.18 → Needs attention). */
    val switchReview: Int = 0,
)

@Serializable data class Coverage(val mapped: Int, val total: Int)

// ---- Routes -----------------------------------------------------------------------------------------

private suspend fun RoutingCall.edit(block: suspend () -> EditResult) {
    try {
        respond(block())
    } catch (e: SyncRefused) {
        respondText(e.message ?: "Refused", status = HttpStatusCode.fromValue(e.status))
    }
}

private suspend fun RoutingCall.answer(block: suspend () -> Any) {
    try {
        when (val r = block()) {
            is HttpStatusCode -> respond(r)
            else -> respond(r)
        }
    } catch (e: SyncRefused) {
        respondText(e.message ?: "Refused", status = HttpStatusCode.fromValue(e.status))
    }
}

private fun RoutingCall.profile(): String = request.queryParameters["profile"]?.takeIf { it.isNotBlank() } ?: SyncService.MAIN

private fun targets(profiles: List<String>) = profiles.ifEmpty { listOf(SyncService.MAIN) }

/** The web admin's pages (PLAN §4.3.18). Reads take ?profile=; edits answer with an EditResult for Undo. */
fun Route.adminPageRoutes(companion: Companion) {
    val channels = AdminChannels(companion)
    val edits = AdminEdits(companion)
    val switch = AdminSwitch(companion)
    val now = { System.currentTimeMillis() }

    // ---- Dashboard ----
    get("/overview") {
        call.answer {
            val view = channels.view(call.profile())
            val (mapped, total) = channels.coverage(call.profile())
            val favs = channels.rowsFor(view, view.favorites.entries.sortedBy { it.value }.map { it.key }, now())
            val failed = companion.store?.jobs(50).orEmpty().filter { it.result == "failed" && it.startedAt > now() - 7 * 24 * 60 * 60_000L }
            Overview(sourceViews(companion, view), Coverage(mapped, total), favs.count { !it.unavailable && it.guide == null }, favs.count { it.unavailable }, failed,
                companion.devices?.all().orEmpty(), companion.version, switch.pending(call.profile()))
        }
    }

    get("/jobs") { call.answer { companion.store?.jobs(100).orEmpty() } }

    // ---- Undo ----
    post("/edits/undo") { call.edit { companion.sync.edit(call.receive<UndoRequest>().ops, "Undo") } }

    // ---- Channels ----
    get("/channels") {
        val q = call.request.queryParameters
        call.answer {
            channels.page(
                call.profile(),
                ChannelFilter(
                    query = q["q"]?.takeIf { it.isNotBlank() }, sourceId = q["source"]?.takeIf { it.isNotBlank() }, group = q["group"]?.takeIf { it.isNotBlank() },
                    favorites = q["favorites"] == "true", playlistId = q["playlist"]?.takeIf { it.isNotBlank() }, hidden = q["hidden"]?.takeIf { it.isNotBlank() },
                    customized = q["customized"] == "true", guide = q["guide"]?.takeIf { it.isNotBlank() },
                ),
                offset = q["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100,
                now = now(),
                sort = q["sort"]?.let { runCatching { ChannelColumn.valueOf(it.uppercase()) }.getOrNull() },
                descending = q["dir"] == "desc",
            )
        }
    }

    get("/channels/{streamId}") {
        val row = channels.channel(call.profile(), call.parameters["streamId"]!!, now())
        if (row == null) call.respondText("No such channel", status = HttpStatusCode.NotFound) else call.respond(row)
    }

    post("/channels/edit") {
        call.edit {
            val e = call.receive<ChannelEdit>()
            val profiles = targets(e.profiles)
            when (e.action) {
                "favorite" -> edits.setFavorite(e.streamIds, profiles, true)
                "unfavorite" -> edits.setFavorite(e.streamIds, profiles, false)
                "hide" -> edits.channelFields(e.streamIds, profiles, fields("hidden" to true))
                "unhide" -> edits.channelFields(e.streamIds, profiles, fields("hidden" to false))
                "reset" -> edits.resetChannels(e.streamIds, profiles)
                "addToPlaylist" -> edits.addToPlaylist(e.playlistId ?: throw SyncRefused(400, "Choose a playlist."), e.streamIds, profiles.first())
                "removeFromPlaylist" -> edits.removeStreamsFromPlaylist(e.playlistId ?: throw SyncRefused(400, "Choose a playlist."), e.streamIds, profiles.first())
                "set" -> {
                    val names = mapOf("customName" to "custom_name", "customLogo" to "custom_logo", "customNumber" to "custom_number", "voiceNames" to "voice_names")
                    val f = e.values.mapNotNull { (k, v) ->
                        names[k]?.let { field ->
                            // Voice names in the app's own format: trimmed, deduplicated, comma-separated.
                            field to if (field == "voice_names") (cleanValue(v) as? JsonPrimitive)?.content?.let { com.signal.data.VoiceNames.format(com.signal.data.VoiceNames.parse(it)) }?.let(::JsonPrimitive) ?: JsonNull
                            else cleanValue(v)
                        }
                    }.toMap()
                    if (f.isEmpty()) throw SyncRefused(400, "Nothing to change.")
                    edits.channelFields(e.streamIds, profiles, JsonObject(f))
                }
                else -> throw SyncRefused(400, "Unknown action ${e.action}")
            }
        }
    }

    // ---- Favorites & playlists ----
    get("/lists") {
        call.answer {
            val view = channels.view(call.profile())
            ListsView(
                favorites = channels.favoriteRows(view, now()),
                playlists = view.playlists.map { p -> PlaylistView(p.id, p.name, channels.playlistRows(view, p, now()), p.sort?.name) },
                favoritesSort = view.favoritesSort?.name,
            )
        }
    }
    post("/favorites/{uc}/move") {
        val m = call.receive<MoveRequest>()
        // A move in a sorted list keeps what's on screen as the new manual order.
        val view = channels.view(m.profile)
        val shown = view.favoritesSort?.let { channels.favoriteRows(view, now()).mapNotNull { it.userChannelId } }
        call.edit { edits.moveFavorite(call.parameters["uc"]!!, m.toIndex, m.profile, shown) }
    }
    post("/favorites/{uc}/remove") { val t = call.receive<ProfileTargets>(); call.edit { edits.removeFavorite(call.parameters["uc"]!!, targets(t.profiles)) } }
    post("/playlists") {
        val r = call.receive<NameRequest>()
        call.edit { edits.createPlaylist(r.name.trim().ifEmpty { throw SyncRefused(400, "Give the playlist a name.") }.take(100), targets(r.profiles)) }
    }
    put("/playlists/{id}") {
        val r = call.receive<NameRequest>()
        call.edit { edits.renamePlaylist(call.parameters["id"]!!, call.profile(), r.name.trim().ifEmpty { throw SyncRefused(400, "Give the playlist a name.") }.take(100)) }
    }
    delete("/playlists/{id}") { call.edit { edits.deletePlaylist(call.parameters["id"]!!, call.profile()) } }
    post("/playlists/{id}/move") { val m = call.receive<MoveRequest>(); call.edit { edits.movePlaylist(call.parameters["id"]!!, m.toIndex, m.profile) } }
    post("/playlists/{id}/add") { val r = call.receive<PlaylistItems>(); call.edit { edits.addToPlaylist(call.parameters["id"]!!, r.streamIds, r.profile) } }
    post("/playlists/{id}/remove") { val r = call.receive<PlaylistItems>(); call.edit { edits.removeFromPlaylist(call.parameters["id"]!!, r.userChannelIds, r.profile) } }
    put("/playlists/{id}/sort") {
        val r = call.receive<SortRequest>()
        val sort = r.sort?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() ?: throw SyncRefused(400, "Unknown sort.") }
        call.edit { edits.setPlaylistSort(call.parameters["id"]!!, call.profile(), sort) }
    }
    post("/playlists/{id}/items/{uc}/move") {
        val m = call.receive<MoveRequest>()
        // As with Favorites: a move in a sorted playlist keeps what's on screen as the new manual order.
        val view = channels.view(m.profile)
        val shown = view.playlists.firstOrNull { it.id == call.parameters["id"] }?.takeIf { it.sort != null }
            ?.let { p -> channels.playlistRows(view, p, now()).mapNotNull { it.userChannelId } }
        call.edit { edits.movePlaylistItem(call.parameters["id"]!!, call.parameters["uc"]!!, m.toIndex, m.profile, shown) }
    }

    // ---- Groups ----
    get("/groups") { call.answer { groupsView(companion, channels.view(call.profile())) } }
    post("/groups/edit") {
        val g = call.receive<GroupEdit>()
        call.edit {
            val f = buildMap<String, JsonElement> {
                g.hidden?.let { put("hidden", JsonPrimitive(it)) }
                if (g.clearName) put("custom_name", JsonNull) else g.name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("custom_name", JsonPrimitive(it.take(100))) }
                g.sort?.let { put("channel_sort", if (it == "DEFAULT") JsonNull else JsonPrimitive(ChannelSort.valueOf(it).name)) }
            }
            if (f.isEmpty()) throw SyncRefused(400, "Nothing to change.")
            edits.groupFields(g.sourceId, g.group, targets(g.profiles), JsonObject(f))
        }
    }
    post("/groups/move") {
        val m = call.receive<GroupMove>()
        call.edit {
            val order = groupsView(companion, channels.view(m.profile)).groups.filter { !it.hidden }.map { it.sourceId to it.name }
            edits.moveGroup(order, m.sourceId, m.group, m.toIndex, m.profile)
        }
    }

    // ---- Movies & series categories: household-wide, since hidden ones' titles are dropped ----
    get("/vod/categories") { call.answer { vodCategoriesView(companion, channels.view(call.profile())) } }
    post("/vod/categories/edit") {
        val r = call.receive<VodCategoryEdit>()
        call.edit {
            val old = VodCategoryFilter.parse(channels.view(SyncService.MAIN).householdSettings[Settings.Key.VOD_CATEGORIES.id])
            var f = old
            r.hidden?.let { f = f.withHidden(r.name, it) }
            if (r.clearName) f = f.withName(r.name, null) else r.customName?.let { f = f.withName(r.name, it.take(100)) }
            if (f == old) throw SyncRefused(400, "Nothing to change.")
            edits.putSetting(SyncScope.HOUSEHOLD, Settings.Key.VOD_CATEGORIES.id, f.toJson(), listOf(SyncService.MAIN))
        }
    }
    post("/vod/categories/sort") {
        val r = call.receive<VodCategorySort>()
        val sort = GroupSort.entries.firstOrNull { it.name == r.sort } ?: throw SyncRefused(400, "Unknown order.")
        call.edit {
            val old = VodCategoryFilter.parse(channels.view(SyncService.MAIN).householdSettings[Settings.Key.VOD_CATEGORIES.id])
            val f = old.withSort(sort)
            if (f == old) throw SyncRefused(400, "Nothing to change.")
            edits.putSetting(SyncScope.HOUSEHOLD, Settings.Key.VOD_CATEGORIES.id, f.toJson(), listOf(SyncService.MAIN))
        }
    }
    post("/vod/categories/move") {
        val m = call.receive<VodCategoryMove>()
        call.edit {
            val view = channels.view(m.profile)
            val old = VodCategoryFilter.parse(view.householdSettings[Settings.Key.VOD_CATEGORIES.id])
            // Positions count among the kind's visible categories, as the TVs show them.
            val shown = vodCategoriesView(companion, view).categories.filter { it.kind == m.kind && !it.hidden }.map { it.name }
            val f = old.withMove(m.kind, shown, m.name, m.toIndex)
            if (f == old) throw SyncRefused(400, "Nothing to change.")
            edits.putSetting(SyncScope.HOUSEHOLD, Settings.Key.VOD_CATEGORIES.id, f.toJson(), listOf(SyncService.MAIN))
        }
    }

    // ---- Guide mapping ----
    post("/mapping/next") {
        val r = call.receive<QueueRequest>()
        call.answer {
            val view = channels.view(r.profile)
            val skip = r.skip.toSet()
            val queue = channels.unmapped(view).filter { it.id !in skip }
            val next = queue.firstOrNull()
            val row = next?.let { channels.channel(r.profile, it.id, now()) }
            QueueView(queue.size, row, next?.let { companion.guideReader.suggest(it.rawName, it.tvgId, view.epgSources, now()) }.orEmpty())
        }
    }
    get("/mapping/search") {
        call.answer {
            val view = channels.view(SyncService.MAIN)
            companion.guideReader.search(call.request.queryParameters["q"].orEmpty(), view.epgSources, now())
        }
    }
    post("/mapping") { val r = call.receive<MappingRequest>(); call.edit { edits.setMapping(r.streamId, r.epgSourceId, r.guideChannelId) } }
    delete("/mapping/{uc}") { call.edit { edits.resetMapping(call.parameters["uc"]!!) } }
    get("/mapping/manual") {
        call.answer {
            val view = channels.view(SyncService.MAIN)
            val streams = companion.catalog.streams.associateBy { it.id }
            view.mappings.map { (uc, m) ->
                val name = view.channelProfiles[uc]?.str("custom_name")
                    ?: view.streamsByUserChannel[uc].orEmpty().firstNotNullOfOrNull { streams[it.streamId]?.displayName } ?: uc
                ManualMapping(uc, name, m.epgSourceId, view.epgSources.firstOrNull { it.id == m.epgSourceId }?.name ?: m.epgSourceId,
                    m.guideChannelId, m.guideChannelId?.let { companion.guideReader.displayName(m.epgSourceId, it) })
            }.sortedBy { it.channelName.lowercase() }
        }
    }

    // ---- Provider switch (PLAN §4.5) ----
    get("/switch") {
        call.answer {
            val target = call.request.queryParameters["source"] ?: throw SyncRefused(400, "Choose the new source.")
            switch.view(target, call.profile(), now())
        }
    }
    // A channel's linked streams and the preferred one (PLAN §4.3.18 → Channels → Row detail drawer).
    get("/links/{uc}") {
        call.answer {
            val view = channels.view(SyncService.MAIN)
            val byId = companion.catalog.streams.associateBy { it.id }
            val names = view.sources.associate { it.first.id to it.first }
            view.streamsByUserChannel[call.parameters["uc"]!!].orEmpty().map { l ->
                val s = byId[l.streamId]
                LinkView(l.streamId, s?.displayName ?: "Gone from its source", s?.groupName.orEmpty(), s?.let { names[it.sourceId]?.name }.orEmpty(), l.preferred, s != null && names[s.sourceId]?.enabled == true)
            }
        }
    }
    post("/links/{uc}/preferred") {
        val r = call.receive<PreferRequest>()
        call.edit {
            val uc = call.parameters["uc"]!!
            val links = channels.view(SyncService.MAIN).streamsByUserChannel[uc].orEmpty()
            if (links.none { it.streamId == r.streamId }) throw SyncRefused(404, "That stream isn't linked to this channel.")
            companion.sync.edit(links.map { l -> AdminOp(SyncType.CHANNEL_LINK, "$uc|${l.streamId}", SyncScope.HOUSEHOLD, fields = fields("preferred" to (l.streamId == r.streamId))) }, "Stream picker")
        }
    }
    post("/switch/accept") { val r = call.receive<AcceptRequest>(); call.edit { switch.accept(r.links) } }
    post("/switch/reject") { val r = call.receive<RejectRequest>(); call.edit { switch.reject(r) } }
    post("/switch/groups") { val r = call.receive<GroupCarry>(); call.edit { switch.carryGroup(r) } }
    post("/switch/groups/all") { val r = call.receive<GroupCarryAll>(); call.edit { switch.carryAllGroups(r) } }

    // ---- Reminders ----
    get("/reminders") {
        call.answer {
            val view = channels.view(call.profile())
            val names = channels.rowsFor(view, view.reminders.mapNotNull { it.fields.str("user_channel_id") }.distinct(), now()).associateBy { it.userChannelId }
            view.reminders.map { r ->
                ReminderView(r.id, r.fields.str("title").orEmpty(), r.fields.long("start_ms") ?: 0, r.fields.long("stop_ms") ?: 0,
                    names[r.fields.str("user_channel_id")]?.name ?: "Unavailable channel")
            }.sortedBy { it.start }
        }
    }
    delete("/reminders/{id}") { call.edit { edits.deleteReminder(call.parameters["id"]!!, call.profile()) } }

    // ---- Library (PLAN §4.3.11) ----
    get("/library") {
        call.answer {
            val view = channels.view(call.profile())
            val items = companion.vodItems()
            val ids = items.map { it.id }.toSet()
            // A TMDB key is available when any source has that title; the companion's matches count too.
            val tmdb = runCatching { companion.vod.index().tmdbIds }.getOrDefault(emptyMap())
            val tmdbKeys = (items.mapNotNull { i -> (tmdb[i.id] ?: i.tmdbId)?.let { com.signal.data.ItemKeys.of(i.copy(tmdbId = it)) } }).toSet()
            view.library.map { l ->
                val vodItemId = l.fields.str("vod_item_id").orEmpty()
                LibraryRow(
                    l.id, l.fields.str("kind") ?: "MOVIE", l.fields.str("title").orEmpty(), l.fields.long("year")?.toInt(), l.fields.long("added_at") ?: 0,
                    available = l.id in tmdbKeys || l.id in ids || vodItemId in ids,
                )
            }.sortedByDescending { it.addedAt }
        }
    }
    post("/library") {
        val r = call.receive<LibraryAdd>()
        call.edit {
            val items = r.itemIds.mapNotNull { companion.catalogItem(it) }
            if (items.isEmpty()) throw SyncRefused(404, "Those titles aren't in the catalog.")
            val tmdb = runCatching { companion.vod.index().tmdbIds }.getOrDefault(emptyMap())
            edits.addToLibrary(items, tmdb, targets(r.profiles))
        }
    }
    delete("/library/{key}") { call.edit { edits.removeFromLibrary(listOf(call.parameters["key"]!!), call.profile()) } }

    // ---- Settings ----
    get("/settings") {
        call.answer {
            val view = channels.view(call.profile())
            SettingsView(view.householdSettings, view.profileSettings, companion.settings.view())
        }
    }
    put("/settings") { val r = call.receive<SettingRequest>(); call.edit { edits.putSetting(r.scope, r.key, r.value, targets(r.profiles)) } }
    post("/cleanup/preview") { val r = call.receive<CleanupPreviewRequest>(); call.answer {
            if (r.target == NameCleanup.Target.VOD) vodCleanupPreview(companion, r.rules) else channels.cleanupPreview(call.profile(), r.rules)
        } }
    put("/settings/companion") {
        val v = call.receive<CompanionSettingsView>()
        val error = companion.settings.save(v)
        if (error == null) call.respond(HttpStatusCode.NoContent) else call.respondText(error, status = HttpStatusCode.BadRequest)
    }

    // ---- Devices: own profile, device settings, Copy to… ----
    post("/devices/{id}/own-profile") {
        call.answer {
            val device = companion.devices?.all()?.firstOrNull { it.id == call.parameters["id"] } ?: throw SyncRefused(404, "No such device")
            companion.sync.ownProfile(device)
        }
    }
    get("/devices/{id}/settings") {
        call.answer {
            val device = companion.devices?.all()?.firstOrNull { it.id == call.parameters["id"] } ?: throw SyncRefused(404, "No such device")
            val settings = companion.sync.liveItems().filter { it.scope == SyncScope.DEVICE && it.owner == device.id && it.type == SyncType.SETTING }
                .mapNotNull { s -> s.fields.str("value")?.let { s.id to it } }.toMap()
            DeviceSettingsView(device, settings)
        }
    }
    put("/devices/{id}/settings") { val r = call.receive<DeviceSettingRequest>(); call.edit { edits.putDeviceSetting(call.parameters["id"]!!, r.key, r.value) } }
    post("/profiles/copy") {
        val r = call.receive<CopyRequest>()
        call.edit {
            val types = r.sections.flatMap { SECTIONS[it] ?: throw SyncRefused(400, "Unknown section $it") }.toSet()
            companion.sync.copySection(r.from, r.to, types, r.replace)
        }
    }

    // ---- Backups ----
    get("/snapshots/{id}/summary") { call.answer { companion.sync.snapshotSummary(call.parameters["id"]!!.toLong()) } }
    post("/snapshots/{id}/restore-part") {
        val r = call.receive<RestoreRequest>()
        call.answer {
            val part = when (r.part) {
                "profile" -> RestorePart.Profile(r.id ?: throw SyncRefused(400, "Choose a profile."))
                "device" -> RestorePart.Device(r.id ?: throw SyncRefused(400, "Choose a device."))
                else -> RestorePart.All
            }
            mapOf("changes" to companion.sync.restore(call.parameters["id"]!!.toLong(), part))
        }
    }
    post("/sync/import") { val r = call.receive<ImportRequest>(); call.edit { companion.sync.importFile(r.file, r.profile) } }

    // ---- Sources and EPG sources ----
    get("/sources") {
        call.answer {
            val view = channels.view(SyncService.MAIN)
            val meta = companion.guide.meta
            SourcesView(
                sourceViews(companion, view),
                view.epgSources.map { e ->
                    val o = meta?.outcomes?.get(e.id)
                    EpgSourceView(e.id, e.name, e.kind.name, e.url, e.sourceId, e.enabled, e.timeShiftMinutes, e.id == view.defaultEpgId, o?.channels ?: 0, o?.programs ?: 0, o?.error, o?.refreshedAt, e.enabled && companion.guide.downloading(e.id))
                },
                companion.guide.running,
            )
        }
    }
    get("/sources/{id}/password") {
        call.answer {
            val s = channels.view(SyncService.MAIN).sources.firstOrNull { it.first.id == call.parameters["id"] } ?: throw SyncRefused(404, "No such source")
            mapOf("password" to s.first.password)
        }
    }
    post("/sources") { val f = call.receive<SourceForm>(); call.edit { edits.saveSource(f.toSource(newId()), isNew = true) } }
    put("/sources/{id}") { val f = call.receive<SourceForm>(); call.edit { edits.saveSource(f.toSource(call.parameters["id"]!!), isNew = false) } }
    post("/sources/{id}/enabled") { val r = call.receive<EnabledRequest>(); call.edit { edits.setSourceEnabled(call.parameters["id"]!!, r.enabled) } }
    // Sources per profile (PLAN §9.1): [ProfileTargets.profiles] are the profiles that show it; the rest leave it out.
    post("/sources/{id}/profiles") {
        val r = call.receive<ProfileTargets>()
        call.edit { edits.setSourceProfiles(call.parameters["id"]!!, r.profiles, companion.sync.status().profiles.map { it.id }) }
    }
    delete("/sources/{id}") { call.edit { edits.deleteSource(call.parameters["id"]!!) } }
    post("/sources/test") {
        val f = call.receive<SourceForm>()
        val result = companion.catalog.test(f.toSource("test"))
        result.fold({ call.respond(mapOf("result" to it)) }, { call.respondText(it.message ?: "Failed", status = HttpStatusCode.BadGateway) })
    }
    post("/sources/{id}/refresh") {
        val id = call.parameters["id"]!!
        companion.scope.launch { companion.catalog.refresh(id) }
        call.respond(HttpStatusCode.Accepted)
    }
    post("/epg-sources") {
        val f = call.receive<EpgForm>()
        call.edit { edits.addXmltv(f.name.orEmpty().trim(), f.url?.trim()?.takeIf { it.isNotEmpty() } ?: throw SyncRefused(400, "Enter the guide's URL.")) }
    }
    put("/epg-sources/{id}") {
        val f = call.receive<EpgForm>()
        call.edit {
            val values = buildMap<String, Any?> {
                f.name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
                f.url?.trim()?.let { put("url", it) }
                f.enabled?.let { put("enabled", it) }
                f.timeShiftMinutes?.let { put("time_shift_minutes", it.coerceIn(-24 * 60, 24 * 60)) }
            }
            if (values.isEmpty()) throw SyncRefused(400, "Nothing to change.")
            edits.updateEpgSource(call.parameters["id"]!!, fields(*values.toList().toTypedArray()))
        }
    }
    post("/epg-sources/{id}/move") { val m = call.receive<MoveRequest>(); call.edit { edits.moveEpgSource(call.parameters["id"]!!, m.toIndex) } }
    delete("/epg-sources/{id}") { call.edit { edits.deleteEpgSource(call.parameters["id"]!!) } }
    put("/epg-sources/default") {
        val r = call.receive<DefaultRequest>()
        call.edit { edits.putSetting(SyncScope.HOUSEHOLD, com.signal.data.Settings.Key.DEFAULT_EPG_SOURCE.id, r.id, listOf(SyncService.MAIN)) }
    }
}

/** Copy to… sections (PLAN §9.1) and the item types each covers. */
private val SECTIONS = mapOf(
    "favorites" to listOf(SyncType.FAVORITE),
    "playlists" to listOf(SyncType.PLAYLIST, SyncType.PLAYLIST_ITEM),
    "channels" to listOf(SyncType.CHANNEL_PROFILE),
    "groups" to listOf(SyncType.GROUP_OVERRIDE),
    "reminders" to listOf(SyncType.REMINDER),
    "settings" to listOf(SyncType.SETTING),
)

/** A JSON value from the browser, kept to strings, numbers and null. */
private fun cleanValue(v: JsonElement): JsonElement = when {
    v is JsonNull -> JsonNull
    v is JsonPrimitive && v.isString -> v.content.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it.take(500)) } ?: JsonNull
    v is JsonPrimitive -> v.content.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
    else -> JsonNull
}

private fun SourceForm.toSource(id: String): Source {
    val k = runCatching { SourceKind.valueOf(kind) }.getOrNull() ?: throw SyncRefused(400, "Choose M3U or Xtream Codes.")
    if (name.isBlank()) throw SyncRefused(400, "Give the source a name.")
    if (url.isBlank()) throw SyncRefused(400, "Enter the source's URL.")
    return Source(
        id = id, name = name.trim().take(100), kind = k, url = url.trim(), username = username.trim(), password = password,
        userAgent = userAgent?.trim()?.ifEmpty { null }, referrer = referrer?.trim()?.ifEmpty { null }, enabled = enabled,
        liveFormat = runCatching { LiveFormat.valueOf(liveFormat) }.getOrDefault(LiveFormat.TS), connectionLimit = if (k == SourceKind.XTREAM) connectionLimit.coerceIn(0, 50) else connectionLimit.takeIf { it > 0 }?.coerceAtMost(50) ?: 2,
    )
}

private suspend fun sourceViews(companion: Companion, view: AdminView): List<SourceView> {
    val states = companion.catalog.sources().associateBy { it.id }
    return view.sources.map { (s, _) ->
        val st = states[s.id]
        SourceView(
            s.id, s.name, s.kind.name, s.url, s.username, s.password.isNotEmpty(), s.userAgent, s.referrer, s.enabled, s.liveFormat.name, s.connectionLimit,
            st?.lastRefreshedAt ?: 0, st?.lastError, st?.streams ?: 0, companion.catalog.refreshing == s.id,
            st?.providerConnections, st?.expiresAt, view.hiddenSourcesByProfile.filterValues { s.id in it }.keys.sorted(),
        )
    }
}

internal suspend fun vodCategoriesView(companion: Companion, view: AdminView): VodCategoriesView {
    val filter = VodCategoryFilter.parse(view.householdSettings[Settings.Key.VOD_CATEGORIES.id])
    val cleanup = NameCleanup.parse(view.profileSettings[Settings.Key.VOD_NAME_CLEANUP.id])
    val sourceNames = view.sources.associate { it.first.id to it.first.name }
    val rows = companion.catalog.vodCategories().map { c ->
        val custom = filter.renamed(c.name)
        VodCategoryView(
            c.source_id, sourceNames[c.source_id] ?: c.source_id, c.kind, c.category_id, c.name,
            custom ?: if (cleanup.groups) cleanup.apply(c.name) else c.name, c.item_count.toInt(), filter.hides(c.name), custom,
        )
    }
    val ordered = listOf("MOVIE", "SERIES").flatMap { kind -> filter.sorted(kind, rows.filter { it.kind == kind }, { it.name }, { it.title }) }
    return VodCategoriesView(filter.categorySort.name, ordered)
}

/** Provider groups in the order the profile shows them (PLAN §4.3.2 → Groups), hidden ones included. */
private fun groupsView(companion: Companion, view: AdminView): GroupsView {
    data class G(val sourceId: String, val name: String, val count: Int, val first: Int)
    val groups = companion.catalog.streams.filter { view.sourceShown(it.sourceId) }
        .groupBy { it.sourceId to it.groupName }
        .map { (k, list) -> G(k.first, k.second, list.size, list.minOf { it.position }) }
        .sortedWith(compareBy({ view.sourceOrder[it.sourceId] }, { it.first }))
    val rows = groups.map { g ->
        val o = view.groupOverrides[view.groupKey(g.sourceId, g.name)]
        Triple(
            GroupView(
                g.sourceId, view.sources.first { it.first.id == g.sourceId }.first.name, g.name, o?.str("custom_name") ?: g.name, g.count,
                o?.get("hidden")?.let { (it as? JsonPrimitive)?.content == "true" || (it as? JsonPrimitive)?.content == "1" } == true,
                o?.str("channel_sort"), o?.str("custom_name"),
            ),
            o?.str("sort_key"),
            g.first,
        )
    }
    val sorted = when (view.groupSort) {
        GroupSort.PROVIDER -> rows
        GroupSort.A_Z -> rows.sortedBy { it.first.title.lowercase() }
        GroupSort.Z_A -> rows.sortedByDescending { it.first.title.lowercase() }
        GroupSort.MANUAL -> rows.sortedWith(compareBy(nullsLast()) { it.second })
    }.map { it.first }
    return GroupsView(view.groupSort.name, view.channelSort.name, sorted)
}

@Serializable data class LinkView(val streamId: String, val name: String, val group: String, val sourceName: String, val preferred: Boolean, val playable: Boolean)
@Serializable data class PreferRequest(val streamId: String)

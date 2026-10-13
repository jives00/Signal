package com.signal.server

import com.signal.data.ChannelRepository
import com.signal.data.FractionalIndex
import com.signal.data.Settings
import com.signal.data.newId
import com.signal.model.EpgKind
import com.signal.model.EpgSource
import com.signal.model.GroupSort
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.sync.SyncFields
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.sync.fields
import com.signal.sync.str
import kotlinx.serialization.json.JsonObject

/**
 * Web admin edits (PLAN §4.3.18 → Saving) as sync items, so they reach the devices the same way a
 * device's own edits do. Profile-scope edits go to every profile in "Apply to" (PLAN §9.1). The
 * rules match the app's repositories: a user channel is made on first need, ordered lists use
 * fractional keys, and Xtream and Demo sources bring their own EPG source.
 */
class AdminEdits(private val companion: Companion) {
    private val sync get() = companion.sync
    private fun view(profile: String) = AdminView(sync.liveItems(), profile)
    private fun now() = System.currentTimeMillis()

    /** Collects ops, with the user channels made so far, so one edit makes each at most once. */
    private class Batch(val household: AdminView) {
        val ops = ArrayList<AdminOp>()
        private val made = HashMap<String, String>()

        fun userChannel(streamId: String, now: Long): String = household.userChannelByStream[streamId] ?: made.getOrPut(streamId) {
            val uc = ChannelRepository.stableUserChannelId(streamId)
            ops += AdminOp(SyncType.USER_CHANNEL, uc, SyncScope.HOUSEHOLD, fields = fields("created_at" to now))
            ops += AdminOp(
                SyncType.CHANNEL_LINK, "$uc|$streamId", SyncScope.HOUSEHOLD,
                fields = fields("user_channel_id" to uc, "stream_id" to streamId, "kind" to "manual", "preferred" to true),
            )
            uc
        }
    }

    private suspend fun run(batch: Batch, by: String = "the web admin"): EditResult = sync.edit(batch.ops, by)

    // ---- Channels (profile scope) ------------------------------------------------------------------

    /** Rename, logo, number, hide: only the fields given (null clears one). */
    suspend fun channelFields(streamIds: List<String>, profiles: List<String>, f: JsonObject): EditResult {
        val batch = Batch(view(profiles.first()))
        for (streamId in streamIds) {
            val uc = batch.userChannel(streamId, now())
            for (p in profiles) batch.ops += AdminOp(SyncType.CHANNEL_PROFILE, uc, SyncScope.PROFILE, p, f)
        }
        return run(batch)
    }

    suspend fun resetChannels(streamIds: List<String>, profiles: List<String>) =
        channelFields(streamIds, profiles, fields("custom_name" to null, "custom_logo" to null, "custom_number" to null))

    suspend fun setFavorite(streamIds: List<String>, profiles: List<String>, favorite: Boolean): EditResult {
        val batch = Batch(view(profiles.first()))
        for (p in profiles) {
            val v = view(p)
            var last = v.favorites.values.maxOrNull()
            for (streamId in streamIds) {
                val uc = batch.userChannel(streamId, now())
                if (favorite) {
                    if (uc in v.favorites) continue
                    last = FractionalIndex.after(last)
                    batch.ops += AdminOp(SyncType.FAVORITE, uc, SyncScope.PROFILE, p, fields("sort_key" to last))
                } else {
                    batch.ops += AdminOp(SyncType.FAVORITE, uc, SyncScope.PROFILE, p, deleted = true)
                }
            }
        }
        return run(batch)
    }

    suspend fun removeFavorite(userChannelId: String, profiles: List<String>): EditResult {
        val batch = Batch(view(profiles.first()))
        profiles.forEach { batch.ops += AdminOp(SyncType.FAVORITE, userChannelId, SyncScope.PROFILE, it, deleted = true) }
        return run(batch)
    }

    /** Drag to reorder: only the moved item's key changes (PLAN §5.4). */
    suspend fun moveFavorite(userChannelId: String, toIndex: Int, profile: String): EditResult {
        val v = view(profile)
        val batch = Batch(v)
        val keys = v.favorites.entries.sortedBy { it.value }.map { it.key to it.value }
        ChannelRepository.newKeyForMove(keys, userChannelId, toIndex)?.let {
            batch.ops += AdminOp(SyncType.FAVORITE, userChannelId, SyncScope.PROFILE, profile, fields("sort_key" to it))
        }
        return run(batch)
    }

    // ---- Playlists (each belongs to one profile) ------------------------------------------------------

    suspend fun createPlaylist(name: String, profiles: List<String>): EditResult {
        val batch = Batch(view(profiles.first()))
        for (p in profiles) {
            val last = view(p).playlists.lastOrNull()?.sortKey
            batch.ops += AdminOp(SyncType.PLAYLIST, newId(), SyncScope.PROFILE, p, fields("name" to name, "sort_key" to FractionalIndex.after(last)))
        }
        return run(batch)
    }

    suspend fun renamePlaylist(id: String, profile: String, name: String): EditResult {
        val batch = Batch(view(profile))
        batch.ops += AdminOp(SyncType.PLAYLIST, id, SyncScope.PROFILE, profile, fields("name" to name))
        return run(batch)
    }

    suspend fun deletePlaylist(id: String, profile: String): EditResult {
        val v = view(profile)
        val batch = Batch(v)
        v.playlistItems[id].orEmpty().forEach { (uc, _) -> batch.ops += AdminOp(SyncType.PLAYLIST_ITEM, "$id|$uc", SyncScope.PROFILE, profile, deleted = true) }
        batch.ops += AdminOp(SyncType.PLAYLIST, id, SyncScope.PROFILE, profile, deleted = true)
        return run(batch)
    }

    suspend fun movePlaylist(id: String, toIndex: Int, profile: String): EditResult {
        val v = view(profile)
        val batch = Batch(v)
        ChannelRepository.newKeyForMove(v.playlists.map { it.id to it.sortKey }, id, toIndex)?.let {
            batch.ops += AdminOp(SyncType.PLAYLIST, id, SyncScope.PROFILE, profile, fields("sort_key" to it))
        }
        return run(batch)
    }

    suspend fun addToPlaylist(playlistId: String, streamIds: List<String>, profile: String): EditResult {
        val v = view(profile)
        val batch = Batch(v)
        val present = v.playlistItems[playlistId].orEmpty().map { it.first }.toSet()
        var last = v.playlistItems[playlistId]?.lastOrNull()?.second
        for (streamId in streamIds) {
            val uc = batch.userChannel(streamId, now())
            if (uc in present) continue
            last = FractionalIndex.after(last)
            batch.ops += AdminOp(SyncType.PLAYLIST_ITEM, "$playlistId|$uc", SyncScope.PROFILE, profile, fields("playlist_id" to playlistId, "user_channel_id" to uc, "sort_key" to last))
        }
        return run(batch)
    }

    suspend fun removeFromPlaylist(playlistId: String, userChannelIds: List<String>, profile: String): EditResult {
        val batch = Batch(view(profile))
        userChannelIds.forEach { batch.ops += AdminOp(SyncType.PLAYLIST_ITEM, "$playlistId|$it", SyncScope.PROFILE, profile, deleted = true) }
        return run(batch)
    }

    /** Bulk "remove from a playlist" by stream, from the Channels page. */
    suspend fun removeStreamsFromPlaylist(playlistId: String, streamIds: List<String>, profile: String): EditResult {
        val v = view(profile)
        return removeFromPlaylist(playlistId, streamIds.mapNotNull { v.userChannelByStream[it] }, profile)
    }

    suspend fun movePlaylistItem(playlistId: String, userChannelId: String, toIndex: Int, profile: String): EditResult {
        val v = view(profile)
        val batch = Batch(v)
        ChannelRepository.newKeyForMove(v.playlistItems[playlistId].orEmpty(), userChannelId, toIndex)?.let {
            batch.ops += AdminOp(SyncType.PLAYLIST_ITEM, "$playlistId|$userChannelId", SyncScope.PROFILE, profile, fields("sort_key" to it))
        }
        return run(batch)
    }

    // ---- Groups (profile scope) -----------------------------------------------------------------------

    private fun groupOp(sourceId: String, group: String, profile: String, f: JsonObject) = AdminOp(
        SyncType.GROUP_OVERRIDE, "$sourceId|$group", SyncScope.PROFILE, profile, JsonObject(fields("source_id" to sourceId, "group_name" to group) + f),
    )

    suspend fun groupFields(sourceId: String, group: String, profiles: List<String>, f: JsonObject): EditResult {
        val batch = Batch(view(profiles.first()))
        profiles.forEach { batch.ops += groupOp(sourceId, group, it, f) }
        return run(batch)
    }

    /**
     * Move group (PLAN §4.2 → Group menu), as on the TV: the first move switches the profile's group
     * sort to Manual and seeds keys from the order shown, so nothing jumps.
     */
    suspend fun moveGroup(order: List<Pair<String, String>>, sourceId: String, group: String, toIndex: Int, profile: String): EditResult {
        val v = view(profile)
        val batch = Batch(v)
        val keys = HashMap<Pair<String, String>, String>()
        if (v.groupSort != GroupSort.MANUAL) {
            FractionalIndex.sequence(order.size).forEachIndexed { i, k -> keys[order[i]] = k; batch.ops += groupOp(order[i].first, order[i].second, profile, fields("sort_key" to k)) }
            batch.ops += AdminOp(SyncType.SETTING, Settings.Key.GROUP_SORT.id, SyncScope.PROFILE, profile, fields("value" to GroupSort.MANUAL.name))
        } else {
            order.forEach { g -> keys[g] = v.groupOverrides[v.groupKey(g.first, g.second)]?.str("sort_key") ?: "" }
        }
        val list = order.map { "${it.first}\u0000${it.second}" to keys.getValue(it) }
        ChannelRepository.newKeyForMove(list, "$sourceId\u0000$group", toIndex)?.let { batch.ops += groupOp(sourceId, group, profile, fields("sort_key" to it)) }
        return run(batch)
    }

    // ---- Guide mappings (household) -------------------------------------------------------------------

    suspend fun setMapping(streamId: String, epgSourceId: String, guideChannelId: String?): EditResult {
        val batch = Batch(view(SyncService.MAIN))
        val uc = batch.userChannel(streamId, now())
        batch.ops += AdminOp(SyncType.GUIDE_MAPPING, uc, SyncScope.HOUSEHOLD, fields = fields("epg_source_id" to epgSourceId, "guide_channel_id" to guideChannelId))
        return run(batch)
    }

    suspend fun resetMapping(userChannelId: String): EditResult {
        val batch = Batch(view(SyncService.MAIN))
        batch.ops += AdminOp(SyncType.GUIDE_MAPPING, userChannelId, SyncScope.HOUSEHOLD, deleted = true)
        return run(batch)
    }

    // ---- Settings -------------------------------------------------------------------------------------

    suspend fun putSetting(scope: String, key: String, value: String?, profiles: List<String>): EditResult {
        if (key in Settings.Key.notSynced || !SETTING_KEY.matches(key)) throw SyncRefused(400, "That setting can't be changed here.")
        val batch = Batch(view(profiles.first()))
        val owners = when (scope) {
            SyncScope.HOUSEHOLD -> listOf("")
            SyncScope.PROFILE -> profiles
            else -> throw SyncRefused(400, "Device settings are changed on the device's page.")
        }
        for (o in owners) {
            batch.ops += if (value == null) AdminOp(SyncType.SETTING, key, scope, o, deleted = true) else AdminOp(SyncType.SETTING, key, scope, o, fields("value" to value))
        }
        return run(batch)
    }

    /** A device-scope setting on one device (PLAN §4.3.18 → Devices: "Changes apply remotely"). */
    suspend fun putDeviceSetting(deviceId: String, key: String, value: String?): EditResult {
        if (key in Settings.Key.notSynced || !SETTING_KEY.matches(key)) throw SyncRefused(400, "That setting can't be changed here.")
        val batch = Batch(view(SyncService.MAIN))
        batch.ops += if (value == null) AdminOp(SyncType.SETTING, key, SyncScope.DEVICE, deviceId, deleted = true)
        else AdminOp(SyncType.SETTING, key, SyncScope.DEVICE, deviceId, fields("value" to value))
        return run(batch)
    }

    // ---- Reminders --------------------------------------------------------------------------------------

    suspend fun deleteReminder(id: String, profile: String): EditResult {
        val batch = Batch(view(profile))
        batch.ops += AdminOp(SyncType.REMINDER, id, SyncScope.PROFILE, profile, deleted = true)
        return run(batch)
    }

    // ---- Library (PLAN §4.3.11: "the web admin can list it and remove items from it") -------------------

    /**
     * Adds catalog titles to the Library of each profile in "Apply to", under the same key a TV would
     * use: the TMDB ID when the title is matched, else its item ID. Titles already there are skipped.
     */
    suspend fun addToLibrary(items: List<com.signal.model.VodItem>, tmdbIds: Map<String, Long>, profiles: List<String>): EditResult {
        val batch = Batch(view(profiles.first()))
        val now = now()
        for (p in profiles) {
            val have = view(p).library.map { it.id }.toSet()
            for (item in items) {
                val key = com.signal.data.ItemKeys.of(item.copy(tmdbId = tmdbIds[item.id] ?: item.tmdbId))
                if (key in have) continue
                batch.ops += AdminOp(
                    SyncType.LIBRARY_ITEM, key, SyncScope.PROFILE, p,
                    fields(
                        "kind" to item.kind.name, "vod_item_id" to item.id, "title" to com.signal.data.VodRepository.displayName(item),
                        "year" to item.year?.toLong(), "poster_url" to item.posterUrl, "added_at" to now,
                    ),
                )
            }
        }
        return run(batch)
    }

    suspend fun removeFromLibrary(keys: List<String>, profile: String): EditResult {
        val batch = Batch(view(profile))
        for (key in keys) batch.ops += AdminOp(SyncType.LIBRARY_ITEM, key, SyncScope.PROFILE, profile, deleted = true)
        return run(batch)
    }

    // ---- Sources and EPG sources (household) ------------------------------------------------------------

    /** Adds or edits a source. An empty password on an edit keeps the stored one. */
    suspend fun saveSource(source: Source, isNew: Boolean): EditResult {
        val v = view(SyncService.MAIN)
        val batch = Batch(v)
        val existing = v.sources.firstOrNull { it.first.id == source.id }
        if (!isNew && existing == null) throw SyncRefused(404, "No such source")
        val password = source.password.ifEmpty { if (isNew) "" else existing!!.first.password }
        val f = SyncFields.source(source.copy(password = password), existing?.second ?: now())
        batch.ops += AdminOp(SyncType.SOURCE, source.id, SyncScope.HOUSEHOLD, fields = f)
        // An Xtream or Demo source brings its own guide (PLAN §4.3.15 → EPG sources), named after it as on the TV.
        val kind = when (source.kind) {
            SourceKind.XTREAM -> EpgKind.XTREAM
            SourceKind.DEMO -> EpgKind.DEMO
            SourceKind.M3U -> null
        }
        if (isNew && kind != null && v.epgSources.none { it.sourceId == source.id }) {
            val epg = EpgSource(id = "epg:${source.id}", name = "${source.name} guide", kind = kind, sourceId = source.id, position = FractionalIndex.after(v.epgSources.lastOrNull()?.position))
            batch.ops += AdminOp(SyncType.EPG_SOURCE, epg.id, SyncScope.HOUSEHOLD, fields = SyncFields.epgSource(epg, now()))
        }
        return run(batch)
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean): EditResult {
        val batch = Batch(view(SyncService.MAIN))
        batch.ops += AdminOp(SyncType.SOURCE, id, SyncScope.HOUSEHOLD, fields = fields("enabled" to enabled))
        return run(batch)
    }

    /** Removes a source with its own EPG source and the mappings to it, as the TV's Remove does. */
    suspend fun deleteSource(id: String): EditResult {
        val v = view(SyncService.MAIN)
        val batch = Batch(v)
        v.epgSources.filter { it.sourceId == id }.forEach { epg -> deleteEpgOps(v, epg.id, batch) }
        batch.ops += AdminOp(SyncType.SOURCE, id, SyncScope.HOUSEHOLD, deleted = true)
        return run(batch)
    }

    private fun deleteEpgOps(v: AdminView, epgId: String, batch: Batch) {
        v.mappings.filter { it.value.epgSourceId == epgId }.keys.forEach { uc -> batch.ops += AdminOp(SyncType.GUIDE_MAPPING, uc, SyncScope.HOUSEHOLD, deleted = true) }
        batch.ops += AdminOp(SyncType.EPG_SOURCE, epgId, SyncScope.HOUSEHOLD, deleted = true)
    }

    suspend fun addXmltv(name: String, url: String): EditResult {
        val v = view(SyncService.MAIN)
        val batch = Batch(v)
        val epg = EpgSource(id = newId(), name = name.ifBlank { "Guide" }, kind = EpgKind.XMLTV, url = url, position = FractionalIndex.after(v.epgSources.lastOrNull()?.position))
        batch.ops += AdminOp(SyncType.EPG_SOURCE, epg.id, SyncScope.HOUSEHOLD, fields = SyncFields.epgSource(epg, now()))
        return run(batch)
    }

    suspend fun updateEpgSource(id: String, f: JsonObject): EditResult {
        val batch = Batch(view(SyncService.MAIN))
        batch.ops += AdminOp(SyncType.EPG_SOURCE, id, SyncScope.HOUSEHOLD, fields = f)
        return run(batch)
    }

    suspend fun moveEpgSource(id: String, toIndex: Int): EditResult {
        val v = view(SyncService.MAIN)
        val batch = Batch(v)
        val others = v.epgSources.filter { it.id != id }
        val i = toIndex.coerceIn(0, others.size)
        batch.ops += AdminOp(SyncType.EPG_SOURCE, id, SyncScope.HOUSEHOLD, fields = fields("position" to FractionalIndex.between(others.getOrNull(i - 1)?.position, others.getOrNull(i)?.position)))
        return run(batch)
    }

    suspend fun deleteEpgSource(id: String): EditResult {
        val v = view(SyncService.MAIN)
        val batch = Batch(v)
        deleteEpgOps(v, id, batch)
        return run(batch)
    }

    companion object Keys {
        private val SETTING_KEY = Regex("[a-z0-9_]{1,64}")
    }
}

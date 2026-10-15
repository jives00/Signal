package com.signal.sync

import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import com.signal.platform.SecretBox
import kotlinx.serialization.json.JsonObject

/**
 * user.db's rows as sync items, and items written back as rows (PLAN §5.4 → the tables, §9.1 →
 * scopes). Household rows, this profile's rows and this device's settings; cached source data never.
 * Device-scope items use an empty owner here: the companion fills in the device's ID.
 *
 * Everything runs on io, inside the caller's transaction where it writes.
 */
class SyncTables(private val db: UserDatabase, private val settings: Settings, private val secrets: SecretBox) {
    private val profile get() = settings.profileId

    private fun item(type: String, scope: String, id: String, f: JsonObject) =
        SyncItem(type, id, scope, if (scope == SyncScope.PROFILE) profile else "", f)

    /** Every item on this device now. */
    fun readAll(): Map<SyncKey, SyncItem> {
        val out = ArrayList<SyncItem>()
        val h = SyncScope.HOUSEHOLD
        val p = SyncScope.PROFILE
        db.sourceQueries.all().executeAsList().forEach { s ->
            val password = if (s.password_enc.isEmpty()) "" else runCatching { secrets.decrypt(s.password_enc) }.getOrDefault("")
            out += item(
                SyncType.SOURCE, h, s.id,
                fields(
                    "name" to s.name, "kind" to s.kind, "url" to s.url, "username" to s.username, SyncFields.PASSWORD to password,
                    "user_agent" to s.user_agent, "referrer" to s.referrer, "enabled" to (s.enabled == 1L), "live_format" to s.live_format,
                    "connection_limit" to s.connection_limit, "created_at" to s.created_at,
                ),
            )
        }
        db.guideQueries.allEpgSources().executeAsList().forEach { e ->
            out += item(
                SyncType.EPG_SOURCE, h, e.id,
                fields(
                    "name" to e.name, "kind" to e.kind, "url" to e.url, "source_id" to e.source_id, "enabled" to (e.enabled == 1L),
                    "time_shift_minutes" to e.time_shift_minutes, "position" to e.position, "created_at" to e.created_at,
                ),
            )
        }
        db.guideQueries.allMappings().executeAsList().forEach { m ->
            out += item(SyncType.GUIDE_MAPPING, h, m.user_channel_id, fields("epg_source_id" to m.epg_source_id, "guide_channel_id" to m.guide_channel_id))
        }
        db.syncQueries.allUserChannels().executeAsList().forEach { u -> out += item(SyncType.USER_CHANNEL, h, u.id, fields("created_at" to u.created_at)) }
        db.syncQueries.allLinks().executeAsList().forEach { l ->
            out += item(
                SyncType.CHANNEL_LINK, h, "${l.user_channel_id}|${l.stream_id}",
                fields("user_channel_id" to l.user_channel_id, "stream_id" to l.stream_id, "kind" to l.kind, "preferred" to (l.preferred == 1L)),
            )
        }
        db.syncQueries.allChannelProfiles(profile).executeAsList().forEach { c ->
            out += item(
                SyncType.CHANNEL_PROFILE, p, c.user_channel_id,
                fields(
                    "custom_name" to c.custom_name, "custom_logo" to c.custom_logo, "custom_number" to c.custom_number, "hidden" to (c.hidden == 1L),
                    "audio_language" to c.audio_language, "subtitle" to c.subtitle, "aspect" to c.aspect, "voice_names" to c.voice_names,
                ),
            )
        }
        db.syncQueries.allFavorites(profile).executeAsList().forEach { f -> out += item(SyncType.FAVORITE, p, f.user_channel_id, fields("sort_key" to f.sort_key)) }
        db.syncQueries.allPlaylists(profile).executeAsList().forEach { pl -> out += item(SyncType.PLAYLIST, p, pl.id, fields("name" to pl.name, "sort_key" to pl.sort_key)) }
        db.syncQueries.allPlaylistItems(profile).executeAsList().forEach { pi ->
            out += item(
                SyncType.PLAYLIST_ITEM, p, "${pi.playlist_id}|${pi.user_channel_id}",
                fields("playlist_id" to pi.playlist_id, "user_channel_id" to pi.user_channel_id, "sort_key" to pi.sort_key),
            )
        }
        db.syncQueries.allGroupOverrides(profile).executeAsList().forEach { g ->
            out += item(
                SyncType.GROUP_OVERRIDE, p, "${g.source_id}|${g.group_name}",
                fields(
                    "source_id" to g.source_id, "group_name" to g.group_name, "hidden" to (g.hidden == 1L), "custom_name" to g.custom_name,
                    "channel_sort" to g.channel_sort, "sort_key" to g.sort_key,
                ),
            )
        }
        db.syncQueries.allReminders(profile).executeAsList().forEach { r ->
            out += item(
                SyncType.REMINDER, p, r.id,
                fields("user_channel_id" to r.user_channel_id, "title" to r.title, "start_ms" to r.start_ms, "stop_ms" to r.stop_ms, "created_at" to r.created_at),
            )
        }
        db.matchQueries.allAliases().executeAsList().forEach { a -> out += item(SyncType.ALIAS, h, a.id, fields("a" to a.a, "b" to a.b)) }
        db.matchQueries.allRejects().executeAsList().forEach { r ->
            out += item(SyncType.MATCH_REJECT, h, "${r.user_channel_id}|${r.stream_id}", fields("user_channel_id" to r.user_channel_id, "stream_id" to r.stream_id))
        }
        db.watchQueries.libraryFor(profile).executeAsList().forEach { l ->
            out += item(SyncType.LIBRARY_ITEM, p, l.item_key, fields("kind" to l.kind, "vod_item_id" to l.vod_item_id, "title" to l.title, "year" to l.year, "poster_url" to l.poster_url, "added_at" to l.added_at))
        }
        db.watchQueries.resumeFor(profile).executeAsList().forEach { r ->
            out += item(
                SyncType.RESUME_POSITION, p, r.item_key,
                fields(
                    "kind" to r.kind, "vod_item_id" to r.vod_item_id, "episode_id" to r.episode_id, "series_key" to r.series_key, "season" to r.season,
                    "episode" to r.episode, "title" to r.title, "position_ms" to r.position_ms, "duration_ms" to r.duration_ms, "updated_at" to r.updated_at,
                ),
            )
        }
        db.watchQueries.watchedFor(profile).executeAsList().forEach { w ->
            out += item(SyncType.WATCHED, p, w.item_key, fields("kind" to w.kind, "vod_item_id" to w.vod_item_id, "series_key" to w.series_key, "season" to w.season, "episode" to w.episode, "watched_at" to w.watched_at))
        }
        db.watchQueries.hiddenFor(profile).executeAsList().forEach { h -> out += item(SyncType.CONTINUE_HIDDEN, p, h.series_key, fields("hidden_at" to h.hidden_at)) }
        settings.rows().forEach { s ->
            if (s.key in Settings.Key.notSynced) return@forEach
            // Another profile's leftovers (from before a move) aren't this device's to send.
            if (s.scope == SyncScope.PROFILE && s.owner != profile) return@forEach
            out += item(SyncType.SETTING, s.scope, s.key, fields("value" to s.value))
        }
        return out.associateBy { it.key }
    }

    /**
     * Writes [item] with [f] as its fields. Returns false for a type this version doesn't know (a
     * newer app added it), which is then left alone.
     */
    fun upsert(item: SyncItem, f: JsonObject): Boolean {
        val id = item.id
        when (item.type) {
            SyncType.SOURCE -> {
                val existing = db.sourceQueries.byId(id).executeAsOneOrNull()
                val password = f.str(SyncFields.PASSWORD).orEmpty()
                db.sourceQueries.upsert(
                    com.signal.db.user.Source(
                        id = id,
                        name = f.str("name") ?: "Source",
                        kind = f.str("kind") ?: "M3U",
                        url = f.str("url").orEmpty(),
                        username = f.str("username").orEmpty(),
                        password_enc = if (password.isEmpty()) "" else secrets.encrypt(password),
                        user_agent = f.str("user_agent"),
                        referrer = f.str("referrer"),
                        enabled = if (f.bool("enabled") != false) 1 else 0,
                        live_format = f.str("live_format") ?: "TS",
                        connection_limit = f.long("connection_limit") ?: existing?.connection_limit ?: 2,
                        expires_at = existing?.expires_at,
                        last_refreshed_at = existing?.last_refreshed_at ?: 0,
                        last_error = existing?.last_error,
                        created_at = f.long("created_at") ?: existing?.created_at ?: 0,
                    ),
                )
            }
            SyncType.EPG_SOURCE -> {
                val existing = db.guideQueries.epgSourceById(id).executeAsOneOrNull()
                db.guideQueries.upsertEpgSource(
                    com.signal.db.user.Epg_source(
                        id = id,
                        name = f.str("name") ?: "Guide",
                        kind = f.str("kind") ?: "XMLTV",
                        url = f.str("url").orEmpty(),
                        source_id = f.str("source_id"),
                        enabled = if (f.bool("enabled") != false) 1 else 0,
                        time_shift_minutes = f.long("time_shift_minutes") ?: 0,
                        position = f.str("position").orEmpty(),
                        last_built_at = existing?.last_built_at ?: 0,
                        last_error = existing?.last_error,
                        created_at = f.long("created_at") ?: existing?.created_at ?: 0,
                    ),
                )
            }
            SyncType.GUIDE_MAPPING -> db.guideQueries.setMapping(id, f.str("epg_source_id") ?: return true, f.str("guide_channel_id"))
            SyncType.USER_CHANNEL -> db.syncQueries.upsertUserChannel(id, f.long("created_at") ?: 0)
            SyncType.CHANNEL_LINK -> db.syncQueries.upsertLink(
                f.str("user_channel_id") ?: return true, f.str("stream_id") ?: return true, f.str("kind") ?: "manual", if (f.bool("preferred") != false) 1 else 0,
            )
            SyncType.CHANNEL_PROFILE -> db.syncQueries.upsertChannelProfile(
                com.signal.db.user.Channel_profile(
                    user_channel_id = id, profile_id = profile, custom_name = f.str("custom_name"), custom_logo = f.str("custom_logo"),
                    custom_number = f.long("custom_number"), hidden = if (f.bool("hidden") == true) 1 else 0,
                    audio_language = f.str("audio_language"), subtitle = f.str("subtitle"), aspect = f.str("aspect"), voice_names = f.str("voice_names"),
                ),
            )
            SyncType.FAVORITE -> db.syncQueries.upsertFavorite(profile, id, f.str("sort_key").orEmpty())
            SyncType.PLAYLIST -> db.syncQueries.upsertPlaylist(id, profile, f.str("name") ?: "Playlist", f.str("sort_key").orEmpty())
            SyncType.PLAYLIST_ITEM -> db.syncQueries.upsertPlaylistItem(f.str("playlist_id") ?: return true, f.str("user_channel_id") ?: return true, f.str("sort_key").orEmpty())
            SyncType.GROUP_OVERRIDE -> db.syncQueries.upsertGroupOverride(
                com.signal.db.user.Group_override(
                    profile_id = profile, source_id = f.str("source_id") ?: return true, group_name = f.str("group_name") ?: return true,
                    hidden = if (f.bool("hidden") == true) 1 else 0, custom_name = f.str("custom_name"), channel_sort = f.str("channel_sort"), sort_key = f.str("sort_key"),
                ),
            )
            SyncType.REMINDER -> {
                val uc = f.str("user_channel_id") ?: return true
                val title = f.str("title").orEmpty()
                val start = f.long("start_ms") ?: 0
                val stop = f.long("stop_ms") ?: 0
                db.syncQueries.insertReminderIfNew(id, profile, uc, title, start, stop, f.long("created_at") ?: 0)
                db.syncQueries.updateReminder(profile, uc, title, start, stop, id)
            }
            SyncType.SETTING -> {
                if (id in Settings.Key.notSynced) return true
                settings.applySynced(item.scope, id, f.str("value"))
            }
            SyncType.ALIAS -> db.matchQueries.putAlias(id, f.str("a") ?: return true, f.str("b") ?: return true)
            SyncType.LIBRARY_ITEM -> db.watchQueries.putLibrary(
                com.signal.db.user.Library_item(profile, id, f.str("kind") ?: "MOVIE", f.str("vod_item_id") ?: return true, f.str("title").orEmpty(), f.long("year"), f.str("poster_url"), f.long("added_at") ?: 0),
            )
            SyncType.RESUME_POSITION -> db.watchQueries.putResume(
                com.signal.db.user.Resume_position(
                    profile, id, f.str("kind") ?: "MOVIE", f.str("vod_item_id") ?: return true, f.str("episode_id"), f.str("series_key"), f.long("season"), f.long("episode"),
                    f.str("title").orEmpty(), f.long("position_ms") ?: 0, f.long("duration_ms") ?: 0, f.long("updated_at") ?: 0,
                ),
            )
            SyncType.WATCHED -> db.watchQueries.putWatched(
                com.signal.db.user.Watched(profile, id, f.str("kind") ?: "MOVIE", f.str("vod_item_id") ?: return true, f.str("series_key"), f.long("season"), f.long("episode"), f.long("watched_at") ?: 0),
            )
            SyncType.CONTINUE_HIDDEN -> db.watchQueries.putHidden(profile, id, f.long("hidden_at") ?: 0)
            SyncType.MATCH_REJECT -> db.matchQueries.putReject(f.str("user_channel_id") ?: return true, f.str("stream_id") ?: return true)
            else -> return false
        }
        return true
    }

    /** Removes the row for [item]; [last] is its last known fields, for types keyed by them. */
    fun delete(item: SyncItem, last: JsonObject?) {
        val id = item.id
        val f = last ?: item.fields
        when (item.type) {
            SyncType.SOURCE -> {
                db.streamQueries.deleteForSource(id)
                db.sourceQueries.delete(id)
            }
            SyncType.EPG_SOURCE -> {
                db.guideQueries.removeMappingsForEpgSource(id)
                db.guideQueries.deleteEpgSource(id)
            }
            SyncType.GUIDE_MAPPING -> db.guideQueries.removeMapping(id)
            SyncType.USER_CHANNEL -> db.syncQueries.deleteUserChannel(id)
            SyncType.CHANNEL_LINK -> id.split('|', limit = 2).takeIf { it.size == 2 }?.let { db.syncQueries.deleteLink(it[0], it[1]) }
            SyncType.CHANNEL_PROFILE -> db.syncQueries.deleteChannelProfile(id, profile)
            SyncType.FAVORITE -> db.listsQueries.removeFavorite(profile, id)
            SyncType.PLAYLIST -> {
                db.listsQueries.deletePlaylistItems(id)
                db.listsQueries.deletePlaylist(id)
            }
            SyncType.PLAYLIST_ITEM -> id.split('|', limit = 2).takeIf { it.size == 2 }?.let { db.listsQueries.removePlaylistItem(it[0], it[1]) }
            SyncType.GROUP_OVERRIDE -> {
                val source = f.str("source_id") ?: id.substringBefore('|')
                val group = f.str("group_name") ?: id.substringAfter('|')
                db.syncQueries.deleteGroupOverride(profile, source, group)
            }
            SyncType.REMINDER -> db.reminderQueries.deleteReminder(id)
            SyncType.SETTING -> if (id !in Settings.Key.notSynced) settings.applySynced(item.scope, id, null)
            SyncType.ALIAS -> db.matchQueries.deleteAlias(id)
            SyncType.LIBRARY_ITEM -> db.watchQueries.removeLibrary(profile, id)
            SyncType.RESUME_POSITION -> db.watchQueries.removeResume(profile, id)
            SyncType.WATCHED -> db.watchQueries.removeWatched(profile, id)
            SyncType.CONTINUE_HIDDEN -> db.watchQueries.removeHidden(profile, id)
            SyncType.MATCH_REJECT -> id.split('|', limit = 2).takeIf { it.size == 2 }?.let { db.matchQueries.deleteReject(it[0], it[1]) }
        }
    }

    /** Moving to another profile (PLAN §5.4): the old profile's rows go, in the caller's transaction. */
    fun dropProfile(profileId: String) {
        db.syncQueries.deleteProfilePlaylistItems(profileId)
        db.syncQueries.deleteProfilePlaylists(profileId)
        db.syncQueries.deleteProfileFavorites(profileId)
        db.syncQueries.deleteProfileGroupOverrides(profileId)
        db.syncQueries.deleteProfileChannelRows(profileId)
        db.syncQueries.deleteProfileReminders(profileId)
        db.watchQueries.deleteProfileWatch(profileId)
        settings.forgetProfile(profileId)
    }
}

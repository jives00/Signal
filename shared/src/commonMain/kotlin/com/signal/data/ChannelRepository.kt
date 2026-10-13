package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.db.user.UserDatabase
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.model.ChannelSort
import com.signal.model.GroupSort
import com.signal.model.ProviderStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** A row in Live TV's group list (PLAN §4.3.2 → Groups). */
data class GroupEntry(
    val group: ChannelGroup,
    val count: Int,
    /** Shown beside provider groups only when more than one source is active. */
    val sourceName: String?,
    val hidden: Boolean,
    /** Provider groups only: what the group menu's sort applies to. */
    val channelSort: ChannelSort? = null,
)

/**
 * Channels as the screens see them: provider streams merged with the user's own record of each,
 * for the device's profile (PLAN §4.5, §5.4). Every write that needs a user channel creates one
 * on demand, in the same transaction (PLAN §4.5 → "A user channel is created...").
 */
class ChannelRepository(
    private val db: UserDatabase,
    private val settings: Settings,
    private val io: CoroutineDispatcher,
) {
    private val profile get() = settings.profileId

    // ---- Reading -------------------------------------------------------------------------

    fun groups(includeHidden: Boolean = false): Flow<List<GroupEntry>> = combine(
        db.streamQueries.groups().asFlow().mapToList(io),
        db.listsQueries.groupOverrides(profile).asFlow().mapToList(io),
        db.listsQueries.playlists(profile).asFlow().mapToList(io),
        db.sourceQueries.all().asFlow().mapToList(io),
        // Settings and favorites only trigger a rebuild; one input keeps this within combine's five.
        merge(settings.changes().map { }, db.listsQueries.favoriteKeys(profile).asFlow().map { }),
    ) { groups, overrides, playlists, sources, _ ->
        val overrideByKey = overrides.associateBy { it.source_id to it.group_name }
        val enabledSources = sources.filter { it.enabled == 1L }
        val sourceNames = sources.associate { it.id to it.name }
        val multiSource = enabledSources.size > 1

        val provider = groups.map { g ->
            val o = overrideByKey[g.source_id to g.group_name]
            Triple(
                GroupEntry(
                    group = ChannelGroup.Provider(g.source_id, g.group_name, o?.custom_name ?: g.group_name),
                    count = g.channel_count.toInt(),
                    sourceName = if (multiSource) sourceNames[g.source_id] else null,
                    hidden = o?.hidden == 1L,
                    channelSort = o?.channel_sort?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() },
                ),
                o?.sort_key,
                g.first_position,
            )
        }.filter { includeHidden || !it.first.hidden }

        val sorted = when (settings.groupSort()) {
            GroupSort.PROVIDER -> provider
            GroupSort.A_Z -> provider.sortedBy { it.first.group.title.lowercase() }
            GroupSort.Z_A -> provider.sortedByDescending { it.first.group.title.lowercase() }
            // Groups never moved by hand keep provider order after the moved ones.
            GroupSort.MANUAL -> provider.sortedWith(compareBy(nullsLast()) { it.second })
        }.map { it.first }

        buildList {
            add(GroupEntry(ChannelGroup.Favorites, favoriteCount(), null, false))
            playlists.forEach { add(GroupEntry(ChannelGroup.Playlist(it.id, it.name), 0, null, false)) }
            addAll(sorted)
        }
    }

    suspend fun groupsNow(includeHidden: Boolean = false): List<GroupEntry> = groups(includeHidden).first()

    private fun favoriteCount(): Int = db.listsQueries.favoriteKeys(profile).executeAsList().size

    fun channels(group: ChannelGroup): Flow<List<Channel>> = when (group) {
        ChannelGroup.Favorites -> combine(
            db.listsQueries.favoriteChannels(profile).asFlow().mapToList(io),
            db.channelLinkTrigger(),
        ) { rows, _ ->
            rows.map { r ->
                channel(
                    stream = r.stream(), displayName = r.display_name, userChannelId = r.fav_user_channel_id, customName = r.custom_name,
                    customLogo = r.custom_logo, customNumber = r.custom_number, hidden = r.hidden, favorite = true,
                )
            }.filter { !it.isHidden }
        }
        is ChannelGroup.Playlist -> db.listsQueries.playlistChannels(group.id).asFlow().mapToList(io).map { rows ->
            rows.map { r ->
                channel(
                    stream = r.stream(), displayName = r.display_name, userChannelId = r.item_user_channel_id, customName = r.custom_name,
                    customLogo = r.custom_logo, customNumber = r.custom_number, hidden = r.hidden, favorite = r.is_favorite,
                )
            }.filter { !it.isHidden }
        }
        is ChannelGroup.Provider -> combine(
            db.streamQueries.channelsInGroup(profile, group.sourceId, group.name).asFlow().mapToList(io),
            db.listsQueries.groupOverrides(profile).asFlow().mapToList(io),
            settings.changes(),
        ) { rows, overrides, _ ->
            val sort = overrides.firstOrNull { it.source_id == group.sourceId && it.group_name == group.name }
                ?.channel_sort?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() }
                ?: settings.channelSort()
            val channels = rows.map { r ->
                channel(
                    stream = r.stream(), displayName = r.display_name, userChannelId = r.user_channel_id, customName = r.custom_name,
                    customLogo = r.custom_logo, customNumber = r.custom_number, hidden = r.hidden, favorite = r.is_favorite,
                )
            }.filter { !it.isHidden }
            when (sort) {
                ChannelSort.PROVIDER -> channels
                ChannelSort.NUMBER -> channels.sortedBy { it.number }
                ChannelSort.A_Z -> channels.sortedBy { it.displayName.lowercase() }
            }
        }
    }

    suspend fun channelsNow(group: ChannelGroup): List<Channel> = channels(group).first()

    /** The stream a user channel plays now, or null if it's unavailable (PLAN §2.1). */
    suspend fun channelByUserChannel(userChannelId: String): Channel? = withContext(io) {
        val stream = db.userChannelQueries.preferredStream(userChannelId).executeAsOneOrNull() ?: return@withContext null
        channelByStream(stream.id)
    }

    suspend fun channelByStream(streamId: String): Channel? = withContext(io) {
        db.streamQueries.channelByIdWithUser(profile, streamId).executeAsOneOrNull()?.let { r ->
            channel(
                stream = r.stream(), displayName = r.display_name, userChannelId = r.user_channel_id, customName = r.custom_name,
                customLogo = r.custom_logo, customNumber = r.custom_number, hidden = r.hidden, favorite = r.is_favorite,
            )
        }
    }

    /** Resolves a stored channel key: a user channel ID, or a stream ID for untouched channels. */
    suspend fun channelByKey(key: String): Channel? = channelByUserChannel(key) ?: channelByStream(key)

    fun hiddenChannels(): Flow<List<Channel>> = db.userChannelQueries.hiddenChannels(profile).asFlow().mapToList(io).map { rows ->
        rows.map { r ->
            channel(
                stream = rowStream(r.id, r.source_id, r.provider_id, r.raw_name, r.group_name, r.logo_url, r.tvg_id, r.provider_number, r.position, r.url, r.headers),
                displayName = r.display_name, userChannelId = r.user_channel_id, customName = r.custom_name,
                customLogo = null, customNumber = null, hidden = 1, favorite = false,
            )
        }
    }

    // ---- User channels --------------------------------------------------------------------

    /** The channel's user channel ID if it has one, looked up fresh (a [Channel] can predate it). */
    suspend fun userChannelIdFor(channel: Channel): String? = channel.userChannelId
        ?: withContext(io) { db.userChannelQueries.userChannelForStream(channel.stream.id).executeAsOneOrNull() }

    /** Returns the channel's user channel ID, creating it (and its profile row) if needed. */
    suspend fun ensureUserChannel(channel: Channel): String = withContext(io) {
        channel.userChannelId?.let { return@withContext it.also { ensureProfileRow(it) } }
        db.transactionWithResult {
            val existing = db.userChannelQueries.userChannelForStream(channel.stream.id).executeAsOneOrNull()
            // Named after the stream, not random: two devices that each create one for the same stream
            // before syncing end up with the same user channel (PLAN §9).
            val id = existing ?: stableUserChannelId(channel.stream.id).also { id ->
                db.userChannelQueries.insertUserChannel(id, Clock.System.now().toEpochMilliseconds())
                db.userChannelQueries.insertLink(id, channel.stream.id, "manual", 1)
            }
            db.userChannelQueries.ensureProfileRow(id, profile)
            id
        }
    }

    private fun ensureProfileRow(id: String) = db.userChannelQueries.ensureProfileRow(id, profile)

    // ---- Favorites --------------------------------------------------------------------------

    suspend fun setFavorite(channel: Channel, favorite: Boolean) {
        val id = ensureUserChannel(channel)
        withContext(io) {
            if (favorite) {
                val last = db.listsQueries.lastFavoriteKey(profile).executeAsOneOrNull()?.max
                db.listsQueries.addFavorite(profile, id, FractionalIndex.after(last))
            } else {
                db.listsQueries.removeFavorite(profile, id)
            }
        }
    }

    /** Move mode drop (PLAN §2.3): only the moved item's key changes (PLAN §5.4). */
    suspend fun moveFavorite(userChannelId: String, toIndex: Int) = withContext(io) {
        val keys = db.listsQueries.favoriteKeys(profile).executeAsList().map { it.user_channel_id to it.sort_key }
        newKeyForMove(keys, userChannelId, toIndex)?.let { db.listsQueries.setFavoriteKey(it, profile, userChannelId) }
    }

    // ---- Playlists --------------------------------------------------------------------------

    suspend fun createPlaylist(name: String): String = withContext(io) {
        val id = newId()
        val last = db.listsQueries.lastPlaylistKey(profile).executeAsOneOrNull()?.max
        db.listsQueries.insertPlaylist(id, profile, name.trim(), FractionalIndex.after(last))
        id
    }

    suspend fun renamePlaylist(id: String, name: String) = withContext(io) { db.listsQueries.renamePlaylist(name.trim(), id) }

    suspend fun deletePlaylist(id: String) = withContext(io) {
        db.transaction {
            db.listsQueries.deletePlaylistItems(id)
            db.listsQueries.deletePlaylist(id)
        }
    }

    suspend fun movePlaylist(id: String, toIndex: Int) = withContext(io) {
        val keys = db.listsQueries.playlists(profile).executeAsList().map { it.id to it.sort_key }
        newKeyForMove(keys, id, toIndex)?.let { db.listsQueries.setPlaylistKey(it, id) }
    }

    suspend fun addToPlaylist(channel: Channel, playlistId: String) {
        val id = ensureUserChannel(channel)
        withContext(io) {
            val last = db.listsQueries.lastPlaylistItemKey(playlistId).executeAsOneOrNull()?.max
            db.listsQueries.addPlaylistItem(playlistId, id, FractionalIndex.after(last))
        }
    }

    suspend fun addAllToPlaylist(channels: List<Channel>, playlistId: String) = channels.forEach { addToPlaylist(it, playlistId) }

    suspend fun removeFromPlaylist(userChannelId: String, playlistId: String) =
        withContext(io) { db.listsQueries.removePlaylistItem(playlistId, userChannelId) }

    suspend fun movePlaylistItem(playlistId: String, userChannelId: String, toIndex: Int) = withContext(io) {
        val keys = db.listsQueries.playlistItemKeys(playlistId).executeAsList().map { it.user_channel_id to it.sort_key }
        newKeyForMove(keys, userChannelId, toIndex)?.let { db.listsQueries.setPlaylistItemKey(it, playlistId, userChannelId) }
    }

    // ---- Per-channel edits (profile scope) ---------------------------------------------------

    suspend fun rename(channel: Channel, name: String?) {
        val id = ensureUserChannel(channel)
        withContext(io) { db.userChannelQueries.setCustomName(name?.trim()?.ifEmpty { null }, id, profile) }
    }

    suspend fun setNumber(channel: Channel, number: Int?) {
        val id = ensureUserChannel(channel)
        withContext(io) { db.userChannelQueries.setCustomNumber(number?.toLong(), id, profile) }
    }

    suspend fun setHidden(channel: Channel, hidden: Boolean) {
        val id = ensureUserChannel(channel)
        withContext(io) { db.userChannelQueries.setHidden(if (hidden) 1 else 0, id, profile) }
    }

    /** Clears the rename, logo and number; favorites and playlists are untouched (PLAN §4.2). */
    suspend fun resetToProviderDefaults(channel: Channel) {
        val id = channel.userChannelId ?: return
        withContext(io) { db.userChannelQueries.resetToProviderDefaults(id, profile) }
    }

    /** Remembered per channel (PLAN §4.3.3 → Remembered choices). */
    suspend fun rememberAudio(channel: Channel, language: String?) {
        val id = ensureUserChannel(channel)
        withContext(io) { db.userChannelQueries.setAudioLanguage(language, id, profile) }
    }

    suspend fun rememberSubtitle(channel: Channel, subtitle: String?) {
        val id = ensureUserChannel(channel)
        withContext(io) { db.userChannelQueries.setSubtitle(subtitle, id, profile) }
    }

    suspend fun rememberAspect(channel: Channel, aspect: String) {
        val id = ensureUserChannel(channel)
        withContext(io) { db.userChannelQueries.setAspect(aspect, id, profile) }
    }

    data class Remembered(val audioLanguage: String?, val subtitle: String?, val aspect: String?)

    suspend fun remembered(channel: Channel): Remembered? = withContext(io) {
        val id = channel.userChannelId ?: return@withContext null
        db.userChannelQueries.profileRow(id, profile).executeAsOneOrNull()?.let { Remembered(it.audio_language, it.subtitle, it.aspect) }
    }

    // ---- Linked streams (PLAN §4.5): the Stream picker --------------------------------------------

    data class LinkedStream(val userChannelId: String, val streamId: String, val preferred: Boolean, val name: String, val group: String, val sourceName: String)

    /** User channels with more than one playable stream, and those streams: small, so it's kept live for menus. */
    fun multiLinked(): Flow<Map<String, List<LinkedStream>>> = db.userChannelQueries.multiLinked().asFlow().mapToList(io).map { rows ->
        rows.groupBy({ it.user_channel_id }) { LinkedStream(it.user_channel_id, it.stream_id, it.preferred == 1L, it.display_name, it.group_name, it.source_name) }
    }

    /** Picks the stream a user channel plays, for every device (household, PLAN §9.1). */
    suspend fun choosePreferred(userChannelId: String, streamId: String) = withContext(io) {
        db.transaction {
            db.userChannelQueries.clearPreferred(userChannelId)
            db.userChannelQueries.setPreferred(userChannelId, streamId)
        }
    }

    // ---- Provider groups --------------------------------------------------------------------

    suspend fun setGroupHidden(group: ChannelGroup.Provider, hidden: Boolean) = groupEdit(group) {
        db.listsQueries.setGroupHidden(if (hidden) 1 else 0, profile, group.sourceId, group.name)
    }

    suspend fun renameGroup(group: ChannelGroup.Provider, name: String?) = groupEdit(group) {
        db.listsQueries.setGroupName(name?.trim()?.ifEmpty { null }, profile, group.sourceId, group.name)
    }

    suspend fun setGroupChannelSort(group: ChannelGroup.Provider, sort: ChannelSort?) = groupEdit(group) {
        db.listsQueries.setGroupChannelSort(sort?.name, profile, group.sourceId, group.name)
    }

    /**
     * Move group (PLAN §4.2 → Group menu). The first move switches group sort to Manual, seeding
     * keys from the order on screen so nothing jumps.
     */
    suspend fun moveGroup(visibleOrder: List<ChannelGroup.Provider>, group: ChannelGroup.Provider, toIndex: Int) = withContext(io) {
        db.transaction {
            if (settings.groupSort() != GroupSort.MANUAL) {
                val seeded = FractionalIndex.sequence(visibleOrder.size)
                visibleOrder.forEachIndexed { i, g ->
                    db.listsQueries.ensureGroupOverride(profile, g.sourceId, g.name)
                    db.listsQueries.setGroupSortKey(seeded[i], profile, g.sourceId, g.name)
                }
                settings.put(Settings.Key.GROUP_SORT, GroupSort.MANUAL.name)
            }
            val overrides = db.listsQueries.groupOverrides(profile).executeAsList().associateBy { it.source_id to it.group_name }
            val keys = visibleOrder.map { g -> "${g.sourceId}\u0000${g.name}" to (overrides[g.sourceId to g.name]?.sort_key ?: "") }
            newKeyForMove(keys, "${group.sourceId}\u0000${group.name}", toIndex)?.let {
                db.listsQueries.ensureGroupOverride(profile, group.sourceId, group.name)
                db.listsQueries.setGroupSortKey(it, profile, group.sourceId, group.name)
            }
        }
    }

    private suspend fun groupEdit(group: ChannelGroup.Provider, block: () -> Unit) = withContext(io) {
        db.transaction {
            db.listsQueries.ensureGroupOverride(profile, group.sourceId, group.name)
            block()
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun channel(
        stream: ProviderStream,
        /** The raw name after character clean-up, as stored at refresh. */
        displayName: String,
        userChannelId: String?,
        customName: String?,
        customLogo: String?,
        customNumber: Long?,
        hidden: Long,
        favorite: Boolean,
    ): Channel = Channel(
        stream = stream,
        userChannelId = userChannelId,
        displayName = customName ?: displayName,
        // Until the user sets one: the provider's number, else the position in the source (PLAN §4.2).
        number = customNumber?.toInt() ?: stream.providerNumber ?: (stream.position + 1),
        logoUrl = customLogo ?: stream.logoUrl,
        isFavorite = favorite,
        isHidden = hidden == 1L,
    )

    private fun rowStream(
        id: String, sourceId: String, providerId: String, rawName: String, groupName: String,
        logo: String?, tvgId: String?, number: Long?, position: Long, url: String?, headers: String?,
    ) = ProviderStream(id, sourceId, providerId, rawName, groupName, logo, tvgId, number?.toInt(), position.toInt(), url, headers.toHeaders())

    private fun com.signal.db.user.ChannelsInGroup.stream() = rowStream(id, source_id, provider_id, raw_name, group_name, logo_url, tvg_id, provider_number, position, url, headers)
    private fun com.signal.db.user.FavoriteChannels.stream() = rowStream(id, source_id, provider_id, raw_name, group_name, logo_url, tvg_id, provider_number, position, url, headers)
    private fun com.signal.db.user.PlaylistChannels.stream() = rowStream(id, source_id, provider_id, raw_name, group_name, logo_url, tvg_id, provider_number, position, url, headers)
    private fun com.signal.db.user.ChannelByIdWithUser.stream() = rowStream(id, source_id, provider_id, raw_name, group_name, logo_url, tvg_id, provider_number, position, url, headers)

    /** Favorites also change when a link changes (a refresh relinks streams); re-query then too. */
    private fun UserDatabase.channelLinkTrigger(): Flow<Unit> =
        streamQueries.groups().asFlow().map { }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun stableUserChannelId(streamId: String) = "ch:$streamId"

        fun String?.toHeaders(): Map<String, String> =
            if (this.isNullOrEmpty()) emptyMap() else runCatching { json.decodeFromString<Map<String, String>>(this) }.getOrDefault(emptyMap())

        /**
         * The new sort key for moving [id] to [toIndex] in [keys] (ordered (id, key) pairs), or
         * null when it's already there. Only the moved item gets a new key.
         */
        fun newKeyForMove(keys: List<Pair<String, String>>, id: String, toIndex: Int): String? {
            val from = keys.indexOfFirst { it.first == id }
            if (from < 0 || from == toIndex) return null
            val others = keys.filterNot { it.first == id }
            val at = toIndex.coerceIn(0, others.size)
            val before = others.getOrNull(at - 1)?.second?.takeIf { it.isNotEmpty() }
            val after = others.getOrNull(at)?.second?.takeIf { it.isNotEmpty() }
            return if (before != null && after != null && before >= after) null else FractionalIndex.between(before, after)
        }
    }
}

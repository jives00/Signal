package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.db.user.UserDatabase
import com.signal.model.ChannelSort
import com.signal.model.GroupSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Who shares a setting (PLAN §9.1). */
enum class Scope(val id: String) { HOUSEHOLD("household"), PROFILE("profile"), DEVICE("device") }

/**
 * Typed settings over the `setting` table. Each key declares its scope, so the sync layer
 * (milestone 3) knows who shares it without a second list to keep in step.
 */
class Settings(private val db: UserDatabase, private val io: CoroutineDispatcher) {
    /** The device's profile. "main" until the companion assigns another (PLAN §9.1). */
    val profileId: String get() = raw(Key.PROFILE_ID) ?: "main"
    val deviceId: String get() = raw(Key.DEVICE_ID) ?: newId().also { put(Key.DEVICE_ID, it) }

    enum class Key(val scope: Scope, val id: String) {
        PROFILE_ID(Scope.DEVICE, "profile_id"),
        DEVICE_ID(Scope.DEVICE, "device_id"),
        LAST_PLAYED_CHANNEL(Scope.DEVICE, "last_played_channel"),
        BACKGROUNDED_AT(Scope.DEVICE, "backgrounded_at"),
        UPDATE_DISMISSED_TAG(Scope.DEVICE, "update_dismissed_tag"),
        CLOCK_24H(Scope.PROFILE, "clock_24h"),
        // The page the app opens on (StartPage); unset is Home.
        START_PAGE(Scope.PROFILE, "start_page"),
        GROUP_SORT(Scope.PROFILE, "group_sort"),
        CHANNEL_SORT(Scope.PROFILE, "channel_sort"),
        // Favorites: unset is the manual order; A_Z or NUMBER sort on top of it (milestone 6).
        FAVORITES_SORT(Scope.PROFILE, "favorites_sort"),
        SHOW_NUMBERS(Scope.PROFILE, "show_numbers"),
        SHOW_LOGOS(Scope.PROFILE, "show_logos"),
        SHOW_NAMES(Scope.PROFILE, "show_names"),
        REMOVE_EMOJI(Scope.PROFILE, "remove_emoji"),
        DEFAULT_EPG_SOURCE(Scope.HOUSEHOLD, "default_epg_source"),
        REMINDER_LEAD_MINUTES(Scope.PROFILE, "reminder_lead_minutes"),
        REMINDER_AUTO_SWITCH(Scope.PROFILE, "reminder_auto_switch"),
        // Movies and series (PLAN §4.3.9 → Autoplay next episode): on by default.
        AUTOPLAY_NEXT(Scope.PROFILE, "autoplay_next"),
        RECENT_SEARCHES(Scope.DEVICE, "recent_searches"),
        SEARCH_FILTER(Scope.DEVICE, "search_filter"),
        // The NAS companion (PLAN §5.7). The key is encrypted with the SecretBox and never synced.
        NAS_HOME_ADDRESS(Scope.DEVICE, "nas_home_address"),
        NAS_TAILSCALE_ADDRESS(Scope.DEVICE, "nas_tailscale_address"),
        NAS_API_KEY(Scope.DEVICE, "nas_api_key"),
        NAS_DEVICE_NAME(Scope.DEVICE, "nas_device_name"),
        /** The companion VOD index version this device has (PLAN §4.3.12). */
        VOD_INDEX_VERSION(Scope.DEVICE, "vod_index_version"),
        ;

        companion object {
            /**
             * Never synced (PLAN §5.7, §9): the NAS connection and its key, the device's identity and
             * profile (the companion owns those), and passing state.
             */
            val notSynced: Set<String> = setOf(PROFILE_ID, DEVICE_ID, BACKGROUNDED_AT, NAS_HOME_ADDRESS, NAS_TAILSCALE_ADDRESS, NAS_API_KEY, NAS_DEVICE_NAME, VOD_INDEX_VERSION)
                .mapTo(HashSet()) { it.id }
        }
    }

    private fun owner(key: Key): String = when (key.scope) {
        Scope.HOUSEHOLD -> ""
        Scope.PROFILE -> profileId
        // Device-scope keys that identify the device can't depend on the device ID itself.
        Scope.DEVICE -> "this-device"
    }

    // Settings are read during composition (the clock format, once per guide cell), so reads come from
    // memory. This class is the table's only writer, so the cache never goes stale.
    private val cache: MutableMap<String, String> by lazy {
        db.settingQueries.all().executeAsList().associateTo(HashMap()) { cacheKey(it.scope, it.owner_id, it.key) to it.value_ }
    }

    private fun cacheKey(scope: String, owner: String, key: String) = "$scope/$owner/$key"

    fun raw(key: Key): String? = synchronized(this) { cache[cacheKey(key.scope.id, owner(key), key.id)] }

    fun put(key: Key, value: String?) {
        synchronized(this) {
            val k = cacheKey(key.scope.id, owner(key), key.id)
            if (value == null) cache.remove(k) else cache[k] = value
        }
        writes.trySend { if (value == null) db.settingQueries.remove(key.scope.id, owner(key), key.id) else db.settingQueries.put(key.scope.id, owner(key), key.id, value) }
    }

    // Writes go to the database in order, one at a time, on io: never on the caller's (often the UI)
    // thread, where they'd block it or race the IO thread's transactions. Reads come from the cache.
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + io).launch { for (write in writes) runCatching { write() } }
    }

    // ---- Sync (PLAN §9) ------------------------------------------------------------------------

    /** One stored setting, with its owner as stored here ("this-device" for device scope). */
    data class Row(val scope: String, val owner: String, val key: String, val value: String)

    /** Every stored setting, pending writes included: what a push compares. */
    fun rows(): List<Row> = synchronized(this) {
        cache.map { (k, v) -> k.split('/', limit = 3).let { Row(it[0], it[1], it[2], v) } }
    }

    /** The owner a setting of [scope] is stored under on this device. */
    fun localOwner(scope: String): String = when (scope) {
        Scope.HOUSEHOLD.id -> ""
        Scope.PROFILE.id -> profileId
        else -> "this-device"
    }

    /**
     * Writes a setting that came from the companion. Called on io inside the sync's transaction, so it
     * writes at once instead of through the queue.
     */
    fun applySynced(scope: String, key: String, value: String?) {
        val owner = localOwner(scope)
        synchronized(this) {
            val k = cacheKey(scope, owner, key)
            if (value == null) cache.remove(k) else cache[k] = value
        }
        if (value == null) db.settingQueries.remove(scope, owner, key) else db.settingQueries.put(scope, owner, key, value)
    }

    /** Drops the cached profile settings after a move to another profile; the new profile's arrive by sync. */
    fun forgetProfile(profileId: String) {
        synchronized(this) { cache.keys.removeAll { it.startsWith("${Scope.PROFILE.id}/$profileId/") } }
        db.settingQueries.all().executeAsList().filter { it.scope == Scope.PROFILE.id && it.owner_id == profileId }
            .forEach { db.settingQueries.remove(it.scope, it.owner_id, it.key) }
    }

    fun bool(key: Key, default: Boolean): Boolean = raw(key)?.toBooleanStrictOrNull() ?: default

    /** Emits the whole settings table on every change; cheap, since it's a few dozen rows. */
    fun changes(): Flow<Map<String, String>> =
        db.settingQueries.all().asFlow().mapToList(io).map { rows -> rows.associate { "${it.scope}/${it.key}" to it.value_ } }

    fun groupSort(): GroupSort = raw(Key.GROUP_SORT)?.let { runCatching { GroupSort.valueOf(it) }.getOrNull() } ?: GroupSort.PROVIDER
    fun channelSort(): ChannelSort = raw(Key.CHANNEL_SORT)?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: ChannelSort.PROVIDER
    fun favoritesSort(): ChannelSort? = raw(Key.FAVORITES_SORT)?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() }?.takeIf { it != ChannelSort.PROVIDER }
}

/** UUIDs for everything the user creates (PLAN §5.4). */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newId(): String = kotlin.uuid.Uuid.random().toString()

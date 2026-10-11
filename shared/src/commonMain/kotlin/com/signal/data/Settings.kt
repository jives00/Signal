package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.db.user.UserDatabase
import com.signal.model.ChannelSort
import com.signal.model.GroupSort
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
        GROUP_SORT(Scope.PROFILE, "group_sort"),
        CHANNEL_SORT(Scope.PROFILE, "channel_sort"),
        SHOW_NUMBERS(Scope.PROFILE, "show_numbers"),
        SHOW_LOGOS(Scope.PROFILE, "show_logos"),
        SHOW_NAMES(Scope.PROFILE, "show_names"),
        REMOVE_EMOJI(Scope.PROFILE, "remove_emoji"),
    }

    private fun owner(key: Key): String = when (key.scope) {
        Scope.HOUSEHOLD -> ""
        Scope.PROFILE -> profileId
        // Device-scope keys that identify the device can't depend on the device ID itself.
        Scope.DEVICE -> "this-device"
    }

    fun raw(key: Key): String? = db.settingQueries.get(key.scope.id, owner(key), key.id).executeAsOneOrNull()

    fun put(key: Key, value: String?) {
        if (value == null) db.settingQueries.remove(key.scope.id, owner(key), key.id)
        else db.settingQueries.put(key.scope.id, owner(key), key.id, value)
    }

    fun bool(key: Key, default: Boolean): Boolean = raw(key)?.toBooleanStrictOrNull() ?: default

    /** Emits the whole settings table on every change; cheap, since it's a few dozen rows. */
    fun changes(): Flow<Map<String, String>> =
        db.settingQueries.all().asFlow().mapToList(io).map { rows -> rows.associate { "${it.scope}/${it.key}" to it.value_ } }

    fun groupSort(): GroupSort = raw(Key.GROUP_SORT)?.let { runCatching { GroupSort.valueOf(it) }.getOrNull() } ?: GroupSort.PROVIDER
    fun channelSort(): ChannelSort = raw(Key.CHANNEL_SORT)?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: ChannelSort.PROVIDER
}

/** UUIDs for everything the user creates (PLAN §5.4). */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newId(): String = kotlin.uuid.Uuid.random().toString()

package com.signal.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Settings sync's wire format (PLAN §5.5, §9), shared by the app and the companion so they can't
 * drift. Everything that syncs is an item: a type, an ID, a scope and its owner (the profile or the
 * device; empty for household), and flat fields. Each field merges on its own, last write wins in
 * the order the companion receives them, and the companion's revision orders everything.
 */
object SyncPaths {
    /** POST [PushRequest] → [PushResult]. */
    const val PUSH = "/device/sync"
    /** GET ?since=<rev>&schema=<n> → [PullResult]. */
    const val PULL = "/device/sync"
    /** Server-sent events: [SyncEvent]s, one per `data:` line. */
    const val EVENTS = "/device/events"
}

/** Bumped when an item's fields change meaning. The companion accepts this and the one before (PLAN §5.5). */
const val BUNDLE_SCHEMA = 1

object SyncScope {
    const val HOUSEHOLD = "household"
    const val PROFILE = "profile"
    const val DEVICE = "device"
}

/** The item types (PLAN §5.4's tables). The companion stores types it doesn't know too, so a newer app can add some. */
object SyncType {
    const val SOURCE = "source"
    const val EPG_SOURCE = "epg_source"
    const val GUIDE_MAPPING = "guide_mapping"
    const val USER_CHANNEL = "user_channel"
    const val CHANNEL_LINK = "channel_link"
    const val CHANNEL_PROFILE = "channel_profile"
    const val FAVORITE = "favorite"
    const val PLAYLIST = "playlist"
    const val PLAYLIST_ITEM = "playlist_item"
    const val GROUP_OVERRIDE = "group_override"
    const val REMINDER = "reminder"
    const val SETTING = "setting"
}

@Serializable
data class SyncItem(
    val type: String,
    val id: String,
    val scope: String,
    /** The profile ID for profile scope, the device ID for device scope, empty for household. */
    val owner: String = "",
    val fields: JsonObject = JsonObject(emptyMap()),
    val deleted: Boolean = false,
    /** Set by the companion; 0 in a push. */
    val rev: Long = 0,
    val updatedAt: Long = 0,
) {
    val key: SyncKey get() = SyncKey(type, scope, owner, id)
}

data class SyncKey(val type: String, val scope: String, val owner: String, val id: String)

@Serializable
data class PushRequest(
    val schema: Int,
    /** Upserts carry only the fields that changed; deletes carry none. */
    val items: List<SyncItem>,
    /**
     * The first sync after pairing (PLAN §5.5): the device sends everything it has, and the companion
     * takes a snapshot first so the merge can be undone.
     */
    val initial: Boolean = false,
)

@Serializable
data class PushResult(val rev: Long, val applied: Int)

@Serializable
data class PullResult(
    val schema: Int,
    /** The newest revision; the next pull asks for changes after it. */
    val rev: Long,
    /**
     * Every live item rather than changes, because the device asked from before the oldest
     * tombstone the companion still keeps (PLAN §5.5: 90 days) or from 0. Items the device has from
     * an earlier sync but that aren't here were deleted.
     */
    val full: Boolean,
    val profileId: String,
    val profileName: String,
    /** Whole items (every field), each with its revision; deleted ones are tombstones. */
    val items: List<SyncItem>,
)

/** What the event stream announces. The data still comes from a pull: an event is only a nudge. */
@Serializable
data class SyncEvent(
    val kind: String,
    val rev: Long = 0,
    /** Guide builds: the new build's time. */
    val builtAt: Long = 0,
) {
    companion object {
        const val HELLO = "hello"
        const val SETTINGS = "settings"
        const val GUIDE = "guide"
    }
}

/**
 * A settings file (PLAN §9.2 → Fallback): the same items, for a USB stick or a manual backup. It
 * holds source passwords in plain text, which the export screen says.
 */
@Serializable
data class SettingsFile(
    val format: String = FORMAT,
    val schema: Int = BUNDLE_SCHEMA,
    val exportedAt: Long,
    val items: List<SyncItem>,
) {
    companion object {
        const val FORMAT = "signal-settings"
    }
}

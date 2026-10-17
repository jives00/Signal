package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.db.user.UserDatabase
import com.signal.match.NameCleanup
import com.signal.match.VodCategoryFilter
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
        // Name cleanup rules as JSON (NameCleanup); unset is none.
        NAME_CLEANUP(Scope.PROFILE, "name_cleanup"),
        // The same for movie and series titles; kept apart from channels (PLAN §4.3.6 → Categories).
        VOD_NAME_CLEANUP(Scope.PROFILE, "vod_name_cleanup"),
        // Which provider movie and series categories to keep, as JSON (VodCategoryFilter); unset is all.
        VOD_CATEGORIES(Scope.HOUSEHOLD, "vod_categories"),
        DEFAULT_EPG_SOURCE(Scope.HOUSEHOLD, "default_epg_source"),
        // Sources this profile leaves out, as HiddenSources stores them (PLAN §9.1 → Sources per profile);
        // unset is every source. Mirrored into user.db's source_hidden for the list queries.
        HIDDEN_SOURCES(Scope.PROFILE, "hidden_sources"),
        REMINDER_LEAD_MINUTES(Scope.PROFILE, "reminder_lead_minutes"),
        REMINDER_AUTO_SWITCH(Scope.PROFILE, "reminder_auto_switch"),
        // Movies and series (PLAN §4.3.9 → Autoplay next episode): on by default.
        AUTOPLAY_NEXT(Scope.PROFILE, "autoplay_next"),
        // Which app updates the profile's TVs are offered (PLAN §4.3.15): unset is stable releases only;
        // ALL is every release, testing ones included. Set in the web admin's Devices & Profiles.
        UPDATE_CHANNEL(Scope.PROFILE, "update_channel"),
        // Which channels voice commands match (PLAN §4.3.14 → Voice commands): unset is favorites,
        // playlists and channels with voice names; ALL is every visible channel.
        VOICE_CHANNELS(Scope.PROFILE, "voice_channels"),
        // The speech model on this device (PLAN §4.3.14 → Speech model): unset picks by the device's
        // processor, ACCURATE is the large model, FAST the small one.
        SPEECH_MODEL(Scope.DEVICE, "speech_model"),
        // Settings → Playback (PLAN §4.3.15 → 5). Languages are the profile's: audio as a comma list of
        // ISO 639 codes, best first; subtitles OFF, FORCED (unset) or ALWAYS.
        AUDIO_LANGUAGES(Scope.PROFILE, "audio_languages"),
        SUBTITLE_LANGUAGE(Scope.PROFILE, "subtitle_language"),
        SUBTITLES(Scope.PROFILE, "subtitles"),
        // Subtitle style and the technical settings are the device's: they depend on the TV and network.
        // Unset is each one's default (PlayerSettings).
        SUBTITLE_SIZE(Scope.DEVICE, "subtitle_size"),
        SUBTITLE_COLOR(Scope.DEVICE, "subtitle_color"),
        SUBTITLE_BACKGROUND(Scope.DEVICE, "subtitle_background"),
        VIDEO_DECODER(Scope.DEVICE, "video_decoder"),
        FRAME_RATE_MATCH(Scope.DEVICE, "frame_rate_match"),
        AUDIO_PASSTHROUGH(Scope.DEVICE, "audio_passthrough"),
        TUNNELING(Scope.DEVICE, "tunneling"),
        LIVE_BUFFER(Scope.DEVICE, "live_buffer"),
        VOD_BUFFER(Scope.DEVICE, "vod_buffer"),
        STREAM_TIMEOUT(Scope.DEVICE, "stream_timeout"),
        STREAM_RETRIES(Scope.DEVICE, "stream_retries"),
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
        if (key == Key.HIDDEN_SOURCES || key == Key.PROFILE_ID) writes.trySend { mirrorHiddenSources() }
    }

    /** The sources this device's profile leaves out. */
    fun hiddenSources(): Set<String> = HiddenSources.parse(raw(Key.HIDDEN_SOURCES))

    /**
     * Copies the profile's hidden sources into source_hidden, which the list queries read through the
     * active_source view. Writes only on a difference: every list query observes that table. Runs on io.
     */
    private fun mirrorHiddenSources() {
        val want = hiddenSources()
        if (db.sourceQueries.hiddenHere().executeAsList().toSet() == want) return
        db.transaction {
            db.sourceQueries.clearHiddenHere()
            want.forEach { db.sourceQueries.hideHere(it) }
        }
    }

    // Writes go to the database in order, one at a time, on io: never on the caller's (often the UI)
    // thread, where they'd block it or race the IO thread's transactions. Reads come from the cache.
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + io).launch { for (write in writes) runCatching { write() } }
        // In case the setting and the table parted: a profile moved on a version without the table.
        writes.trySend { mirrorHiddenSources() }
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
        if (scope == Scope.PROFILE.id && key == Key.HIDDEN_SOURCES.id) mirrorHiddenSources()
    }

    /** Drops the cached profile settings after a move to another profile; the new profile's arrive by sync. */
    fun forgetProfile(profileId: String) {
        synchronized(this) { cache.keys.removeAll { it.startsWith("${Scope.PROFILE.id}/$profileId/") } }
        db.settingQueries.all().executeAsList().filter { it.scope == Scope.PROFILE.id && it.owner_id == profileId }
            .forEach { db.settingQueries.remove(it.scope, it.owner_id, it.key) }
        mirrorHiddenSources()
    }

    fun bool(key: Key, default: Boolean): Boolean = raw(key)?.toBooleanStrictOrNull() ?: default

    /** Emits the whole settings table on every change; cheap, since it's a few dozen rows. */
    fun changes(): Flow<Map<String, String>> =
        db.settingQueries.all().asFlow().mapToList(io).map { rows -> rows.associate { "${it.scope}/${it.key}" to it.value_ } }

    // Parsed once per value: it's read for every channel a list builds.
    private var cleanupCache: Pair<String?, NameCleanup> = null to NameCleanup.NONE

    private var vodCleanupCache: Pair<String?, NameCleanup> = null to NameCleanup.NONE
    private var vodFilterCache: Pair<String?, VodCategoryFilter> = null to VodCategoryFilter.NONE

    fun nameCleanup(): NameCleanup {
        val raw = raw(Key.NAME_CLEANUP)
        return synchronized(this) {
            cleanupCache.takeIf { it.first == raw }?.second ?: NameCleanup.parse(raw).also { cleanupCache = raw to it }
        }
    }

    /** Movie and series title cleanup: read for every title a grid loads. */
    fun vodNameCleanup(): NameCleanup {
        val raw = raw(Key.VOD_NAME_CLEANUP)
        return synchronized(this) {
            vodCleanupCache.takeIf { it.first == raw }?.second ?: NameCleanup.parse(raw).also { vodCleanupCache = raw to it }
        }
    }

    fun vodCategoryFilter(): VodCategoryFilter {
        val raw = raw(Key.VOD_CATEGORIES)
        return synchronized(this) {
            vodFilterCache.takeIf { it.first == raw }?.second ?: VodCategoryFilter.parse(raw).also { vodFilterCache = raw to it }
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(key: Key, default: E): E =
        raw(key)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    /** Settings → Playback, as the player takes them (PLAN §4.3.15 → 5). */
    fun playerSettings(): com.signal.player.PlayerSettings {
        val d = com.signal.player.PlayerSettings()
        return com.signal.player.PlayerSettings(
            audioLanguages = raw(Key.AUDIO_LANGUAGES)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            subtitleLanguage = raw(Key.SUBTITLE_LANGUAGE)?.takeIf { it.isNotBlank() },
            subtitles = enumOf(Key.SUBTITLES, d.subtitles),
            subtitleSize = enumOf(Key.SUBTITLE_SIZE, d.subtitleSize),
            subtitleColor = enumOf(Key.SUBTITLE_COLOR, d.subtitleColor),
            subtitleBackground = enumOf(Key.SUBTITLE_BACKGROUND, d.subtitleBackground),
            decoder = enumOf(Key.VIDEO_DECODER, d.decoder),
            frameRate = enumOf(Key.FRAME_RATE_MATCH, d.frameRate),
            passthrough = bool(Key.AUDIO_PASSTHROUGH, d.passthrough),
            tunneling = bool(Key.TUNNELING, d.tunneling),
            liveBuffer = enumOf(Key.LIVE_BUFFER, d.liveBuffer),
            vodBuffer = enumOf(Key.VOD_BUFFER, d.vodBuffer),
            timeoutSeconds = raw(Key.STREAM_TIMEOUT)?.toIntOrNull()?.takeIf { it in com.signal.player.PlayerSettings.TIMEOUTS } ?: d.timeoutSeconds,
            retries = raw(Key.STREAM_RETRIES)?.toIntOrNull()?.takeIf { it in com.signal.player.PlayerSettings.RETRIES } ?: d.retries,
        )
    }

    fun groupSort(): GroupSort = raw(Key.GROUP_SORT)?.let { runCatching { GroupSort.valueOf(it) }.getOrNull() } ?: GroupSort.PROVIDER
    fun channelSort(): ChannelSort = raw(Key.CHANNEL_SORT)?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() } ?: ChannelSort.PROVIDER
    fun favoritesSort(): ChannelSort? = raw(Key.FAVORITES_SORT)?.let { runCatching { ChannelSort.valueOf(it) }.getOrNull() }?.takeIf { it != ChannelSort.PROVIDER }
}

/**
 * The profile setting hidden_sources (PLAN §9.1 → Sources per profile): the IDs of the household's
 * sources a profile leaves out, comma-separated. A list of what's left out, not what's kept, so a
 * source added later shows on every profile until one hides it.
 */
object HiddenSources {
    fun parse(raw: String?): Set<String> = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** Null when nothing is hidden: the setting is removed. */
    fun encode(ids: Set<String>): String? = ids.filter { it.isNotBlank() }.sorted().joinToString(",").ifEmpty { null }
}

/** UUIDs for everything the user creates (PLAN §5.4). */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newId(): String = kotlin.uuid.Uuid.random().toString()

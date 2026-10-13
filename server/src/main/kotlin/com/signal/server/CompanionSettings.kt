package com.signal.server

import kotlinx.serialization.Serializable

/** Settings → Companion (PLAN §4.3.18, §5.9): the companion's own settings, in the database, not env vars. */
@Serializable
data class CompanionSettingsView(
    val buildTime: String,
    val timeZone: String,
    val snapshotsToKeep: Int,
    val tombstoneDays: Int,
    /** "main": new devices join Main; "own": each gets its own profile, a copy of Main (PLAN §9.1). */
    val newDeviceProfile: String,
    /** Whether a TMDB API key is saved. The key itself never leaves the companion. */
    val tmdbKeySet: Boolean = false,
    /** On save: a new key, "" to remove it, or null to keep the saved one. */
    val tmdbKey: String? = null,
    /** The image cache's size limit (PLAN §4.3.12). */
    val imageCacheGb: Int = 20,
)

class CompanionSettings(private val companion: Companion) {
    private fun get(key: String) = companion.store?.setting(key)

    val snapshotsToKeep: Int get() = get(SNAPSHOTS_KEEP)?.toIntOrNull()?.coerceIn(1, 365) ?: 30
    val tombstoneDays: Int get() = get(TOMBSTONE_DAYS)?.toIntOrNull()?.coerceIn(7, 3650) ?: 90
    val newDeviceProfile: String get() = get(NEW_DEVICE_PROFILE)?.takeIf { it == OWN } ?: MAIN
    val imageCacheGb: Int get() = get(IMAGE_CACHE_GB)?.toIntOrNull()?.coerceIn(1, 500) ?: 20

    /** Decrypted with the secret key (PLAN §5.7); null when unset or the key can't decrypt it. */
    val tmdbKey: String? get() = get(TMDB_KEY)?.takeIf { it.isNotEmpty() }?.let { enc -> runCatching { companion.cipher?.decrypt(enc) }.getOrNull() }

    fun view(): CompanionSettingsView {
        val schedule = companion.guideJob.schedule()
        return CompanionSettingsView(schedule.time, schedule.timeZone, snapshotsToKeep, tombstoneDays, newDeviceProfile, tmdbKey != null, null, imageCacheGb)
    }

    /** Validates and saves; returns an error for people, or null. */
    fun save(v: CompanionSettingsView): String? {
        val store = companion.store ?: return "The database isn't connected yet."
        val schedule = GuideSchedule(v.buildTime, v.timeZone)
        if (!schedule.isValid()) return "Enter a time like 03:00 and a time zone like America/Chicago."
        if (v.snapshotsToKeep !in 1..365) return "Keep between 1 and 365 nightly backups."
        if (v.tombstoneDays !in 7..3650) return "Keep deletes between 7 and 3650 days."
        if (v.newDeviceProfile != MAIN && v.newDeviceProfile != OWN) return "New devices join Main or get their own profile."
        if (v.imageCacheGb !in 1..500) return "Keep the image cache between 1 and 500 GB."
        val key = v.tmdbKey?.trim()
        if (!key.isNullOrEmpty() && companion.cipher == null) return "SIGNAL_SECRET_KEY isn't set, so the TMDB key can't be stored."
        if (!key.isNullOrEmpty() && !key.all { it.isLetterOrDigit() || it in "._-" }) return "That doesn't look like a TMDB API key or read access token."
        companion.guideJob.setSchedule(schedule)
        store.putSetting(SNAPSHOTS_KEEP, v.snapshotsToKeep.toString())
        store.putSetting(TOMBSTONE_DAYS, v.tombstoneDays.toString())
        store.putSetting(NEW_DEVICE_PROFILE, v.newDeviceProfile)
        store.putSetting(IMAGE_CACHE_GB, v.imageCacheGb.toString())
        when {
            key == null -> {}
            key.isEmpty() -> store.putSetting(TMDB_KEY, "")
            else -> store.putSetting(TMDB_KEY, companion.cipher!!.encrypt(key))
        }
        return null
    }

    companion object Keys {
        const val SNAPSHOTS_KEEP = "snapshots_keep"
        const val TOMBSTONE_DAYS = "tombstone_days"
        const val NEW_DEVICE_PROFILE = "new_device_profile"
        const val TMDB_KEY = "tmdb_api_key"
        const val IMAGE_CACHE_GB = "image_cache_gb"
        const val MAIN = "main"
        const val OWN = "own"
    }
}

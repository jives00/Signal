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
)

class CompanionSettings(private val companion: Companion) {
    private fun get(key: String) = companion.store?.setting(key)

    val snapshotsToKeep: Int get() = get(SNAPSHOTS_KEEP)?.toIntOrNull()?.coerceIn(1, 365) ?: 30
    val tombstoneDays: Int get() = get(TOMBSTONE_DAYS)?.toIntOrNull()?.coerceIn(7, 3650) ?: 90
    val newDeviceProfile: String get() = get(NEW_DEVICE_PROFILE)?.takeIf { it == OWN } ?: MAIN

    fun view(): CompanionSettingsView {
        val schedule = companion.guideJob.schedule()
        return CompanionSettingsView(schedule.time, schedule.timeZone, snapshotsToKeep, tombstoneDays, newDeviceProfile)
    }

    /** Validates and saves; returns an error for people, or null. */
    fun save(v: CompanionSettingsView): String? {
        val store = companion.store ?: return "The database isn't connected yet."
        val schedule = GuideSchedule(v.buildTime, v.timeZone)
        if (!schedule.isValid()) return "Enter a time like 03:00 and a time zone like America/Chicago."
        if (v.snapshotsToKeep !in 1..365) return "Keep between 1 and 365 nightly backups."
        if (v.tombstoneDays !in 7..3650) return "Keep deletes between 7 and 3650 days."
        if (v.newDeviceProfile != MAIN && v.newDeviceProfile != OWN) return "New devices join Main or get their own profile."
        companion.guideJob.setSchedule(schedule)
        store.putSetting(SNAPSHOTS_KEEP, v.snapshotsToKeep.toString())
        store.putSetting(TOMBSTONE_DAYS, v.tombstoneDays.toString())
        store.putSetting(NEW_DEVICE_PROFILE, v.newDeviceProfile)
        return null
    }

    companion object Keys {
        const val SNAPSHOTS_KEEP = "snapshots_keep"
        const val TOMBSTONE_DAYS = "tombstone_days"
        const val NEW_DEVICE_PROFILE = "new_device_profile"
        const val MAIN = "main"
        const val OWN = "own"
    }
}

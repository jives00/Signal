package com.signal.nas

import kotlinx.serialization.Serializable

/**
 * The wire format between the app and the NAS companion, shared by both sides so they can't drift
 * (PLAN §5.2). Device endpoints live under /device; the web admin's under /api.
 */
object NasPaths {
    const val HEALTH = "/health"
    /** POST [PairRequest] → [PairStarted]. Open on the home network and Tailscale, no key yet. */
    const val PAIR = "/device/pair"
    /** GET → [PairStatus]; the key is handed over once, on the first poll after approval. */
    fun pairStatus(pairingId: String) = "/device/pair/$pairingId"
    /** GET with the device's key → [DeviceInfo]; 401 once the key is revoked. */
    const val ME = "/device/me"
    /** POST plain text with the device's key: Settings → About → Send logs (PLAN §4.3.15). */
    const val LOGS = "/device/logs"
}

/** The companion's /health answer; the app reads only that it answers and its version. */
@Serializable
data class NasHealth(val status: String, val version: String, val database: String)

/** A device asking to pair: its own UUID (PLAN §5.4), so pairing again replaces its old key. */
@Serializable
data class PairRequest(val deviceId: String, val name: String, val model: String, val appVersion: String)

@Serializable
data class PairStarted(
    /** Long and random: only the device that asked knows it, so only it can collect the key. */
    val pairingId: String,
    /** The 6 digits shown on screen and typed into the web admin (PLAN §5.7). */
    val code: String,
    val expiresInSeconds: Int,
)

@Serializable
data class PairStatus(val state: PairState, val apiKey: String? = null, val deviceName: String? = null)

@Serializable
enum class PairState { PENDING, APPROVED, EXPIRED }

@Serializable
data class DeviceInfo(val id: String, val name: String)

/** The header a paired device sends its key in. */
const val DEVICE_KEY_HEADER = "Authorization"
fun bearer(key: String) = "Bearer $key"

/** Sent by the app on every request, so the web admin's Devices page shows each device's version. */
const val APP_VERSION_HEADER = "X-Signal-Version"

/** Guide endpoints (PLAN §5.2 → Endpoints → Guide). All need the device's key. */
object NasGuidePaths {
    /** GET → [NasGuideMeta]; 404 when the NAS hasn't built one yet. */
    const val META = "/device/guide"
    /** GET → the gzipped guide.db that [META] describes. */
    const val FILE = "/device/guide/file"
}

/**
 * What the NAS builds the guide from: the household's sources and EPG sources, from the synced items
 * (PLAN §5.2). The EPG source IDs are the devices' own, because guide.db is keyed by them.
 */
@Serializable
data class GuideInputs(val sources: List<com.signal.model.Source>, val epgSources: List<com.signal.model.EpgSource>)

@Serializable
data class NasGuideMeta(
    val builtAt: Long,
    /** GuideStore.SCHEMA of the build; the app only installs its own. */
    val schema: Int,
    val sizeBytes: Long,
    /** SHA-256 of the .gz file, hex. */
    val sha256: String,
    val channels: Int,
    val programs: Int,
    /** By EPG source ID, so the app can show each source's state as if it had built it. */
    val outcomes: Map<String, NasGuideOutcome> = emptyMap(),
)

@Serializable
data class NasGuideOutcome(
    val channels: Int, val programs: Int, val error: String? = null, val carriedOver: Boolean = false,
    /** When this source last downloaded successfully; older than builtAt when a build reused or carried it over. */
    val refreshedAt: Long? = null,
)

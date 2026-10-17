package com.signal.model

import kotlinx.serialization.Serializable

/** One configured IPTV provider connection (PLAN §2.1 "Source"). */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val kind: SourceKind,
    /** M3U: the playlist URL. Xtream: the server base URL, e.g. `http://host:8080`. */
    val url: String,
    val username: String = "",
    val password: String = "",
    val userAgent: String? = null,
    val referrer: String? = null,
    val enabled: Boolean = true,
    /** Xtream live format: TS (default) or HLS (PLAN §5.6). */
    val liveFormat: LiveFormat = LiveFormat.TS,
    /**
     * The user's limit on streams at once (PLAN §4.3.15 → Connection limit). M3U: set by hand. Xtream:
     * only lower than the provider's, and 0 means "the provider's".
     */
    val connectionLimit: Int = 2,
    /** Last refresh, epoch millis; 0 = never. */
    val lastRefreshedAt: Long = 0,
    val lastError: String? = null,
    /** What the provider allows (Xtream `user_info.max_connections`), as of the last refresh; this device only. */
    val providerConnections: Int? = null,
    /** When the provider account expires, epoch seconds (Xtream `exp_date`); this device only. */
    val expiresAt: Long? = null,
    /** Left out by this device's profile (PLAN §9.1 → Sources per profile); this device only. */
    @kotlinx.serialization.Transient
    val hiddenHere: Boolean = false,
) {
    /** Shown on this device: enabled for the household and not left out by its profile. */
    val active: Boolean get() = enabled && !hiddenHere

    /** Streams this source may play at once: the user's limit, never above the provider's. Null when neither is known. */
    val connections: Int?
        get() {
            val provider = providerConnections?.takeIf { it > 0 }
            val own = connectionLimit.takeIf { it > 0 }
            return if (provider != null && own != null) minOf(provider, own) else provider ?: own
        }

    /** Headers sent with every request and stream for this source. */
    val headers: Map<String, String>
        get() = buildMap {
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
}

@Serializable
enum class SourceKind { M3U, XTREAM, DEMO }

@Serializable
enum class LiveFormat(val extension: String) { TS("ts"), HLS("m3u8") }

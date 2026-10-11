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
    /** Streams the provider allows at once; M3U is set by hand, Xtream comes from user_info (PLAN §4.3.15). */
    val connectionLimit: Int = 2,
    /** Last refresh, epoch millis; 0 = never. */
    val lastRefreshedAt: Long = 0,
    val lastError: String? = null,
) {
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

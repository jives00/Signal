package com.signal.model

/**
 * One entry in a source's channel list (PLAN §2.1 "Stream"). Rebuilt on every source refresh and
 * never edited. What the user sees is a [Channel]; this is the raw provider entry.
 */
data class ProviderStream(
    /** Deterministic across refreshes and devices (PLAN §5.4), see [StreamIds]. */
    val id: String,
    val sourceId: String,
    /** Xtream `stream_id`; for M3U the stable hash that [id] is built from. */
    val providerId: String,
    /** The name exactly as the provider sends it. */
    val rawName: String,
    val groupName: String,
    val logoUrl: String?,
    val tvgId: String?,
    /** The provider's own number (M3U `tvg-chno`, Xtream `num`), if any. */
    val providerNumber: Int?,
    /** Position in the source's list, which is "provider order". */
    val position: Int,
    /**
     * M3U: the full URL. Xtream: null, because the URL embeds the credentials and is built at
     * play time from the source (see [StreamUrls]), so it never sits in the database or logs.
     */
    val url: String?,
    /** Per-stream headers from `#EXTVLCOPT` / `http-user-agent`. */
    val headers: Map<String, String> = emptyMap(),
)

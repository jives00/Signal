package com.signal.model

/**
 * Stream IDs must survive refreshes and devices (PLAN §5.4). Xtream has stable provider IDs; M3U
 * doesn't, so its ID is a hash of the `tvg-id`, the name and the URL with credentials removed, so
 * a password change doesn't change every ID.
 */
object StreamIds {
    fun xtream(sourceId: String, streamId: String): String = "$sourceId:x:$streamId"

    fun m3uProviderId(tvgId: String?, rawName: String, url: String, source: Source?): String =
        fnv1a64("${tvgId.orEmpty()}\u0000$rawName\u0000${CredentialMasking.stripCredentials(url, source)}")

    fun m3u(sourceId: String, providerId: String): String = "$sourceId:m:$providerId"

    /** FNV-1a 64-bit as hex: stable on every platform, unlike hashCode(). */
    fun fnv1a64(text: String): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

/**
 * Credentials never appear in logs or IDs (PLAN §1, §5.4). Removes `user:pass@`, credential
 * query parameters, and Xtream-style `/{user}/{pass}/` path segments matching the source's own.
 */
object CredentialMasking {
    private val credentialParams = setOf("username", "password", "user", "pass", "token", "auth", "key")

    fun stripCredentials(url: String, source: Source? = null): String {
        var result = url.replace(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@?#]*@"), "$1")
        val queryStart = result.indexOf('?')
        if (queryStart >= 0) {
            val base = result.substring(0, queryStart)
            val kept = result.substring(queryStart + 1).split('&').filter { pair ->
                pair.substringBefore('=').lowercase() !in credentialParams
            }
            result = if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }
        if (source != null && source.username.isNotEmpty() && source.password.isNotEmpty()) {
            result = result.replace("/${source.username}/${source.password}/", "/")
        }
        return result
    }

    /** For logs: credentials removed, and only scheme, host and the last path segment kept. */
    fun forLog(url: String, source: Source? = null): String {
        val stripped = stripCredentials(url, source).substringBefore('?')
        val host = stripped.substringAfter("://").substringBefore('/')
        val last = stripped.substringAfterLast('/')
        return "${stripped.substringBefore("://")}://$host/…/$last"
    }
}

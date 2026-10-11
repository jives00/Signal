package com.signal.model

/** Builds the URL to play. Xtream URLs embed credentials, so they're only built at play time (PLAN §5.6). */
object StreamUrls {
    fun live(source: Source, stream: ProviderStream): String = when (source.kind) {
        SourceKind.XTREAM -> buildString {
            append(source.url.trimEnd('/'))
            append("/live/").append(source.username).append('/').append(source.password)
            append('/').append(stream.providerId).append('.').append(source.liveFormat.extension)
        }
        SourceKind.M3U, SourceKind.DEMO -> requireNotNull(stream.url) { "M3U stream without a URL" }
    }

    fun headers(source: Source, stream: ProviderStream): Map<String, String> = source.headers + stream.headers
}

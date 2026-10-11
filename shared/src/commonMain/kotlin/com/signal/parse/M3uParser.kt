package com.signal.parse

import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.StreamIds

/**
 * Streaming line parser for M3U channel lists (PLAN §6). Feed it lines one at a time, so a 50k+
 * entry list never needs to be held in memory as a whole. Handles `tvg-id`, `tvg-name`,
 * `tvg-logo`, `tvg-chno`, `group-title`, `#EXTGRP`, `http-user-agent` and `#EXTVLCOPT` headers.
 */
class M3uParser(private val source: Source) {
    private val streams = ArrayList<ProviderStream>()
    private val seenIds = HashSet<String>()
    private var pendingInfo: String? = null
    private var pendingGroup: String? = null
    private val pendingHeaders = LinkedHashMap<String, String>()

    fun line(raw: String) {
        val line = raw.trim().removePrefix("﻿")
        when {
            line.isEmpty() -> {}
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                pendingInfo = line
                pendingGroup = null
                pendingHeaders.clear()
            }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val opt = line.substringAfter(':')
                val key = opt.substringBefore('=').trim().lowercase()
                val value = opt.substringAfter('=', "").trim()
                when (key) {
                    "http-user-agent" -> pendingHeaders["User-Agent"] = value
                    "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                }
            }
            line.startsWith("#") -> {}
            else -> pendingInfo?.let { info ->
                addStream(info, line)
                pendingInfo = null
            }
        }
    }

    fun result(): List<ProviderStream> = streams

    private fun addStream(info: String, url: String) {
        val split = titleComma(info)
        val attrs = parseAttributes(if (split >= 0) info.substring(0, split) else info)
        val name = (if (split >= 0) info.substring(split + 1) else "").trim()
            .ifEmpty { attrs["tvg-name"].orEmpty() }
            .ifEmpty { url.substringAfterLast('/') }
        attrs["http-user-agent"]?.let { pendingHeaders.putIfAbsent("User-Agent", it) }
        attrs["http-referrer"]?.let { pendingHeaders.putIfAbsent("Referer", it) }
        val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        var providerId = StreamIds.m3uProviderId(tvgId, name, url, source)
        // Exact duplicates in one list still need distinct IDs; suffix by occurrence, which is stable.
        var id = StreamIds.m3u(source.id, providerId)
        var n = 2
        while (!seenIds.add(id)) {
            providerId = StreamIds.m3uProviderId(tvgId, "$name#${n++}", url, source)
            id = StreamIds.m3u(source.id, providerId)
        }
        streams += ProviderStream(
            id = id,
            sourceId = source.id,
            providerId = providerId,
            rawName = name,
            groupName = (attrs["group-title"] ?: pendingGroup).orEmpty().trim().ifEmpty { "Uncategorized" },
            logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            tvgId = tvgId,
            providerNumber = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
            position = streams.size,
            url = url,
            headers = pendingHeaders.toMap(),
        )
    }

    companion object {
        private val attribute = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

        /**
         * The comma that starts the title: the first one outside a quoted attribute value.
         * Titles may contain commas ("CNN, International"), and attribute values may too.
         */
        internal fun titleComma(info: String): Int {
            var inQuotes = false
            for (i in info.indices) {
                when (info[i]) {
                    '"' -> inQuotes = !inQuotes
                    ',' -> if (!inQuotes) return i
                }
            }
            return -1
        }

        internal fun parseAttributes(head: String): Map<String, String> =
            attribute.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

        fun parse(source: Source, text: String): List<ProviderStream> {
            val parser = M3uParser(source)
            text.lineSequence().forEach(parser::line)
            return parser.result()
        }
    }
}

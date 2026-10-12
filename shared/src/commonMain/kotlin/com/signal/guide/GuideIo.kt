package com.signal.guide

import com.signal.parse.XmltvParser
import io.ktor.client.HttpClient

/** File operations for the guide directory (PLAN §6: each build is a new file, swapped in when complete). */
internal expect object GuideFiles {
    /** Absolute paths of the files in [dir]. */
    fun list(dir: String): List<String>
    fun delete(path: String)
    fun join(dir: String, name: String): String
    fun rename(from: String, to: String): Boolean
}

/**
 * Downloads an XMLTV guide and runs [block] on it as characters, as they arrive. Gzipped guides
 * (`.gz`, or gzip bytes whatever the name says) are unpacked on the fly. Blocking: call it on IO.
 */
internal expect suspend fun <T> readXmltv(
    http: HttpClient,
    url: String,
    headers: Map<String, String>,
    block: (XmltvParser.CharSource) -> T,
): T

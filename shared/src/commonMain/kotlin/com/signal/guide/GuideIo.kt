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
    /** A small text file, or null when there is none. */
    fun readText(path: String): String?
    /** Writes [text] whole, through a temporary file, so a reader never sees half of it. */
    fun writeText(path: String, text: String)
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

/**
 * Downloads a gzipped guide.db to [outPath], unpacking it as it arrives, and checks the .gz bytes
 * against [sha256] (hex). Throws on any failure; the caller deletes [outPath].
 */
internal expect suspend fun downloadGunzipped(http: HttpClient, url: String, headers: Map<String, String>, outPath: String, sha256: String)

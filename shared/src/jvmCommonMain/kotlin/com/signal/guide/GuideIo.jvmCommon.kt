package com.signal.guide

import com.signal.parse.XmltvParser
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

internal actual object GuideFiles {
    actual fun list(dir: String): List<String> = File(dir).listFiles()?.map { it.absolutePath }.orEmpty()
    actual fun delete(path: String) {
        File(path).delete()
        // SQLite's side files, if a connection left any.
        File("$path-journal").delete()
        File("$path-wal").delete()
        File("$path-shm").delete()
    }
    actual fun join(dir: String, name: String): String = File(dir, name).absolutePath
    actual fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))
    actual fun readText(path: String): String? = File(path).takeIf { it.exists() }?.readText()
    actual fun writeText(path: String, text: String) {
        val part = File("$path.part")
        part.writeText(text)
        if (!part.renameTo(File(path))) { File(path).delete(); part.renameTo(File(path)) }
    }
}

internal actual suspend fun <T> readXmltv(
    http: HttpClient,
    url: String,
    headers: Map<String, String>,
    block: (XmltvParser.CharSource) -> T,
): T = http.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { response ->
    if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
    val raw = BufferedInputStream(response.bodyAsChannel().toInputStream(), 64 * 1024)
    // Sniff the gzip magic number rather than trusting the URL or the content type.
    raw.mark(2)
    val gzip = raw.read() == 0x1f && raw.read() == 0x8b
    raw.reset()
    val input = if (gzip) GZIPInputStream(raw, 64 * 1024) else raw
    InputStreamReader(input, Charsets.UTF_8).use { reader -> block(XmltvParser.CharSource { buf -> reader.read(buf) }) }
}

internal actual suspend fun downloadGunzipped(http: HttpClient, url: String, headers: Map<String, String>, outPath: String, sha256: String) {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    http.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { response ->
        if (!response.status.isSuccess()) error("HTTP ${response.status.value}")
        val raw = java.security.DigestInputStream(BufferedInputStream(response.bodyAsChannel().toInputStream(), 64 * 1024), digest)
        raw.use {
            val input = GZIPInputStream(raw, 64 * 1024)
            File(outPath).outputStream().use { input.copyTo(it, 64 * 1024) }
            // Whatever gzip didn't need (it can stop before the last bytes) still counts toward the checksum.
            raw.readBytes()
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(sha256, ignoreCase = true)) error("Checksum mismatch")
}

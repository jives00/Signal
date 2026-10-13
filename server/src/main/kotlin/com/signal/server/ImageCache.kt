package com.signal.server

import com.signal.nas.TmdbImages
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * TMDB images, downloaded when first asked for and kept on the data volume (PLAN §4.3.12): posters at
 * 500 wide, episode stills at 780, and backdrops resized to 1920 so they stay sharp on a 4K TV. The
 * oldest-used files go when the cache passes its size limit (Settings → Companion).
 */
class ImageCache(
    private val dir: File,
    private val http: HttpClient,
    private val limitBytes: () -> Long,
    private val base: String = "https://image.tmdb.org/t/p",
) {
    private val log = LoggerFactory.getLogger("ImageCache")
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    private val total = AtomicLong(-1)
    private val evicting = Mutex()

    /** The cached file, downloading it first if needed; null for a bad request or when TMDB has nothing. */
    suspend fun get(size: String, name: String): File? {
        if (size !in SIZES || !name.matches(NAME)) return null
        val file = File(File(dir, size), name)
        if (file.isFile) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val lock = inFlight.computeIfAbsent("$size/$name") { Mutex() }
        return lock.withLock {
            try {
                if (file.isFile) return@withLock file
                val r = http.get("$base/${if (size == TmdbImages.BACKDROP) "original" else size}/$name")
                if (!r.status.isSuccess()) return@withLock null
                val bytes = r.readRawBytes()
                withContext(Dispatchers.IO) {
                    val out = if (size == TmdbImages.BACKDROP) resized(bytes, BACKDROP_WIDTH) ?: bytes else bytes
                    file.parentFile.mkdirs()
                    val tmp = File(file.parentFile, "$name.part")
                    tmp.writeBytes(out)
                    tmp.renameTo(file)
                    if (total.get() >= 0) total.addAndGet(out.size.toLong())
                }
                evictIfNeeded()
                file
            } finally {
                inFlight.remove("$size/$name")
            }
        }
    }

    /** Scaled down to [width] as a JPEG, or null if it's already that small or can't be read. */
    private fun resized(bytes: ByteArray, width: Int): ByteArray? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        if (src.width <= width) return null
        val height = (src.height.toLong() * width / src.width).toInt()
        val dst = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        dst.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(src, 0, 0, width, height, null)
            dispose()
        }
        java.io.ByteArrayOutputStream().also { ImageIO.write(dst, "jpg", it) }.toByteArray()
    }.onFailure { log.warn("Resizing a backdrop failed: ${it.message}") }.getOrNull()

    /** Bytes on disk, counted once and then kept up to date. */
    fun size(): Long {
        if (total.get() < 0) total.set(files().sumOf { it.length() })
        return total.get()
    }

    private fun files(): List<File> = SIZES.flatMap { File(dir, it).listFiles()?.filter { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty() }

    private suspend fun evictIfNeeded() {
        if (size() <= limitBytes()) return
        if (!evicting.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                // Down to 90% of the limit, so it isn't trimming after every download.
                val target = limitBytes() * 9 / 10
                var now = files().sumOf { it.length() }
                for (f in files().sortedBy { it.lastModified() }) {
                    if (now <= target) break
                    val len = f.length()
                    if (f.delete()) now -= len
                }
                total.set(now)
            }
        } finally {
            evicting.unlock()
        }
    }

    companion object Sizes {
        val SIZES = setOf(TmdbImages.POSTER, TmdbImages.STILL, TmdbImages.BACKDROP)
        /** TMDB file names: "/abc123.jpg" without the slash. Nothing that could leave the directory. */
        val NAME = Regex("""[A-Za-z0-9_-]{1,64}\.(jpg|png|webp|svg)""")
        const val BACKDROP_WIDTH = 1920
    }
}

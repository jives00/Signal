package com.signal.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.Log
import com.signal.db.guide.GuideDatabase
import com.signal.guide.GuideBuilder
import com.signal.guide.GuideStore
import com.signal.nas.GuideInputs
import com.signal.nas.NasGuideMeta
import com.signal.nas.NasGuideOutcome
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Properties
import java.util.zip.GZIPOutputStream

/**
 * The nightly guide build (PLAN §5.2 → Jobs): the app's own GuideBuilder writes a fresh guide.db,
 * which is checked, gzipped and published. The previous build stays published until the new one
 * passes, and stays on disk (unpacked) so a failing source can carry over its programs, as on the
 * device. Files, in [dir]: guide-<builtAt>.db, guide-<builtAt>.db.gz and guide.json (the meta).
 */
class GuideBuilds(private val dir: File, private val http: HttpClient) {
    private val log = LoggerFactory.getLogger("Guide")
    private val json = Json { ignoreUnknownKeys = true }
    private val metaFile = File(dir, "guide.json")
    private val lock = Mutex()

    /** The published build, or null before the first one. */
    @Volatile var meta: NasGuideMeta? = runCatching { json.decodeFromString<NasGuideMeta>(metaFile.readText()) }.getOrNull()
        ?.takeIf { gzFor(it.builtAt).isFile }
        private set

    val running: Boolean get() = lock.isLocked

    fun file(): File? = meta?.let { gzFor(it.builtAt) }?.takeIf { it.isFile }

    private fun dbFor(builtAt: Long) = File(dir, "guide-$builtAt.db")
    private fun gzFor(builtAt: Long) = File(dir, "guide-$builtAt.db.gz")

    /**
     * Builds from [inputs] and publishes the result. Fails, leaving the published build alone, when
     * a build is already running or nothing produced any programs.
     */
    suspend fun build(inputs: GuideInputs, now: Long): Result<NasGuideMeta> {
        if (!lock.tryLock()) return Result.failure(IllegalStateException("A guide build is already running"))
        return try {
            runCatching { buildLocked(inputs, now) }.onFailure { log.error("Guide build failed: ${it.message}") }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun buildLocked(inputs: GuideInputs, now: Long): NasGuideMeta {
        dir.mkdirs()
        val part = File(dir, "guide-$now.db.part")
        part.delete()
        val sharedLog = Log { level, message -> if (level == "I") log.info(message) else log.warn(message) }
        val builder = GuideBuilder(http, { id -> inputs.sources.firstOrNull { it.id == id } }, Dispatchers.IO, sharedLog)
        val previousFile = meta?.let { dbFor(it.builtAt) }?.takeIf { it.isFile }
        val previous = previousFile?.let { JdbcSqliteDriver("jdbc:sqlite:${it.path}", Properties(), GuideDatabase.Schema) }
        val outcomes = try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${part.path}", Properties(), GuideDatabase.Schema)
            try {
                val outcomes = builder.build(inputs.epgSources, GuideDatabase(driver), previous?.let { GuideDatabase(it) }, now)
                if (outcomes.values.none { it.programs > 0 }) error(outcomes.values.firstNotNullOfOrNull { it.error }?.let { "No programs: $it" } ?: "No programs")
                GuideDatabase(driver).guideQueries.run {
                    putMeta(GuideStore.META_BUILT_AT, now.toString())
                    putMeta(GuideStore.META_SCHEMA, GuideStore.SCHEMA.toString())
                    putMeta(GuideStore.META_ORIGIN, GuideStore.ORIGIN_NAS)
                }
                outcomes
            } finally {
                driver.close()
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            previous?.close()
        }

        // Check what was written before anyone can download it.
        val (schema, programs) = DriverManager.getConnection("jdbc:sqlite:${part.path}").use { c ->
            c.createStatement().use { s ->
                val schema = s.executeQuery("SELECT value FROM meta WHERE key = '${GuideStore.META_SCHEMA}'").use { if (it.next()) it.getString(1) else null }
                val programs = s.executeQuery("SELECT COUNT(*) FROM program").use { it.next(); it.getLong(1) }
                schema to programs
            }
        }
        if (schema != GuideStore.SCHEMA.toString() || programs == 0L) {
            part.delete()
            error("The new guide didn't check out (schema $schema, $programs programs)")
        }

        val db = dbFor(now)
        if (!part.renameTo(db)) error("Couldn't move the new guide into place")
        val gz = gzFor(now)
        val gzPart = File(dir, gz.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        GZIPOutputStream(DigestOutputStream(gzPart.outputStream().buffered(), digest)).use { out -> db.inputStream().use { it.copyTo(out, 64 * 1024) } }
        if (!gzPart.renameTo(gz)) error("Couldn't move the gzipped guide into place")

        val published = NasGuideMeta(
            builtAt = now,
            schema = GuideStore.SCHEMA,
            sizeBytes = gz.length(),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            channels = outcomes.values.sumOf { it.channels },
            programs = programs.toInt(),
            outcomes = outcomes.mapValues { (_, o) -> NasGuideOutcome(o.channels, o.programs, o.error, o.carriedOver) },
        )
        val metaPart = File(dir, "guide.json.part")
        metaPart.writeText(json.encodeToString(NasGuideMeta.serializer(), published))
        if (!metaPart.renameTo(metaFile)) {
            metaFile.delete()
            metaPart.renameTo(metaFile)
        }
        meta = published
        // Only now is the previous build no longer needed.
        dir.listFiles()?.filter { it.name.startsWith("guide-") && it != db && it != gz }?.forEach { it.delete() }
        log.info("Guide built: ${published.programs} programs, ${published.channels} channels, ${published.sizeBytes / 1024} KB gzipped")
        return published
    }
}

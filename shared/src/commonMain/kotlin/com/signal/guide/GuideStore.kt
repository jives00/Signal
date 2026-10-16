package com.signal.guide

import app.cash.sqldelight.db.SqlDriver
import com.signal.data.Log
import com.signal.db.guide.GuideDatabase
import com.signal.platform.Platform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The live guide.db and its replacement (PLAN §6). Each build writes a new file beside the live
 * one, named by its build time; when it's complete it's swapped in, and the old file is closed
 * and deleted a minute later, once any read still using it has finished. Nothing is renamed over
 * an open database.
 */
class GuideStore(
    private val platform: Platform,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val log: Log,
) {
    private class Opened(val path: String, val driver: SqlDriver, val db: GuideDatabase)

    @kotlin.concurrent.Volatile private var live: Opened? = null
    /** The live guide's driver, for tests that compare raw queries. */
    internal val liveDriver: SqlDriver? get() = live?.driver
    private val buildLock = Mutex()

    private val _version = MutableStateFlow(0)
    /** Bumped on every swap, so readers drop what they cached from the old guide. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The live guide, or null when none has been built yet. */
    val db: GuideDatabase? get() = live?.db

    /** At launch: opens the newest complete guide and deletes everything else in the directory. */
    fun open() {
        val dir = platform.guideDirectory
        val files = GuideFiles.list(dir)
        val newest = files.filter { NAME.matches(it.substringAfterLast('/').substringAfterLast('\\')) }.maxOrNull()
        files.filter { it != newest && !it.endsWith("-journal") }.forEach(GuideFiles::delete)
        if (newest != null) {
            val opened = runCatching { openFile(newest) }.getOrNull()
            val current = opened != null && runCatching { opened.db.guideQueries.meta(META_SCHEMA).executeAsOneOrNull() == SCHEMA.toString() }.getOrDefault(false)
            if (current) {
                live = opened
                _version.value++
            } else {
                // guide.db is never migrated (PLAN §5.4): a file from another schema is deleted, and the stale guide rebuilds.
                log.warn("Guide file from another schema, deleted")
                runCatching { opened?.driver?.close() }
                GuideFiles.delete(newest)
            }
        }
    }

    /** The guide's build time, or null when there's none. */
    fun builtAt(): Long? = db?.guideQueries?.meta(META_BUILT_AT)?.executeAsOneOrNull()?.toLongOrNull()

    /** Where the live guide came from: [ORIGIN_NAS] or [ORIGIN_DEVICE] (older files have none). */
    fun origin(): String? = db?.guideQueries?.meta(META_ORIGIN)?.executeAsOneOrNull()

    /**
     * Writes a new guide with [write] and swaps it in. If [write] throws, or returns false (nothing
     * worth keeping), the new file is deleted and the live guide stays. Builds never overlap.
     */
    suspend fun build(now: Long, write: suspend (fresh: GuideDatabase, previous: GuideDatabase?) -> Boolean): Boolean = buildLock.withLock {
        withContext(io) { buildLocked(now, write) }
    }

    private suspend fun buildLocked(now: Long, write: suspend (fresh: GuideDatabase, previous: GuideDatabase?) -> Boolean): Boolean {
        val dir = platform.guideDirectory
        val finalPath = GuideFiles.join(dir, "guide-$now.db")
        val partPath = "$finalPath.part"
        GuideFiles.delete(partPath)
        val keep = try {
            val driver = platform.createGuideDatabaseDriver(partPath)
            try {
                write(GuideDatabase(driver), live?.db).also { keep ->
                    if (keep) GuideDatabase(driver).guideQueries.run {
                        putMeta(META_BUILT_AT, now.toString())
                        putMeta(META_SCHEMA, SCHEMA.toString())
                        putMeta(META_ORIGIN, ORIGIN_DEVICE)
                    }
                }
            } finally {
                driver.close()
            }
        } catch (e: Throwable) {
            GuideFiles.delete(partPath)
            throw e
        }
        if (!keep || !GuideFiles.rename(partPath, finalPath)) {
            GuideFiles.delete(partPath)
            return false
        }
        swapIn(finalPath)
        return true
    }

    /**
     * Swaps in a complete guide.db built elsewhere (the NAS, PLAN §6), after checking it opens and has
     * this app's schema. The file at [path] is moved into the guide directory, or deleted if it fails.
     */
    suspend fun install(path: String): Boolean = buildLock.withLock {
        withContext(io) {
            val ok = runCatching {
                val driver = platform.createGuideDatabaseDriver(path)
                try {
                    GuideDatabase(driver).guideQueries.meta(META_SCHEMA).executeAsOneOrNull() == SCHEMA.toString()
                } finally {
                    driver.close()
                }
            }.getOrDefault(false)
            val finalPath = GuideFiles.join(platform.guideDirectory, "guide-${kotlin.time.Clock.System.now().toEpochMilliseconds()}.db")
            if (!ok || !GuideFiles.rename(path, finalPath)) {
                GuideFiles.delete(path)
                return@withContext false
            }
            swapIn(finalPath)
            true
        }
    }

    private fun swapIn(finalPath: String) {
        val old = live
        live = openFile(finalPath)
        _version.value++
        if (old != null) scope.launch {
            delay(60_000)
            runCatching { old.driver.close() }
            GuideFiles.delete(old.path)
        }
    }

    private fun openFile(path: String): Opened {
        val driver = platform.createGuideDatabaseDriver(path)
        return Opened(path, driver, GuideDatabase(driver))
    }

    companion object {
        const val META_BUILT_AT = "built_at"
        const val META_SCHEMA = "schema"
        const val META_ORIGIN = "origin"
        const val ORIGIN_DEVICE = "device"
        const val ORIGIN_NAS = "nas"
        /** Bumped whenever Guide.sq changes: older files are deleted rather than migrated. 2: guide_channel_name. */
        const val SCHEMA = 2
        private val NAME = Regex("""guide-\d+\.db""")
    }
}

package com.signal.server

import com.signal.app.ProviderAccounts
import com.signal.model.Source
import com.signal.parse.XtreamParser
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("Recorder")

/** One way to fetch the channel. [account] is what to ask about it; null when there's nothing to ask (M3U). */
class RecordingLogin(val name: String, val key: String, val url: String, val headers: Map<String, String>, val account: Source?)

/** The provider answered a stream request with an error. */
class StreamRefused(val status: Int) : Exception("HTTP $status")

fun interface StreamFetch {
    /** Delivers the stream until it ends, and returns then. Throws when it's refused or drops. */
    suspend fun stream(url: String, headers: Map<String, String>, onData: (ByteArray, Int) -> Unit)
}

class HttpStreamFetch(private val http: HttpClient) : StreamFetch {
    override suspend fun stream(url: String, headers: Map<String, String>, onData: (ByteArray, Int) -> Unit) {
        http.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { response ->
            if (!response.status.isSuccess()) throw StreamRefused(response.status.value)
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = channel.readAvailable(buffer)
                if (n < 0) break
                if (n > 0) onData(buffer, n)
            }
        }
    }
}

/** Why a recording can't go on, whatever connection it has: no point retrying. */
private class RecordingStopped(message: String) : Exception(message)

/**
 * Saves live channels to the recordings share (dvr.md).
 *
 * A TV always beats a recording. The provider closes the older of two TS streams on a login, so a TV
 * that starts on a login being recorded just plays. The danger is the reverse: a recording that
 * connects to a login in use cuts that TV off. So a recording only ever connects to a login the
 * provider says is free, every time, reconnects included, and an unclear answer counts as busy.
 *
 * HTTP errors can carry the stream's URL, which holds the login, so only their type is ever logged
 * or stored.
 */
class Recorder(
    private val root: File?,
    private val store: () -> RecordingStore?,
    /** The logins that can fetch this recording's channel, best first. */
    private val logins: suspend (Recording) -> List<RecordingLogin>,
    private val askProvider: suspend (Source) -> XtreamParser.AccountInfo?,
    private val fetch: StreamFetch,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Timing(
        /** How often a waiting recording asks again. */
        val retryMs: Long = 30_000,
        /** After a drop, long enough for the provider to stop counting the old connection (about 5 s). */
        val reconnectMs: Long = 10_000,
        /** Before a second try at an unclear answer. */
        val recheckMs: Long = 2_000,
        /** Below this nothing starts; below a fifth of it, anything recording stops. */
        val minFreeBytes: Long = 5L shl 30,
        /** Replaced by the retention settings in the next stage. */
        val keepMs: Long = 30 * 24 * 60 * 60_000L,
    )

    private companion object Limits {
        const val STUCK_AFTER_MS = 5 * 60_000L
    }

    private class Run(@Volatile var recording: Recording, val resumed: Boolean) {
        lateinit var job: Job
        var reason: String? = null
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val picking = Mutex()
    /** Logins our own recordings are on, which the provider may not count yet. */
    private val held = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var stopping = false

    /** Why recording can't start now, or null. The folder has to be the mounted share: never fall back to the container's disk. */
    fun problem(): String? {
        val dir = root ?: return "Recording isn't set up: RECORDINGS_DIR isn't set."
        return when {
            !dir.isDirectory -> "The recordings folder isn't mounted."
            !dir.canWrite() -> "Can't write to the recordings folder."
            dir.usableSpace < timing.minFreeBytes -> "The recordings folder is nearly full."
            else -> null
        }
    }

    fun freeBytes(): Long = root?.takeIf { it.isDirectory }?.usableSpace ?: 0

    /**
     * For the deploy guard: true while anything is recording or waiting to, restarted or not. A row
     * well past its end with nothing running is stuck, and must never hold deploys back for good.
     */
    fun busy(): Boolean = runs.isNotEmpty() ||
        runCatching { store()?.unfinished().orEmpty().any { it.stopMs > now() - STUCK_AFTER_MS } }.getOrDefault(false)

    /** Starts recording now, until [stopMs]. Throws [IllegalStateException] with the reason when it can't. */
    fun start(profileId: String, streamId: String, title: String, channelName: String, stopMs: Long): Recording {
        problem()?.let { error(it) }
        if (stopping) error("The companion is shutting down.")
        val store = store() ?: error("The database isn't connected yet.")
        val at = now()
        val recording = Recording(UUID.randomUUID().toString(), profileId, streamId, title, channelName, at, stopMs, RecordingState.WAITING, updatedAt = at)
        store.save(recording)
        launch(Run(recording, resumed = false))
        return recording
    }

    /** Ends a recording early, keeping what it has. False when it isn't running. */
    fun stop(id: String): Boolean {
        val run = runs[id] ?: return false
        run.job.cancel()
        return true
    }

    /** Removes a finished recording and its files. Null when there's no such recording; false while it's running. */
    fun delete(id: String): Boolean? {
        val store = store() ?: return null
        val recording = store.get(id) ?: return null
        if (runs.containsKey(id)) return false
        folder(recording)?.deleteRecursively()
        store.delete(id)
        return true
    }

    /** A file from a recording's folder, or null. */
    fun file(id: String, name: String): File? {
        if (!RecordingFiles.servable(name)) return null
        val recording = store()?.get(id) ?: return null
        return folder(recording)?.resolve(name)?.takeIf { it.isFile }
    }

    /**
     * After a restart: recordings still running carry on, with a gap for the time down, and any whose
     * end has passed are closed as they stand.
     */
    fun recover() {
        val store = store() ?: return
        store.unfinished().filter { !runs.containsKey(it.id) }.forEach { recording ->
            if (recording.stopMs > now() && problem() == null) {
                log.info("Resuming a recording: ${recording.title}")
                launch(Run(recording, resumed = true))
            } else {
                val missed = (recording.stopMs - recording.updatedAt).coerceAtLeast(0)
                val run = Run(recording.copy(gaps = recording.gaps + 1, gapMs = recording.gapMs + missed), resumed = true)
                run.reason = "The companion was restarted."
                folder(recording)?.takeIf { it.isDirectory }?.let { dir -> runCatching { RecordingFiles(dir).end() } }
                finish(run)
            }
        }
    }

    /** On SIGTERM: closes what's open and leaves each recording unfinished, for [recover]. */
    fun shutdown(timeoutMs: Long = 5_000) {
        stopping = true
        val jobs = runs.values.map { it.job }
        jobs.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(timeoutMs) { jobs.joinAll() } }
    }

    private fun folder(recording: Recording): File? {
        val base = root?.canonicalFile ?: return null
        val dir = File(base, "${recording.profileId}/${recording.id}").canonicalFile
        return dir.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun launch(run: Run) {
        runs[run.recording.id] = run
        run.job = scope.launch(Dispatchers.IO) {
            try {
                record(run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything left unfinished would look busy, so nothing leaves here without an end.
                log.error("A recording failed: ${e.javaClass.simpleName}")
                run.reason = "Something went wrong while recording."
                finish(run)
            } finally {
                runs.remove(run.recording.id)
            }
        }
    }

    private fun update(run: Run, change: Recording.() -> Recording) {
        run.recording = run.recording.change().copy(updatedAt = now())
        // A database blip mustn't cost the recording; the next update carries this one's values.
        runCatching { store()?.save(run.recording) }.onFailure { log.warn("Couldn't save a recording's state: ${it.javaClass.simpleName}") }
    }

    private suspend fun record(run: Run) {
        val dir = folder(run.recording) ?: return finish(run)
        var gapFrom: Long? = if (run.resumed) run.recording.updatedAt else null
        val files = RecordingFiles(dir) { durationMs, bytes ->
            update(run) { copy(durationMs = this.durationMs + durationMs, bytes = this.bytes + bytes) }
            if (dir.usableSpace < timing.minFreeBytes / 5) throw RecordingStopped("The recordings folder is nearly full.")
        }
        try {
            while (true) {
                val left = run.recording.stopMs - now()
                if (left <= 0) break
                val login = pick(run)
                if (login == null) {
                    gapFrom = gapFrom ?: now()
                    update(run) { copy(state = RecordingState.WAITING) }
                    delay(minOf(timing.retryMs, left))
                    continue
                }
                update(run) { copy(state = RecordingState.RECORDING, loginName = login.name) }
                val segmenter = TsSegmenter(files)
                var dropped = true
                try {
                    val ended = withTimeoutOrNull(left) {
                        fetch.stream(login.url, login.headers) { data, length ->
                            gapFrom?.let { from ->
                                update(run) { copy(gaps = gaps + 1, gapMs = gapMs + (now() - from).coerceAtLeast(0)) }
                                gapFrom = null
                            }
                            segmenter.feed(data, 0, length)
                        }
                        true
                    }
                    if (ended == null) dropped = false else log.info("Stream ended: ${run.recording.title}")
                } catch (e: StreamRefused) {
                    run.reason = "The provider refused the stream (${e.status})."
                    log.info("Stream refused, ${e.status}: ${run.recording.title}")
                } catch (e: RecordingStopped) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    run.reason = "The stream dropped."
                    log.info("Stream dropped, ${e.javaClass.simpleName}: ${run.recording.title}")
                } finally {
                    held.remove(login.key)
                    segmenter.close()
                }
                if (!dropped) break
                gapFrom = gapFrom ?: now()
                files.markGap()
                update(run) { copy(state = RecordingState.WAITING) }
                delay(minOf(timing.reconnectMs, run.recording.stopMs - now()).coerceAtLeast(0))
            }
        } catch (e: CancellationException) {
            // Stopped by hand: fall through and finish. At shutdown, stay unfinished for recover().
            if (stopping) throw e
        } catch (e: RecordingStopped) {
            run.reason = e.message
            log.warn("Recording stopped: ${e.message}")
        } catch (e: IOException) {
            run.reason = "Couldn't write to the recordings folder."
            log.error("Couldn't write a recording: ${e.javaClass.simpleName}")
        }
        gapFrom?.let { from ->
            val end = minOf(now(), run.recording.stopMs)
            run.recording = run.recording.copy(gaps = run.recording.gaps + 1, gapMs = run.recording.gapMs + (end - from).coerceAtLeast(0))
        }
        runCatching { files.end() }
        finish(run)
    }

    private fun finish(run: Run) {
        val r = run.recording
        val state = when {
            r.bytes == 0L -> RecordingState.FAILED
            r.gaps > 0 -> RecordingState.PARTIAL
            else -> RecordingState.DONE
        }
        val at = now()
        val reason = when (state) {
            RecordingState.FAILED -> run.reason ?: "No login was free."
            RecordingState.PARTIAL -> run.reason
            else -> null
        }
        update(run) { copy(state = state, endedAt = at, reason = reason, expiresAt = at + timing.keepMs) }
        log.info("Recording ${state.name.lowercase()}: ${r.title}, ${r.durationMs / 1000} s, ${r.gaps} gaps")
    }

    /** The first login that's free now, taken under a lock so two recordings can't pick the same one. */
    private suspend fun pick(run: Run): RecordingLogin? = picking.withLock {
        val options = logins(run.recording)
        if (options.isEmpty()) run.reason = "That channel isn't available."
        for (login in options) {
            if (login.key !in held && free(login)) {
                held.add(login.key)
                return@withLock login
            }
        }
        null
    }

    private suspend fun free(login: RecordingLogin): Boolean {
        val account = login.account ?: return true
        repeat(2) { attempt ->
            ProviderAccounts.isFree(askProvider(account))?.let { return it }
            if (attempt == 0) delay(timing.recheckMs)
        }
        return false
    }
}

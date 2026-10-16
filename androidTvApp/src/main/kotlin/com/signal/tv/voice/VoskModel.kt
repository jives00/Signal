package com.signal.tv.voice

import android.content.Context
import android.util.Log
import com.signal.platform.VoiceStatus
import org.vosk.Model
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * The speech models, downloaded once into the app's files rather than shipped in the APK (PLAN
 * §4.3.14 → Voice commands). Both Apache 2.0.
 *
 * - [VoskModel]: the larger English model with a runtime grammar ("lgraph", about 130 MB), for
 *   channels and commands. The small one mixed up letters too often for initials like CBS (2026-10-15).
 * - [VoskModel.Free]: the small English model (about 40 MB), for the free recognizer's titles. The
 *   lgraph model without a grammar decoded slower than speech on the Shield (9.9 s for "play the
 *   matrix", 0.2.27); the small one is built for real time on devices like it.
 */
internal object VoskModel {
    private const val TAG = "Signal"

    /** One model: its download, its loading, and its state. */
    class Downloaded(private val name: String, private val label: String) {
        private val url = "https://alphacephei.com/vosk/models/$name.zip"
        @Volatile var model: Model? = null
            private set
        @Volatile private var preparing = false
        @Volatile var failed = false
            private set
        @Volatile var downloading = false
            private set

        val isPreparing get() = preparing

        fun downloaded(context: Context) = File(context.filesDir, "vosk/$name.ready").exists()

        /** Downloads and loads it, once (again after a failure); [then] runs once it's loaded. */
        fun prepare(context: Context, then: () -> Unit = {}) {
            if (model != null || preparing) return
            preparing = true
            failed = false
            // Low priority: it loads as Signal opens, and competed with the guide's first rows (2026-10-16).
            thread(name = "vosk-$label", priority = Thread.MIN_PRIORITY) {
                runCatching {
                    val dir = File(context.filesDir, "vosk")
                    val modelDir = File(dir, name)
                    val ready = File(dir, "$name.ready")
                    if (!ready.exists()) {
                        downloading = true
                        try { download(dir, modelDir, ready) } finally { downloading = false }
                    }
                    val started = System.currentTimeMillis()
                    model = Model(modelDir.absolutePath)
                    Log.i(TAG, "Voice: $label model loaded in ${System.currentTimeMillis() - started} ms")
                    then()
                }.onFailure { failed = true; Log.e(TAG, "Voice: $label model failed", it) }
                preparing = false
            }
        }

        private fun download(dir: File, modelDir: File, ready: File) {
            Log.i(TAG, "Voice: downloading the $label model")
            dir.mkdirs()
            modelDir.deleteRecursively()
            val started = System.currentTimeMillis()
            ZipInputStream(URL(url).openStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(dir, entry.name)
                    // Zip entries must stay inside the folder.
                    require(out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Bad zip entry ${entry.name}" }
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            ready.createNewFile()
            // Models from earlier versions: only the ones in use stay.
            dir.listFiles()?.filter { f -> USED.none { f.name == it || f.name == "$it.ready" } }?.forEach { it.deleteRecursively() }
            Log.i(TAG, "Voice: $label model downloaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    private const val MAIN_NAME = "vosk-model-en-us-0.22-lgraph"
    private const val FREE_NAME = "vosk-model-small-en-us-0.15"
    private val USED = listOf(MAIN_NAME, FREE_NAME)
    private const val FREE_DELAY_MS = 60_000L

    private val main = Downloaded(MAIN_NAME, "speech")
    /** The free recognizer's model; until it's loaded, the free recognizer uses the main one. */
    val Free = Downloaded(FREE_NAME, "title")

    /** For Settings → Voice commands: the main model's state, which voice commands need. */
    fun state(context: Context): VoiceStatus.Model = when {
        main.model != null -> VoiceStatus.Model.READY
        main.downloading -> VoiceStatus.Model.DOWNLOADING
        main.isPreparing -> VoiceStatus.Model.LOADING
        main.failed -> VoiceStatus.Model.FAILED
        main.downloaded(context) -> VoiceStatus.Model.LOADING
        else -> VoiceStatus.Model.NOT_DOWNLOADED
    }

    /** The loaded main model, or null while it downloads or loads (which [prepare] starts). */
    fun get(context: Context): Model? = main.model ?: run { prepare(context); null }

    /** The main model if it's loaded; never starts loading. */
    fun loaded(): Model? = main.model

    /** Downloads and loads the main model in the background, then the free recognizer's. */
    fun prepare(context: Context) {
        main.prepare(context) {
            val app = context.applicationContext as com.signal.tv.SignalApplication
            ReadyRecognizer.warm(app)
            ReadyRecognizer.follow(app)
            // The title model a minute later: until then the free recognizer uses the main model, and
            // loading both as Signal opened (8 s on the Shield) slowed the guide's first rows.
            thread(name = "vosk-title-wait", priority = Thread.MIN_PRIORITY) {
                Thread.sleep(FREE_DELAY_MS)
                Free.prepare(context) { ReadyRecognizer.warm(app) }
            }
        }
    }
}

package com.signal.tv.voice

import android.content.Context
import android.util.Log
import org.vosk.Model
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Vosk's larger English model with a runtime grammar ("lgraph", about 130 MB, Apache 2.0): the small
 * one (40 MB) mixed up letters too often for initials like CBS (2026-10-15). Downloaded once into
 * the app's files rather than shipped in the APK (PLAN §4.3.14 → Voice commands).
 */
internal object VoskModel {
    private const val TAG = "Signal"
    private const val NAME = "vosk-model-en-us-0.22-lgraph"
    private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    @Volatile private var model: Model? = null
    @Volatile private var preparing = false

    /** The loaded model, or null while it downloads or loads (which [prepare] starts). */
    fun get(context: Context): Model? = model ?: run { prepare(context); null }

    /** The model if it's loaded; never starts loading. */
    fun loaded(): Model? = model

    /** Downloads and loads the model in the background, once. */
    fun prepare(context: Context) {
        if (model != null || preparing) return
        preparing = true
        thread(name = "vosk-model") {
            runCatching {
                val dir = File(context.filesDir, "vosk")
                val modelDir = File(dir, NAME)
                val ready = File(dir, "$NAME.ready")
                if (!ready.exists()) download(dir, modelDir, ready)
                val started = System.currentTimeMillis()
                model = Model(modelDir.absolutePath)
                Log.i(TAG, "Voice: speech model loaded in ${System.currentTimeMillis() - started} ms")
                val app = context.applicationContext as com.signal.tv.SignalApplication
                ReadyRecognizer.warm(app)
                ReadyRecognizer.follow(app)
            }.onFailure { Log.e(TAG, "Voice: speech model failed", it) }
            preparing = false
        }
    }

    private fun download(dir: File, modelDir: File, ready: File) {
        Log.i(TAG, "Voice: downloading the speech model")
        dir.mkdirs()
        modelDir.deleteRecursively()
        val started = System.currentTimeMillis()
        ZipInputStream(URL(URL).openStream().buffered()).use { zip ->
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
        // Other models from earlier versions: only this one is used.
        dir.listFiles()?.filter { it.name != NAME && it.name != "$NAME.ready" }?.forEach { it.deleteRecursively() }
        Log.i(TAG, "Voice: speech model downloaded in ${System.currentTimeMillis() - started} ms")
    }
}

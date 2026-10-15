package com.signal.tv.voice

import android.util.Log
import com.signal.tv.SignalApplication
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * One Vosk recognizer, built ahead of the mic press (PLAN §4.3.14 → Voice commands). Building its
 * grammar from every channel name takes seconds on the Shield, longer than people wait before
 * talking, so a press reuses the one ready and it's rebuilt in the background only when the
 * channel names change.
 */
internal object ReadyRecognizer {
    private const val TAG = "Signal"
    private const val SAMPLE_RATE = 16000f
    private const val MAX_GUESSES = 5

    private val background = Executors.newSingleThreadExecutor { Thread(it, "voice-grammar") }
    private var recognizer: Recognizer? = null
    private var phrasesKey = 0

    @Volatile private var following = false

    /**
     * Rebuilds whenever the channels change (a refresh, hiding a group, a new voice name), a few
     * seconds after they settle, so the next press already knows them.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun follow(app: SignalApplication) {
        if (following) return
        following = true
        app.controller.scope.launch {
            app.controller.search.versions.drop(1).debounce(5_000).collect { warm(app) }
        }
    }

    /** Builds or refreshes the recognizer in the background, once the model is loaded. */
    fun warm(app: SignalApplication) {
        background.execute {
            val model = VoskModel.loaded() ?: return@execute
            runCatching { refresh(app, model) }.onFailure { Log.e(TAG, "Voice: preparing the recognizer failed", it) }
        }
    }

    /**
     * Runs [block] with the recognizer, building it first if it isn't ready, and resets it after.
     * Holds the lock throughout, so a refresh never swaps it mid-press.
     */
    @Synchronized
    fun <T> use(app: SignalApplication, model: Model, block: (Recognizer) -> T): T {
        val rec = recognizer ?: refresh(app, model)
        try {
            return block(rec)
        } finally {
            rec.reset()
        }
    }

    /** The slow part, the phrases, is outside the lock; only the swap holds it. */
    private fun refresh(app: SignalApplication, model: Model): Recognizer {
        val started = System.currentTimeMillis()
        val phrases = kotlinx.coroutines.runBlocking { app.controller.voicePhrases() }
        val key = phrases.hashCode()
        synchronized(this) {
            recognizer?.takeIf { key == phrasesKey }?.let { return it }
        }
        // Only the commands' phrases and the channels' names, so a near miss like "nbt" can't win
        // over "nbc". "[unk]" takes everything else.
        val rec = Recognizer(model, SAMPLE_RATE, JSONArray(phrases + "[unk]").toString())
        // Its top guesses, not just the best: the controller takes the first that names a channel.
        rec.setMaxAlternatives(MAX_GUESSES)
        synchronized(this) {
            recognizer?.close()
            recognizer = rec
            phrasesKey = key
        }
        Log.i(TAG, "Voice: recognizer built with ${phrases.size} phrases in ${System.currentTimeMillis() - started} ms")
        return rec
    }
}

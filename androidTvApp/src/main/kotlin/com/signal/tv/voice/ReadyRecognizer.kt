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

    // Low priority: it builds as Signal opens, beside the guide's first rows (2026-10-16).
    private val background = Executors.newSingleThreadExecutor { Thread(it, "voice-grammar").apply { priority = Thread.MIN_PRIORITY } }
    private var recognizer: Recognizer? = null
    private var phrasesKey = 0
    private var recognizerModel: Model? = null

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
            app.controller.voiceInputs.drop(1).debounce(5_000).collect { warm(app) }
        }
    }

    /** Builds or refreshes the recognizer in the background, once the model is loaded. */
    fun warm(app: SignalApplication) {
        background.execute {
            val model = VoskModel.loaded(app) ?: return@execute
            runCatching { refresh(app, model) }.onFailure { Log.e(TAG, "Voice: preparing the recognizer failed", it) }
            // The free one too, so the first press that needs it doesn't wait for it to be built.
            synchronized(this) { freeFor(model) }
        }
    }

    /**
     * The free recognizer (PLAN §4.3.14 → Voice commands, phase 2): no grammar, so it hears any
     * words, for movie and series titles. It needs no phrases, so it's built once per model.
     */
    private var free: Recognizer? = null
    private var freeModel: Model? = null

    /**
     * Runs [block] with the grammar recognizer and the free one, building them first if they
     * aren't ready, and resets them after. Holds the lock throughout, so a refresh never swaps
     * one mid-press.
     */
    @Synchronized
    fun <T> use(app: SignalApplication, model: Model, block: (Recognizer, Recognizer?) -> T): T {
        val rec = recognizer ?: refresh(app, model)
        val open = freeFor(model)
        try {
            return block(rec, open)
        } finally {
            rec.reset()
            open?.reset()
        }
    }

    /**
     * The free recognizer, built once per model: on the small title model once it's loaded
     * (VoskModel.Free, fast enough for real time), else on [main]. Called holding the lock.
     */
    private fun freeFor(main: Model): Recognizer? {
        val model = VoskModel.Free.model ?: main
        return free?.takeIf { freeModel === model } ?: runCatching {
            free?.close()
            Recognizer(model, SAMPLE_RATE).also { free = it; freeModel = model }
        }.onFailure { Log.e(TAG, "Voice: the free recognizer failed", it) }.getOrNull()
    }

    /** The slow part, the phrases, is outside the lock; only the swap holds it. */
    private fun refresh(app: SignalApplication, model: Model): Recognizer {
        val started = System.currentTimeMillis()
        val phrases = kotlinx.coroutines.runBlocking { app.controller.voicePhrases() }
        val key = phrases.hashCode()
        synchronized(this) {
            // A new model (Settings → Speech model) rebuilds it even with the same phrases.
            recognizer?.takeIf { key == phrasesKey && model === recognizerModel }?.let { return it }
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
            recognizerModel = model
        }
        Log.i(TAG, "Voice: recognizer built with ${phrases.size} phrases in ${System.currentTimeMillis() - started} ms")
        // On the fast setting, the accurate model is no longer needed (a change of setting, or files
        // from before it): let it go. Nothing to do otherwise.
        VoskModel.retire(app)
        return rec
    }
}

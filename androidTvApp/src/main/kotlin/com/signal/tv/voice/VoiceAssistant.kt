package com.signal.tv.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.Log
import com.signal.tv.MainActivity
import com.signal.tv.SignalApplication
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.concurrent.thread

/*
 * Signal as the device's assistant (PLAN §4.3.14 → Voice commands). The remote's mic button opens
 * a session here from anywhere, and the remote service plays the remote's audio to whichever app
 * is the assistant, so a plain AudioRecord hears it. Vosk turns it into text on the device.
 *
 * Signal becomes the assistant with:
 *   adb shell cmd role add-role-holder android.app.role.ASSISTANT com.signal.tv
 *   adb shell pm grant com.signal.tv android.permission.RECORD_AUDIO
 * and Google's comes back with the same role command and com.google.android.katniss.
 */

private const val TAG = "Signal"
private const val SAMPLE_RATE = 16000
/** Longest a press listens, in case the end of speech is never detected. */
private const val MAX_LISTEN_MS = 8000L

class SignalAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Voice: Signal is the assistant")
        VoskModel.prepare(this)
    }
}

class SignalAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ListenSession(this)
}

/** Required for an assistant to be valid. Other apps' speech requests aren't served. */
class SignalRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

/** One press of the mic button: listen, recognize, act, with the overlay showing each step. */
private class ListenSession(context: Context) : VoiceInteractionSession(context) {
    @Volatile private var listening = false
    private var overlay: ListeningOverlay? = null
    private var closing: Job? = null

    override fun onCreateContentView(): View = ListeningOverlay(context).also { overlay = it }.view

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (listening) return
        closing?.cancel()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Voice: no microphone permission")
            // Signal asks for it as it opens.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.ASK_MICROPHONE, true))
            closeAfter(2500) { overlay?.message("Allow the microphone to use voice commands") }
            return
        }
        val model = VoskModel.get(context)
        if (model == null) {
            Log.w(TAG, "Voice: speech model not ready")
            closeAfter(2500) { overlay?.message("Voice isn't ready yet. Try again in a minute") }
            return
        }
        listening = true
        overlay?.listening()
        val app = context.applicationContext as SignalApplication
        thread(name = "voice") {
            val heard = runCatching {
                listen(model, app) { partial -> mainScope.launch { overlay?.heard(partial) } }
            }.onFailure { Log.e(TAG, "Voice: listening failed", it) }.getOrNull() ?: Heard(emptyList(), null)
            listening = false
            mainScope.launch {
                val result = app.controller.voice(heard.guesses, heard.free)
                overlay?.outcome(result.heard, result.best, result.shown, result.acted)
                // Bring Signal forward when it acted from another app or the home screen. The
                // overlay stays on top a moment so the outcome can be read.
                if (result.acted) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // Longer when there's more to read: a miss, or a later guess that won.
                closeAfter(if (result.acted && result.heard == result.best) 1500 else 3000)
            }
        }
    }

    private fun closeAfter(ms: Long, before: () -> Unit = {}) {
        before()
        closing?.cancel()
        closing = mainScope.launch { delay(ms); hide() }
    }

    override fun onHide() {
        listening = false
        closing?.cancel()
    }

    /** The grammar recognizer's guesses, best first, and what the free one heard. */
    private class Heard(val guesses: List<String>, val free: String?)

    /** Records until Vosk hears the end of speech, and returns what both recognizers heard. */
    @Suppress("MissingPermission")
    private fun listen(model: org.vosk.Model, app: SignalApplication, onPartial: (String) -> Unit): Heard {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Recording starts first: the remote only sends audio while its mic is open, and people
        // start talking at once. Three seconds of buffer cover a recognizer that isn't ready yet.
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE * 2 * 3))
        try {
            record.startRecording()
            return ReadyRecognizer.use(app, model) { rec, free -> recognize(record, rec, free, onPartial) }
        } finally {
            runCatching { record.stop() }
            record.release()
            ReadyRecognizer.warm(app) // picks up channel changes for the next press
        }
    }

    /**
     * Feeds the recording to both recognizers until the grammar one hears the end of speech,
     * typing out partials as it goes: the free one's, which has the real words where the grammar's
     * has "?". Both run on every press for now (PLAN §4.3.14 → Voice commands, phase 2); the log
     * line has the time each took, to judge the cost on the Shield.
     */
    private fun recognize(record: AudioRecord, rec: Recognizer, free: Recognizer?, onPartial: (String) -> Unit): Heard {
        val started = System.currentTimeMillis()
        val buffer = ShortArray(1600) // 100 ms
        var guesses = emptyList<String>()
        var partial = ""
        var freeText = ""
        var freeMs = 0L
        while (listening && System.currentTimeMillis() - started < MAX_LISTEN_MS) {
            val n = record.read(buffer, 0, buffer.size)
            if (n <= 0) break
            if (free != null) {
                val t = System.currentTimeMillis()
                // The free one may end an utterance early, on a pause mid-title: keep each piece.
                if (free.acceptWaveForm(buffer, n)) freeText = joined(freeText, JSONObject(free.result).optString("text"))
                freeMs += System.currentTimeMillis() - t
            }
            if (rec.acceptWaveForm(buffer, n)) {
                guesses = guesses(rec.result)
                // An end detected in the silence before speaking: keep listening.
                if (guesses.isNotEmpty()) break
            } else {
                val now = free?.let { joined(freeText, JSONObject(it.partialResult).optString("partial")) }?.ifBlank { null }
                    ?: JSONObject(rec.partialResult).optString("partial")
                if (now != partial) { partial = now; onPartial(now) }
            }
        }
        if (guesses.isEmpty()) guesses = guesses(rec.finalResult)
        if (free != null) {
            val t = System.currentTimeMillis()
            freeText = joined(freeText, JSONObject(free.finalResult).optString("text"))
            freeMs += System.currentTimeMillis() - t
        }
        Log.i(TAG, "Voice: heard ${guesses.joinToString(" | ") { "\"$it\"" }}, free \"$freeText\" in ${System.currentTimeMillis() - started} ms (free recognizer ${freeMs} ms)")
        return Heard(guesses, freeText.ifBlank { null })
    }

    private fun joined(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

    /** A result's guesses, best first, without blanks: `{"alternatives": [{"text": …}, …]}`. */
    private fun guesses(json: String): List<String> {
        val alternatives = JSONObject(json).optJSONArray("alternatives") ?: return listOfNotNull(JSONObject(json).optString("text").ifBlank { null })
        val all = (0 until alternatives.length()).map { alternatives.getJSONObject(it) }
        // Confidences, to choose a cutoff for weak guesses (PLAN §4.3.14 → Voice commands).
        if (all.isNotEmpty()) Log.i(TAG, "Voice: confidences " + all.joinToString(" | ") { "\"${it.optString("text")}\" ${"%.1f".format(it.optDouble("confidence"))}" })
        return all.map { it.optString("text").trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private companion object {
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

package com.signal.tv

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.signal.player.PlaybackState
import com.signal.ui.SignalApp
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val signal get() = application as SignalApplication
    private var started = false

    // Search's mic (PLAN §4.3.14): the system recognizer, so Signal needs no microphone permission.
    private var speechCallback: ((String) -> Unit)? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) speechCallback?.invoke(text)
        speechCallback = null
    }

    fun listenForSpeech(onText: (String) -> Unit) {
        speechCallback = onText
        runCatching {
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }.onFailure { speechCallback = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signal.platform.activity = WeakReference(this)
        // Black, not the theme's grey, behind video while it starts and between frames.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        val controller = signal.controller
        setContent { SignalApp(controller) }

        // Back belongs to the key layer, which acts on key down (PLAN §4.2). Android acts on key up,
        // which can arrive after the screen that handled the down is gone; without this it would
        // finish the activity. Exiting is the key layer's call too (Back on Home's nav bar).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        // The screen stays awake only while video plays; paused or idle, the screensaver works (PLAN §4.2).
        lifecycleScope.launch {
            signal.platform.player.state.collect { s ->
                if (s.status == PlaybackState.Status.PLAYING || s.status == PlaybackState.Status.BUFFERING) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    // The key layer consumes every remote key while anything in the app has focus. A remote key that
    // comes back unhandled means nothing does (seen after the system installer closed), and Compose
    // would drop every key from then on, so put focus back on the key layer and replay the press.
    // onKeyDown only runs for keys no view consumed.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in REMOTE_KEYS && event.repeatCount == 0) {
            signal.controller.dispatcher.recoverFocus()
            if (window.superDispatchKeyEvent(event)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Sleep, the screensaver, the Home button or another app: stop playback and free the stream;
    // coming back resumes where the user was (PLAN §4.2 → Sleep, the Home button and interruptions).
    override fun onStop() {
        super.onStop()
        signal.controller.onBackground()
    }

    override fun onStart() {
        super.onStart()
        if (started) signal.controller.onForeground()
        started = true
    }

    private companion object {
        val REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        )
    }
}

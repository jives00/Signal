package com.signal.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.signal.player.PlaybackState
import com.signal.tv.player.ExoVideoPlayer
import com.signal.ui.SignalApp
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val signal get() = application as SignalApplication
    private var started = false

    // Settings files (PLAN §9.2 → Fallback): the system picker. Throws ActivityNotFoundException where
    // Android TV has none, and AndroidSettingsFiles falls back to Downloads.
    private var createResult: kotlinx.coroutines.CompletableDeferred<android.net.Uri?>? = null
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        createResult?.complete(uri)
        createResult = null
    }
    private var openResult: kotlinx.coroutines.CompletableDeferred<android.net.Uri?>? = null
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        openResult?.complete(uri)
        openResult = null
    }

    /**
     * Whether a real file picker handles [action]. Android TV builds often register only
     * `frameworkpackagestubs`, which cancels at once, so that counts as none.
     */
    fun hasFilePicker(action: String): Boolean {
        val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
        val handler = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: return false
        return handler != "com.android.tv.frameworkpackagestubs" && handler != "android"
    }

    suspend fun pickFileToCreate(name: String): android.net.Uri? {
        val result = kotlinx.coroutines.CompletableDeferred<android.net.Uri?>()
        createResult = result
        createDocument.launch(name)
        return result.await()
    }

    suspend fun pickFileToOpen(): android.net.Uri? {
        val result = kotlinx.coroutines.CompletableDeferred<android.net.Uri?>()
        openResult = result
        openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        return result.await()
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
            if (signal.controller.dispatcher.recoverFocus() && window.superDispatchKeyEvent(event)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // The same for releases: focus can vanish between OK's down and its up (an info bar hiding under
    // the press), and a lost up left the key layer thinking OK was still held, so the next OK did
    // nothing (user, 2026-10-13).
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in REMOTE_KEYS) {
            if (signal.controller.dispatcher.recoverFocus() && window.superDispatchKeyEvent(event)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // Sleep, the screensaver, the Home button or another app: stop playback and free the stream;
    // coming back resumes where the user was (PLAN §4.2 → Sleep, the Home button and interruptions).
    override fun onStop() {
        super.onStop()
        signal.controller.onBackground()
    }

    override fun onStart() {
        super.onStart()
        if (started) {
            (signal.platform.player as? ExoVideoPlayer)?.rebuildView()
            signal.controller.onForeground()
        }
        started = true
        askForMicrophone()
    }

    // Voice commands (PLAN §4.3.14): once Signal is the assistant (by adb), it needs the microphone.
    // Asked once per launch; Android stops showing the prompt after it's been declined twice, and
    // Settings → Voice commands can ask again.
    private var askedForMicrophone = false

    // The assistant opens Signal with this when a press found no permission: ask even if already asked.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ASK_MICROPHONE, false)) { askedForMicrophone = false; askForMicrophone() }
    }

    private fun askForMicrophone() {
        if (askedForMicrophone) return
        val voice = signal.platform.voice
        val status = voice.status()
        if (status.isAssistant && !status.hasMicrophone) {
            askedForMicrophone = true
            voice.requestMicrophone()
        }
    }

    companion object {
        /** An extra: ask for the microphone even if this launch already asked (see onNewIntent). */
        const val ASK_MICROPHONE = "ask_microphone"

        private val REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        )
    }
}

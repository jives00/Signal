package com.signal.tv

import android.os.Bundle
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
}

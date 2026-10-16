package com.signal.tv

import android.app.Application
import com.signal.app.AppController
import com.signal.data.Log

/** Holds the app's object graph for the life of the process, across activity recreation. */
class SignalApplication : Application() {
    lateinit var platform: AndroidPlatform
        private set
    val controller: AppController by lazy {
        AppController(platform, Log { level, message ->
            when (level) {
                "E" -> android.util.Log.e(TAG, message)
                "W" -> android.util.Log.w(TAG, message)
                else -> android.util.Log.i(TAG, message)
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        // Back as the assistant after a force stop, when allowed (voice/AssistantKeeper.kt).
        com.signal.tv.voice.AssistantKeeper.start(this)
    }

    private companion object {
        const val TAG = "Signal"
    }
}

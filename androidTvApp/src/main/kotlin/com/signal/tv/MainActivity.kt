package com.signal.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.signal.ui.screens.SignalApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SignalApp(versionLabel = "Signal ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TAG}")
        }
    }
}

package com.signal.input

import androidx.compose.ui.input.key.KeyEvent

actual fun KeyEvent.isRepeat(): Boolean = nativeKeyEvent.repeatCount > 0

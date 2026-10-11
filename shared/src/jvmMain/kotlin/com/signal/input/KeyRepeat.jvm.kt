package com.signal.input

import androidx.compose.ui.input.key.KeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

// AWT has no repeat flag: a held key sends repeated KEY_PRESSED events. Track which keys are
// down, so a second press before the release counts as a repeat, matching Android.
private val down = HashSet<Int>()

actual fun KeyEvent.isRepeat(): Boolean {
    val awt = nativeKeyEvent as AwtKeyEvent
    return when (awt.id) {
        AwtKeyEvent.KEY_PRESSED -> !down.add(awt.keyCode)
        AwtKeyEvent.KEY_RELEASED -> { down.remove(awt.keyCode); false }
        else -> false
    }
}

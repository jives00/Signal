package com.signal.input

/**
 * Turns key downs and ups into [RemoteAction]s (PLAN §4.2 → Keys). Pure logic, so it's unit
 * tested; the Compose layer ([RemoteInput]) feeds it events and runs the long-press timer.
 *
 * - OK: a press held [LONG_PRESS_MS] fires [RemoteAction.Options] while still held. A shorter
 *   press fires [RemoteAction.Select] on release. Auto-repeat of OK is ignored.
 * - D-pad and channel keys act on key down and repeat while held, so holding scrolls.
 * - Back, Play/Pause, Rewind, Fast-forward and digits act once per press.
 */
class KeyInterpreter {
    private var okDown = false
    private var okLongPressed = false

    /** The action to perform now, plus whether the caller should start the long-press timer. */
    data class DownResult(val action: RemoteAction?, val startLongPressTimer: Boolean = false)

    fun keyDown(key: RemoteKey, isRepeat: Boolean): DownResult = when (key) {
        RemoteKey.OK -> if (isRepeat || okDown) DownResult(null) else {
            okDown = true
            okLongPressed = false
            DownResult(null, startLongPressTimer = true)
        }
        RemoteKey.UP -> DownResult(RemoteAction.Up)
        RemoteKey.DOWN -> DownResult(RemoteAction.Down)
        RemoteKey.LEFT -> DownResult(RemoteAction.Left)
        RemoteKey.RIGHT -> DownResult(RemoteAction.Right)
        RemoteKey.CHANNEL_UP -> DownResult(RemoteAction.ChannelUp)
        RemoteKey.CHANNEL_DOWN -> DownResult(RemoteAction.ChannelDown)
        else -> if (isRepeat) DownResult(null) else DownResult(once(key))
    }

    /** Called by the timer [LONG_PRESS_MS] after an OK down. Null if OK was already released. */
    fun longPressElapsed(): RemoteAction? {
        if (!okDown || okLongPressed) return null
        okLongPressed = true
        return RemoteAction.Options
    }

    fun keyUp(key: RemoteKey): RemoteAction? {
        if (key != RemoteKey.OK || !okDown) return null
        okDown = false
        return if (okLongPressed) null else RemoteAction.Select
    }

    /** Focus moved away or the window lost focus mid-press: forget the press without acting. */
    fun reset() {
        okDown = false
        okLongPressed = false
    }

    private fun once(key: RemoteKey): RemoteAction? = when (key) {
        RemoteKey.BACK -> RemoteAction.Back
        RemoteKey.PLAY_PAUSE -> RemoteAction.PlayPause
        RemoteKey.REWIND -> RemoteAction.Rewind
        RemoteKey.FAST_FORWARD -> RemoteAction.FastForward
        else -> key.digit()?.let(RemoteAction::Digit)
    }

    private fun RemoteKey.digit(): Int? = if (name.startsWith("DIGIT_")) name.removePrefix("DIGIT_").toInt() else null

    companion object {
        const val LONG_PRESS_MS = 500L
    }
}

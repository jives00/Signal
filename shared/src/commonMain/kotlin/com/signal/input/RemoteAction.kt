package com.signal.input

/**
 * What a key press means, independent of where it came from (PLAN §12.3). Screens handle these,
 * never raw keys, so the dev harness and the Shield behave identically and a remap is one change.
 */
sealed interface RemoteAction {
    data object Up : RemoteAction
    data object Down : RemoteAction
    data object Left : RemoteAction
    data object Right : RemoteAction
    /** OK, short press: acts on release, never waits for a possible long press. */
    data object Select : RemoteAction
    /** OK held about 500ms: the single "more" gesture, fired while still held (PLAN §4.2). */
    data object Options : RemoteAction
    data object Back : RemoteAction
    /** Each screen gives it its own meaning: recall, jump to now, audio tile, pause (PLAN §12.2). */
    data object PlayPause : RemoteAction
    data object Rewind : RemoteAction
    data object FastForward : RemoteAction
    data object ChannelUp : RemoteAction
    data object ChannelDown : RemoteAction
    data class Digit(val value: Int) : RemoteAction

    val isDirection: Boolean get() = this == Up || this == Down || this == Left || this == Right
}

/** The physical keys the app understands, after platform mapping (see [RemoteKeyMap]). */
enum class RemoteKey {
    UP, DOWN, LEFT, RIGHT, OK, BACK, PLAY_PAUSE, REWIND, FAST_FORWARD, CHANNEL_UP, CHANNEL_DOWN,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
}

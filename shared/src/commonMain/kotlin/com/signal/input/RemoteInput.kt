package com.signal.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalRemoteDispatcher = compositionLocalOf<RemoteDispatcher> { error("No RemoteInput above this point") }

/**
 * The one place raw keys become [RemoteAction]s, for every screen on every platform. Install it
 * once at the root. It consumes every key it maps, so platform defaults (Android's own D-pad
 * focus search, desktop Tab traversal) never run alongside it.
 */
@Composable
fun RemoteInput(dispatcher: RemoteDispatcher, content: @Composable () -> Unit) {
    val interpreter = remember { KeyInterpreter() }
    val scope = rememberCoroutineScope()
    val longPressJob = remember { arrayOfNulls<Job>(1) }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val key = RemoteKeyMap.map(event, textInputFocused = dispatcher.focusedItem?.isTextInput == true)
                ?: return@onPreviewKeyEvent false
            val repeat = event.isRepeat() // Called for downs and ups: the desktop tracker needs both.
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val result = interpreter.keyDown(key, isRepeat = repeat)
                    if (result.startLongPressTimer) {
                        longPressJob[0]?.cancel()
                        longPressJob[0] = scope.launch {
                            delay(KeyInterpreter.LONG_PRESS_MS)
                            interpreter.longPressElapsed()?.let(dispatcher::dispatch)
                        }
                    }
                    result.action?.let(dispatcher::dispatch)
                }
                KeyEventType.KeyUp -> {
                    if (key == RemoteKey.OK) longPressJob[0]?.cancel()
                    interpreter.keyUp(key)?.let(dispatcher::dispatch)
                }
            }
            true
        },
    ) { content() }
}

/** Platform key → [RemoteKey]. The dev harness keys follow PLAN §7.1 → Remote emulation. */
object RemoteKeyMap {
    fun map(event: KeyEvent, textInputFocused: Boolean): RemoteKey? = when (event.key) {
        Key.DirectionUp -> RemoteKey.UP
        Key.DirectionDown -> RemoteKey.DOWN
        Key.DirectionLeft -> RemoteKey.LEFT
        Key.DirectionRight -> RemoteKey.RIGHT
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> RemoteKey.OK
        Key.Back, Key.Escape -> RemoteKey.BACK
        Key.Backspace -> if (textInputFocused) null else RemoteKey.BACK
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> RemoteKey.PLAY_PAUSE
        Key.Spacebar -> if (textInputFocused) null else RemoteKey.PLAY_PAUSE
        Key.MediaRewind -> RemoteKey.REWIND
        Key.MediaFastForward -> RemoteKey.FAST_FORWARD
        Key.Comma -> if (textInputFocused) null else RemoteKey.REWIND
        Key.Period -> if (textInputFocused) null else RemoteKey.FAST_FORWARD
        Key.ChannelUp, Key.PageUp -> RemoteKey.CHANNEL_UP
        Key.ChannelDown, Key.PageDown -> RemoteKey.CHANNEL_DOWN
        else -> if (textInputFocused) null else digit(event.key)
    }

    private fun digit(key: Key): RemoteKey? = when (key) {
        Key.Zero, Key.NumPad0 -> RemoteKey.DIGIT_0
        Key.One, Key.NumPad1 -> RemoteKey.DIGIT_1
        Key.Two, Key.NumPad2 -> RemoteKey.DIGIT_2
        Key.Three, Key.NumPad3 -> RemoteKey.DIGIT_3
        Key.Four, Key.NumPad4 -> RemoteKey.DIGIT_4
        Key.Five, Key.NumPad5 -> RemoteKey.DIGIT_5
        Key.Six, Key.NumPad6 -> RemoteKey.DIGIT_6
        Key.Seven, Key.NumPad7 -> RemoteKey.DIGIT_7
        Key.Eight, Key.NumPad8 -> RemoteKey.DIGIT_8
        Key.Nine, Key.NumPad9 -> RemoteKey.DIGIT_9
        else -> null
    }
}

/** Platform repeat flag (Android repeatCount > 0; desktop has none, so held keys repeat as downs). */
expect fun KeyEvent.isRepeat(): Boolean

/** Registers a screen or overlay handler for as long as it's in composition. Topmost wins. */
@Composable
fun RemoteHandler(enabled: Boolean = true, handler: (RemoteAction) -> Boolean) {
    val dispatcher = LocalRemoteDispatcher.current
    val current = rememberUpdatedState(handler)
    val owner = remember { Any() }
    DisposableEffect(dispatcher, enabled) {
        if (enabled) dispatcher.push(owner) { current.value(it) }
        onDispose { dispatcher.remove(owner) }
    }
}

/**
 * Makes an element respond to OK and long-press OK while it has focus. Combine with
 * `Modifier.focusable()` (or [com.signal.ui.components.focusItem], which does both).
 */
@Composable
fun Modifier.remoteItem(
    onSelect: (() -> Unit)?,
    onOptions: (() -> Unit)? = null,
    isTextInput: Boolean = false,
): Modifier {
    val dispatcher = LocalRemoteDispatcher.current
    val owner = remember { Any() }
    val select = rememberUpdatedState(onSelect)
    val options = rememberUpdatedState(onOptions)
    DisposableEffect(dispatcher) { onDispose { dispatcher.clearFocused(owner) } }
    return onFocusChanged { state ->
        if (state.isFocused) {
            dispatcher.setFocused(
                owner,
                RemoteDispatcher.FocusedItem(
                    onSelect = select.value?.let { { select.value?.invoke() } },
                    onOptions = options.value?.let { { options.value?.invoke() } },
                    isTextInput = isTextInput,
                ),
            )
        } else {
            dispatcher.clearFocused(owner)
        }
    }
}

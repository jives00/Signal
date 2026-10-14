package com.signal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CoroutineScope

/**
 * False while a kept-alive page is hidden behind another (PLAN §8 → milestone 5). Opening Movies,
 * Series or Search rebuilt the whole page each time, with frames of 100–150 ms on the Shield; kept
 * pages stay composed and only stop being placed. A hidden page handles no keys ([com.signal.input.RemoteHandler])
 * and holds no video slot ([VideoBox]); Compose doesn't move focus into nodes that aren't placed.
 */
val LocalPageActive = compositionLocalOf { true }

/**
 * A page kept composed while hidden: not measured or placed, so it costs no layout or drawing, and
 * shown again without being rebuilt.
 *
 * [warm]: measured while still hidden, but never placed. Lazy lists compose their rows when they're
 * measured, and that's most of a first visit's cost, so a page warmed in idle time opens like a
 * revisit. Nothing unplaced can take focus or draw.
 */
@Composable
fun KeptPage(active: Boolean, warm: Boolean = false, content: @Composable () -> Unit) {
    // A page hidden while it holds focus keeps it: Compose doesn't take focus from a node that stops
    // being placed. Full screen over the guide then sent every OK to the hidden guide's "watch", never
    // the info bar (the "OK sometimes does nothing" report). So hiding hands focus to the key layer's
    // root, as a disposed page does; if the page shown next already took focus, this is a no-op.
    val dispatcher = com.signal.ui.LocalApp.current.dispatcher
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active && hasFocus) dispatcher.focusRoot() }
    CompositionLocalProvider(LocalPageActive provides active) {
        Box(
            Modifier.fillMaxSize()
                .onFocusChanged { hasFocus = it.hasFocus }
                // Unplaced nodes still report semantics: a hidden page isn't there for accessibility (or tests).
                .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
                .layout { measurable, constraints ->
                if (!active && !warm) return@layout layout(0, 0) {}
                val placeable = measurable.measure(constraints)
                if (!active) return@layout layout(0, 0) {}
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        ) { content() }
    }
}

/** Runs [block] each time the page is shown, including when it's first composed visible. */
@Composable
fun OnPageShown(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val active = LocalPageActive.current
    LaunchedEffect(active, *keys) { if (active) block() }
}

/** Runs [block] when a shown page is hidden, or leaves the composition while shown. */
@Composable
fun OnPageHidden(block: () -> Unit) {
    val active = LocalPageActive.current
    val current = rememberUpdatedState(block)
    DisposableEffect(active) { onDispose { if (active) current.value() } }
}

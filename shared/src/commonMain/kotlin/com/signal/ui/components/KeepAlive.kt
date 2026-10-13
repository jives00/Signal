package com.signal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
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
 */
@Composable
fun KeptPage(active: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageActive provides active) {
        Box(
            Modifier.fillMaxSize().layout { measurable, constraints ->
                if (!active) return@layout layout(0, 0) {}
                val placeable = measurable.measure(constraints)
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

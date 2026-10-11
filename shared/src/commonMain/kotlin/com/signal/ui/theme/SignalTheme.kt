package com.signal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalSignalColors = staticCompositionLocalOf { DarkSlate }

/** Wraps a screen tree in the design canvas and the current token set. */
@Composable
fun SignalTheme(colors: SignalColors = DarkSlate, content: @Composable () -> Unit) {
    DesignCanvas {
        CompositionLocalProvider(LocalSignalColors provides colors, content = content)
    }
}

object SignalTheme {
    val colors: SignalColors
        @Composable get() = LocalSignalColors.current
}

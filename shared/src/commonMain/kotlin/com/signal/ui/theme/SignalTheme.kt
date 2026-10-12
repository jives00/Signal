package com.signal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.signal.resources.Res
import com.signal.resources.archivo_400
import com.signal.resources.archivo_600
import com.signal.resources.archivo_800
import org.jetbrains.compose.resources.Font

val LocalSignalColors = staticCompositionLocalOf { DarkSlate }
val LocalSignalFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/** Wraps a screen tree in the design canvas, the current token set and Archivo (PLAN §3). */
@Composable
fun SignalTheme(colors: SignalColors = DarkSlate, content: @Composable () -> Unit) {
    val archivo = FontFamily(
        Font(Res.font.archivo_400, FontWeight.Normal),
        Font(Res.font.archivo_600, FontWeight.SemiBold),
        Font(Res.font.archivo_800, FontWeight.ExtraBold),
    )
    DesignCanvas {
        CompositionLocalProvider(
            LocalSignalColors provides colors,
            LocalSignalFont provides archivo,
            content = content,
        )
    }
}

object SignalTheme {
    val colors: SignalColors
        @Composable get() = LocalSignalColors.current
    val font: FontFamily
        @Composable get() = LocalSignalFont.current
}

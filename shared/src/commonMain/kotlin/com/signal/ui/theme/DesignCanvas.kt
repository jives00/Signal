package com.signal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density

/**
 * The one scaling rule (PLAN §3 → Resolution and scaling): 1 design unit = screen width ÷ 1920.
 *
 * Design numbers are 1080p pixels, not Android dp. A 1080p TV is 960×540 dp, so `112.dp` under
 * the platform density would be twice the intended size. Overriding [Density] once at the root
 * lets every screen write the handoff's numbers directly (`112.dp`, `26.sp`) and look identical
 * on a 1080p TV, a 4K TV and the dev harness window at any size.
 */
const val DESIGN_WIDTH = 1920f
const val DESIGN_HEIGHT = 1080f

fun designDensity(widthPx: Int): Density =
    Density(density = widthPx / DESIGN_WIDTH, fontScale = 1f)

@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    val widthPx = LocalWindowInfo.current.containerSize.width
    if (widthPx == 0) return // Not measured yet; the next frame has a size.
    CompositionLocalProvider(LocalDensity provides designDensity(widthPx), content = content)
}

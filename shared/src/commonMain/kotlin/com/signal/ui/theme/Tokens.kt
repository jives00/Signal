package com.signal.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens from the Claude Design handoff (design/design_handoff_iptv_app/README.md).
 * Screens read these and never hard-code a color, because the visuals will change again
 * and Settings → Appearance swaps whole token sets (PLAN §3, §4.3.15).
 */
data class SignalColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val divider: Color,
    val accent: Color,
    val accent600: Color,
    val accentText: Color,
    val tagBg: Color,
    val tagText: Color,
    val onAccent: Color,
    val neutral300: Color,
    val neutral400: Color,
    val muted: Color,
    val secondaryText: Color,
    val videoGround: Color,
)

val DarkSlate = SignalColors(
    bg = Color(0xFF30343B),
    surface = Color(0xFF3A3F47),
    text = Color(0xFFECEEF1),
    divider = Color(0x38ECEEF1), // rgba(236,238,241,0.22)
    accent = Color(0xFF2F6FE8),
    accent600 = Color(0xFF2860CC),
    accentText = Color(0xFF9CC0FF),
    tagBg = Color(0xFF24365A),
    tagText = Color(0xFFBCD4FF),
    onAccent = Color(0xFFFFFFFF),
    neutral300 = Color(0xFF474C55),
    neutral400 = Color(0xFF575D67),
    muted = Color(0xFFAEB4BD),
    secondaryText = Color(0xFFD3D7DD),
    videoGround = Color(0xFF1B1D21),
)

/** Sizes in design units: 1 unit = 1 pixel of the 1920×1080 canvas (see [DesignCanvas]). */
object SignalSizes {
    const val DIVIDER = 2
    const val FOCUS_OUTLINE = 4
    const val EDGE_MARGIN = 96
    const val NAV_BAR_HEIGHT = 112
    const val MIN_TEXT = 20
}

/** The handoff's type scale, in design units. */
object SignalType {
    const val WORDMARK = 36
    const val NAV = 26
    const val BODY = 26
    const val SECTION_TITLE = 28
    const val DISPLAY = 64
}

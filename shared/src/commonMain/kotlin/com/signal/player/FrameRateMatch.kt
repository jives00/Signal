package com.signal.player

import kotlin.math.abs

/**
 * Auto frame rate's choice of display mode (PLAN §4.3.15 → 5, §5.6), apart from Android so it can be
 * tested. The Android side reads the display's modes and applies the answer.
 */
object FrameRateMatch {
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /**
     * The mode for a video at [fps] (and [width]×[height] with [FrameRateMode.ON_RESOLUTION]), from
     * [modes], where [base] is the display's own mode. Null when none fits, and [base] itself when
     * that's already right.
     *
     * 25 and 29.97 are usually interlaced TV shown as 50 or 59.94 fields a second, so they take the
     * doubled rate first; film (23.976, 24) its own. Otherwise the lowest whole multiple that exists.
     */
    fun choose(modes: List<Mode>, base: Mode, fps: Float, width: Int, height: Int, setting: FrameRateMode): Mode? {
        if (setting == FrameRateMode.OFF || fps <= 0f) return null
        fun sameSize(w: Int, h: Int) = modes.filter { it.width == w && it.height == h }
        val sized = if (setting == FrameRateMode.ON_RESOLUTION && width > 0 && height > 0) {
            // The smallest mode the video fits in, else the display's own size.
            modes.filter { it.width >= width && it.height >= height }.minByOrNull { it.width.toLong() * it.height }?.let { sameSize(it.width, it.height) }
        } else null
        val candidates = sized ?: sameSize(base.width, base.height)
        val first = if (fps in 24.5f..30.5f) 2 else 1
        return (first..5).firstNotNullOfOrNull { k -> candidates.firstOrNull { abs(it.refreshRate - fps * k) < 0.05f * k } }
    }
}

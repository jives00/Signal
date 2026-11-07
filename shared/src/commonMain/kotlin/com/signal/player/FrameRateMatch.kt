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
     *
     * [current] is the mode on screen now. When it's within [NEAR] of the answer (60 for 59.94, 24 for
     * 23.976) it stays: the mismatch costs one repeated frame every 17 s or more, but switching blanks
     * the TV for a second or two. A 60 fps stream had left the Loft at 60 Hz, and going full screen
     * on a 59.94 channel blanked it (user, 2026-11-07).
     */
    fun choose(modes: List<Mode>, base: Mode, fps: Float, width: Int, height: Int, setting: FrameRateMode, current: Mode? = null): Mode? {
        val chosen = exact(modes, base, fps, width, height, setting) ?: return null
        val near = current != null && current.width == chosen.width && current.height == chosen.height &&
            abs(current.refreshRate - chosen.refreshRate) / chosen.refreshRate < NEAR
        return if (near) current else chosen
    }

    /** How far apart two rates can be and still count as the same: 60 against 59.94 is 0.1%. */
    private const val NEAR = 0.002f

    private fun exact(modes: List<Mode>, base: Mode, fps: Float, width: Int, height: Int, setting: FrameRateMode): Mode? {
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

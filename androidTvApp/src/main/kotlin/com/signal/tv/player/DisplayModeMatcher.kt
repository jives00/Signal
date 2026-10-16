package com.signal.tv.player

import android.app.Activity
import android.util.Log
import android.view.Display
import com.signal.player.FrameRateMatch
import com.signal.player.FrameRateMode

/**
 * Auto frame rate (PLAN §4.3.15 → 5, §5.6): switches the display to a mode whose refresh rate fits the
 * video, so 23.976 film doesn't judder on a 59.94 Hz display. "Also switch resolution" picks the mode
 * that fits the video's size as well, so the TV does the upscaling instead of the Shield. The choice
 * itself is [FrameRateMatch].
 *
 * It goes through the window's preferred display mode rather than `Surface.setFrameRate`: on Android 11
 * (the Shield) that only makes seamless switches, and an HDMI mode change never is one.
 */
internal class DisplayModeMatcher(private val activity: Activity) {
    /** The mode asked for; 0 is the system's own. */
    private var applied = 0

    /** The display's mode before Signal changed it: the resolution "On" keeps. */
    private var systemMode: Display.Mode? = null

    /**
     * [fps], [width] and [height] are the video's, or null while unknown. [match] is true on full screen
     * and on-demand playback; a mode already set stays while video carries on elsewhere ([keep]), and
     * goes back to the system's once nothing plays.
     */
    fun update(mode: FrameRateMode, fps: Float?, width: Int, height: Int, match: Boolean, keep: Boolean) {
        val target = when {
            mode == FrameRateMode.OFF -> 0
            match && fps != null && fps > 0 -> pick(mode, fps, width, height)
            match || keep -> applied
            else -> 0
        }
        set(target)
    }

    /** Back to the system's mode, e.g. when the app goes to the background. */
    fun reset() = set(0)

    private fun set(id: Int) {
        if (id == applied) return
        val window = activity.window ?: return
        applied = id
        window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        val mode = activity.display?.supportedModes?.firstOrNull { it.modeId == id }
        Log.i("Signal", "Display mode: " + (mode?.let { "${it.physicalWidth}x${it.physicalHeight} @ ${"%.3f".format(it.refreshRate)} Hz" } ?: "the system's"))
    }

    /** The mode to ask for: 0 when the system's own fits, or when none does. */
    private fun pick(mode: FrameRateMode, fps: Float, width: Int, height: Int): Int {
        val display = activity.display ?: return 0
        if (applied == 0) systemMode = display.mode
        val base = (systemMode ?: display.mode).toMode()
        val chosen = FrameRateMatch.choose(display.supportedModes.map { it.toMode() }, base, fps, width, height, mode) ?: return 0
        return if (chosen.id == base.id) 0 else chosen.id
    }

    private fun Display.Mode.toMode() = FrameRateMatch.Mode(modeId, physicalWidth, physicalHeight, refreshRate)
}

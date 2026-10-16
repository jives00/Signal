package com.signal.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameRateMatchTest {
    // A 4K TV like the Shield's: 4K and 1080p at the usual rates.
    private var id = 0
    private val modes = listOf(3840 to 2160, 1920 to 1080).flatMap { (w, h) ->
        listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f).map { FrameRateMatch.Mode(++id, w, h, it) }
    }
    private val base = modes.first { it.width == 3840 && it.refreshRate == 59.94f }

    private fun choose(fps: Float, w: Int = 1920, h: Int = 1080, setting: FrameRateMode = FrameRateMode.ON) =
        FrameRateMatch.choose(modes, base, fps, w, h, setting)

    @Test fun filmGetsItsOwnRateAtTheDisplaysSize() {
        val m = choose(23.976f)!!
        assertEquals(23.976f, m.refreshRate)
        assertEquals(3840, m.width)
    }

    @Test fun interlacedRatesAreDoubled() {
        assertEquals(59.94f, choose(29.97f)!!.refreshRate)
        assertEquals(50f, choose(25f)!!.refreshRate)
    }

    @Test fun liveSportsStaysAtItsRate() {
        assertEquals(base, choose(59.94f))
        assertEquals(50f, choose(50f)!!.refreshRate)
    }

    @Test fun alsoSwitchingResolutionPicksTheSizeTheVideoFits() {
        val hd = choose(23.976f, setting = FrameRateMode.ON_RESOLUTION)!!
        assertEquals(1920 to 1080, hd.width to hd.height)
        // 720p fits in 1080p, the smallest mode there is.
        assertEquals(1920, choose(59.94f, 1280, 720, FrameRateMode.ON_RESOLUTION)!!.width)
        assertEquals(3840, choose(23.976f, 3840, 2160, FrameRateMode.ON_RESOLUTION)!!.width)
    }

    @Test fun noFitOrOffLeavesTheDisplayAlone() {
        assertNull(choose(12.5f * 1.1f))
        assertNull(choose(23.976f, setting = FrameRateMode.OFF))
        assertNull(choose(0f))
    }
}

package com.signal.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameRateMeterTest {
    /** Feeds frames at [fps] and returns the first non-null result. */
    private fun measure(fps: Double, frames: Int = 100, startUs: Long = 1_000_000L, meter: FrameRateMeter = FrameRateMeter()): Float? =
        (0 until frames).firstNotNullOfOrNull { i -> meter.add(startUs + (i * 1_000_000.0 / fps).toLong()) }

    @Test fun snapsNtscRates() {
        assertEquals(29.97f, measure(30000.0 / 1001))
        assertEquals(59.94f, measure(60000.0 / 1001))
        assertEquals(23.976f, measure(24000.0 / 1001))
    }

    @Test fun snapsWholeRates() {
        assertEquals(25f, measure(25.0))
        assertEquals(50f, measure(50.0))
        assertEquals(60f, measure(60.0))
    }

    @Test fun oddRateIsRoundedNotSnapped() {
        assertEquals(15f, measure(15.0))
    }

    @Test fun ignoresDiscontinuities() {
        val meter = FrameRateMeter(samples = 10)
        var t = 0L
        var result: Float? = null
        repeat(30) { i ->
            // A 5 s jump every 4 frames, as after a provider reconnect.
            t += if (i % 4 == 3) 5_000_000 else 40_000
            result = result ?: meter.add(t)
        }
        assertEquals(25f, result)
    }

    @Test fun reportsOnce() {
        val meter = FrameRateMeter(samples = 4)
        assertEquals(25f, measure(25.0, frames = 5, meter = meter))
        assertNull(measure(25.0, frames = 20, startUs = 10_000_000, meter = meter))
    }
}

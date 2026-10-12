package com.signal.player

import kotlin.math.abs
import kotlin.math.round

/**
 * Measures a stream's frame rate from decoded frame timestamps, for streams whose container doesn't
 * declare one (many TS streams). It's used by the info bar (PLAN §4.3.3 → Info bar).
 *
 * Feed it each rendered frame's presentation time. Once it has enough gaps between frames it
 * returns the rate a single time, snapped to the nearest broadcast rate when it's close to one.
 */
class FrameRateMeter(private val samples: Int = 48) {
    private val deltas = ArrayList<Long>(samples)
    private var lastUs = -1L
    private var done = false

    /** Returns the frame rate once enough frames have been seen, else null. */
    fun add(presentationTimeUs: Long): Float? {
        if (done) return null
        val last = lastUs
        lastUs = presentationTimeUs
        if (last < 0) return null
        val delta = presentationTimeUs - last
        // Skip seeks, discontinuities and dropped runs: no real stream is below 5 fps.
        if (delta <= 0 || delta > 200_000) return null
        deltas += delta
        if (deltas.size < samples) return null
        done = true
        val median = deltas.sorted()[deltas.size / 2]
        return snap(1_000_000f / median)
    }

    companion object {
        private val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f)

        /** The nearest standard rate if it's within 0.3%; otherwise rounded to hundredths. */
        fun snap(fps: Float): Float {
            val nearest = STANDARD.minBy { abs(fps - it) }
            return if (abs(fps - nearest) / nearest < 0.003f) nearest else round(fps * 100) / 100
        }
    }
}

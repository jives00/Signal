package com.signal.server

/**
 * Splits a live MPEG-TS stream into HLS segments as it arrives, with no FFmpeg (dvr.md → Capture).
 * Nothing is decoded or changed: packets are copied through, and a segment ends at the first keyframe
 * after [targetMs], or at [maxMs] on a channel that never flags one. Each segment opens with the
 * latest PAT and PMT, so it plays on its own after a seek.
 *
 * Durations come from the stream's PCR. The clock is only the fallback when the PCR is missing or jumps.
 */
class TsSegmenter(
    private val sink: Sink,
    private val targetMs: Long = TARGET_MS,
    private val maxMs: Long = MAX_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Sink {
        fun begin()
        fun write(data: ByteArray, offset: Int, length: Int)
        fun finish(durationMs: Long)
    }

    private val buffer = ByteArray(BUFFER_SIZE)
    private var filled = 0

    private var pat: ByteArray? = null
    private var pmt: ByteArray? = null
    private var pmtPid = -1
    /** Where segments may cut: the video, or the first stream on a radio channel. */
    private var cutPid = -1
    private var readyAt = 0L

    private var open = false
    private var segmentPcr: Long? = null
    private var lastPcr: Long? = null
    private var segmentWall = 0L

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var from = offset
        var left = length
        while (left > 0) {
            val n = minOf(left, buffer.size - filled)
            data.copyInto(buffer, filled, from, from + n)
            filled += n
            from += n
            left -= n
            drain(final = false)
        }
    }

    /** Ends the open segment. The segmenter holds nothing after this; a new connection gets a new one. */
    fun close() {
        drain(final = true)
        filled = 0
        if (open) endSegment()
    }

    private fun drain(final: Boolean) {
        var at = 0
        while (filled - at >= PACKET) {
            // The packet after this one confirms the sync byte, which also occurs in payloads.
            val confirmable = filled - at > PACKET
            when {
                buffer[at] != SYNC -> at++
                confirmable && buffer[at + PACKET] != SYNC -> at++
                !confirmable && !final -> break
                else -> {
                    packet(buffer, at)
                    at += PACKET
                }
            }
        }
        buffer.copyInto(buffer, 0, at, filled)
        filled -= at
    }

    private fun packet(b: ByteArray, o: Int) {
        val unitStart = b[o + 1].toInt() and 0x40 != 0
        val pid = ((b[o + 1].toInt() and 0x1f) shl 8) or u(b, o + 2)
        val control = (b[o + 3].toInt() shr 4) and 3
        val hasField = control and 2 != 0
        val fieldLength = if (hasField) u(b, o + 4) else 0
        val flags = if (hasField && fieldLength > 0) u(b, o + 5) else 0
        val keyframe = flags and 0x40 != 0
        if (flags and 0x10 != 0 && fieldLength >= 7) {
            lastPcr = (u(b, o + 6).toLong() shl 25) or (u(b, o + 7).toLong() shl 17) or (u(b, o + 8).toLong() shl 9) or
                (u(b, o + 9).toLong() shl 1) or (u(b, o + 10).toLong() shr 7)
            if (segmentPcr == null) segmentPcr = lastPcr
        }
        if (unitStart && control and 1 != 0) {
            val payload = if (hasField) o + 5 + fieldLength else o + 4
            if (payload < o + PACKET) {
                if (pid == 0) readPat(b, o, payload)
                else if (pid == pmtPid) readPmt(b, o, payload)
            }
        }

        val cuttable = unitStart && pid == cutPid
        if (!open) {
            if (pat == null || pmt == null || !cuttable) return
            if (!keyframe && clock() - readyAt < START_WAIT_MS) return
            startSegment()
        } else if (cuttable) {
            val elapsed = elapsed()
            if ((keyframe && elapsed >= targetMs) || elapsed >= maxMs) {
                endSegment()
                startSegment()
            }
        }
        sink.write(b, o, PACKET)
    }

    private fun readPat(b: ByteArray, o: Int, payload: Int) {
        val section = payload + 1 + u(b, payload)
        if (section + 12 > o + PACKET) return
        val end = minOf(section + 3 + length(b, section) - 4, o + PACKET)
        var at = section + 8
        while (at + 4 <= end) {
            val program = (u(b, at) shl 8) or u(b, at + 1)
            if (program != 0) {
                pmtPid = ((b[at + 2].toInt() and 0x1f) shl 8) or u(b, at + 3)
                pat = b.copyOfRange(o, o + PACKET)
                return
            }
            at += 4
        }
    }

    private fun readPmt(b: ByteArray, o: Int, payload: Int) {
        val section = payload + 1 + u(b, payload)
        if (section + 12 > o + PACKET) return
        val end = minOf(section + 3 + length(b, section) - 4, o + PACKET)
        var at = section + 12 + (((b[section + 10].toInt() and 0xf) shl 8) or u(b, section + 11))
        var first = -1
        var video = -1
        while (at + 5 <= end) {
            val pid = ((b[at + 1].toInt() and 0x1f) shl 8) or u(b, at + 2)
            if (first < 0) first = pid
            if (video < 0 && u(b, at) in VIDEO_TYPES) video = pid
            at += 5 + (((b[at + 3].toInt() and 0xf) shl 8) or u(b, at + 4))
        }
        if (first < 0) return
        cutPid = if (video >= 0) video else first
        if (pmt == null) readyAt = clock()
        pmt = b.copyOfRange(o, o + PACKET)
    }

    private fun startSegment() {
        sink.begin()
        open = true
        segmentPcr = lastPcr
        segmentWall = clock()
        pat?.let { sink.write(it, 0, PACKET) }
        pmt?.let { sink.write(it, 0, PACKET) }
    }

    private fun endSegment() {
        sink.finish(elapsed())
        open = false
    }

    private fun elapsed(): Long {
        val first = segmentPcr
        val last = lastPcr
        if (first != null && last != null) {
            // The mask keeps the 26-hour wrap positive. A backward jump comes out huge and is thrown out.
            val ms = ((last - first) and PCR_MASK) / 90
            if (ms <= MAX_PCR_STEP_MS) return ms
        }
        return clock() - segmentWall
    }

    private fun length(b: ByteArray, section: Int) = ((b[section + 1].toInt() and 0xf) shl 8) or u(b, section + 2)
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    companion object {
        const val TARGET_MS = 10_000L
        const val MAX_MS = 15_000L
        /** What the playlist declares: nothing runs past [MAX_MS] by more than one picture. */
        const val PLAYLIST_TARGET_SECONDS = 16
        private const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val BUFFER_SIZE = PACKET * 512
        /** How long to wait for a first flagged keyframe before starting anyway. */
        private const val START_WAIT_MS = 3_000L
        private const val PCR_MASK = (1L shl 33) - 1
        private const val MAX_PCR_STEP_MS = 60_000L
        /** MPEG-2, H.264, HEVC, MPEG-4 part 2. */
        private val VIDEO_TYPES = setOf(0x02, 0x1b, 0x24, 0x10)
    }
}

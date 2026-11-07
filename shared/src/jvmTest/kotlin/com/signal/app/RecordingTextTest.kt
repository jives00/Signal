package com.signal.app

import com.signal.nas.NasRecording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingTextTest {
    private val day = 86_400_000L
    private fun rec(id: String = "r", state: String = "DONE", duration: Long = 3_600_000, at: Long = 0, position: Long = 0, watched: Long? = null, expires: Long? = null, gap: Long = 0, reason: String? = null) =
        NasRecording(id, "Show", "Channel", at, at + duration, state, durationMs = duration, gapMs = gap, gaps = if (gap > 0) 1 else 0, reason = reason, expiresAt = expires, positionMs = position, watchedAt = watched)

    @Test fun lengthsReadNaturally() {
        assertEquals("12 s", RecordingText.length(12_000))
        assertEquals("45 min", RecordingText.length(45 * 60_000))
        assertEquals("2 hr", RecordingText.length(120 * 60_000))
        assertEquals("1 hr 22 min", RecordingText.length(82 * 60_000))
    }

    @Test fun expiryRoundsUpAndNeverSaysZero() {
        assertEquals("Expires in 3 days", RecordingText.expiry(3 * day - 1_000, 0))
        assertEquals("Expires in 2 days", RecordingText.expiry(day + 1, 0))
        assertEquals("Expires today", RecordingText.expiry(5_000, 0))
        assertEquals("Expires soon", RecordingText.expiry(0, 5_000))
    }

    @Test fun statusSaysWhatMatters() {
        assertEquals("1 hr · Expires in 3 days", RecordingText.status(rec(expires = 3 * day), 0))
        assertEquals("Missing 12 s · 1 hr · Expires in 3 days", RecordingText.status(rec(state = "PARTIAL", gap = 12_000, expires = 3 * day), 0))
        assertEquals("1 hr · Watched · Expires today", RecordingText.status(rec(watched = 1, expires = 1_000), 0))
        assertEquals("Scheduled", RecordingText.status(rec(state = "SCHEDULED", duration = 0), 0))
        assertEquals("No source was free. · Expires today", RecordingText.status(rec(state = "FAILED", duration = 0, reason = "No source was free.", expires = 1_000), 0))
        assertEquals("Waiting for a free source", RecordingText.status(rec(state = "WAITING", duration = 0), 0))
    }

    @Test fun picksUpWhereItLeftOffUnlessWatched() {
        assertEquals(1_000, rec(position = 1_000).resumeMs)
        assertEquals(0, rec(position = 1_000, watched = 1).resumeMs)
        assertEquals(0, rec(position = 3_600_000).resumeMs, "at the very end there's nothing left to resume")
    }

    @Test fun onlyWhatHasVideoIsPlayable() {
        assertTrue(rec().playable)
        assertTrue(rec(state = "RECORDING").playable, "it can be watched while recording")
        assertFalse(rec(state = "RECORDING", duration = 0).playable)
        assertFalse(rec(state = "SCHEDULED").playable)
        assertFalse(rec(state = "FAILED").playable)
    }

    @Test fun homeShowsWhatsOnThenComingThenUnwatched() {
        val rows = listOf(rec("seen", watched = 1), rec("old", at = 1), rec("new", at = 2), rec("soon", "SCHEDULED", at = 5), rec("on", "RECORDING", at = 3), rec("bad", "FAILED"))
        assertEquals(listOf("on", "soon", "new", "old"), com.signal.ui.screens.homeRecordings(rows).map { it.id })
    }
}

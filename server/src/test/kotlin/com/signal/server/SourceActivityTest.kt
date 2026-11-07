package com.signal.server

import com.signal.nas.NowPlaying
import com.signal.nas.PlayKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceActivityTest {
    private var clock = 0L
    private val activity = SourceActivity(now = { clock })
    private fun live(stream: String) = NowPlaying("src", "main", PlayKind.LIVE, stream, "Channel $stream")

    @Test fun aReportStaysWhileHeartbeatsComeAndGoesWhenTheyStop() {
        activity.report("tv", SourceActivity.Who.TV, "Shield", "main", live("1"))
        clock += SourceActivity.QUIET_MS - 1
        activity.seen("tv")
        clock += SourceActivity.QUIET_MS - 1
        assertEquals(listOf("1"), activity.current().map { it.playing.streamId })
        clock += 2
        assertEquals(emptyList(), activity.current(), "a device that went quiet drops out")
    }

    @Test fun anEmptyReportOrAStopEndsItButAStaleStopDoesnt() {
        activity.report("tv", SourceActivity.Who.TV, "Shield", "main", live("1"))
        activity.report("tv", SourceActivity.Who.TV, "Shield", "main", NowPlaying())
        assertEquals(emptyList(), activity.current())

        // A browser changing channel: the new channel's start can land before the old one's stop.
        activity.report("viewer", SourceActivity.Who.VIEWER, "Phone", "main", live("2"))
        activity.stopped("viewer", "1")
        assertEquals(listOf("2"), activity.current().map { it.playing.streamId })
        activity.stopped("viewer", "2")
        assertEquals(emptyList(), activity.current())
    }
}

package com.signal.sports

import com.signal.model.Program
import com.signal.nas.NasRecording

/** The source a stream ID belongs to ("<source>:x:<stream>"). */
private fun sourceOf(streamId: String) = streamId.substringBefore(':')

/**
 * The recordings a TV would cut off by playing from [sourceId] (live-sports.md). The provider closes
 * the older of two streams on a login, and a TV borrows a free login when it has one, so a recording
 * is only at risk once every login of the source is recording something.
 */
fun recordingsAtRisk(recordings: List<NasRecording>, sourceId: String, logins: Int): List<NasRecording> {
    val on = recordings.filter { it.state == "RECORDING" && sourceOf(it.streamId) == sourceId }
    return if (on.size >= logins) on else emptyList()
}

/** The listing for a channel, or one made from the event for a channel with no guide: enough to record or be reminded of. */
fun SportEvent.programOn(channel: EventChannel): Program =
    channel.program ?: Program(id = 0, epgSourceId = "", guideChannelId = "", start = start, stop = stop, title = heading)

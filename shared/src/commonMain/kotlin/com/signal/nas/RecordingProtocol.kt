package com.signal.nas

import kotlinx.serialization.Serializable

/** A recording on the NAS, as a device sees it (dvr.md). The companion's own copy has more; unknown fields are ignored. */
@Serializable
data class NasRecording(
    val id: String,
    val title: String,
    val channelName: String,
    val startMs: Long,
    val stopMs: Long,
    val state: String,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val gaps: Int = 0,
    val gapMs: Long = 0,
    val reason: String? = null,
    val expiresAt: Long? = null,
    val programStart: Long? = null,
    val positionMs: Long = 0,
    val watchedAt: Long? = null,
    val streamId: String = "",
) {
    val upcoming: Boolean get() = state == "SCHEDULED"
    val running: Boolean get() = state == "RECORDING" || state == "WAITING"
    val failed: Boolean get() = state == "FAILED"
    /** Something to play: finished, or far enough in to have a segment. */
    val playable: Boolean get() = !upcoming && !failed && durationMs > 0
    /** When the program starts, or the recording did. */
    val at: Long get() = programStart ?: startMs
    /** Where to pick up: nowhere once it's been watched to the end. */
    val resumeMs: Long get() = if (watchedAt == null && positionMs in 1 until durationMs) positionMs else 0
}

@Serializable
data class NasRecordings(val problem: String? = null, val recordings: List<NasRecording> = emptyList(), val conflicts: List<String> = emptyList())

@Serializable
internal data class NasRecordingRequest(val streamId: String, val start: Long, val stop: Long, val title: String)

@Serializable
internal data class NasRecordingSet(val recording: NasRecording, val conflict: Boolean = false)

@Serializable
internal data class NasRecordingExtend(val minutes: Int)

@Serializable
internal data class NasRecordingProgress(val positionMs: Long)

/** What the companion said to setting a recording. */
sealed interface RecordingAnswer {
    /** [conflict]: more recordings overlap than there are sources. */
    data class Set(val conflict: Boolean) : RecordingAnswer
    data object OutOfReach : RecordingAnswer
    /** It's there but didn't answer, so it may have been set. */
    data object NoAnswer : RecordingAnswer
    data class Refused(val message: String) : RecordingAnswer
}

object NasRecordingPaths {
    const val LIST = "/device/recordings"
    fun one(id: String) = "$LIST/$id"
    fun playlist(id: String) = "$LIST/$id/files/index.m3u8"
    fun progress(id: String) = "$LIST/$id/progress"
    fun keep(id: String) = "$LIST/$id/keep"
    fun stop(id: String) = "$LIST/$id/stop"
    fun extend(id: String) = "$LIST/$id/extend"
}

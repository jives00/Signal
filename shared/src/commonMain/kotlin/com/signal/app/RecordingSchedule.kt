package com.signal.app

import com.signal.model.Channel
import com.signal.model.Program
import com.signal.nas.NasConnection
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordings
import com.signal.nas.RecordingAnswer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The recording set for this program on this stream, if there is one still to come or under way. */
fun findRecording(all: List<NasRecording>, streamId: String, p: Program): NasRecording? =
    all.firstOrNull { (it.upcoming || it.running) && it.streamId == streamId && it.programStart == p.start && it.title == p.title }

/** What the user is told after pressing Record; null when it's set and the button says so. */
fun recordNote(answer: RecordingAnswer): String? = when (answer) {
    is RecordingAnswer.Set -> if (answer.conflict) RecordingText.CONFLICT else null
    RecordingAnswer.OutOfReach -> RecordingText.OUT_OF_REACH
    is RecordingAnswer.Refused -> answer.message.ifBlank { "The companion couldn't set that." }
}

/**
 * The profile's recordings, kept for the guide's markers and the Record buttons (dvr.md). A failed
 * look keeps what was known: a marker shouldn't vanish because the TV lost the NAS for a moment. A
 * request that can't reach the companion is never queued, since it would look set and not be.
 */
class RecordingSchedule(private val nas: NasConnection) {
    private val _list = MutableStateFlow<List<NasRecording>>(emptyList())
    val list: StateFlow<List<NasRecording>> = _list.asStateFlow()

    /** Null when the companion can't be reached. */
    suspend fun refresh(): NasRecordings? {
        if (!nas.isPaired) {
            _list.value = emptyList()
            return null
        }
        val answer = nas.recordings() ?: return null
        _list.value = answer.recordings
        return answer
    }

    suspend fun record(ch: Channel, p: Program): RecordingAnswer {
        val answer = nas.scheduleRecording(ch.stream.id, p.start, p.stop, p.title)
        if (answer is RecordingAnswer.Set) refresh()
        return answer
    }

    suspend fun cancel(r: NasRecording): Boolean = nas.deleteRecording(r.id).also { if (it) refresh() }

    suspend fun stop(r: NasRecording): Boolean = nas.stopRecording(r.id).also { if (it) refresh() }

    suspend fun keepLonger(r: NasRecording): Boolean = nas.extendRecording(r.id, RecordingText.KEEP_LONGER_MINUTES)

    companion object {
        const val REFRESH_MS = 5 * 60_000L
    }
}

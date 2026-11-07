package com.signal.app

import com.signal.model.Channel
import com.signal.model.Program
import com.signal.nas.NasConnection
import com.signal.nas.NasRecording
import com.signal.nas.NasRecordings
import com.signal.nas.RecordingAnswer
import com.signal.nas.RecordingDetails
import kotlinx.coroutines.delay
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
    RecordingAnswer.NoAnswer -> RecordingText.NO_ANSWER
    is RecordingAnswer.Refused -> answer.message.ifBlank { "The companion couldn't set that." }
}

/**
 * The profile's recordings, kept for the guide's markers and the Record buttons (dvr.md). A failed
 * look keeps what was known: a marker shouldn't vanish because the TV lost the NAS for a moment. A
 * request that can't reach the companion is never queued, since it would look set and not be.
 */
class RecordingSchedule(private val nas: NasConnection, private val settleMs: Long = 1_500) {
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
        val answer = nas.scheduleRecording(ch.stream.id, p.start, p.stop, p.title, RecordingDetails.of(p))
        // No answer may still mean it was set.
        if (answer is RecordingAnswer.Set || answer is RecordingAnswer.NoAnswer) refresh()
        return answer
    }

    /** What to say when a Cancel or a Keep recording failed: the true reason, not always the network's fault. */
    suspend fun failure(): String = if (nas.canReach()) RecordingText.NO_ANSWER else RecordingText.OUT_OF_REACH

    /**
     * Takes it back. It may have started since this TV last looked: the companion won't delete one
     * running, so stop it, and then leave no row behind if nothing was saved.
     */
    suspend fun cancel(r: NasRecording): Boolean {
        if (nas.deleteRecording(r.id)) {
            refresh()
            return true
        }
        if (!nas.stopRecording(r.id)) return false
        delay(settleMs)
        val stopped = refresh()?.recordings?.firstOrNull { it.id == r.id }
        if (stopped != null && !stopped.running && stopped.durationMs == 0L && nas.deleteRecording(r.id)) refresh()
        return true
    }

    /** The companion takes a moment to finish, and until then the row still says it is recording. */
    suspend fun stop(r: NasRecording): Boolean {
        if (!nas.stopRecording(r.id)) return false
        delay(settleMs)
        refresh()
        return true
    }

    suspend fun keepLonger(r: NasRecording): Boolean = nas.extendRecording(r.id, RecordingText.KEEP_LONGER_MINUTES)

    // The Recordings page is rebuilt on each visit, and comes back from the player: what it had open is kept here.
    /** The line focus was last on. */
    var lastFocus: String? = null
    /** The show listed on its own, by [com.signal.ui.screens.showKeyOf]. */
    var openShow: String? = null
    var failedOpen: Boolean = false

    companion object {
        const val REFRESH_MS = 5 * 60_000L
    }
}

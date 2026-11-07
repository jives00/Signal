package com.signal.ui.screens

import com.signal.nas.NasRecording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dvr.md → TV, the redesign: the Recordings page's sections, filters and shows. */
class RecordingsListTest {
    private val hour = 3_600_000L
    // Noon on a fixed day, so "Today" and "Tomorrow" don't depend on when the test runs.
    private val now = 1_800_000_000_000L - 1_800_000_000_000L % (24 * hour) + 12 * hour

    private fun rec(id: String, state: String = "DONE", title: String = "Show", at: Long = now - hour, watched: Long? = null, position: Long = 0) =
        NasRecording(id, title, "Channel", at, at + hour, state, durationMs = if (state == "SCHEDULED" || state == "FAILED") 0 else hour, programStart = at, positionMs = position, watchedAt = watched)

    private fun List<RecordingsEntry>.keys() = map { it.key }

    @Test fun allPutsWhatsOnFirstThenComingThenSavedThenFailed() {
        val rows = listOf(
            rec("old", title = "A", at = now - 3 * hour), rec("new", title = "B", at = now - 2 * hour),
            rec("soon", "SCHEDULED", at = now + hour), rec("on", "RECORDING", at = now - 600_000), rec("bad", "FAILED", title = "C"),
        )
        val entries = recordingEntries(rows, RecordingsFilter.ALL, now)
        assertEquals(listOf("h:Recording now", "on", "h:Scheduled · Today", "soon", "h:Recorded", "new", "old", RecordingsEntry.FAILED_KEY), entries.keys())
        val open = recordingEntries(rows, RecordingsFilter.ALL, now, failedOpen = true)
        assertEquals(listOf(RecordingsEntry.FAILED_KEY, "bad"), open.keys().takeLast(2))
    }

    @Test fun scheduledIsGroupedByDay() {
        val rows = listOf(rec("later", "SCHEDULED", at = now + 30 * hour), rec("soon", "SCHEDULED", at = now + hour))
        assertEquals(listOf("h:Scheduled · Today", "soon", "h:Scheduled · Tomorrow", "later"), recordingEntries(rows, RecordingsFilter.SCHEDULED, now).keys())
    }

    @Test fun aShowWithMoreThanOneRecordingFoldsIntoOneRow() {
        val rows = listOf(
            rec("j1", title = "Jeopardy!", at = now - 30 * hour, watched = 1), rec("j2", title = "jeopardy! ", at = now - 6 * hour),
            rec("j3", title = "Jeopardy!", at = now - 5 * hour), rec("m", title = "Movie", at = now - 10 * hour),
        )
        val entries = recordingEntries(rows, RecordingsFilter.ALL, now)
        assertEquals(listOf("h:Recorded", RecordingsEntry.showKey("jeopardy!"), "m"), entries.keys())
        val show = entries[1] as RecordingsEntry.Show
        assertEquals(listOf("j3", "j2", "j1"), show.recordings.map { it.id }, "newest first")
        assertEquals(2, show.unwatched)
        assertEquals("j2", show.next.id, "play starts at the oldest not yet watched")
    }

    @Test fun anOpenShowListsUnwatchedOldestFirstThenWatched() {
        val rows = listOf(
            rec("j1", title = "Jeopardy!", at = now - 30 * hour, watched = 1), rec("j0", title = "Jeopardy!", at = now - 50 * hour, watched = 1),
            rec("j2", title = "Jeopardy!", at = now - 6 * hour), rec("j3", title = "Jeopardy!", at = now - 5 * hour),
        )
        val entries = recordingEntries(rows, RecordingsFilter.ALL, now, openShow = "jeopardy!")
        assertEquals(listOf("h:Jeopardy!", "j2", "j3", "j1", "j0"), entries.keys())
        assertTrue(entries.drop(1).all { it is RecordingsEntry.One && it.inShow })
        // Its last recording gone: back to the whole list.
        assertEquals("h:Recorded", recordingEntries(listOf(rec("m", title = "Movie")), RecordingsFilter.ALL, now, openShow = "jeopardy!").first().key)
    }

    @Test fun filtersShowOnlyTheirPart() {
        val rows = listOf(rec("seen", title = "A", watched = 1), rec("unseen", title = "B"), rec("on", "RECORDING"), rec("soon", "SCHEDULED", at = now + hour))
        assertEquals(listOf("h:Unwatched", "unseen"), recordingEntries(rows, RecordingsFilter.UNWATCHED, now).keys())
        assertEquals(listOf("h:Recording now", "on"), recordingEntries(rows, RecordingsFilter.RECORDING, now).keys())
        assertEquals(1, filterCount(rows, RecordingsFilter.UNWATCHED))
        assertEquals(4, filterCount(rows, RecordingsFilter.ALL))
    }

    @Test fun focusFindsTheLineOrTheShowHoldingIt() {
        val rows = listOf(rec("j1", title = "Jeopardy!"), rec("j2", title = "Jeopardy!", at = now - 2 * hour), rec("m", title = "Movie", at = now - 9 * hour))
        val entries = recordingEntries(rows, RecordingsFilter.ALL, now)
        assertEquals(2, entryIndex(entries, "m"))
        assertEquals(1, entryIndex(entries, "j2"), "a recording inside a folded show focuses the show")
        assertEquals(1, entryIndex(entries, "gone"), "else the first line past the heading")
        assertEquals(-1, entryIndex(emptyList(), null))
    }

    @Test fun deletingMovesFocusToTheNextLineElseTheOneBefore() {
        val rows = listOf(rec("a", title = "A", at = now - hour), rec("b", title = "B", at = now - 2 * hour))
        val entries = recordingEntries(rows, RecordingsFilter.ALL, now)
        assertEquals("b", neighborKey(entries, "a"))
        assertEquals("a", neighborKey(entries, "b"))
        assertNull(neighborKey(recordingEntries(listOf(rec("a")), RecordingsFilter.ALL, now), "a"))
    }

    @Test fun newMeansNeverStarted() {
        assertTrue(rec("a").isNew)
        assertEquals(false, rec("a", position = 1_000).isNew)
        assertEquals(false, rec("a", watched = 1).isNew)
        assertEquals(false, rec("a", "RECORDING").isNew)
    }
}

package com.signal.ui.screens

import com.signal.nas.NasRecording

/** The Recordings page's filters (dvr.md → TV, the redesign), kept per TV in the device setting `recordings_filter`. */
enum class RecordingsFilter(val label: String) {
    ALL("All"),
    UNWATCHED("Unwatched"),
    SCHEDULED("Scheduled"),
    RECORDING("Recording now");

    companion object {
        fun parse(raw: String?): RecordingsFilter = entries.firstOrNull { it.name == raw } ?: ALL
    }
}

/** One line of the Recordings list. [key] is what focus follows across reloads. */
sealed interface RecordingsEntry {
    val key: String

    data class Heading(val text: String) : RecordingsEntry {
        override val key get() = "h:$text"
    }

    /** One recording. [inShow]: listed inside an open show, so its title is the episode, not the show. */
    data class One(val recording: NasRecording, val inShow: Boolean = false) : RecordingsEntry {
        override val key get() = recording.id
    }

    /** Two or more recordings of one show, folded into a row; OK opens them. Newest first. */
    data class Show(val showKey: String, val title: String, val recordings: List<NasRecording>) : RecordingsEntry {
        override val key get() = showKey(showKey)
        val unwatched: Int get() = recordings.count { it.unwatched }
        /** What a show row's details describe: the newest. */
        val newest: NasRecording get() = recordings.first()
        /** Where Play starts: the oldest not yet watched, else the newest. */
        val next: NasRecording get() = recordings.filter { it.unwatched || it.resumeMs > 0 }.minByOrNull { it.at } ?: newest
    }

    /** Failed recordings, folded at the bottom; OK shows or hides them. */
    data class Failed(val recordings: List<NasRecording>, val open: Boolean) : RecordingsEntry {
        override val key get() = FAILED_KEY
    }

    companion object {
        const val FAILED_KEY = "failed"
        fun showKey(show: String) = "show:$show"
    }
}

/** Not watched to the end, and something to watch. */
internal val NasRecording.unwatched: Boolean get() = playable && watchedAt == null

/** Never started: shown as New. */
internal val NasRecording.isNew: Boolean get() = unwatched && positionMs == 0L && !running

/** Where the program, not the padding, ends. */
internal val NasRecording.programEnd: Long get() = programStop?.let { it + extraMs } ?: stopMs

/** Recordings of the same show group together: the same title, give or take case and spacing. */
internal fun showKeyOf(title: String): String = title.trim().lowercase().replace(Regex("\\s+"), " ")

/** Recorded and finished (or recording, while on its own filter): what the Recorded part lists. Failed ones go to the bottom. */
private fun NasRecording.recorded() = !running && !upcoming && !failed

/**
 * The page's lines for [filter]: what's on now, what's coming (by day), then what's saved (by
 * show, newest first), with failed ones folded at the bottom. [openShow] lists one show's
 * recordings instead: unwatched first, oldest first, so they play in order; then the watched ones.
 */
internal fun recordingEntries(
    all: List<NasRecording>,
    filter: RecordingsFilter,
    now: Long,
    openShow: String? = null,
    failedOpen: Boolean = false,
): List<RecordingsEntry> = buildList {
    if (openShow != null) {
        val episodes = all.filter { it.recorded() && showKeyOf(it.title) == openShow }
        if (episodes.isNotEmpty()) {
            add(RecordingsEntry.Heading(episodes.first().title))
            val (toWatch, watched) = episodes.partition { it.watchedAt == null }
            (toWatch.sortedBy { it.at } + watched.sortedByDescending { it.at }).forEach { add(RecordingsEntry.One(it, inShow = true)) }
            return@buildList
        }
        // The show is gone (its last recording was deleted): back to the whole list.
    }
    fun running() {
        val on = all.filter { it.running }.sortedBy { it.at }
        if (on.isEmpty()) return
        add(RecordingsEntry.Heading("Recording now"))
        on.forEach { add(RecordingsEntry.One(it)) }
    }
    fun scheduled() {
        all.filter { it.upcoming }.sortedBy { it.at }.groupBy { dayLabel(it.at, now) }.forEach { (day, rows) ->
            add(RecordingsEntry.Heading("Scheduled · $day"))
            rows.forEach { add(RecordingsEntry.One(it)) }
        }
    }
    fun recorded(heading: String, keep: (NasRecording) -> Boolean) {
        val rows = all.filter { it.recorded() && keep(it) }
        if (rows.isEmpty()) return
        add(RecordingsEntry.Heading(heading))
        rows.groupBy { showKeyOf(it.title) }
            .map { (key, list) -> key to list.sortedByDescending { it.at } }
            .sortedByDescending { (_, list) -> list.first().at }
            .forEach { (key, list) ->
                add(if (list.size == 1) RecordingsEntry.One(list.first()) else RecordingsEntry.Show(key, list.first().title, list))
            }
    }
    fun failed() {
        val rows = all.filter { it.failed }.sortedByDescending { it.at }
        if (rows.isEmpty()) return
        add(RecordingsEntry.Failed(rows, failedOpen))
        if (failedOpen) rows.forEach { add(RecordingsEntry.One(it)) }
    }
    when (filter) {
        RecordingsFilter.ALL -> {
            running()
            scheduled()
            recorded("Recorded") { true }
            failed()
        }
        RecordingsFilter.UNWATCHED -> recorded("Unwatched") { it.watchedAt == null }
        RecordingsFilter.SCHEDULED -> scheduled()
        RecordingsFilter.RECORDING -> running()
    }
}

/** How many each filter would show, for its button. */
internal fun filterCount(all: List<NasRecording>, filter: RecordingsFilter): Int = when (filter) {
    RecordingsFilter.ALL -> all.size
    RecordingsFilter.UNWATCHED -> all.count { it.recorded() && it.watchedAt == null }
    RecordingsFilter.SCHEDULED -> all.count { it.upcoming }
    RecordingsFilter.RECORDING -> all.count { it.running }
}

/** Where [key] sits; else the show holding recording [key]; else the first line focus can take; -1 when there's none. */
internal fun entryIndex(entries: List<RecordingsEntry>, key: String?): Int {
    if (key != null) {
        entries.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { return it }
        entries.indexOfFirst { it is RecordingsEntry.Show && it.recordings.any { r -> r.id == key } }.takeIf { it >= 0 }?.let { return it }
        entries.indexOfFirst { it is RecordingsEntry.Failed && it.recordings.any { r -> r.id == key } }.takeIf { it >= 0 }?.let { return it }
    }
    return entries.indexOfFirst { it !is RecordingsEntry.Heading }
}

/** Where focus goes when the line [key] goes away: the next line, else the one before. */
internal fun neighborKey(entries: List<RecordingsEntry>, key: String): String? {
    val at = entries.indexOfFirst { it.key == key }
    if (at < 0) return null
    return (entries.drop(at + 1) + entries.take(at).reversed()).firstOrNull { it !is RecordingsEntry.Heading }?.key
}

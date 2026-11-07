package com.signal.nas

import com.signal.model.Program
import kotlinx.serialization.Serializable

/**
 * What the guide said about a recorded program, saved when it's set (dvr.md → TV, the redesign):
 * the guide drops a program once it has aired, and the Recordings page still wants to show it.
 * Shared by the companion, which stores it, and the TV, which sends it and shows it.
 */
@Serializable
data class RecordingDetails(
    val subTitle: String? = null,
    val description: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeLabel: String? = null,
    val icon: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    val isNew: Boolean = false,
    val categories: List<String> = emptyList(),
) {
    /** "S3 E12", "E12" or the guide's own label. */
    val episodeText: String?
        get() = when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            else -> episodeLabel
        }

    /** Nothing worth keeping. */
    val isEmpty: Boolean
        get() = subTitle == null && description == null && season == null && episode == null && episodeLabel == null &&
            icon == null && rating == null && year == null && !isNew && categories.isEmpty()

    companion object {
        /** Null when the guide has nothing beyond the title. */
        fun of(p: Program): RecordingDetails? = RecordingDetails(
            subTitle = p.subTitle?.takeIf { it.isNotBlank() },
            description = p.description?.takeIf { it.isNotBlank() },
            season = p.season,
            episode = p.episode,
            episodeLabel = p.episodeLabel?.takeIf { it.isNotBlank() },
            icon = p.icon?.takeIf { it.isNotBlank() },
            rating = p.rating?.takeIf { it.isNotBlank() },
            year = p.year,
            isNew = p.isNew,
            categories = p.categories,
        ).takeIf { !it.isEmpty }
    }
}

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
    val programStop: Long? = null,
    val extraMs: Long = 0,
    val bytes: Long = 0,
    /** The guide's details, from companions since the Recordings redesign; null before that, or when the guide had none. */
    val details: RecordingDetails? = null,
    /** The channel's logo as the profile sees it, added by the companion to each list. */
    val channelLogo: String? = null,
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
data class NasRecordings(val problem: String? = null, val freeBytes: Long = 0, val recordings: List<NasRecording> = emptyList(), val conflicts: List<String> = emptyList())

/** [details] is what the TV's guide has, so a TV on its own guide still gets them saved; companions before the redesign refuse unknown fields, so it's left out when null. */
@Serializable
internal data class NasRecordingRequest(val streamId: String, val start: Long, val stop: Long, val title: String, val details: RecordingDetails? = null)

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

package com.signal.model

/** One EPG source (PLAN §2.2 "Guide source"): an XMLTV URL, an Xtream source's own guide, or the Demo guide. */
@kotlinx.serialization.Serializable
data class EpgSource(
    val id: String,
    val name: String,
    val kind: EpgKind,
    /** XMLTV only. An Xtream guide's URL is built from its source at fetch time, so credentials never sit here. */
    val url: String = "",
    /** The IPTV source this guide belongs to (Xtream and Demo), else null. */
    val sourceId: String? = null,
    val enabled: Boolean = true,
    /** Added to every program time when the guide is read (PLAN §6 → Time shift). */
    val timeShiftMinutes: Int = 0,
    /** Fractional index key: the fallback order. */
    val position: String = "",
    val lastBuiltAt: Long = 0,
    val lastError: String? = null,
) {
    val timeShiftMs: Long get() = timeShiftMinutes * 60_000L
}

@kotlinx.serialization.Serializable
enum class EpgKind { XMLTV, XTREAM, DEMO }

/** One scheduled airing (PLAN §2.2 "Program"). Times are epoch milliseconds with the EPG source's time shift applied. */
data class Program(
    val id: Long,
    val epgSourceId: String,
    val guideChannelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subTitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    val episodeLabel: String? = null,
    val icon: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    val isNew: Boolean = false,
    val isLive: Boolean = false,
    val credits: List<String> = emptyList(),
) {
    fun isOn(now: Long) = now in start until stop
    val durationMs: Long get() = stop - start

    /** "S3 E7", else the guide's own label, else null. */
    val episodeText: String?
        get() = when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            else -> episodeLabel
        }
}

/** Where a channel's guide comes from, once mapping and fallback are applied (PLAN §6). */
data class GuideRef(val epgSourceId: String, val guideChannelId: String, val timeShiftMs: Long, val isFallback: Boolean)

/** A reminder (PLAN §4.3.4 → Reminders): a user channel plus the program's title and start. */
data class Reminder(
    val id: String,
    val userChannelId: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val fired: Boolean,
)

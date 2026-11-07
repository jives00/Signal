package com.signal.sports

import com.signal.model.Program
import kotlin.math.abs

/**
 * Event channels (PPV and the like) shown for what they carry (backlog → PPV channels). Their names
 * hold the event and its time, and they have no guide, so the name is read ([PpvNames.event]):
 *
 * - the channel's shown name is the event's short title, not the whole name;
 * - with no guide, the event is its program, so the guide, info bar and recordings have it;
 * - empty slots and events that are over are hidden from the channel lists.
 *
 * Only channels in groups that can hold games are read ([SportsGroups.isSports]), and only while
 * the profile's name cleanup has [com.signal.match.NameCleanup.events] on. The device and the
 * companion both use this.
 */
object EventChannels {
    /** Marks the programs made here; real guide programs come from an EPG source. */
    const val EPG_SOURCE = "event-name"

    /** The event [rawName] carries, if its group is one that holds events. */
    fun event(group: String, rawName: String, now: Long): PpvNames.Event? =
        if (!SportsGroups.isSports(group)) null else PpvNames.event(group, rawName, now)

    /** The name to show: the event's title, or null to keep the usual name. */
    fun shortName(group: String, rawName: String, now: Long): String? = event(group, rawName, now)?.title

    /** True for an event channel with nothing on it, or nothing still to come: an empty slot, or an event that's over. */
    fun hides(group: String, rawName: String, now: Long): Boolean {
        if (!SportsGroups.isSports(group)) return false
        if (PpvNames.isEmptySlot(rawName)) return true
        return PpvNames.event(group, rawName, now)?.isOver(now) == true
    }

    /** The event as a program on the channel [streamId], or null when its name doesn't give one or it's over. */
    fun program(streamId: String, group: String, rawName: String, now: Long): Program? {
        val e = event(group, rawName, now)?.takeIf { !it.ended } ?: return null
        val about = buildList {
            if (e.others.isNotEmpty()) add("Also on this channel: ${e.others.joinToString(", ")}.")
            if (e.dayGuessed) add("The channel's name gives only the time, so the day is a guess.")
            if (e.stopGuessed) add("The end time is a guess.")
        }.joinToString(" ").ifEmpty { null }
        return Program(
            // Negative, so never a guide.db row's.
            id = -1L - abs(streamId.hashCode().toLong()),
            epgSourceId = EPG_SOURCE,
            guideChannelId = streamId,
            start = e.start,
            stop = e.stop,
            title = e.title,
            subTitle = e.tag ?: e.game?.sportName ?: e.game?.sport?.takeIf { it != Sport.OTHER }?.label,
            description = about,
            categories = if (e.game != null) listOf("Sports") else emptyList(),
        )
    }
}

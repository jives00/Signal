package com.signal.server

import com.signal.data.Settings
import com.signal.sports.Sport
import com.signal.sports.SportsGroups
import com.signal.sports.SportsSettings
import kotlinx.serialization.Serializable

@Serializable
data class SportRow(val id: String, val label: String, val shown: Boolean)

/** A provider group Live Sports can read channel names from. [on] is what the profile gets, by default or by choice. */
@Serializable
data class SportsGroupRow(val name: String, val on: Boolean, val us: Boolean)

/** The web admin's Live Sports page (live-sports.md): the profile's setting, and what it comes to. */
@Serializable
data class SportsView(
    val hiddenSports: Set<String>,
    val groupsOn: Set<String>,
    val groupsOff: Set<String>,
    val sports: List<SportRow>,
    val groups: List<SportsGroupRow>,
)

internal fun sportsView(companion: Companion, view: AdminView): SportsView {
    val s = SportsSettings.parse(view.profileSettings[Settings.Key.SPORTS.id])
    val names = companion.catalog.streams.asSequence().filter { view.sourceShown(it.sourceId) }.map { it.groupName }.distinct().filter(SportsGroups::isSports)
    return SportsView(
        s.hiddenSports, s.groupsOn, s.groupsOff,
        Sport.entries.map { SportRow(it.name, it.label, s.shows(it)) },
        names.map { SportsGroupRow(it, s.reads(it), SportsGroups.country(it) == "US") }.sortedWith(compareBy({ !it.us }, { it.name })).toList(),
    )
}

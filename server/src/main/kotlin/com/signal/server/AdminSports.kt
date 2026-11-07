package com.signal.server

import com.signal.data.Settings
import com.signal.sports.PpvNames
import com.signal.sports.Sport
import com.signal.sports.SportKeys
import com.signal.sports.SportImages
import com.signal.sports.SportsGroups
import com.signal.sports.SportsSettings
import kotlinx.serialization.Serializable

/**
 * One entry of the TV's Sport filter, in the profile's order: a league ([kind] "league"), Other ("other"),
 * or a sport the feeds name that would be Other ("named", id "named:Field Hockey"). [inOther] is a league
 * counted as Other, or a named sport left in Other rather than given its own entry.
 */
@Serializable
data class SportRow(val id: String, val label: String, val shown: Boolean, val kind: String = "league", val inOther: Boolean = false)

/**
 * A league's image (live-sports.md → Round 2), household-wide. [url] is what the TVs show: the chosen
 * one, else [defaultUrl]; null draws a badge with [short]. [chosen] is the setting's own value, "" for none.
 */
@Serializable
data class SportImageRow(val id: String, val label: String, val family: String, val short: String, val url: String?, val defaultUrl: String?, val chosen: String?)

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
    /** The household setting sport_images as stored, for writing it back. */
    val images: Map<String, String> = emptyMap(),
    val imageRows: List<SportImageRow> = emptyList(),
    val order: List<String> = emptyList(),
    val asOther: Set<String> = emptySet(),
    val ownEntry: Set<String> = emptySet(),
)

internal fun sportsView(companion: Companion, view: AdminView): SportsView {
    val s = SportsSettings.parse(view.profileSettings[Settings.Key.SPORTS.id])
    val images = SportImages.parse(view.householdSettings[Settings.Key.SPORT_IMAGES.id])
    val names = companion.catalog.streams.asSequence().filter { view.sourceShown(it.sourceId) }.map { it.groupName }.distinct().filter(SportsGroups::isSports)
    return SportsView(
        s.hiddenSports, s.groupsOn, s.groupsOff,
        sportRows(companion, view, s),
        names.map { SportsGroupRow(it, s.reads(it), SportsGroups.country(it) == "US") }.sortedWith(compareBy({ !it.us }, { it.name })).toList(),
        images,
        Sport.entries.map { SportImageRow(it.name, it.label, it.family.name, it.short, SportImages.url(it, images), SportImages.defaults[it], images[it.name]) },
        s.order, s.asOther, s.ownEntry,
    )
}

/**
 * Every league, Other, and the sports that land in Other by name: what the channel names the profile
 * reads give ("Swimming and Diving"), ESPN's feeds of their own ("Field Hockey"), and any already given
 * an entry. In the profile's order, the way the TV lists them.
 */
private fun sportRows(companion: Companion, view: AdminView, s: SportsSettings): List<SportRow> {
    val now = System.currentTimeMillis()
    val named = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)
    fun add(name: String?) { if (!name.isNullOrBlank()) named.putIfAbsent(name.trim(), name.trim()) }
    s.ownEntry.forEach(::add)
    companion.catalog.streams.asSequence()
        .filter { view.sourceShown(it.sourceId) && SportsGroups.isSports(it.groupName) && s.reads(it.groupName) }
        .flatMap { PpvNames.parseAll(it.groupName, it.rawName, now) }
        .filter { it.sport == Sport.OTHER }
        .forEach { add(it.sportName) }
    runCatching { companion.sportsSchedule.aroundNow() }.getOrDefault(emptyList()).forEach { if (it.sport == Sport.OTHER) add(it.label) }
    val leagues = Sport.entries.map { sport ->
        when (sport) {
            Sport.OTHER -> SportRow(sport.name, sport.label, s.shows(sport), kind = "other")
            else -> SportRow(sport.name, sport.label, s.shows(sport), inOther = sport.name in s.asOther)
        }
    }
    val own = named.values.map { name ->
        val key = SportKeys.named(name)
        val inOther = s.ownEntry.none { it.equals(name, ignoreCase = true) }
        SportRow(key, name, key !in s.hiddenSports, kind = "named", inOther = inOther)
    }
    val byKey = (leagues + own).associateBy { it.id }
    return s.sorted(byKey.keys).map { byKey.getValue(it) }
}

package com.signal.sports

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * The profile setting sports (live-sports.md). [groupsOn] and [groupsOff] are provider group names the
 * user changed from [SportsGroups.readByDefault]; [hiddenSports] are the [Sport] names not to show.
 */
@Serializable
data class SportsSettings(
    val hiddenSports: Set<String> = emptySet(),
    val groupsOn: Set<String> = emptySet(),
    val groupsOff: Set<String> = emptySet(),
) {
    fun reads(group: String): Boolean = group in groupsOn || (group !in groupsOff && SportsGroups.readByDefault(group))

    fun shows(sport: Sport): Boolean = sport.name !in hiddenSports

    fun encode(): String? = if (this == SportsSettings()) null else json.encodeToString(serializer(), this)

    companion object {
        fun parse(raw: String?): SportsSettings =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: SportsSettings()
    }
}

/**
 * The page's filters, the device setting sports_filters: each TV keeps its own, between visits and
 * restarts. [sports] holds [Sport] names; empty is every sport.
 */
@Serializable
data class SportsFilters(
    val region: SportsRegion = SportsRegion.US,
    val whenOn: SportsWhen = SportsWhen.TODAY,
    val sports: Set<String> = emptySet(),
) {
    /** The chosen sports. A name this version doesn't know is left out. */
    val chosen: Set<Sport> get() = Sport.entries.filterTo(LinkedHashSet()) { it.name in sports }

    /** What the Sport filter's button says. */
    val sportLabel: String
        get() = when (chosen.size) {
            0 -> "All sports"
            1, 2 -> chosen.joinToString(", ") { it.label }
            else -> "${chosen.size} sports"
        }

    fun toggled(sport: Sport): SportsFilters = copy(sports = if (sport.name in sports) sports - sport.name else sports + sport.name)

    fun encode(): String? = if (this == SportsFilters()) null else json.encodeToString(serializer(), this)

    companion object {
        fun parse(raw: String?): SportsFilters =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: SportsFilters()
    }
}

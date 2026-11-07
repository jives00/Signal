package com.signal.sports

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * The profile setting sports (live-sports.md). [groupsOn] and [groupsOff] are provider group names the
 * user changed from [SportsGroups.readByDefault]; [hiddenSports] are the filter keys ([SportKeys]) not to show.
 *
 * The Sport filter's entries ([SportKeys]): a league, Other, or a sport that would be Other given its own
 * entry ([ownEntry], by the name the feeds give it). [asOther] puts leagues in Other instead. [order] is
 * the filter's order, as the web admin's Live Sports page last saved it; anything not in it keeps its
 * usual place after.
 */
@Serializable
data class SportsSettings(
    val hiddenSports: Set<String> = emptySet(),
    val groupsOn: Set<String> = emptySet(),
    val groupsOff: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val asOther: Set<String> = emptySet(),
    val ownEntry: Set<String> = emptySet(),
) {
    fun reads(group: String): Boolean = group in groupsOn || (group !in groupsOff && SportsGroups.readByDefault(group))

    fun shows(sport: Sport): Boolean = sport.name !in hiddenSports

    /** A game shows unless its league or its filter entry is turned off. */
    fun shows(e: SportEvent): Boolean = e.sport.name !in hiddenSports && key(e) !in hiddenSports

    /** The filter entry [e] is listed under. */
    fun key(e: SportEvent): String = key(e.sport, e.sportName)

    fun key(sport: Sport, sportName: String?): String = when {
        sport == Sport.OTHER -> sportName?.let { n -> ownEntry.firstOrNull { it.equals(n, ignoreCase = true) } }?.let(SportKeys::named) ?: Sport.OTHER.name
        sport.name in asOther -> Sport.OTHER.name
        else -> sport.name
    }

    /** [keys] in the filter's order. */
    fun sorted(keys: Collection<String>): List<String> {
        val placed = order.withIndex().associate { (i, k) -> k to i }
        return keys.distinct().sortedWith(compareBy<String>({ placed[it] ?: Int.MAX_VALUE }, { SportKeys.rank(it) }, { SportKeys.label(it) }))
    }

    fun encode(): String? = if (this == SportsSettings()) null else json.encodeToString(serializer(), this)

    companion object {
        fun parse(raw: String?): SportsSettings =
            (raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: SportsSettings())
                .let { it.copy(hiddenSports = Sport.upgrade(it.hiddenSports)) }
    }
}

/** The Sport filter's entries: a [Sport] by name, or a sport given its own entry as "named:Field Hockey". */
object SportKeys {
    const val NAMED = "named:"

    fun named(name: String) = NAMED + name

    fun label(key: String): String = if (key.startsWith(NAMED)) key.removePrefix(NAMED) else Sport.entries.firstOrNull { it.name == key }?.label ?: key

    /** The league for a key's badge and image; a named sport is drawn as Other. */
    fun sport(key: String): Sport = Sport.entries.firstOrNull { it.name == key } ?: Sport.OTHER

    /** The usual place: leagues in [Sport]'s order, sports with their own entry just before Other. */
    fun rank(key: String): Int = if (key.startsWith(NAMED)) Sport.OTHER.ordinal * 2 - 1 else (Sport.entries.firstOrNull { it.name == key }?.ordinal ?: Sport.OTHER.ordinal) * 2
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
    /** The chosen filter entries ([SportKeys]). */
    val chosen: Set<String> get() = sports

    /** What the Sport filter's button says. */
    val sportLabel: String
        get() = when (sports.size) {
            0 -> "All sports"
            1, 2 -> sports.joinToString(", ") { SportKeys.label(it) }
            else -> "${sports.size} sports"
        }

    fun toggled(key: String): SportsFilters = copy(sports = if (key in sports) sports - key else sports + key)

    fun encode(): String? = if (this == SportsFilters()) null else json.encodeToString(serializer(), this)

    companion object {
        fun parse(raw: String?): SportsFilters =
            (raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: SportsFilters())
                .let { it.copy(sports = Sport.upgrade(it.sports)) }
    }
}

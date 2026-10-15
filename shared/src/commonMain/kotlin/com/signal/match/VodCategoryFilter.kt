package com.signal.match

import com.signal.model.GroupSort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which provider movie and series categories to keep (PLAN §4.3.6 → Categories). One household
 * setting holds it as JSON. Titles in a hidden category are dropped when the catalog loads, so
 * they're gone from browsing, Search, Recently added and the companion's TMDB matching alike.
 *
 * Patterns match the whole category name (after [CharacterCleanup]), ignoring case, with `*` for
 * any text and `?` for one character. With [showOnly] set, a category must match one of them; a
 * match in [hide] always wins. So a category the provider adds later stays hidden unless it looks
 * like one to keep.
 *
 * The web admin's Movies & Series → Categories tab hides and shows single categories ([hidden] and
 * [shown], exact names that win over the patterns) and renames them ([names]). The patterns were set
 * once by hand (2026-10-14, "hide the non-English groups").
 *
 * [sort] orders the category column: provider order, A–Z, Z–A or manual. The manual order is
 * [order], category names per kind ("MOVIE", "SERIES"), by name like the rest. A category not in it
 * goes after the ordered ones, in provider order.
 */
@Serializable
data class VodCategoryFilter(
    @SerialName("show_only") val showOnly: List<String> = emptyList(),
    val hide: List<String> = emptyList(),
    /** Categories hidden one by one, by exact name (ignoring case). Wins over everything. */
    val hidden: List<String> = emptyList(),
    /** Categories shown one by one, by exact name, though a pattern would hide them. */
    val shown: List<String> = emptyList(),
    /** Category name → the name the TVs show instead. */
    val names: Map<String, String> = emptyMap(),
    /** How the category column is ordered: a [GroupSort] name; unset is provider order. */
    val sort: String? = null,
    /** The manual order: kind → category names, first to last. */
    val order: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = showOnly.isEmpty() && hide.isEmpty() && hidden.isEmpty()

    /** Only what decides which titles are kept: a rename or a new order alone doesn't drop or refresh anything. */
    val visibility: VodCategoryFilter get() = copy(names = emptyMap(), sort = null, order = emptyMap())

    /** The column's order; an unknown value is provider order. */
    val categorySort: GroupSort get() = sort?.let { s -> GroupSort.entries.firstOrNull { it.name == s } } ?: GroupSort.PROVIDER

    private val showRegexes by lazy { showOnly.mapNotNull(::compile) }
    private val hideRegexes by lazy { hide.mapNotNull(::compile) }
    private val hiddenKeys by lazy { hidden.map(::key).toSet() }
    private val shownKeys by lazy { shown.map(::key).toSet() }
    private val nameKeys by lazy { names.entries.associate { key(it.key) to it.value } }

    /** True when titles in the category named [name] should be left out. */
    fun hides(name: String): Boolean {
        if (isEmpty) return false
        val k = key(name)
        if (k in hiddenKeys) return true
        if (k in shownKeys) return false
        if (hideRegexes.any { it.matches(name) }) return true
        return showRegexes.isNotEmpty() && showRegexes.none { it.matches(name) }
    }

    /** The name the category is renamed to, or null to use its own (after title cleanup). */
    fun renamed(name: String): String? = if (names.isEmpty()) null else nameKeys[key(name)]

    /** Hides or shows the category named [name], whatever the patterns say. */
    fun withHidden(name: String, hide: Boolean): VodCategoryFilter {
        val k = key(name)
        val base = copy(hidden = hidden.filter { key(it) != k }, shown = shown.filter { key(it) != k })
        return when {
            base.hides(name) == hide -> base
            hide -> base.copy(hidden = base.hidden + name.trim())
            else -> base.copy(shown = base.shown + name.trim())
        }
    }

    /** Renames the category named [name]; null or blank goes back to its own name. */
    fun withName(name: String, newName: String?): VodCategoryFilter {
        val k = key(name)
        val rest = names.filterKeys { key(it) != k }
        val n = newName?.trim()?.takeIf { it.isNotEmpty() && it != name.trim() }
        return copy(names = if (n == null) rest else rest + (name.trim() to n))
    }

    /**
     * [items], given in provider order, in the column's order. [name] is a category's own name, which
     * the manual order goes by; [title] is the name shown, which A–Z goes by.
     */
    fun <T> sorted(kind: String, items: List<T>, name: (T) -> String, title: (T) -> String): List<T> = when (categorySort) {
        GroupSort.PROVIDER -> items
        GroupSort.A_Z -> items.sortedBy { title(it).lowercase() }
        GroupSort.Z_A -> items.sortedByDescending { title(it).lowercase() }
        GroupSort.MANUAL -> {
            val at = order[kind].orEmpty().withIndex().associate { (i, n) -> key(n) to i }
            items.sortedBy { at[key(name(it))] ?: Int.MAX_VALUE }
        }
    }

    /** Sets the column's order; provider order clears it. The manual order is kept for later. */
    fun withSort(sort: GroupSort): VodCategoryFilter = copy(sort = sort.name.takeIf { sort != GroupSort.PROVIDER })

    /**
     * Moves the category named [name] to [toIndex] among [shown], the [kind]'s category names as the
     * column shows them now, and switches to the manual order seeded from [shown], so nothing else jumps.
     */
    fun withMove(kind: String, shown: List<String>, name: String, toIndex: Int): VodCategoryFilter {
        val list = shown.map { it.trim() }.distinctBy(::key).toMutableList()
        val from = list.indexOfFirst { key(it) == key(name) }
        if (from < 0) return this
        list.add(toIndex.coerceIn(0, list.size - 1), list.removeAt(from))
        return copy(sort = GroupSort.MANUAL.name, order = order + (kind to list))
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val NONE = VodCategoryFilter()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Reads the setting; an unreadable value keeps everything rather than hiding the catalog. */
        fun parse(raw: String?): VodCategoryFilter =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: NONE

        private fun key(name: String) = name.trim().lowercase()

        private fun compile(pattern: String): Regex? {
            val text = pattern.trim().ifEmpty { return null }
            val body = buildString {
                var literal = StringBuilder()
                fun flush() { if (literal.isNotEmpty()) append(Regex.escape(literal.toString())); literal = StringBuilder() }
                for (c in text) when (c) {
                    '*' -> { flush(); append(".*") }
                    '?' -> { flush(); append('.') }
                    else -> literal.append(c)
                }
                flush()
            }
            return Regex("\\s*$body\\s*", RegexOption.IGNORE_CASE)
        }
    }
}

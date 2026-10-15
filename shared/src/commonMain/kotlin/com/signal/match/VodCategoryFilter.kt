package com.signal.match

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
 * There's no screen for this yet: it was set once by hand (2026-10-14, "hide the non-English groups").
 */
@Serializable
data class VodCategoryFilter(
    @SerialName("show_only") val showOnly: List<String> = emptyList(),
    val hide: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = showOnly.isEmpty() && hide.isEmpty()

    private val showRegexes by lazy { showOnly.mapNotNull(::compile) }
    private val hideRegexes by lazy { hide.mapNotNull(::compile) }

    /** True when titles in the category named [name] should be left out. */
    fun hides(name: String): Boolean {
        if (isEmpty) return false
        if (hideRegexes.any { it.matches(name) }) return true
        return showRegexes.isNotEmpty() && showRegexes.none { it.matches(name) }
    }

    companion object {
        val NONE = VodCategoryFilter()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Reads the setting; an unreadable value keeps everything rather than hiding the catalog. */
        fun parse(raw: String?): VodCategoryFilter =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: NONE

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

package com.signal.match

/**
 * Search folding (PLAN §4.3.14 → Matching): the same character clean-up as names, then lowercase
 * and no accents, so "Fútbol ᴴᴰ" and "futbol hd" match. Indexes and queries both go through it.
 */
object SearchText {
    fun fold(text: String): String =
        stripAccents(CharacterCleanup.clean(text, removeEmoji = true).lowercase())
            .replace(nonWord, " ").trim().replace(spaces, " ")

    /** A query's words, each matched as a prefix ("fox spo" finds FOX Sports). */
    fun words(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private val spaces = Regex("""\s+""")
}

/** Removes combining accents: "é" becomes "e". */
internal expect fun stripAccents(text: String): String

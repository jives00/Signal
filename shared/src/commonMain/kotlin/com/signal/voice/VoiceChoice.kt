package com.signal.voice

/**
 * Whether a voice command acts on a channel at once or asks which one (PLAN §4.3.14 → Voice
 * commands, phase 2). Hits with the same name count once, as the first of them (the search's
 * order: voice names, favorites, then the best matches): with name cleanup, one channel is often
 * several streams of the same name, and asking between identical names helps no one.
 *
 * One clear match acts: the only exact one (a voice name or the whole name), the only exact
 * favorite, or failing an exact match, the only match or the only favorite. Anything else is a
 * choice of the top three, so a weak or shared name never tunes a guess.
 */
object VoiceChoice {
    sealed interface Pick<T> {
        data class One<T>(val hit: T) : Pick<T>
        data class Several<T>(val hits: List<T>) : Pick<T>
    }

    const val CHOICES = 3

    /** [hits] in search order. Null when empty. */
    fun <T> choose(hits: List<T>, name: (T) -> String, exact: (T) -> Boolean, favorite: (T) -> Boolean): Pick<T>? {
        if (hits.isEmpty()) return null
        val distinct = hits.distinctBy { name(it).lowercase() }
        val pool = distinct.filter(exact).ifEmpty { distinct }
        if (pool.size == 1) return Pick.One(pool[0])
        val favorites = pool.filter(favorite)
        if (favorites.size == 1) return Pick.One(favorites[0])
        return Pick.Several(favorites.ifEmpty { pool }.take(CHOICES))
    }
}

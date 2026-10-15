package com.signal.data

/**
 * A channel's voice names (PLAN §4.3.14 → Voice names): stored as one comma-separated field,
 * `voice_names`, on the channel's profile row, and synced like its custom name. The web admin
 * writes the same format.
 */
object VoiceNames {
    const val MAX = 10
    const val MAX_LENGTH = 50

    fun parse(stored: String?): List<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX)

    /** Null when there are none, so the field clears. */
    fun format(names: List<String>): String? =
        names.map { it.replace(",", " ").trim().take(MAX_LENGTH) }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.take(MAX).joinToString(", ").ifEmpty { null }
}

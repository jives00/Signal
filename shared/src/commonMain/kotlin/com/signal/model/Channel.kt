package com.signal.model

/**
 * A channel as the screens show it: one provider stream merged with the user's own record of it,
 * if there is one (PLAN §4.5). Built by the repository; screens never see raw streams.
 */
data class Channel(
    val stream: ProviderStream,
    /** Null until something is stored about this channel (PLAN §4.5 "A user channel is created..."). */
    val userChannelId: String?,
    val displayName: String,
    val number: Int,
    val logoUrl: String?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
) {
    /** Stable key for lists and focus: the user channel when there is one, else the stream. */
    val key: String get() = userChannelId ?: stream.id
    val groupName: String get() = stream.groupName
    val initials: String get() = displayName.initials()
}

/** Logo tiles show these until a logo loads, and keep them as the fallback (PLAN §3). */
fun String.initials(): String {
    val words = split(' ', '-', '_', '.').filter { w -> w.any { it.isLetterOrDigit() } }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].filter { it.isLetterOrDigit() }.take(3).uppercase()
        else -> words.take(3).joinToString("") { w -> w.first { it.isLetterOrDigit() }.uppercase() }
    }
}

/** A group in Live TV's group list (PLAN §4.3.2): Favorites, a playlist or a provider group. */
sealed interface ChannelGroup {
    val key: String
    val title: String

    data object Favorites : ChannelGroup {
        override val key = "favorites"
        override val title = "Favorites"
    }

    data class Playlist(val id: String, override val title: String) : ChannelGroup {
        override val key = "playlist:$id"
    }

    data class Provider(val sourceId: String, val name: String, override val title: String) : ChannelGroup {
        override val key = "group:$sourceId:$name"
    }
}

enum class GroupSort { PROVIDER, A_Z, Z_A, MANUAL }
enum class ChannelSort { PROVIDER, NUMBER, A_Z }

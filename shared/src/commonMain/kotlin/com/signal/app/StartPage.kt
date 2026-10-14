package com.signal.app

/** Settings → General → Start page (milestone 6): the page the app opens on. Stored by name; unset is Home. */
enum class StartPage(val label: String) {
    HOME("Home"),
    LIVE_TV("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    LAST_CHANNEL("Last channel, full screen"),
    ;

    companion object {
        fun from(raw: String?): StartPage = entries.firstOrNull { it.name == raw } ?: HOME
    }
}

package com.signal.voice

import kotlinx.serialization.Serializable

/**
 * What can be said to Signal and what it does (PLAN §4.3.14 → Voice commands, phase 2): the one
 * text behind the TV's Settings → What to say and the web admin's Voice Commands page, which the
 * companion serves at /api/voice/commands. Written by the user (2026-10-16): an opening line, then
 * sections with a line on their keyword and a bullet per command. Keep it in step with
 * [VoiceCommand] and docs/voice-commands.md.
 */
@Serializable
data class VoiceHelp(val intro: String, val groups: List<Group>) {
    /** [items] are bullets: each a sentence, its spoken examples in quotes. */
    @Serializable
    data class Group(val title: String, val intro: String? = null, val items: List<String>)

    companion object {
        val ALL = VoiceHelp(
            intro = "Press or hold the mic button on your remote and say a channel, title, or command. The panel at the bottom of the screen will show what was heard and what action is taken.",
            groups = listOf(
                Group(
                    "Live TV",
                    "The primary keyword is “Watch” to indicate use Live TV.",
                    listOf(
                        "“ESPN”, “Switch to Fox Sports 1” or “Watch the Weather Channel” to switch to that channel.",
                        "The system only searches your favorites, playlists and channels with voice names by default. This can be changed in Settings > Voice Commands to search all unhidden channels.",
                        "Add aliases to a channel under Voice Names, e.g. FS1 for Fox Sports 1.",
                        "“Channel up” or “channel down” will change channels.",
                        "“Last channel”, “previous channel” or “go to last channel” will switch back to the last watched one.",
                        "“What’s on ESPN” can be used to see what is currently airing on a channel without switching.",
                        "“Watch Friends” will change to the channel currently airing the show.",
                        "“Watch the Bears game” will switch to the channel currently airing that game.",
                        "“Remind me about the Bears game” will add a reminder to the home page.",
                    ),
                ),
                Group(
                    "Movies & Series On Demand",
                    "The primary keyword is “Play” to indicate using video on demand.",
                    listOf(
                        "“Play Inception” will start playing the movie.",
                        "“Play The Office” will start playing the show.",
                        "“Play The Office Season 2 Episode 3” will play that specific episode.",
                        "“Continue The Office” or “Keep watching The Office” will pick up where you left off.",
                        "“Pause”, “Resume”, “Play” and “Stop” will all affect the streaming video.",
                        "“Back 30 seconds”, “Skip ahead 5 minutes” will move the stream to that timestamp.",
                        "“Start over” or “Restart this” will reset the time to the beginning.",
                        "“Next” or “Next episode” will skip to the next episode in the show.",
                    ),
                ),
                Group(
                    "Other",
                    items = listOf(
                        "“Captions on”, “Captions”, “Turn off Subtitles” will turn on/off closed captions.",
                        "“Change audio to French” will switch the audio track (if available).",
                        "Saying “Go to Guide” will navigate to that page in the app (Home, Live TV, Guide, Movies, Series, Search or Settings).",
                    ),
                ),
            ),
        )
    }
}

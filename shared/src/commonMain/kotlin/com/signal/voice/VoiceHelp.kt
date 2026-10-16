package com.signal.voice

import kotlinx.serialization.Serializable

/**
 * What can be said to Signal and what it does (PLAN §4.3.14 → Voice commands, phase 2): the one
 * list behind the TV's Settings → What to say and the web admin's Voice Commands page, which the
 * companion serves at /api/voice/commands. Keep it in step with [VoiceCommand] and
 * docs/voice-commands.md.
 */
@Serializable
data class VoiceHelp(val groups: List<Group>, val tips: List<String>) {
    @Serializable
    data class Group(val title: String, val intro: String? = null, val commands: List<Command>, val since: String? = null)

    /** [say] are examples, as spoken; [does] says what happens. */
    @Serializable
    data class Command(val say: List<String>, val does: String)

    companion object {
        val ALL = VoiceHelp(
            groups = listOf(
                Group(
                    "Channels",
                    "Say a channel's name, alone or after \"switch to\", \"watch\", \"go to\", \"change to\" or \"put on\".",
                    listOf(
                        Command(listOf("ESPN", "switch to fox sports one", "watch the weather channel"), "Tunes the channel full screen, from anywhere on the TV."),
                        Command(listOf("E S P N", "C B S"), "Initials work spelled out, letter by letter."),
                        Command(listOf("fox"), "When a name fits more than one channel, a panel lists up to three: OK picks one and Back cancels. One clear match tunes at once: the only channel with exactly that name, or the only favorite among them."),
                        Command(listOf("channel up", "channel down", "next channel"), "Changes channel, as the remote's channel buttons do."),
                        Command(listOf("last channel", "go to the last channel", "previous channel"), "Goes back to the channel before this one."),
                        Command(listOf("what's on ESPN", "what's on"), "Shows the program on now without changing channel. With no channel, it's the one playing."),
                        Command(listOf("watch Friends", "watch Jeopardy"), "Tunes the channel showing that program now. (\"Play Friends\" plays it from Movies & Series instead.)"),
                    ),
                ),
                Group(
                    "Games and reminders",
                    "Games need their teams in the guide's title, which the NAS's guide adds for most big-league games.",
                    listOf(
                        Command(listOf("watch the Steelers game", "put on the Cleveland game"), "Tunes the game on now with that team, by nickname or city. When it's on more than one channel, a panel asks which. A game that hasn't started says when and where."),
                        Command(listOf("remind me about the Steelers game", "remind me about Jeopardy"), "Sets a reminder on the next airing. When several programs match, a panel lists up to three."),
                    ),
                    since = "Signal 0.2.24 or later",
                ),
                Group(
                    "Playback",
                    "These act on what's playing. Live TV can't pause, stop or skip, so on live TV Signal says so and keeps playing.",
                    listOf(
                        Command(listOf("pause", "resume", "play"), "Pauses or resumes a movie or episode."),
                        Command(listOf("stop"), "Stops a movie or episode and goes back."),
                        Command(listOf("back thirty seconds", "skip ahead ten minutes", "rewind", "fast forward"), "Skips back or ahead in a movie or episode. \"Rewind\" or \"fast forward\" alone moves 30 seconds."),
                        Command(listOf("start over", "start the movie over", "restart this"), "Plays the movie or episode from the beginning."),
                        Command(listOf("next episode", "next"), "Plays the next episode. On live TV, \"next\" is the next channel."),
                    ),
                ),
                Group(
                    "Captions and audio",
                    commands = listOf(
                        Command(listOf("captions on", "turn off subtitles", "captions"), "Turns captions on or off. \"Captions\" alone switches them."),
                        Command(listOf("Spanish audio", "change the audio to French"), "Picks the audio track in that language, when the channel or video has one."),
                    ),
                ),
                Group(
                    "Pages",
                    "\"Go to\", \"open\" and \"show me\" work in front of any of these.",
                    listOf(
                        Command(listOf("guide", "live TV"), "Live TV's guide."),
                        Command(listOf("my favorites"), "Live TV, on Favorites."),
                        Command(listOf("home", "movies", "series", "search", "settings"), "Opens the page."),
                    ),
                ),
                Group(
                    "Movies and series",
                    commands = listOf(
                        Command(listOf("search Seinfeld", "find the office"), "Opens Search with the words filled in."),
                        Command(listOf("play Inception", "play the movie Inception"), "Plays the title when exactly one has that name: a movie resumes where you left it, a series where you're up to. When several share it, a panel lists up to three. If nothing matches, Search opens with the words filled in."),
                        Command(listOf("play The Office season two episode five"), "Plays that episode."),
                        Command(listOf("continue Seinfeld", "keep watching The Office"), "Picks up from Continue watching, or where the series is up to."),
                    ),
                    since = "Signal 0.2.22 or later",
                ),
            ),
            tips = listOf(
                "Voice names give a channel easier names to say (\"fox\" for your local FOX station). Set them by long-pressing OK on a channel, or on the web admin's Channels page. A voice name wins over everything else.",
                "Channels you name are your favorites, playlists and channels with voice names, which are understood best. Settings → Voice commands → Channels to match can widen that to every visible channel. Games are found on every channel either way.",
                "Start a movie or show with \"play\", \"search\" or \"continue\": those words make Signal listen for any title, which takes a moment longer. For channels, say \"watch\" or just the name, which is faster.",
                "A channel's name wins when it matches exactly. Titles you're likely to say are recognized best: Continue watching, your Library, series with new episodes, and the newest movies and series.",
            ),
        )
    }
}

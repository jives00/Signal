package com.signal.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCommandTest {
    private fun tune(heard: String) = (VoiceCommand.parse(heard) as? VoiceCommand.Tune)?.channel

    @Test fun switchTo() = assertEquals("nbc", tune("switch to NBC"))
    @Test fun spelledInitials() = assertEquals("nbc", tune("switch to n b c"))
    @Test fun watch() = assertEquals("espn", tune("watch e s p n"))
    @Test fun changeTheChannelTo() = assertEquals("fox news", tune("change the channel to fox news"))
    @Test fun bareName() = assertEquals("cnn", tune("c n n"))
    @Test fun fillersAndTrailing() = assertEquals("abc channel", tune("ok please go to a b c channel please"))

    // "Channel" is part of some names and a filler after others: both are searched, the name first.
    @Test fun trailingChannelIsTriedBothWays() {
        assertEquals(listOf("golf channel", "golf"), VoiceCommand.Tune("golf channel").names)
        assertEquals(listOf("channel"), VoiceCommand.Tune("channel").names)
    }

    // "Watch the discovery channel" found nothing (Chromecast, 0.2.33): "the" is tried as said, then without.
    @Test fun leadingTheIsTriedBothWays() {
        assertEquals(
            listOf("the discovery channel", "the discovery", "discovery channel", "discovery"),
            VoiceCommand.Tune("the discovery channel").names,
        )
        assertEquals(listOf("the"), VoiceCommand.Tune("the").names)
    }
    @Test fun lettersInsideWords() = assertEquals("fox sports 1", tune("put on fox sports 1"))
    @Test fun nothing() = assertNull(VoiceCommand.parse("  "))
    @Test fun onlyAPrefix() = assertEquals("switch", tune("switch"))
    @Test fun spokenNumber() = assertEquals("abc 7 chicago", tune("switch to a b c seven chicago"))
    @Test fun twoWordNumber() = assertEquals("fox 29", tune("watch fox twenty nine"))
    @Test fun hundreds() = assertEquals("channel 101", tune("go to channel one hundred one"))
    @Test fun separateNumbers() = assertEquals("4 5", tune("four five"))
    @Test fun unknownWords() = assertEquals("golf", tune("switch to [unk] golf [unk]"))
    @Test fun onlyUnknown() = assertNull(VoiceCommand.parse("switch to [unk]"))

    @Test fun toHeardAsTwo() = assertEquals("nbc", tune("switch two nbc"))
    @Test fun changeChannelToo() = assertEquals("nbc", tune("change channel too n b c"))

    private val initials = listOf("abc", "nbc", "cbs", "espn", "pbs", "tnt", "us", "hd", "fhd")

    // What the Shield heard on 2026-10-15.
    @Test fun abcHeardWithAnExtraLetter() = assertEquals("abc", VoiceCommand.soundsLike("eabt", initials).first())
    @Test fun espnHeardAsRhymes() = assertEquals("espn", VoiceCommand.soundsLike("efen", initials).first())
    @Test fun espnHeardAsEfTn() = assertEquals("espn", VoiceCommand.soundsLike("ef tn", initials).first())
    @Test fun differentSoundIsNoMatch() = assert("cbs" !in VoiceCommand.soundsLike("pba", initials))
    @Test fun wordsAreNotLetters() = assert(VoiceCommand.soundsLike("fox", listOf("fox")).isEmpty() && VoiceCommand.soundsLike("lounge", initials).isEmpty())
    @Test fun nRhymesWithMNotA() = assertEquals(listOf("nbc"), VoiceCommand.soundsLike("nbd", listOf("abc", "nbc")))
    @Test fun closestLettersFirst() = assertEquals(listOf("pbs", "tbs"), VoiceCommand.soundsLike("pbf", listOf("tbs", "pbs")))

    @Test fun initialsAreUnpronounceable() {
        for (w in listOf("espn", "cbs", "nbc", "abc", "hbo", "usa", "tnt", "fs", "hd")) assert(VoiceCommand.isInitials(w)) { w }
        for (w in listOf("ten", "bet", "fox", "east", "news", "fs1", "network")) assert(!VoiceCommand.isInitials(w)) { w }
    }

    // Phase 2 (PLAN §4.3.14 → Voice commands, phase 2).
    private fun parse(heard: String) = VoiceCommand.parse(heard)

    @Test fun playback() {
        assertEquals(VoiceCommand.Pause, parse("pause"))
        assertEquals(VoiceCommand.Pause, parse("ok pause it please"))
        assertEquals(VoiceCommand.Resume, parse("play"))
        assertEquals(VoiceCommand.Resume, parse("resume"))
        assertEquals(VoiceCommand.Stop, parse("stop"))
        assertEquals(VoiceCommand.StartOver, parse("start over"))
        assertEquals(VoiceCommand.Next, parse("next"))
        assertEquals(VoiceCommand.NextEpisode, parse("next episode"))
        assertEquals(VoiceCommand.LastChannel, parse("last channel"))
        assertEquals(VoiceCommand.LastChannel, parse("previous channel"))
        assertEquals(VoiceCommand.ChannelStep(-1), parse("channel up"))
        assertEquals(VoiceCommand.ChannelStep(1), parse("channel down"))
        assertEquals(VoiceCommand.ChannelStep(1), parse("next channel"))
    }

    // What the user said on 0.2.20 that wasn't understood, and its neighbors.
    @Test fun moreWaysToSayThem() {
        for (s in listOf("go to the last channel", "go back to the previous channel", "switch to the last channel", "the last channel", "flip back"))
            assertEquals(VoiceCommand.LastChannel, parse(s), s)
        for (s in listOf("start the movie over", "start it over", "restart the show", "play the episode from the beginning", "start from the beginning", "start over"))
            assertEquals(VoiceCommand.StartOver, parse(s), s)
        // A command after a channel's lead-in.
        assertEquals(VoiceCommand.Captions(null), parse("put on captions"))
        assertEquals(VoiceCommand.NextEpisode, parse("play the next episode"))
        // Names that only start like one still tune.
        assertEquals(VoiceCommand.Tune("start tv"), parse("watch start tv"))
    }

    @Test fun moviesAndSeries() {
        assertEquals(VoiceCommand.Search("seinfeld"), parse("search seinfeld"))
        assertEquals(VoiceCommand.Search("the office"), parse("search for the office"))
        assertEquals(VoiceCommand.Search("apollo thirteen"), parse("look up apollo thirteen"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.SEARCH), parse("search"))
        assertEquals(VoiceCommand.ContinueTitle("seinfeld"), parse("continue seinfeld"))
        assertEquals(VoiceCommand.ContinueTitle("the office"), parse("keep watching the office"))
        assertEquals(VoiceCommand.Resume, parse("continue watching"))
        assertEquals(VoiceCommand.PlayTitle("inception", com.signal.model.VodKind.MOVIE), parse("play the movie inception"))
        assertEquals(VoiceCommand.PlayTitle("the wire", com.signal.model.VodKind.SERIES), parse("watch the show the wire"))
        assertEquals(VoiceCommand.PlayTitle("the office", com.signal.model.VodKind.SERIES, 2, 5), parse("play the office season two episode five"))
        assertEquals(VoiceCommand.PlayTitle("lost", com.signal.model.VodKind.SERIES, 3, 12), parse("watch lost episode twelve of season three"))
        assertEquals(VoiceCommand.PlayTitle("24", com.signal.model.VodKind.SERIES, 1, 1), parse("twenty four season one episode one"))
        // "Play ___" alone is a channel first; the controller tries titles when no channel is clear.
        assertEquals(VoiceCommand.Tune("seinfeld"), parse("play seinfeld"))
    }

    // What starts the free recognizer, from the grammar's words so far (0.2.23 on the Shield).
    @Test fun whatWantsATitle() {
        for (s in listOf("play the me tribeca", "play me", "ok play the", "search the off", "continue the office", "keep watching lost", "watch the movie make", "[unk]", "the [unk] show"))
            assert(VoiceCommand.wantsTitle(s)) { s }
        for (s in listOf("watch e s p n", "espn", "play", "pause", "continue", "guide", "channel up", "back thirty seconds", "fox sports one"))
            assert(!VoiceCommand.wantsTitle(s)) { s }
    }

    @Test fun gamesAndReminders() {
        assertEquals(VoiceCommand.Game("steelers"), parse("watch the steelers game"))
        assertEquals(VoiceCommand.Game("pittsburgh steelers"), parse("put on the pittsburgh steelers game"))
        assertEquals(VoiceCommand.Game("cubs"), parse("cubs game"))
        // Not teams.
        assertEquals(null, VoiceCommand.gameOf("the next game"))
        assertEquals(null, VoiceCommand.gameOf("the football game"))
        assertEquals(VoiceCommand.Remind("jeopardy"), parse("remind me about jeopardy"))
        assertEquals(VoiceCommand.Remind("the steelers game"), parse("remind me about the steelers game"))
        assertEquals("steelers", VoiceCommand.Remind("the steelers game").team)
        assertEquals(null, VoiceCommand.Remind("jeopardy").team)
        assertEquals(VoiceCommand.Remind("the news"), parse("set a reminder for the news"))
        // "Remind me" starts the free recognizer; a game doesn't need it.
        assert(VoiceCommand.wantsTitle("remind me about jeopardy"))
        assert(!VoiceCommand.wantsTitle("watch the steelers game"))
    }

    @Test fun teamsInTheGrammar() {
        val phrases = VoiceCommand.phrases(emptyList(), emptySet(), teams = listOf("pittsburgh steelers", "steelers", "49ers"))
        for (p in listOf("steelers game", "pittsburgh steelers game", "forty nine ers game", "remind me about the")) assert(p in phrases) { p }
    }

    @Test fun gameTitlesGiveTheirTeams() {
        val m = com.signal.guide.GameTitles.matchup("NFL Football - Pittsburgh Steelers vs. Cleveland Browns")
        assertEquals(com.signal.guide.GameTitles.Matchup("Pittsburgh Steelers", "Cleveland Browns"), m)
        assertNull(com.signal.guide.GameTitles.matchup("NFL Football"))
        assertEquals(listOf("Pittsburgh Steelers", "Steelers", "Pittsburgh"), com.signal.guide.GameTitles.spokenNames("Pittsburgh Steelers"))
        assertEquals(listOf("Texas State Bobcats", "Bobcats", "Texas State"), com.signal.guide.GameTitles.spokenNames("Texas State Bobcats"))
    }

    @Test fun spokenTitlesKeepTheirWords() {
        assertEquals("apollo thirteen" to true, VoiceCommand.spokenTitle("ok play apollo thirteen please"))
        assertEquals("seinfeld" to false, VoiceCommand.spokenTitle("seinfeld"))
    }

    @Test fun likelyTitlesAreInTheGrammar() {
        val phrases = VoiceCommand.phrases(emptyList(), emptySet(), titles = listOf("the office", "apollo 13"))
        for (p in listOf("the office", "apollo thirteen", "season two", "episode five", "continue watching")) assert(p in phrases) { p }
    }

    @Test fun seeks() {
        assertEquals(VoiceCommand.Seek(-30_000), parse("back thirty seconds"))
        assertEquals(VoiceCommand.Seek(-30_000), parse("rewind"))
        assertEquals(VoiceCommand.Seek(600_000), parse("skip ahead ten minutes"))
        assertEquals(VoiceCommand.Seek(60_000), parse("fast forward a minute"))
        assertEquals(VoiceCommand.Seek(-45_000), parse("go back forty five seconds"))
        assertEquals(VoiceCommand.Seek(-15_000), parse("back fifteen"))
        assertEquals(VoiceCommand.Seek(VoiceCommand.DEFAULT_SEEK_MS), parse("skip ahead"))
    }

    // Without an amount, "back" isn't a seek: it could be a channel's name.
    @Test fun bareBackIsNotASeek() = assertEquals(VoiceCommand.Tune("back"), parse("back"))

    @Test fun pages() {
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.GUIDE), parse("guide"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.GUIDE), parse("go to the guide"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.HOME), parse("go home"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.MOVIES), parse("show me movies"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.SERIES), parse("open tv shows"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.FAVORITES), parse("my favorites"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.SETTINGS), parse("open settings"))
        assertEquals(VoiceCommand.Open(VoiceCommand.Page.SEARCH), parse("search"))
    }

    // A name that starts like a page is still a channel.
    @Test fun channelsThatStartLikePages() = assertEquals(VoiceCommand.Tune("home and garden"), parse("watch home and garden"))

    @Test fun captionsAndAudio() {
        assertEquals(VoiceCommand.Captions(true), parse("turn on captions"))
        assertEquals(VoiceCommand.Captions(false), parse("subtitles off"))
        assertEquals(VoiceCommand.Captions(false), parse("turn closed captions off"))
        assertEquals(VoiceCommand.Captions(null), parse("captions"))
        assertEquals(VoiceCommand.Audio(VoiceCommand.Language.SPANISH), parse("spanish audio"))
        assertEquals(VoiceCommand.Audio(VoiceCommand.Language.FRENCH), parse("change the audio to french"))
        // "Switch to Spanish" stays a channel: a channel can be called that.
        assertEquals(VoiceCommand.Tune("spanish"), parse("switch to spanish"))
    }

    @Test fun languageMatchesCodesAndLabels() {
        assert(VoiceCommand.Language.SPANISH.matches("spa", "Track 2"))
        assert(VoiceCommand.Language.SPANISH.matches("es-419", ""))
        assert(VoiceCommand.Language.SPANISH.matches(null, "Spanish (Stereo)"))
        assert(!VoiceCommand.Language.SPANISH.matches("en", "English"))
    }

    @Test fun whatsOn() {
        assertEquals(VoiceCommand.WhatsOn("espn"), parse("what's on e s p n"))
        assertEquals(VoiceCommand.WhatsOn("fox 29"), parse("what is on fox twenty nine"))
        assertEquals(VoiceCommand.WhatsOn("nbc"), parse("what's playing on n b c"))
        assertEquals(VoiceCommand.WhatsOn(null), parse("what's on"))
        assertEquals(VoiceCommand.WhatsOn(null), parse("what's on now"))
    }

    @Test fun commandPhrasesAreInTheGrammar() {
        val phrases = VoiceCommand.phrases(emptyList(), emptySet())
        for (p in listOf("pause", "next episode", "channel up", "back thirty seconds", "skip ahead ten minutes", "turn on captions", "spanish audio", "what's on", "the guide"))
            assert(p in phrases) { p }
    }

    @Test fun twoLettersNeverBySound() = assert(VoiceCommand.soundsLike("vba", listOf("ca")).isEmpty())

    @Test fun phrasesFromNames() {
        val phrases = VoiceCommand.phrases(listOf("abc 7 chicago", "fox 29 philadelphia", "ultra 4k"), initials = setOf("abc", "fox"))
        for (p in listOf("switch to", "abc", "a b c", "abc seven", "a b c seven chicago", "chicago", "fox twenty nine", "f o x", "ultra four k", "four k"))
            assert(p in phrases) { p }
        // Bare numbers and letters are never phrases of their own: they made "switch two" and "eighty e".
        for (p in listOf("seven", "twenty nine", "7", "a", "two")) assert(p !in phrases) { p }
        // Only initials are spelled out: spelling every short word made "a b d".
        for (p in listOf("u l t r a", "c h i")) assert(p !in phrases) { p }
    }
}

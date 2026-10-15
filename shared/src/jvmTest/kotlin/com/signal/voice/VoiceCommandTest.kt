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
    @Test fun fillersAndTrailing() = assertEquals("abc", tune("ok please go to a b c channel please"))
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

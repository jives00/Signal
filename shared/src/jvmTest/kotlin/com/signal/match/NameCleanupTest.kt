package com.signal.match

import com.signal.match.NameCleanup.At
import com.signal.match.NameCleanup.Preset
import com.signal.match.NameCleanup.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameCleanupTest {
    private fun presets(vararg p: Preset) = NameCleanup(presets = p.toSet())

    @Test fun countryCodesAtTheStart() {
        val c = presets(Preset.COUNTRY)
        assertEquals("ESPN", c.apply("US: ESPN"))
        assertEquals("BBC One", c.apply("|UK| BBC One"))
        assertEquals("TSN", c.apply("[CA] TSN"))
        assertEquals("Sports 1", c.apply("US| Sports 1"))
        // Lower-case words and longer tags aren't country codes.
        assertEquals("Fox: Live", c.apply("Fox: Live"))
        assertEquals("GOLD: TV6", c.apply("GOLD: TV6"))
    }

    @Test fun shortTagsBeforeAColon() {
        val c = presets(Preset.TAG)
        assertEquals("SIC", c.apply("MEO: SIC"))
        assertEquals("SKY SPORT UHD 3840P", c.apply("4K: SKY SPORT UHD 3840P"))
        assertEquals("EROTIC 2 RAW", c.apply("ADULT: EROTIC 2 RAW"))
        assertEquals("Fox: Live", c.apply("Fox: Live"))
    }

    @Test fun qualityTagsAtTheEnd() {
        val c = presets(Preset.QUALITY)
        assertEquals("ESPN", c.apply("ESPN FHD"))
        assertEquals("SKY SPORT", c.apply("SKY SPORT UHD 3840P"))
        assertEquals("TV 2 DIREKTE", c.apply("TV 2 DIREKTE RAW 50fps"))
        assertEquals("Sky Go Filme 33", c.apply("Sky Go Filme 33 4K"))
        assertEquals("ESPN", c.apply("ESPN [HD]"))
        assertEquals("ESPN", c.apply("ESPN - HD"))
        // Only whole words at the end.
        assertEquals("ESPNHD", c.apply("ESPNHD"))
        assertEquals("HD Network", c.apply("HD Network"))
        // A name that is only a tag stays.
        assertEquals("4K", c.apply("4K"))
    }

    @Test fun markersAtTheEnd() {
        val c = presets(Preset.MARKERS)
        assertEquals("ESPN", c.apply("ESPN (Backup)"))
        assertEquals("SKY SPORT", c.apply("SKY SPORT (LIVE-EVENT)"))
        assertEquals("SKY SPORT", c.apply("SKY SPORT [VIP]"))
        assertEquals("BEIN 4K", c.apply("BEIN 4K (EVENT ONLY)"))
        // Other brackets carry information.
        assertEquals("Iowa vs Rockford (Home) (2026-10-16 20:00)", c.apply("Iowa vs Rockford (Home) (2026-10-16 20:00)"))
        assertEquals("FRANCE 24 (I)", c.apply("FRANCE 24 (I)"))
        assertEquals("DAZN 1", c.apply("DAZN 1 VIP"))
        assertEquals("Pacific (OR) Live", c.apply("Pacific (OR) Live"))
    }

    @Test fun presetsCombineUntilNothingChanges() {
        val c = presets(*Preset.entries.toTypedArray())
        assertEquals("RIKSTOTO DIREKTE 3", c.apply("NO: RIKSTOTO DIREKTE 3 RAW VIP"))
        assertEquals("SKY SPORT", c.apply("4K: SKY SPORT UHD 3840P (LIVE-EVENT)"))
        assertEquals("ESPN", c.apply("US: ESPN FHD HEVC"))
    }

    @Test fun customPrefixesAndSuffixes() {
        val c = NameCleanup(custom = listOf(Rule("us:", At.START), Rule("HD", At.END), Rule("|*|", At.START), Rule(" (*)", At.END)))
        assertEquals("ESPN", c.apply("US: ESPN HD"))
        assertEquals("BBC One", c.apply("|UK| BBC One"))
        assertEquals("CNN (Live) News", c.apply("CNN (Live) News"))
        assertEquals("CNN (Live) News", c.apply("CNN (Live) News (Backup)"))
        assertEquals("ESPNHD", c.apply("ESPNHD"))
        // Regex characters in the text are literal.
        assertEquals("A.B", NameCleanup(custom = listOf(Rule("[x]", At.END))).apply("A.B [x]"))
    }

    @Test fun customWildcardsTakeTheShortestMatch() {
        assertEquals("Movies (2020) East", NameCleanup(custom = listOf(Rule("(*)", At.END))).apply("Movies (2020) East (HD)"))
        assertEquals("CNN", NameCleanup(custom = listOf(Rule("US?", At.START))).apply("USA CNN"))
    }

    @Test fun anywhereRemovesEveryWholeMatch() {
        val c = NameCleanup(custom = listOf(Rule("HD", At.ANYWHERE)))
        assertEquals("ESPN East", c.apply("ESPN HD East"))
        assertEquals("ESPN East", c.apply("hd ESPN HD East HD"))
        assertEquals("HDTV ESPNHD", c.apply("HDTV ESPNHD"))
        assertEquals("Sky Sport", NameCleanup(custom = listOf(Rule("[*]", At.ANYWHERE))).apply("Sky [VIP] Sport"))
    }

    @Test fun regexRulesAnchorForStartAndEnd() {
        assertEquals("ESPN", NameCleanup(custom = listOf(Rule("""\(FLSP \d+\) \|""", At.START, regex = true))).apply("(FLSP 435) | ESPN"))
        assertEquals("Hockey: A vs B", NameCleanup(custom = listOf(Rule("""\(\d{4}-\d\d-\d\d [\d:]+\)""", At.END, regex = true))).apply("Hockey: A vs B (2026-10-16 19:03:10)"))
        // Anchored: the same pattern at the start doesn't touch the end.
        assertEquals("A HD", NameCleanup(custom = listOf(Rule("HD", At.START, regex = true))).apply("A HD"))
        assertEquals("A B", NameCleanup(custom = listOf(Rule("""\d+""", At.ANYWHERE, regex = true))).apply("A 12 B 3"))
    }

    @Test fun badRegexIsSkippedAndReported() {
        val bad = Rule("(unclosed", At.ANYWHERE, regex = true)
        val c = NameCleanup(custom = listOf(bad, Rule("HD", At.END)))
        assertEquals("ESPN", c.apply("ESPN HD"))
        assertEquals(listOf(bad), c.invalidRules())
        // As plain text the same characters are literal.
        assertEquals(emptyList(), NameCleanup(custom = listOf(bad.copy(regex = false))).invalidRules())
    }

    @Test fun neverBlank() {
        assertEquals("HD", NameCleanup(custom = listOf(Rule("HD", At.END))).apply("HD"))
    }

    @Test fun roundTripsAndIgnoresJunk() {
        val c = NameCleanup(setOf(Preset.COUNTRY), listOf(Rule("HD", At.END), Rule("  ", At.START)), groups = true)
        val encoded = c.encode()!!
        assertEquals(c.copy(custom = listOf(Rule("HD", At.END))), NameCleanup.parse(encoded))
        assertNull(NameCleanup.NONE.encode())
        assertEquals(NameCleanup.NONE, NameCleanup.parse("not json"))
        assertEquals(NameCleanup.NONE, NameCleanup.parse(null))
    }
}

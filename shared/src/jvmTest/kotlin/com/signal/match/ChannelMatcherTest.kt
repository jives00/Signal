package com.signal.match

import com.signal.match.ChannelMatcher.Confidence
import com.signal.match.ChannelMatcher.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelMatcherTest {
    private fun s(name: String, tvg: String? = null, id: String = name, source: String = "new", group: String = "") = Stream(id, source, name, tvg, groupName = group)

    @Test fun sameNameAcrossPrefixesAndQualityTagsIsConfident() {
        val m = assertNotNull(ChannelMatcher.score(s("US: FOX Sports 1 HD", source = "old"), s("|US| Fox Sports 1 FHD (Backup)")))
        assertEquals(Confidence.CONFIDENT, m.confidence)
    }

    @Test fun numbersKeepChannelsApart() {
        assertNull(ChannelMatcher.score(s("Fox Sports 1"), s("Fox Sports 2")))
        assertNull(ChannelMatcher.score(s("ESPN"), s("ESPN 2")))
    }

    @Test fun aliasesMatch() {
        assertEquals(Confidence.CONFIDENT, ChannelMatcher.score(s("FS1"), s("US: Fox Sports 1"))?.confidence)
        val learned = ChannelMatcher.aliases(listOf("Sky Sport Main Event" to "SS Main Event"))
        assertEquals(Confidence.CONFIDENT, ChannelMatcher.score(s("SS Main Event"), s("Sky Sport Main Event"), learned)?.confidence)
    }

    @Test fun theRegionalTrapNeverAutoMatchesAffiliates() {
        // Different numbers already keep these apart...
        assertNull(ChannelMatcher.score(s("FOX 5 New York"), s("FOX 29 Philadelphia")))
        // ...and a shared name with different cities is a suggestion at most.
        val m = ChannelMatcher.score(s("ABC New York"), s("ABC Chicago"))
        assertTrue(m == null || m.confidence == Confidence.SUGGESTED)
        assertEquals(Confidence.CONFIDENT, ChannelMatcher.score(s("NBC 4 New York"), s("US: NBC 4 New York HD"))?.confidence)
        val callsign = ChannelMatcher.score(s("CBS KCBS"), s("CBS WCBS"))
        assertTrue(callsign == null || callsign.confidence == Confidence.SUGGESTED)
    }

    @Test fun sameTvgIdIsStrongEvenWithDifferentNames() {
        assertEquals(Confidence.CONFIDENT, ChannelMatcher.score(s("Sportsnet One", "sn1.ca"), s("SN1", "SN1.ca"))?.confidence)
    }

    @Test fun similarNamesAreOnlySuggestions() {
        assertEquals(Confidence.SUGGESTED, ChannelMatcher.score(s("Discovery Science"), s("Discovery Science Channel"))?.confidence)
    }

    @Test fun theReportSortsChannelsIntoConfidentSuggestedAndUnmatched() {
        val old = listOf(s("US: ESPN HD", id = "o1", source = "old"), s("US: Golf Channel", id = "o2", source = "old"), s("Local Access 7", id = "o3", source = "old"), s("FS1", id = "o4", source = "old"))
        val ucs = listOf(
            ProviderSwitch.UserChannel("uc1", "ESPN", listOf(old[0]), favorite = true),
            ProviderSwitch.UserChannel("uc2", "Golf", listOf(old[1])),
            ProviderSwitch.UserChannel("uc3", "Local", listOf(old[2]), favorite = true),
            ProviderSwitch.UserChannel("uc4", "FS1", listOf(old[3], s("Fox Sports 1", id = "n9"))),
        )
        val target = listOf(s("ESPN FHD", id = "n1"), s("Golf Channel Extra", id = "n2"), s("Fox Sports 1", id = "n9"), s("ESPN", id = "n3"))
        val r = ProviderSwitch.report("new", ucs, target, linkedStreamIds = setOf("n9"), groupsWithSettings = listOf("old" to "US| Sports", "old" to "Kids"))
        assertEquals(listOf("uc1"), r.confident.map { it.userChannel.id })
        assertEquals(listOf("uc2"), r.suggested.map { it.userChannel.id })
        assertEquals(listOf("uc3"), r.unmatched.map { it.id })
        assertEquals(1, r.alreadyLinked)

        // A rejected pair stays out.
        val again = ProviderSwitch.report("new", ucs, target, setOf("n9"), rejected = setOf("uc2" to "n2"))
        assertTrue(again.suggested.none { it.userChannel.id == "uc2" })
    }

    @Test fun groupsMatchByNormalizedName() {
        val g = ProviderSwitch.groups(listOf("old" to "US| SPORTS HD", "old" to "Kids"), listOf("USA Sports", "Movies", "Sports"))
        assertEquals("Sports", g.first { it.group == "US| SPORTS HD" }.toGroup)
        assertNull(g.first { it.group == "Kids" }.toGroup)
    }

    @Test fun aGroupWithTheIdenticalNameWinsOverOneThatNormalizesTheSame() {
        // A second account on the same provider: both normalize alike, and the other comes first.
        val target = listOf("UK| 24/7 HD/RAW", "AL| 24/7", "US| SOCCER PPV", "CA| SOCCER PPV")
        val g = ProviderSwitch.groups(listOf("old" to "AL| 24/7", "old" to "CA| SOCCER PPV"), target)
        assertEquals("AL| 24/7", g.first { it.group == "AL| 24/7" }.toGroup)
        assertEquals("CA| SOCCER PPV", g.first { it.group == "CA| SOCCER PPV" }.toGroup)
    }
}

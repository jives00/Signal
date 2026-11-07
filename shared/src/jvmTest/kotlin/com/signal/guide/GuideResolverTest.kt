package com.signal.guide

import com.signal.model.EpgKind
import com.signal.model.EpgSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Name matching when a name fits several guide channels (the user's guides, 2026-10-14). */
class GuideResolverTest {
    private val provider = EpgSource("p", "Provider guide", EpgKind.XTREAM)
    private val us2 = EpgSource("u", "EPGShare01 US2", EpgKind.XMLTV)

    private val indexes = mapOf(
        "p" to GuideResolver.SourceIndex(
            byId = mapOf("hbosignature.us" to "HBOSignature.us"),
            byName = mapOf("hbo" to listOf("HBO.hu", "hbo.pl"), "espn2" to listOf("espn2.nl"), "cbs" to listOf("WCBS.us"), "hbo 2" to listOf("HBO2.bg")),
        ),
        "u" to GuideResolver.SourceIndex(
            byId = emptyMap(),
            byName = mapOf(
                "hbo" to listOf("HBO.(Pacific).us2"),
                "hbo east" to listOf("HBO.East.us2"),
                "fx" to listOf("FX.HD.(Pacific).us2", "FX.HD.us2"),
                "espn2" to listOf("ESPN2.HD.us2"),
                "hbo2" to listOf("HBO2.(Pacific).us2", "HBO2.HD.us2"),
            ),
        ),
    )

    private fun resolve(name: String, default: String = "p", tvgId: String? = null) =
        GuideResolver.resolve(null, tvgId, name, listOf(provider, us2), default) { indexes.getValue(it) }?.let { it.epgSourceId to it.guideChannelId }

    @Test fun theChannelsOwnCountryBeatsAnotherInTheDefaultGuide() {
        // The provider's guide only has Hungarian and Polish HBO by that name: US2's feed wins.
        assertEquals("u" to "HBO.East.us2", resolve("US: HBO HD"))
        assertEquals("u" to "ESPN2.HD.us2", resolve("US: ESPN2 HD"))
    }

    /** From the user's guides, 2026-11-06. */
    private val locals = EpgSource("l", "EPGShare01 US Locals1", EpgKind.XMLTV)
    private val hour = 60 * 60_000L
    private val deep = mapOf(
        "p" to GuideResolver.SourceIndex(
            byId = mapOf("wfld.us" to "WFLD.us", "foxsports1.us" to "FoxSports1.us", "wbbm.us" to "WBBM.us"),
            byName = mapOf("cbs" to listOf("WBBM.us"), "espn" to listOf("ESPN.us")),
            lastStart = mapOf("WFLD.us" to 8 * hour, "FoxSports1.us" to 8 * hour, "WBBM.us" to 8 * hour, "ESPN.us" to 60 * hour),
        ),
        "l" to GuideResolver.SourceIndex(
            byId = mapOf("wfld-dt.us_locals1" to "WFLD-DT.us_locals1", "wbbm-dt.us_locals1" to "WBBM-DT.us_locals1"),
            byName = mapOf("dt" to listOf("WFLD-DT.us_locals1")),
            lastStart = mapOf("WFLD-DT.us_locals1" to 60 * hour, "WBBM-DT.us_locals1" to 60 * hour),
        ),
        "u" to GuideResolver.SourceIndex(
            byId = emptyMap(),
            byName = mapOf(
                "discovery channel" to listOf("Discovery.Channel.HD.us2"),
                "the travel channel" to listOf("The.Travel.Channel.HD.us2"),
                "fs1 fox sports 1" to listOf("FS1.Fox.Sports.1.HD.us2"),
                "music television" to listOf("MTV.Music.Television.HD.us2"),
                "espn" to listOf("ESPN.HD.us2"),
            ),
            lastStart = mapOf("FS1.Fox.Sports.1.HD.us2" to 60 * hour, "ESPN.HD.us2" to 66 * hour),
        ),
    )
    private fun deepest(name: String, tvgId: String? = null, mapping: GuideResolver.Mapping? = null) =
        GuideResolver.resolve(mapping, tvgId, name, listOf(provider, locals, us2), "p") { deep.getValue(it) }?.guideChannelId

    @Test fun aLocalStationIsFoundByCallSignInTheGuideThatGoesFurther() {
        assertEquals("WFLD-DT.us_locals1", deepest("US: FOX 32 (WFLD) CHICAGO HD", "WFLD.us"))
    }

    @Test fun aFewHoursMoreDoesntMoveAChannel() {
        assertEquals("ESPN.us", deepest("US: ESPN HD"), "6 hours further, the default guide keeps it")
    }

    @Test fun aChannelsOtherNamesAreTried() {
        assertEquals("FS1.Fox.Sports.1.HD.us2", deepest("US: FOX SPORTS 1 4K", "FoxSports1.us"))
        assertEquals("Discovery.Channel.HD.us2", deepest("US: DISCOVERY HD", "DiscoveryChannel.us"))
        assertEquals("The.Travel.Channel.HD.us2", deepest("US: TRAVEL CHANNEL HD"))
        assertEquals("MTV.Music.Television.HD.us2", deepest("US: MTV HD"))
    }

    @Test fun aGenericTvgIdDoesntOverruleTheChannelsOwnName() {
        val names = com.signal.match.GuideNames
        assertEquals(emptyList(), names.otherNames("fox sports detroit", "FOXSports.us").filter { "sports" in it && "detroit" !in it })
        assert("discovery channel" in names.otherNames("discovery", "DiscoveryChannel.us"))
        assert("fs1 fox sports 1" in names.otherNames("fox sports 1", "FoxSports1.us"))
    }

    @Test fun aWestFeedNeverTakesTheEastListing() {
        assertEquals(emptyList(), com.signal.match.GuideNames.otherNames("travel channel west", "TravelChannel.us"))
        assertEquals("FoxSports1.us", deepest("US: FOX SPORTS 1 WEST HD", "FoxSports1.us"))
    }

    @Test fun droppingAWordIsntAnotherName() {
        assertEquals(false, "nbc sports" in com.signal.match.GuideNames.otherNames("nbc sports network", null))
    }

    @Test fun otherNamesAreForUsChannelsOnly() {
        assertEquals(null, deepest("DE: DISCOVERY HD"))
    }

    @Test fun aMappingsChoiceStandsEvenWithAShortGuide() {
        assertEquals("WFLD.us", deepest("US: FOX 32 (WFLD) CHICAGO HD", "WFLD.us", GuideResolver.Mapping("p", null)))
        assertEquals("WFLD.us", deepest("US: FOX 32 (WFLD) CHICAGO HD", "WFLD.us", GuideResolver.Mapping("p", "WFLD.us")))
    }

    @Test fun eastBeatsPacific() {
        assertEquals("u" to "HBO.East.us2", resolve("US: HBO HD", default = "u"))
        assertEquals("u" to "FX.HD.us2", resolve("US: FX HD", default = "u"))
    }

    @Test fun aCleanMatchInTheDefaultStillWins() {
        assertEquals("p" to "WCBS.us", resolve("US: CBS"))
    }

    @Test fun aFlawedMatchIsUsedWhenNothingBetterExists() {
        assertEquals("p" to "HBO.hu", resolve("HBO"))
    }

    @Test fun spacesAsideBeatsAnotherCountry() {
        // The provider says "HBO 2", US2 says "HBO2": Bulgaria's "HBO 2" must not win.
        assertEquals("u" to "HBO2.HD.us2", resolve("US: HBO 2 HD"))
        assertEquals("p" to "HBO2.bg", resolve("HBO 2"))
    }

    @Test fun tvgIdStaysAbsolute() {
        assertEquals("p" to "HBOSignature.us", resolve("US: HBO SIGNATURE HD", tvgId = "HBOSignature.us"))
    }
}

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

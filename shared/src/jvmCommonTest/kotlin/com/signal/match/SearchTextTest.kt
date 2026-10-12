package com.signal.match

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTextTest {
    @Test fun foldsCaseAccentsAndJunk() {
        assertEquals("futbol hd", SearchText.fold("Fútbol ᴴᴰ"))
        assertEquals("us fox sports 1", SearchText.fold("┃US┃ FOX Sports 1 ⭐"))
        assertEquals("cafe tv", SearchText.fold("Café-TV"))
    }

    @Test fun queryWords() {
        assertEquals(listOf("fox", "spo"), SearchText.words("  FOX   spo "))
        assertEquals(emptyList(), SearchText.words(" - "))
    }

    @Test fun guideNamesDropPrefixesAndQualityButKeepNumbers() {
        assertEquals(GuideNames.normalize("FOX Sports 1"), GuideNames.normalize("┃US┃ FOX Sports 1 ᴴᴰ (Backup)"))
        assertEquals("fox sports 1", GuideNames.normalize("US: Fox Sports 1 FHD"))
        assert(GuideNames.normalize("FOX Sports 1") != GuideNames.normalize("FOX Sports 2"))
    }
}

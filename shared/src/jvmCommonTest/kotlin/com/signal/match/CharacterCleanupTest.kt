package com.signal.match

import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterCleanupTest {
    private fun clean(s: String, emoji: Boolean = true) = CharacterCleanup.clean(s, removeEmoji = emoji)

    @Test fun superscriptsFold() = assertEquals("ESPN HD", clean("ESPN ᴴᴰ"))
    @Test fun mathBoldFolds() = assertEquals("USA Network", clean("𝐔𝐒𝐀 Network"))
    @Test fun circledLettersFold() = assertEquals("Us Sports", clean("Ⓤⓢ Sports"))
    @Test fun fullWidthFolds() = assertEquals("FOX 5", clean("ＦＯＸ ５"))
    @Test fun barsBecomePipes() = assertEquals("| US | FOX", clean("┃ US ❘ FOX"))
    @Test fun dashesNormalize() = assertEquals("A - B", clean("A — B"))
    @Test fun flagsBecomeCountryCodes() = assertEquals("US ESPN", clean("🇺🇸 ESPN"))
    @Test fun invisiblesAndOddSpacesGo() = assertEquals("FOX Sports 1", clean("FOX​  Sports  1 "))
    @Test fun emojiRemovedByDefault() = assertEquals("Sports", clean("⭐ Sports 🔥"))
    @Test fun emojiKeptWhenAsked() = assertEquals("⭐ Sports", clean("⭐ Sports", emoji = false))
    @Test fun plainTextUnchanged() = assertEquals("BBC One", clean("BBC One"))
}

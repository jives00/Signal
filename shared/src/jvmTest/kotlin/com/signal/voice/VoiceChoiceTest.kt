package com.signal.voice

import com.signal.voice.VoiceChoice.Pick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceChoiceTest {
    private data class Hit(val name: String, val exact: Boolean = false, val favorite: Boolean = false)

    private fun choose(vararg hits: Hit) = VoiceChoice.choose(hits.toList(), { it.name }, { it.exact }, { it.favorite })

    @Test fun nothing() = assertNull(choose())

    @Test fun theOnlyMatchActs() = assertEquals(Pick.One(Hit("fox sports 1")), choose(Hit("fox sports 1")))

    @Test fun theOnlyExactMatchActsOverFavoritesThatOnlyStartTheSame() {
        val espn = Hit("espn", exact = true)
        assertEquals(Pick.One(espn), choose(Hit("espn 2", favorite = true), espn, Hit("espnu")))
    }

    @Test fun anExactFavoriteActsOverOtherExactNames() {
        val fav = Hit("espn", exact = true, favorite = true)
        assertEquals(Pick.One(fav), choose(fav, Hit("espn", exact = true), Hit("espn", exact = true)))
    }

    // After name cleanup one channel is often several streams of the same name: the first is taken.
    @Test fun theSameNameTwiceActsOnTheFirst() {
        val first = Hit("Golf Channel", exact = true)
        assertEquals(Pick.One(first), choose(first, Hit("golf channel", exact = true), Hit("Golf Channel 2")))
    }

    // Two voice names said the same way, on different channels, ask.
    @Test fun differentNamesBothExactAsk() {
        val a = Hit("FOX 5 New York", exact = true)
        val b = Hit("FOX 29 Philadelphia", exact = true)
        assertEquals(Pick.Several(listOf(a, b)), choose(a, b))
    }

    @Test fun theChoiceListsEachNameOnce() {
        val hits = arrayOf(Hit("FOX 5"), Hit("FOX 5"), Hit("FOX 29"), Hit("FOX 13"), Hit("FOX News"))
        assertEquals(Pick.Several(listOf(Hit("FOX 5"), Hit("FOX 29"), Hit("FOX 13"))), choose(*hits))
    }

    @Test fun aWeakMatchWithOneFavoriteActs() {
        val fav = Hit("fox sports 1", favorite = true)
        assertEquals(Pick.One(fav), choose(fav, Hit("fox sports 2"), Hit("fox sports racing")))
    }

    @Test fun weakMatchesAskTheTopThree() {
        val hits = arrayOf(Hit("fox 29"), Hit("fox 5"), Hit("fox news"), Hit("fox business"))
        assertEquals(Pick.Several(hits.take(3)), choose(*hits))
    }

    @Test fun severalFavoritesAskAmongThem() {
        val a = Hit("fox 29", favorite = true)
        val b = Hit("fox news", favorite = true)
        assertEquals(Pick.Several(listOf(a, b)), choose(a, b, Hit("fox 5")))
    }
}

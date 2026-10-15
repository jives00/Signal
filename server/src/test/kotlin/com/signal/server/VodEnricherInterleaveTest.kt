package com.signal.server

import kotlin.test.Test
import kotlin.test.assertEquals

class VodEnricherInterleaveTest {
    @Test fun moviesAndSeriesTakeTurns() {
        assertEquals(listOf("m1", "s1", "m2", "s2", "m3", "m4"), VodEnricher.interleave(listOf("m1", "m2", "m3", "m4") to listOf("s1", "s2")))
        assertEquals(listOf("s1"), VodEnricher.interleave(emptyList<String>() to listOf("s1")))
    }
}

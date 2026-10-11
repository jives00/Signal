package com.signal.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FractionalIndexTest {
    @Test
    fun appendingKeepsOrder() {
        val keys = FractionalIndex.sequence(500)
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun insertingBetweenNeighborsAlwaysFits() {
        var lo = "V"
        val hi = "W"
        repeat(200) {
            val mid = FractionalIndex.between(lo, hi)
            assertTrue(lo < mid && mid < hi, "$lo < $mid < $hi")
            lo = mid
        }
    }

    @Test
    fun insertingAtTheFrontAlwaysFits() {
        var first = FractionalIndex.after(null)
        repeat(200) {
            val k = FractionalIndex.before(first)
            assertTrue(k < first, "$k < $first")
            first = k
        }
    }

    @Test
    fun randomMovesKeepAStrictOrder() {
        val rnd = Random(42)
        val keys = FractionalIndex.sequence(20).toMutableList()
        repeat(1000) {
            val i = rnd.nextInt(keys.size + 1)
            val k = FractionalIndex.between(keys.getOrNull(i - 1), keys.getOrNull(i))
            keys.add(i, k)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.none { it.endsWith("0") })
    }
}

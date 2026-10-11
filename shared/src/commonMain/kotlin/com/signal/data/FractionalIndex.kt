package com.signal.data

/**
 * Sort keys for manually ordered lists (PLAN §5.4): strings that sort between their neighbors,
 * so a move rewrites only the moved item's key and two devices reordering at once merge cleanly.
 *
 * Keys are base-62 fractions (`0.xyz`) written without the `0.`, never ending in the zero digit,
 * which keeps every key unique and always leaves room for another between two neighbors.
 */
object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62

    /** A key strictly between [a] and [b]; null [a] means "before everything", null [b] "after". */
    fun between(a: String?, b: String?): String {
        require(a == null || b == null || a < b) { "keys out of order: $a >= $b" }
        return midpoint(a.orEmpty(), b)
    }

    fun after(last: String?): String = between(last, null)
    fun before(first: String?): String = between(null, first)

    /** Keys for a list of [count] items in order, evenly spread. */
    fun sequence(count: Int): List<String> {
        val out = ArrayList<String>(count)
        var last: String? = null
        repeat(count) { last = after(last).also(out::add) }
        return out
    }

    private fun digit(s: String, i: Int, ifMissing: Int): Int =
        if (i < s.length) DIGITS.indexOf(s[i]) else ifMissing

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // Skip the shared prefix, padding a with zeros.
            var n = 0
            while (n < b.length && digit(a, n, 0) == digit(b, n, -1)) n++
            if (n > 0) return b.substring(0, n) + midpoint(if (a.length > n) a.substring(n) else "", b.substring(n))
        }
        val da = digit(a, 0, 0)
        val db = if (b == null) BASE else digit(b, 0, BASE)
        if (db - da > 1) return DIGITS[(da + db) / 2].toString()
        // Consecutive digits: take a's digit and find room in the next position.
        return if (b != null && b.length > 1) b.substring(0, 1)
        else DIGITS[da] + midpoint(if (a.length > 1) a.substring(1) else "", null)
    }
}

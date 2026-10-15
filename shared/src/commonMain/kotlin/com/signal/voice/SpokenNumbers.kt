package com.signal.voice

/**
 * Numbers as the recognizer says them: it knows "seven" and "twenty nine", never "7" or "29".
 * Channel names are written with digits, so their numbers are spoken into the vocabulary and the
 * heard words turned back into digits (PLAN §4.3.14 → Voice commands).
 */
internal object SpokenNumbers {
    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** Every word a number can be spoken with. */
    val words: Set<String> = (ones + tens.drop(2) + "hundred" + "oh").toSet()

    /** "29" → "twenty nine"; null past 999. */
    fun say(n: Int): String? = when {
        n < 0 || n > 999 -> null
        n < 20 -> ones[n]
        n < 100 -> tens[n / 10] + if (n % 10 != 0) " " + ones[n % 10] else ""
        else -> ones[n / 100] + " hundred" + if (n % 100 != 0) " " + say(n % 100) else ""
    }

    /** "fox twenty nine philadelphia" → "fox 29 philadelphia". */
    fun toDigits(text: String): String {
        val out = ArrayList<String>()
        var number: Int? = null
        fun flush() { number?.let { out += it.toString() }; number = null }
        for (word in text.split(' ')) {
            val one = ones.indexOf(word)
            val ten = tens.indexOf(word).takeIf { it >= 2 }
            val n = number
            when {
                word == "hundred" && n != null && n in 1..9 -> number = n * 100
                ten != null && (n == null || n % 100 == 0 && n >= 100) -> number = (n ?: 0) + ten * 10
                one >= 0 && n != null && (n % 10 == 0 && n >= 20 && n % 100 != 0 || n % 100 == 0 && n >= 100) && one < (if (n % 100 == 0) 20 else 10) -> number = n + one
                one >= 0 -> { flush(); number = one }
                else -> { flush(); out += word }
            }
        }
        flush()
        return out.joinToString(" ")
    }
}

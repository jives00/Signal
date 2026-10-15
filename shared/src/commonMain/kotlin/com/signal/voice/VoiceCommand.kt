package com.signal.voice

/**
 * A spoken command, from the text the recognizer heard (PLAN §4.3.14 → Voice commands). The
 * recognizer gives lowercase words with no punctuation, and spells initials as single letters.
 */
sealed interface VoiceCommand {
    /** "Switch to NBC", "watch ESPN", or just a channel's name. */
    data class Tune(val channel: String) : VoiceCommand

    companion object {
        /** How commands start and end, as whole phrases for the recognizer. */
        private val commandPhrases = listOf(
            "switch to", "change to", "change the channel to", "change channel to", "turn to", "flip to", "go to",
            "tune to", "move to", "watch", "put on", "show me", "play", "please", "channel", "now", "hey", "ok",
        )

        /**
         * What the recognizer may hear, as phrases it strings together: the commands' own, and the
         * channels' names (folded). Phrases rather than loose words, so it can't build "switch two"
         * or "eighty e" from a stray number or letter. A name gives the phrases a search would
         * match by: its first one to four words, and each word alone, except bare numbers and
         * letters. The recognizer only knows spoken words, so digits become number words ("7" →
         * "seven"), and [initials] (words the names write in capitals) are also spelled out, for
         * the ones it has no word for ("a b c"). Only those: spelling every short word gave it
         * thousands of letter strings to confuse ("a b d").
         */
        fun phrases(channelNames: Collection<String>, initials: Set<String>): Set<String> {
            val out = HashSet<String>(commandPhrases)
            fun spelled(word: String) = if (word in initials) word.toList().joinToString(" ") else null
            for (name in channelNames) {
                val words = name.split(' ').filter { it.isNotEmpty() }
                for (k in 1..minOf(words.size, MAX_PHRASE_WORDS)) {
                    val prefix = words.subList(0, k)
                    out += prefix.joinToString(" ") { spoken(it) }
                    if (prefix.any { it in initials }) out += prefix.joinToString(" ") { spelled(it) ?: spoken(it) }
                }
                for (word in words) {
                    if (word.length < 2 || word.all { it.isDigit() }) continue
                    out += spoken(word)
                    spelled(word)?.let { out += it }
                }
            }
            out.remove("")
            return out
        }

        private const val MAX_PHRASE_WORDS = 4

        /**
         * The initials in [initials] that [heard] sounds like, best first. The recognizer mixes
         * up letters that rhyme ("e a b t" for ABC, "e f e n" for ESPN), so letters are compared by
         * how they sound: the same sounds, or the same with one extra letter heard. Ties go to the
         * most letters matched exactly. Null when [heard] isn't a run of letters.
         */
        fun soundsLike(heard: String, initials: Collection<String>): List<String> {
            val letters = heard.replace(" ", "")
            if (letters.length !in 2..6 || !letters.all { it in 'a'..'z' }) return emptyList()
            val sound = sounds(letters)
            return initials.asSequence()
                // Two letters are too easy to land on by chance ("v b a" → "ca").
                .filter { it.length in 3..6 && it != letters }
                .filter { val s = sounds(it); s == sound || s.length + 1 == sound.length && sound.indices.any { i -> sound.removeRange(i, i + 1) == s } }
                .sortedByDescending { candidate -> exactLetters(letters, candidate) }
                .toList()
        }

        /**
         * Whether a word is said as its letters: "ESPN", "CBS", "HBO", "USA", but not "TEN", "BET",
         * "FOX" or "EAST", which channel names also write in capitals but people say as words. A
         * word with a consonant-vowel-consonant run, or two vowels together, is pronounceable.
         */
        fun isInitials(word: String): Boolean {
            val w = word.lowercase()
            if (w.length !in 2..5 || !w.all { it in 'a'..'z' }) return false
            val vowel = w.map { it in "aeiouy" }
            if ((0 until w.length - 1).any { vowel[it] && vowel[it + 1] }) return false
            if ((0 until w.length - 2).any { !vowel[it] && vowel[it + 1] && !vowel[it + 2] }) return false
            return true
        }

        /** Letters grouped by the sound that rhymes them: "ee" (b c d e g p t v z), "eh" (f l m n s x), "ay" (a h j k), "you" (q u w), "eye" (i y). */
        private fun sounds(letters: String): String = letters.map { c ->
            when (c) {
                'b', 'c', 'd', 'e', 'g', 'p', 't', 'v', 'z' -> 'E'
                'f', 'l', 'm', 'n', 's', 'x' -> 'F'
                'a', 'h', 'j', 'k' -> 'A'
                'q', 'u', 'w' -> 'U'
                'i', 'y' -> 'I'
                else -> c
            }
        }.joinToString("")

        /** Letters [candidate] shares with [heard] in place, aligned at the end (an extra letter is usually heard first). */
        private fun exactLetters(heard: String, candidate: String): Int {
            val offset = heard.length - candidate.length
            return candidate.indices.count { i -> heard.getOrNull(i + offset) == candidate[i] }
        }

        /** "7" → "seven", "4k" → "four k". */
        private fun spoken(word: String): String = digitsOrLetters.findAll(word).joinToString(" ") { run ->
            val part = run.value
            if (part[0].isDigit()) part.toIntOrNull()?.let(SpokenNumbers::say) ?: part else part
        }


        fun parse(heard: String): VoiceCommand? {
            var text = heard.lowercase().replace(unknown, " ").replace(nonWord, " ").trim().replace(spaces, " ")
            text = text.removePrefix(fillers)
            text = tunePrefix.find(text)?.let { text.substring(it.range.last + 1) } ?: text
            text = text.replace(trailing, "").trim()
            text = SpokenNumbers.toDigits(joinLetters(text))
            return if (text.isEmpty()) null else Tune(text)
        }

        /** "n b c" → "nbc": initials spoken one letter at a time, as channel names write them. */
        internal fun joinLetters(text: String): String =
            text.replace(letters) { it.value.replace(" ", "") }

        private fun String.removePrefix(regex: Regex): String = regex.find(this)?.let { substring(it.range.last + 1).trim() } ?: this

        /** What the recognizer writes for speech outside its vocabulary. */
        private val unknown = Regex("""\[unk]""")
        private val digitsOrLetters = Regex("""\p{N}+|\p{L}+""")
        private val nonWord = Regex("""[^\p{L}\p{N}]+""")
        private val spaces = Regex("""\s+""")
        private val fillers = Regex("""^((hey|ok|okay|please|signal)\s+)+""")
        private val tunePrefix = Regex("""^(switch|change|turn|flip|go|tune|move)( (it|over|the channel|channel))? (to|two|too)( |$)|^(watch|put on|tune|show me|play)( |$)""")
        private val trailing = Regex("""(\s+(please|channel|on signal|now))+$""")
        private val letters = Regex("""\b\p{L}( \p{L}\b)+""")
    }
}

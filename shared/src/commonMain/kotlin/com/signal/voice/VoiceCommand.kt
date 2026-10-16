package com.signal.voice

/**
 * A spoken command, from the text the recognizer heard (PLAN §4.3.14 → Voice commands). The
 * recognizer gives lowercase words with no punctuation, and spells initials as single letters.
 */
sealed interface VoiceCommand {
    /** "Switch to NBC", "watch ESPN", or just a channel's name. */
    data class Tune(val channel: String) : VoiceCommand {
        /**
         * What to search for, in order: as said, then without a trailing "channel" ("a b c channel"),
         * which is part of some names (Golf Channel) and only a filler after others.
         */
        val names: List<String> get() = listOf(channel) + listOfNotNull(channel.removeSuffix(" channel").takeIf { it != channel && it.isNotBlank() })
    }

    // Playback (PLAN §4.3.14 → Voice commands, phase 2). What they do depends on what's playing.
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Stop : VoiceCommand
    data object StartOver : VoiceCommand
    /** The next episode in a series, the next channel on live TV. */
    data object Next : VoiceCommand
    data object NextEpisode : VoiceCommand
    /** Last-channel recall. */
    data object LastChannel : VoiceCommand
    /** As the remote's Channel up (-1) and Channel down (+1). */
    data class ChannelStep(val step: Int) : VoiceCommand
    data class Seek(val byMs: Long) : VoiceCommand

    data class Open(val page: Page) : VoiceCommand
    enum class Page { HOME, GUIDE, FAVORITES, MOVIES, SERIES, SEARCH, SETTINGS }

    /** Captions on, off, or toggled (null). */
    data class Captions(val on: Boolean?) : VoiceCommand
    data class Audio(val language: Language) : VoiceCommand

    /** The program on a channel now, or on the one playing when [channel] is null. */
    data class WhatsOn(val channel: String?) : VoiceCommand

    // Movies and series (PLAN §4.3.14 → Voice commands, phase 2). Titles come from the free
    // recognizer, so they keep their words as heard ("apollo thirteen"); matching tries digits too.
    /** "Search Seinfeld": Search, filled in. */
    data class Search(val query: String) : VoiceCommand
    /** "Play the movie Inception", "play The Office season 2 episode 5". "Play ___" alone is a [Tune] first. */
    data class PlayTitle(val title: String, val kind: com.signal.model.VodKind? = null, val season: Int? = null, val episode: Int? = null) : VoiceCommand
    /** "Continue Seinfeld": where it was left, or the series' next episode. */
    data class ContinueTitle(val title: String) : VoiceCommand

    /** A spoken language and the codes tracks give it (ISO 639-1 and -2). */
    enum class Language(val spoken: String, val codes: Set<String>) {
        ENGLISH("english", setOf("en", "eng")), SPANISH("spanish", setOf("es", "spa")), FRENCH("french", setOf("fr", "fre", "fra")),
        GERMAN("german", setOf("de", "ger", "deu")), ITALIAN("italian", setOf("it", "ita")), PORTUGUESE("portuguese", setOf("pt", "por")),
        RUSSIAN("russian", setOf("ru", "rus")), JAPANESE("japanese", setOf("ja", "jpn")), KOREAN("korean", setOf("ko", "kor")),
        CHINESE("chinese", setOf("zh", "chi", "zho")), HINDI("hindi", setOf("hi", "hin")), ARABIC("arabic", setOf("ar", "ara"));

        fun matches(language: String?, label: String): Boolean =
            language?.lowercase()?.substringBefore('-') in codes || label.lowercase().contains(spoken)
    }

    companion object {
        /** How commands start and end, as whole phrases for the recognizer. */
        private val commandPhrases = listOf(
            "switch to", "change to", "change the channel to", "change channel to", "turn to", "flip to", "go to",
            "tune to", "move to", "watch", "put on", "show me", "play", "please", "channel", "now", "hey", "ok",
        )

        /** The playback, page and player commands, whole (PLAN §4.3.14 → Voice commands, phase 2). */
        private val fixedPhrases: List<String> = buildList {
            addAll(listOf(
                "pause", "pause it", "pause this", "resume", "unpause", "keep playing", "continue", "play it",
                "stop", "stop playing", "start over", "restart", "from the beginning", "play from the beginning",
                "next", "next episode", "play the next episode", "next channel", "channel up", "channel down",
                "last channel", "previous channel", "flip back", "go back to the last channel",
                "go home", "open", "the", "my", "home", "guide", "the guide", "tv guide", "live tv", "favorites", "movies",
                "series", "shows", "tv shows", "search", "settings", "page",
                "what's on", "what is on", "what's playing on", "what's on now",
                "audio", "language", "audio in", "change the audio to", "switch the audio to",
            ))
            for (thing in listOf("captions", "closed captions", "subtitles")) {
                add(thing)
                for (state in listOf("on", "off")) { add("$thing $state"); add("turn $state $thing"); add("turn $thing $state"); add("turn $state the $thing") }
            }
            Language.entries.forEach { add(it.spoken); add("${it.spoken} audio") }
            for (thing in listOf("it", "this", "the movie", "the show", "the episode", "the video")) {
                add("start $thing over"); add("restart $thing"); add("play $thing from the beginning")
            }
            // Movies and series: the lead-ins, and seasons and episodes as whole phrases. The titles
            // come from the free recognizer, and the likely ones from [phrases]' titles.
            addAll(listOf("search for", "find", "look up", "continue watching", "keep watching", "play the movie", "play the show",
                "watch the movie", "watch the show", "the movie", "the show"))
            for (n in 1..30) { add("season ${SpokenNumbers.say(n)}"); add("episode ${SpokenNumbers.say(n)}") }
            for (lead in listOf("go to the", "go back to the", "switch to the", "back to the", "the")) {
                add("$lead last channel"); add("$lead previous channel")
            }
            // Seeks as whole phrases, so a stray number never joins a channel's name.
            val amounts = listOf(5, 10, 15, 20, 30, 45).map { "${SpokenNumbers.say(it)} seconds" } +
                listOf("a minute", "one minute") + listOf(2, 3, 4, 5, 10, 15, 20, 30).map { "${SpokenNumbers.say(it)} minutes" }
            val seekVerbs = listOf("back", "go back", "skip back", "jump back", "rewind", "skip ahead", "skip forward", "jump ahead", "forward", "fast forward", "ahead")
            addAll(seekVerbs)
            for (verb in seekVerbs) for (amount in amounts) add("$verb $amount")
        }

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
        fun phrases(channelNames: Collection<String>, initials: Set<String>, titles: Collection<String> = emptyList()): Set<String> {
            val out = HashSet<String>(commandPhrases)
            out += fixedPhrases
            // Likely titles whole (folded), said the way the recognizer spells them: a title is said
            // in full, unlike a channel, which people shorten.
            for (title in titles) out += title.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { spoken(it) }
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
            // "what's" arrives as "what s" once the apostrophe goes.
            text = text.replace(whats, "whats")
            command(text.replace(politeEnd, "").trim())?.let { return it }
            text = tunePrefix.find(text)?.let { text.substring(it.range.last + 1) } ?: text
            text = text.replace(trailing, "").trim()
            // A command after a channel's lead-in: "go to the last channel", "put on captions".
            command(text)?.let { return it }
            text = SpokenNumbers.toDigits(joinLetters(text))
            return if (text.isEmpty()) null else Tune(text)
        }

        /** A playback, page or player command said whole, or null for a channel (or nothing). */
        private fun command(text: String): VoiceCommand? {
            when (text) {
                "pause", "pause it", "pause this", "pause the movie", "pause the show", "pause the video" -> return Pause
                "resume", "unpause", "keep playing", "continue", "play", "play it", "continue watching", "keep watching", "resume playing" -> return Resume
                "stop", "stop playing", "stop it" -> return Stop
                "next" -> return Next
                "next episode", "play the next episode", "the next episode" -> return NextEpisode
                "next channel", "channel down" -> return ChannelStep(1)
                "channel up" -> return ChannelStep(-1)
                "flip back" -> return LastChannel
                "rewind" -> return Seek(-DEFAULT_SEEK_MS)
                "fast forward", "skip ahead", "skip forward", "jump ahead" -> return Seek(DEFAULT_SEEK_MS)
                "skip back", "jump back" -> return Seek(-DEFAULT_SEEK_MS)
            }
            if (startOver.matches(text)) return StartOver
            if (lastChannel.matches(text)) return LastChannel
            searchFor.matchEntire(text)?.let { return Search(it.groupValues[1]) }
            continueTitle.matchEntire(text)?.let { return ContinueTitle(it.groupValues[1]) }
            val digits = SpokenNumbers.toDigits(text)
            seasonEpisode.matchEntire(digits)?.let { m ->
                return PlayTitle(m.groupValues[1], com.signal.model.VodKind.SERIES, m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
            episodeOfSeason.matchEntire(digits)?.let { m ->
                return PlayTitle(m.groupValues[1], com.signal.model.VodKind.SERIES, m.groupValues[3].toInt(), m.groupValues[2].toInt())
            }
            playKind.matchEntire(text)?.let { m ->
                val kind = if (m.groupValues[1] == "movie" || m.groupValues[1] == "film") com.signal.model.VodKind.MOVIE else com.signal.model.VodKind.SERIES
                return PlayTitle(m.groupValues[2], kind)
            }
            page.matchEntire(text)?.let { m ->
                return Open(when (m.groupValues[1]) {
                    "home", "home screen" -> Page.HOME
                    "guide", "tv guide", "live tv" -> Page.GUIDE
                    "favorites", "favourites" -> Page.FAVORITES
                    "movies" -> Page.MOVIES
                    "series", "shows", "tv shows" -> Page.SERIES
                    "search" -> Page.SEARCH
                    else -> Page.SETTINGS
                })
            }
            captions.matchEntire(text)?.let { m ->
                val state = m.groupValues[1].ifEmpty { m.groupValues[2] }
                return Captions(if (state.isEmpty()) null else state == "on")
            }
            (audioFirst.matchEntire(text) ?: audioLast.matchEntire(text))?.let { m ->
                return Audio(Language.entries.first { it.spoken == m.groupValues[1] })
            }
            whatsOn.matchEntire(text)?.let { m ->
                val channel = m.groupValues[1].replace(trailing, "").trim()
                return WhatsOn(if (channel.isEmpty()) null else SpokenNumbers.toDigits(joinLetters(channel)))
            }
            seek.matchEntire(SpokenNumbers.toDigits(text))?.let { m ->
                val (verb, amount, unit) = m.destructured
                val back = verb.endsWith("back") || verb.startsWith("back") || verb == "rewind"
                val n = when (amount) { "" -> null; "a", "an" -> 1L; else -> amount.toLong() }
                // A bare "back" or "forward" isn't a seek: it needs an amount.
                if (n == null) return null
                val ms = n * if (unit.startsWith("min")) 60_000L else 1_000L
                return Seek(if (back) -ms else ms)
            }
            return null
        }

        const val DEFAULT_SEEK_MS = 30_000L

        /** What's playing, as people name it: "start the movie over", "restart this", "play it from the beginning". */
        private const val PLAYING = """(?:it|this|the (?:movie|show|episode|video|program))"""
        private val startOver = Regex("""^(?:(?:start|play)(?: $PLAYING)? (?:over|again|from the (?:beginning|start))|restart(?: $PLAYING)?|(?:start )?from the (?:beginning|start))$""")
        private val lastChannel = Regex("""^(?:(?:go|switch|flip|jump|change)\s+)?(?:back\s+)?(?:to\s+)?(?:the\s+|my\s+)?(?:last|previous) channel$""")
        private val searchFor = Regex("""^(?:search for|search|find|look up|look for)\s+(?!for$)(.+)$""")
        private val continueTitle = Regex("""^(?:continue|resume|keep watching|finish)(?:\s+watching)?\s+(?!watching$|playing$)(.+)$""")
        // "The" only goes with "show": in "the office season 2", it's the title's.
        private const val PLAY_LEAD = """(?:(?:play|watch|put on|start)\s+)?(?:(?:the\s+)?(?:show|series|tv show)\s+)?"""
        private val seasonEpisode = Regex("""^$PLAY_LEAD(.+?)\s+season\s+(\d+)\s+episode\s+(\d+)$""")
        private val episodeOfSeason = Regex("""^$PLAY_LEAD(.+?)\s+episode\s+(\d+)\s+(?:of\s+)?season\s+(\d+)$""")
        private val playKind = Regex("""^(?:play|watch|put on|start)\s+(?:the\s+)?(movie|film|show|series|tv show)\s+(.+)$""")

        /**
         * Whether a press needs the free recognizer, from the grammar recognizer's words so far: a
         * title's lead-in with something after it ("play the …", "search …", "continue …"), or a word
         * it doesn't know. The unknown word alone wasn't enough: with every channel's words to choose
         * from, the grammar recognizer forces a title into them ("the matrix" as "the me tribeca") and
         * hardly ever says "[unk]" (0.2.23 on the Shield). "Watch" alone isn't a lead-in, so "watch
         * ESPN" stays fast.
         */
        fun wantsTitle(partial: String): Boolean {
            val text = partial.lowercase().trim()
            return "[unk]" in text || titleLead.containsMatchIn(text.removePrefix(fillers))
        }

        private val titleLead = Regex("""^(play|search|find|look up|look for|continue|resume|keep watching|watch the (movie|show|film|series))\s+\S""")

        /**
         * What a free guess says after any lead-in, as heard ("play seinfeld" → "seinfeld", true), for
         * matching a title. The flag says it began with a lead-in, so a miss can still open Search.
         */
        fun spokenTitle(heard: String): Pair<String, Boolean> {
            var text = heard.lowercase().replace(unknown, " ").replace(nonWord, " ").trim().replace(spaces, " ")
            text = text.removePrefix(fillers)
            val lead = tunePrefix.find(text)
            if (lead != null) text = text.substring(lead.range.last + 1)
            return text.replace(trailing, "").trim() to (lead != null)
        }

        private val whats = Regex("""^(what s|what is)\b""")
        private val politeEnd = Regex("""(\s+(please|now))+$""")
        private val page = Regex("""^(?:(?:go|open|show me|show|take me)\s+)?(?:to\s+)?(?:the\s+|my\s+)?(home|home screen|guide|tv guide|live tv|favorites|favourites|movies|series|shows|tv shows|search|settings)(?:\s+(?:page|screen))?$""")
        private val captions = Regex("""^(?:turn\s+|switch\s+)?(on|off)?\s*(?:the\s+)?(?:closed captions|captions|closed captioning|subtitles|subs|cc)(?:\s+(on|off))?$""")
        private val languages = Language.entries.joinToString("|") { it.spoken }
        private val audioFirst = Regex("""^(?:(?:switch|change|set)\s+(?:the\s+)?)?(?:audio|language)\s+(?:to\s+|in\s+)?($languages)$""")
        private val audioLast = Regex("""^($languages)\s+(?:audio|language)$""")
        private val whatsOn = Regex("""^whats\s+(?:on|playing)(?:\s+now)?(?:\s+on)?(?:\s+(.*))?$""")
        private val seek = Regex("""^(back|backward|backwards|go back|skip back|jump back|rewind|forward|forwards|ahead|go ahead|skip ahead|skip forward|jump ahead|jump forward|fast forward)(?:\s+by)?(?:\s+(\d+|a|an))?(?:\s+(seconds?|secs?|minutes?|mins?))?$""")

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
        /** Fillers after a name. A trailing "channel" stays: see [Tune.names]. */
        private val trailing = Regex("""(\s+(please|on signal|now))+$""")
        private val letters = Regex("""\b\p{L}( \p{L}\b)+""")
    }
}

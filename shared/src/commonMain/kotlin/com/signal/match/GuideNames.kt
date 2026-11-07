package com.signal.match

/**
 * Normalizes channel names for guide mapping by name (PLAN §6 → Channel ↔ guide mapping, step 3):
 * "┃US┃ FOX Sports 1 ᴴᴰ (Backup)" and the guide's "Fox Sports 1" both become "fox sports 1".
 *
 * It works on raw names, never on cleanup-rule output (PLAN §4.3.2 → Name cleanup rules), and
 * strips only what never tells two channels apart: country prefixes, quality tags, "(Backup)" and
 * punctuation. Numbers and words that do (FOX Sports 1 vs 2, East vs West) are kept.
 */
object GuideNames {
    private val countryPrefix = Regex("""^\s*(?:\|[A-Z]{2,3}\||\[[A-Z]{2,3}\]|[A-Z]{2,3}\s*[:|\-])\s*""")
    private val qualityTag = Regex("""\b(?:uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|raw|60fps|50fps|1080p|720p|2160p)\b""")
    private val bracketed = Regex("""[(\[][^)\]]*[)\]]""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /**
     * What a big US channel goes by in the XMLTV guides, when that isn't just its name with a word
     * added. Keys and values are normalized, so "MTV - Music Television" is "music television": its
     * "MTV -" looks like a country prefix.
     */
    private val aliases = mapOf(
        "fox sports 1" to "fs1 fox sports 1",
        "fox sports 2" to "fs2 fox sports 2",
        "mtv" to "music television",
        "showtime" to "paramount with showtime",
        "adult swim" to "cartoon network",
        "e entertainment" to "e entertainment television",
        "disney jr" to "disney junior",
    )
    private val inParens = Regex("""\(([KW][A-Z]{2,3})\)""")
    private val stationId = Regex("""^([KW][A-Z]{2,3})(?:\.|$)""")
    private val regional = Regex("""\b(?:pacific|west)\b""")
    private val wordBreak = Regex("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])""")

    /** A US station's call sign, from "(WLS)" in its name or a "WLS.us" guide ID, lowercase. */
    fun callSign(rawName: String?, id: String?): String? =
        (rawName?.let { inParens.find(it)?.groupValues?.get(1) } ?: id?.let { stationId.find(it)?.groupValues?.get(1) })?.lowercase()

    /** The words in a tvg-id: "DiscoveryChannel.us" → "discovery channel", "FoxSports1.us" → "fox sports 1". */
    fun tvgWords(tvgId: String): String = tvgId.substringBefore('.').replace(wordBreak, " ").lowercase()

    /** Other ways guides write [name] ("discovery" → "discovery channel"), nearest first. Never shorter: "NBC Sports Network" isn't "NBC Sports". */
    fun variants(name: String): List<String> {
        val base = name.removeSuffix(" channel").removeSuffix(" network")
        return listOfNotNull(aliases[name], aliases[base], "$base channel", "$base network", "the $name", "the $base channel", "$base tv")
            .filter { it != name }.distinct()
    }

    /**
     * Other names to try for a US channel. None for a West or Pacific feed, whose times differ from the
     * East listing these lead to. A tvg-id's words count only if they agree with the name: providers
     * tag every regional "FOX SPORTS DETROIT" as FOXSports.us, and no listing beats the wrong one.
     */
    fun otherNames(name: String, tvgId: String?): List<String> {
        if (regional.containsMatchIn(name)) return emptyList()
        val own = name.removeSuffix(" east")
        val words = tvgId?.let { tvgWords(it) }?.takeIf { it.replace(" ", "").contains(own.replace(" ", "")) }
        return (listOfNotNull(own) + variants(own) + listOfNotNull(words) + words?.let { variants(it) }.orEmpty()).filter { it != name }.distinct()
    }

    /** The country a channel name starts with ("US: HBO" → "us"), or null when it has no such prefix. */
    fun country(name: String): String? {
        val m = countryPrefix.find(CharacterCleanup.clean(name, removeEmoji = true)) ?: return null
        return Regex("""[A-Z]{2,3}""").find(m.value)?.value?.lowercase()
    }

    fun normalize(name: String): String {
        var s = CharacterCleanup.clean(name, removeEmoji = true)
        // Repeated prefixes like "US: |US| " come off one at a time.
        repeat(3) { s = s.replace(countryPrefix, "") }
        s = s.lowercase()
            .replace(bracketed, " ")
            .replace(qualityTag, " ")
            .replace("&", " and ")
            .replace(nonWord, " ")
        return s.trim().replace(Regex("""\s+"""), " ")
    }
}

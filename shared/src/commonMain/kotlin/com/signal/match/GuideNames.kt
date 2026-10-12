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

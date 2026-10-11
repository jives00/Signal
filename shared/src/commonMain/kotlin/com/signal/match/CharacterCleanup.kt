package com.signal.match

/**
 * The fixed character clean-up step that runs before cleanup rules, display and matching
 * (PLAN §4.3.2 → Junk characters in provider names):
 * 1. Unicode compatibility folding (NFKC): `ᴴᴰ` → `HD`, `𝐔𝐒𝐀` → `USA`, full-width → ASCII.
 * 2. A look-alike table for what NFKC misses: bars, dashes, quotes, bullets.
 * 3. Flags → country codes: 🇺🇸 → `US`.
 * 4. Invisible characters removed; every kind of space becomes one normal space; trimmed.
 * 5. Optionally, the remaining emoji and pictographs are removed.
 */
object CharacterCleanup {
    fun clean(text: String, removeEmoji: Boolean = true): String {
        val folded = nfkc(foldModifierLetters(text))
        val sb = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = codePointAt(folded, i)
            val len = if (cp > 0xFFFF) 2 else 1
            val next = if (i + len < folded.length) codePointAt(folded, i + len) else -1
            when {
                isRegionalIndicator(cp) && isRegionalIndicator(next) -> {
                    sb.append(('A' + (cp - 0x1F1E6))).append(('A' + (next - 0x1F1E6)))
                    i += len + 2
                    continue
                }
                isInvisible(cp) -> {}
                isSpace(cp) -> sb.append(' ')
                else -> {
                    val lookalike = LookalikeTable.map[cp]
                    when {
                        lookalike != null -> sb.append(lookalike)
                        removeEmoji && isEmojiOrPictograph(cp) -> {}
                        else -> appendCodePoint(sb, cp)
                    }
                }
            }
            i += len
        }
        return sb.toString().replace(spaceRuns, " ").trim()
    }

    private val spaceRuns = Regex(" {2,}")

    /** Superscript/subscript letters that NFKC maps to lower-case, kept upper-case for `ᴴᴰ`. */
    private fun foldModifierLetters(text: String): String {
        if (text.none { it in modifierCapitals }) return text
        return buildString { text.forEach { append(modifierCapitals[it] ?: it) } }
    }

    private val modifierCapitals: Map<Char, Char> = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J',
        'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T',
        'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W',
    )

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isInvisible(cp: Int) =
        cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD ||
            cp in 0xFE00..0xFE0F || cp in 0xE0000..0xE007F || cp == 0x200E || cp == 0x200F ||
            cp in 0x202A..0x202E || cp in 0x2066..0x2069 || (cp < 0x20 && cp != 0x09) || cp in 0x7F..0x9F

    private fun isSpace(cp: Int) =
        cp == 0x20 || cp == 0x09 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
            cp == 0x202F || cp == 0x205F || cp == 0x3000

    private fun isEmojiOrPictograph(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x1F900..0x1F9FF || cp == 0x2122 || cp == 0x00A9 || cp == 0x00AE || cp in 0x2190..0x21FF ||
            cp in 0x25A0..0x25FF || cp in 0x2300..0x23FF

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
        }
        return c.code
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) sb.append(cp.toChar()) else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}

/** Look-alikes NFKC leaves alone. Lives in shared/, easy to extend when a new oddity turns up. */
object LookalikeTable {
    val map: Map<Int, String> = buildMap {
        // Every vertical bar variant → |
        listOf(0x2502, 0x2503, 0x2551, 0x275A, 0x2758, 0x2759, 0x23D0, 0x01C0, 0x2223, 0xFFE8, 0x2016, 0x2225, 0x007C)
            .forEach { put(it, "|") }
        // Every dash variant → -
        listOf(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212, 0x2E3A, 0x2E3B, 0xFE58, 0xFE63, 0x2500, 0x2501, 0x2574, 0x2576)
            .forEach { put(it, "-") }
        // Fancy quotes → plain
        listOf(0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x02BC).forEach { put(it, "'") }
        listOf(0x201C, 0x201D, 0x201E, 0x201F, 0x2033).forEach { put(it, "\"") }
        // Bullets and middle dots → ·
        listOf(0x2022, 0x2023, 0x2043, 0x2219, 0x22C5, 0x30FB, 0x00B7, 0x25CF, 0x25E6).forEach { put(it, "·") }
    }
}

/** Unicode compatibility normalization; java.text.Normalizer on both JVM targets. */
internal expect fun nfkc(text: String): String

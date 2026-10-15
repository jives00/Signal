package com.signal.match

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Name cleanup rules (PLAN §4.3.2 → Name cleanup rules): bulk removal of prefixes and suffixes from
 * displayed channel names (optionally group names), or movie and series titles (optionally category
 * names). Each is one profile setting (name_cleanup, vod_name_cleanup) holding the whole rule set
 * as JSON, so it syncs like any other setting.
 *
 * Rules run on the name after [CharacterCleanup], so a plain `|` or `HD` catches every variant. They
 * change the **displayed name only**: the matcher, guide mapping and search keep the raw name, and a
 * manual rename always wins.
 */
@Serializable
data class NameCleanup(
    val presets: Set<Preset> = emptySet(),
    val custom: List<Rule> = emptyList(),
    /** Off by default: with country codes removed, "US| Sports" and "UK| Sports" would read the same. */
    val groups: Boolean = false,
) {
    /** What a rule set cleans: channel names (and groups), or movie and series titles (and categories). */
    enum class Target { CHANNELS, VOD }

    /**
     * The common patterns, picked from a list. Each one's regex keeps group 1. A [once] pattern runs
     * only in the first pass, so it can't eat into the title it uncovered ("EN - WWE - Raw" keeps "WWE - Raw").
     */
    enum class Preset(val label: String, val example: String, pattern: String, val target: Target = Target.CHANNELS, val once: Boolean = false) {
        COUNTRY(
            "Country codes at the start", "US: ESPN, |UK| BBC One → ESPN, BBC One",
            """^(?:\|\s*[A-Z]{2,3}\s*\||\[\s*[A-Z]{2,3}\s*]|\(\s*[A-Z]{2,3}\s*\)|[A-Z]{2,3}\s*[:|])\s*(.+)$""",
        ),
        TAG(
            "Any short tag before a colon", "MEO: SIC, 4K: Sky → SIC, Sky",
            """^[\p{Lu}\p{N}][\p{Lu}\p{N}+&]{0,9}\s*:\s*(.+)$""",
        ),
        QUALITY(
            "Quality tags at the end", "ESPN FHD, TV 2 RAW 50fps → ESPN, TV 2",
            """(?i)^(.+?)(?:[\s|\-·/]+[\[(]?(?:F?HD|UHD|SD|HQ|[48]K|HEVC|H\.?26[45]|HDR|RAW|\d{3,4}P|\d{2,3}\s?FPS)[\])]?)+\s*$""",
        ),
        MARKERS(
            "Backup, VIP and event markers at the end", "ESPN (Backup), DAZN 1 VIP → ESPN, DAZN 1",
            // Known words only: event channels carry real information in brackets, like "(Home) (2026-10-16 19:03)".
            """(?i)^(.+?)(?:\s*[\[(]\s*(?:BACKUP|BKP|ALT|VIP|LIVE|EVENT|LIVE[- ]EVENTS?|EVENTS?[- ]ONLY|ONLY[- ]EVENTS?)\s*[\])]|[\s|\-]+(?:VIP|BACKUP))+\s*$""",
        ),
        VOD_PREFIX(
            "Provider tags at the start", "EN - Heat, NF - Heat, 4K-TOP - Heat → Heat",
            // An all-caps code (EN, NF, 4K-EN, EN-TOP, D+, 007), then a dash with a space before it. Not a
            // colon: that would take "X-MEN:" and "CSI:" too.
            """^[\p{Lu}\p{N}][\p{Lu}\p{N}+]{0,5}(?:-[\p{Lu}\p{N}+]{1,5})?\s+-\s*(.+)$""",
            Target.VOD, once = true,
        ),
        VOD_COUNTRY(
            "Country codes at the end", "Reckoning (US), Lupin (2021) (FR) → Reckoning, Lupin",
            """^(.+?)(?:\s*\(\s*\p{Lu}{2,3}\s*\))+\s*$""",
            Target.VOD,
        ),
        ;

        internal val regex = Regex(pattern)
    }

    enum class At { START, END, ANYWHERE }

    /**
     * A custom rule. Plain text has `*` (any text) and `?` (one character); with [regex] it's a full
     * regular expression (PLAN §4.3.2 → Advanced mode). Both match ignoring case.
     */
    @Serializable
    data class Rule(val text: String, val at: At, val regex: Boolean = false)

    val isEmpty: Boolean get() = presets.isEmpty() && custom.none { it.text.isNotBlank() }

    /** Rules that can't run: a regex that doesn't compile. They're skipped, not fatal. */
    fun invalidRules(): List<Rule> = custom.filter { it.text.isNotBlank() && compile(it) == null }

    /** One compiled rule: keep group 1 of a whole-name match, or remove every match. */
    private class Step(val regex: Regex, val keep: Boolean, val once: Boolean = false)

    // Not serialized: kotlinx.serialization skips delegated properties.
    private val compiled: List<Step> by lazy {
        Preset.entries.filter { it in presets }.map { Step(it.regex, keep = true, once = it.once) } + custom.mapNotNull { compile(it) }
    }

    /** The displayed name: the rules applied until nothing changes. Never blank. */
    fun apply(name: String): String {
        if (compiled.isEmpty()) return name
        var current = name
        repeat(MAX_PASSES) { pass ->
            val before = current
            for (step in compiled) {
                if (step.once && pass > 0) continue
                val result = if (step.keep) {
                    step.regex.matchEntire(current)?.groupValues?.get(1) ?: continue
                } else {
                    step.regex.replace(current, " ").takeIf { it != current }?.replace(spaceRuns, " ") ?: continue
                }
                current = result.trim { it.isWhitespace() || it in SEPARATORS }.ifEmpty { current }
            }
            if (current == before) return current
        }
        return current
    }

    /** The setting's value; null when there's nothing to store. */
    fun encode(): String? = if (isEmpty && !groups) null else json.encodeToString(serializer(), copy(custom = custom.filter { it.text.isNotBlank() }))

    companion object {
        val NONE = NameCleanup()
        /** The presets that apply to [target], in the order they run. */
        fun presets(target: Target): List<Preset> = Preset.entries.filter { it.target == target }
        private const val MAX_PASSES = 4
        private const val SEPARATORS = "|:-·/"
        private const val WORD = """[\p{L}\p{N}]"""
        private val spaceRuns = Regex("""\s{2,}""")
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Reads the setting; an unreadable value means no rules rather than an error on every name. */
        fun parse(raw: String?): NameCleanup =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: NONE

        /**
         * A custom rule as a step. Plain text that starts or ends with a letter or digit only matches
         * at a word edge, so `HD` at the end leaves "ESPNHD" alone. Wildcards in an end rule take the
         * shortest suffix, and in a start rule the shortest prefix. A regex is used as written, anchored
         * for start and end; null if it doesn't compile.
         */
        private fun compile(rule: Rule): Step? {
            val text = rule.text.trim().ifEmpty { return null }
            if (rule.regex) {
                val pattern = when (rule.at) {
                    At.START -> "^\\s*(?:$text)"
                    At.END -> "(?:$text)\\s*$"
                    At.ANYWHERE -> text
                }
                return runCatching { Step(Regex(pattern, RegexOption.IGNORE_CASE), keep = false) }.getOrNull()
            }
            val body = buildString {
                var literal = StringBuilder()
                fun flush() { if (literal.isNotEmpty()) append(Regex.escape(literal.toString())); literal = StringBuilder() }
                for (c in text) when (c) {
                    '*' -> { flush(); append(".*?") }
                    '?' -> { flush(); append('.') }
                    else -> literal.append(c)
                }
                flush()
            }
            val before = if (text.first().isLetterOrDigit()) "(?<!$WORD)" else ""
            val after = if (text.last().isLetterOrDigit()) "(?!$WORD)" else ""
            val (pattern, keep) = when (rule.at) {
                At.START -> "^\\s*$body$after\\s*(.+)$" to true
                At.END -> "^(.+)$before$body\\s*$" to true
                At.ANYWHERE -> "$before$body$after" to false
            }
            return runCatching { Step(Regex(pattern, RegexOption.IGNORE_CASE), keep) }.getOrNull()
        }
    }
}

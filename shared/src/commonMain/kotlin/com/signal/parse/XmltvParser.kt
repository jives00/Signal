package com.signal.parse

/**
 * A streaming XMLTV parser (PLAN §6 → Guides): it reads characters as they arrive and hands each
 * `<channel>` and `<programme>` to a callback, so a 100MB guide is never held in memory.
 *
 * It's a small purpose-built tokenizer rather than a platform XML parser: XMLTV is simple, the
 * same code then runs on the Shield and the NAS companion, and there's no `expect/actual` to keep
 * in step. It handles what real guides send: a prolog and DOCTYPE, comments, CDATA, the five named
 * entities and numeric ones, and unknown elements (skipped).
 */
class XmltvParser(
    private val onChannel: (XmltvChannel) -> Unit,
    private val onProgram: (XmltvProgram) -> Unit,
) {
    /** Where the characters come from: return how many were read into [buf], or -1 at the end. */
    fun interface CharSource {
        fun read(buf: CharArray): Int
    }

    private lateinit var src: CharSource
    private val buf = CharArray(64 * 1024)
    private var len = 0
    private var pos = 0
    // The current element's text, including any CDATA.
    private val text = StringBuilder()

    fun parse(source: CharSource) {
        src = source
        len = 0
        pos = 0
        val path = ArrayList<String>()
        var channel: ChannelBuilder? = null
        var program: ProgramBuilder? = null
        text.setLength(0)
        var attrs: Map<String, String> = emptyMap()

        while (true) {
            val c = next() ?: break
            if (c != '<') {
                if (program != null || channel != null) {
                    if (c == '&') text.append(entity()) else text.append(c)
                }
                continue
            }
            val tag = readTag() ?: continue
            when (tag.kind) {
                TagKind.OPEN, TagKind.SELF_CLOSING -> {
                    text.setLength(0)
                    attrs = tag.attrs
                    when (tag.name) {
                        "channel" -> if (program == null) channel = ChannelBuilder(tag.attrs["id"].orEmpty())
                        "programme" -> program = ProgramBuilder(
                            channel = tag.attrs["channel"].orEmpty(),
                            start = XmltvTime.parse(tag.attrs["start"]),
                            stop = XmltvTime.parse(tag.attrs["stop"]),
                        )
                        "icon" -> {
                            val src = tag.attrs["src"]
                            if (src != null) {
                                if (program != null) program.icon = program.icon ?: src
                                else if (channel != null) channel.icon = channel.icon ?: src
                            }
                        }
                        "new" -> program?.isNew = true
                        "live" -> program?.isLive = true
                        "previously-shown" -> program?.previouslyShown = true
                    }
                    if (tag.kind == TagKind.OPEN) path += tag.name
                }
                TagKind.CLOSE -> {
                    val value = text.toString().trim()
                    text.setLength(0)
                    when (tag.name) {
                        "channel" -> channel?.let { if (it.id.isNotEmpty()) onChannel(it.build()); channel = null }
                        "programme" -> program?.let { if (it.valid) onProgram(it.build()); program = null }
                        "display-name" -> channel?.let { if (value.isNotEmpty()) it.names += value }
                        "title" -> program?.let { if (it.title == null && value.isNotEmpty()) it.title = value }
                        "sub-title" -> program?.let { if (it.subTitle == null && value.isNotEmpty()) it.subTitle = value }
                        "desc" -> program?.let { if (it.description == null && value.isNotEmpty()) it.description = value }
                        "category" -> program?.let { if (value.isNotEmpty() && value !in it.categories) it.categories += value }
                        "episode-num" -> program?.episode(attrs["system"], value)
                        "value" -> if (path.getOrNull(path.size - 2) == "rating") program?.let { if (it.rating == null && value.isNotEmpty()) it.rating = value }
                        "date" -> program?.let { it.date = value.take(4).toIntOrNull() }
                        "director", "actor", "presenter", "writer" -> program?.let { if (value.isNotEmpty() && it.credits.size < 12) it.credits += value }
                    }
                    if (path.lastOrNull() == tag.name) path.removeAt(path.lastIndex)
                    else path.lastIndexOf(tag.name).takeIf { it >= 0 }?.let { i -> while (path.size > i) path.removeAt(path.lastIndex) }
                }
            }
        }
    }

    private fun next(): Char? {
        if (pos >= len) {
            len = src.read(buf)
            pos = 0
            if (len <= 0) { len = 0; return null }
        }
        return buf[pos++]
    }

    private enum class TagKind { OPEN, CLOSE, SELF_CLOSING }
    private class Tag(val name: String, val kind: TagKind, val attrs: Map<String, String>)

    /** After '<': reads one tag. Comments, CDATA, the prolog and DOCTYPE return null; CDATA goes into [text]. */
    private fun readTag(): Tag? {
        val first = next() ?: return null
        when (first) {
            '?' -> { skipUntil("?>"); return null }
            '!' -> {
                val a = next(); val b = next()
                when {
                    a == '-' && b == '-' -> skipUntil("-->")
                    // "<![" has been read, and "C" with it: skip to the second "[".
                    a == '[' -> { skipUntil("["); text.append(readUntil("]]>")) }
                    else -> skipDeclaration()
                }
                return null
            }
        }
        val closing = first == '/'
        val name = StringBuilder()
        if (!closing) name.append(first)
        var c: Char?
        while (true) {
            c = next() ?: return null
            if (c == '>' || c == '/' || c.isWhitespace()) break
            name.append(c)
        }
        if (closing) {
            while (c != '>') c = next() ?: return null
            return Tag(name.toString(), TagKind.CLOSE, emptyMap())
        }
        val attrs = HashMap<String, String>(4)
        while (true) {
            if (c == '>') return Tag(name.toString(), TagKind.OPEN, attrs)
            if (c == '/') { while (c != '>') c = next() ?: return null; return Tag(name.toString(), TagKind.SELF_CLOSING, attrs) }
            if (c!!.isWhitespace()) { c = next() ?: return null; continue }
            // An attribute: name="value" or name='value'.
            val key = StringBuilder()
            while (c != '=' && c != '>' && c != '/' && !c!!.isWhitespace()) { key.append(c); c = next() ?: return null }
            while (c != null && c.isWhitespace()) c = next()
            if (c != '=') continue
            c = next()
            while (c != null && c.isWhitespace()) c = next()
            val quote = c ?: return null
            if (quote != '"' && quote != '\'') continue
            val value = StringBuilder()
            while (true) {
                val v = next() ?: return null
                if (v == quote) break
                if (v == '&') value.append(entity()) else value.append(v)
            }
            attrs[key.toString()] = value.toString()
            c = next() ?: return null
        }
    }

    private fun skipUntil(end: String) {
        var matched = 0
        while (matched < end.length) {
            val c = next() ?: return
            matched = if (c == end[matched]) matched + 1 else if (c == end[0]) 1 else 0
        }
    }

    private fun readUntil(end: String): String {
        val sb = StringBuilder()
        var matched = 0
        while (matched < end.length) {
            val c = next() ?: break
            sb.append(c)
            matched = if (c == end[matched]) matched + 1 else if (c == end[0]) 1 else 0
        }
        return sb.substring(0, (sb.length - end.length).coerceAtLeast(0))
    }

    /** A DOCTYPE or other declaration, which may contain a bracketed internal subset. */
    private fun skipDeclaration() {
        var depth = 0
        while (true) {
            val c = next() ?: return
            if (c == '[') depth++
            else if (c == ']') depth--
            else if (c == '>' && depth <= 0) return
        }
    }

    /** After '&': the entity's text, or the raw text when it isn't one. */
    private fun entity(): String {
        val sb = StringBuilder()
        while (sb.length < 10) {
            val c = next() ?: break
            if (c == ';') {
                val e = sb.toString()
                return when {
                    e == "amp" -> "&"
                    e == "lt" -> "<"
                    e == "gt" -> ">"
                    e == "quot" -> "\""
                    e == "apos" -> "'"
                    e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let(::codePoint) ?: "&$e;"
                    e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint) ?: "&$e;"
                    else -> "&$e;"
                }
            }
            sb.append(c)
        }
        return "&$sb"
    }

    private fun codePoint(cp: Int): String = if (cp < 0x10000) cp.toChar().toString() else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    private class ChannelBuilder(val id: String) {
        val names = ArrayList<String>(2)
        var icon: String? = null
        fun build() = XmltvChannel(id, names.toList(), icon)
    }

    private class ProgramBuilder(val channel: String, val start: Long?, val stop: Long?) {
        var title: String? = null
        var subTitle: String? = null
        var description: String? = null
        val categories = ArrayList<String>(2)
        var season: Int? = null
        var episodeNumber: Int? = null
        var episodeLabel: String? = null
        var icon: String? = null
        var rating: String? = null
        var date: Int? = null
        var isNew = false
        var isLive = false
        var previouslyShown = false
        val credits = ArrayList<String>()

        val valid get() = channel.isNotEmpty() && start != null && stop != null && stop > start && title != null

        /** xmltv_ns is "season.episode.part", zero-based; onscreen is free text like "S01E02". */
        fun episode(system: String?, value: String) {
            if (value.isEmpty()) return
            if (system == "xmltv_ns") {
                val parts = value.split('.')
                parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.let { season = it + 1 }
                parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.let { episodeNumber = it + 1 }
            } else if (episodeLabel == null) {
                episodeLabel = value
                Regex("""[Ss](\d{1,3})\s*[Ee](\d{1,4})""").find(value)?.let { m ->
                    season = season ?: m.groupValues[1].toInt()
                    episodeNumber = episodeNumber ?: m.groupValues[2].toInt()
                }
            }
        }

        fun build() = XmltvProgram(
            channel = channel, start = start!!, stop = stop!!, title = title!!, subTitle = subTitle,
            description = description, categories = categories.toList(), season = season,
            episode = episodeNumber, episodeLabel = episodeLabel, icon = icon, rating = rating, year = date,
            isNew = isNew, isLive = isLive, previouslyShown = previouslyShown, credits = credits.toList(),
        )
    }
}

data class XmltvChannel(val id: String, val displayNames: List<String>, val icon: String?)

/** One programme. Times are epoch milliseconds in UTC, as the guide gives them; time shift is applied when read (PLAN §6). */
data class XmltvProgram(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subTitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    val episodeLabel: String? = null,
    val icon: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    val isNew: Boolean = false,
    val isLive: Boolean = false,
    val previouslyShown: Boolean = false,
    val credits: List<String> = emptyList(),
)

/** XMLTV times: "20261012083000 +0200", with the offset optional (UTC then) and seconds optional. */
object XmltvTime {
    fun parse(text: String?): Long? {
        val s = text?.trim() ?: return null
        if (s.length < 12) return null
        val y = s.substring(0, 4).toIntOrNull() ?: return null
        val mo = s.substring(4, 6).toIntOrNull() ?: return null
        val d = s.substring(6, 8).toIntOrNull() ?: return null
        val h = s.substring(8, 10).toIntOrNull() ?: return null
        val mi = s.substring(10, 12).toIntOrNull() ?: return null
        val hasSeconds = s.length >= 14 && s[12].isDigit() && s[13].isDigit()
        val sec = if (hasSeconds) s.substring(12, 14).toInt() else 0
        val zone = s.substring(if (hasSeconds) 14 else 12).trim()
        val offsetMin = zone.takeIf { it.length >= 5 && (it[0] == '+' || it[0] == '-') }?.let {
            val sign = if (it[0] == '-') -1 else 1
            val hh = it.substring(1, 3).toIntOrNull() ?: return@let 0
            val mm = it.substring(3, 5).toIntOrNull() ?: return@let 0
            sign * (hh * 60 + mm)
        } ?: 0
        val days = daysFromCivil(y, mo, d)
        return ((days * 86_400L + h * 3600L + mi * 60L + sec) - offsetMin * 60L) * 1000L
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}

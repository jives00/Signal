package com.signal.update

import kotlinx.coroutines.flow.StateFlow

/**
 * The auto-updater (PLAN §4.3.15 → About & updates), the same approach as Pulse, Quest, Travel and
 * Weather: a release build compares its own apk-* tag with the newest GitHub Release and, on
 * Install, downloads the APK and hands it to Android's package installer.
 */
interface Updater {
    val state: StateFlow<UpdateState>

    /**
     * Quietly does nothing on failure: a check must never get in the way. Returns whether GitHub
     * answered, so Settings can say so. A [manual] check also offers a version put off with Later.
     */
    suspend fun check(manual: Boolean = false): Boolean
    fun install()
    /** Hides the banner until the next release. */
    fun later()
}

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val tag: String, val changelog: String) : UpdateState
    data class Downloading(val tag: String, val progress: Float) : UpdateState
    data class Failed(val tag: String, val message: String) : UpdateState
}

/** Release lookup, shared so it's testable: the newest release and its APK asset. */
object GitHubReleases {
    const val REPO = "jives00/Signal"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val tag: String, val body: String, val apkUrl: String)

    fun parseLatest(json: String): Release? {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return null
        fun str(o: kotlinx.serialization.json.JsonObject, k: String) =
            (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val tag = str(root, "tag_name") ?: return null
        val assets = root["assets"] as? kotlinx.serialization.json.JsonArray ?: return null
        val apk = assets.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .firstOrNull { str(it, "name")?.endsWith(".apk") == true } ?: return null
        val url = str(apk, "browser_download_url") ?: return null
        return Release(tag, changelog(str(root, "body").orEmpty()), url)
    }

    /**
     * A release body as plain text for the TV: list items become bullets, and markdown headings and
     * GitHub's "**Full Changelog**: <compare URL>" footer (the whole body of older releases) are dropped.
     */
    fun changelog(body: String): String =
        body.lines()
            .map { it.trim() }
            .filterNot { it.startsWith("#") || it.startsWith("**Full Changelog**") || it.startsWith("<!--") }
            .map { line -> if (line.startsWith("- ") || line.startsWith("* ")) "• " + line.drop(2).trim() else line }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** The recent releases, newest first: enough to cover a TV that skipped a few. */
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** Each release in a list response, its notes as [changelog] gives them. The APK URL is empty where there's none. */
    fun parseReleases(json: String): List<Release> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        fun str(o: kotlinx.serialization.json.JsonObject, k: String) =
            (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return root.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.mapNotNull { o ->
            if ((o["draft"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") return@mapNotNull null
            val tag = str(o, "tag_name") ?: return@mapNotNull null
            val apk = (o["assets"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                ?.firstOrNull { str(it, "name")?.endsWith(".apk") == true }
            Release(tag, changelog(str(o, "body").orEmpty()), apk?.let { str(it, "browser_download_url") }.orEmpty())
        }
    }

    /** "apk-v0.2.29" → [0, 2, 29], for ordering; null for anything else ("dev"). */
    fun version(tag: String): List<Int>? =
        tag.removePrefix("apk-").removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /**
     * What's new between the installed version and the one offered (user, 2026-10-16): every release
     * after [currentTag] up to [latestTag], newest first, each under its version. The notes' own
     * "Signal 0.2.29" first line goes, since the heading says it. Null when [releases] has nothing
     * to show, so the latest release's notes stand alone.
     */
    fun changesSince(releases: List<Release>, currentTag: String, latestTag: String): String? {
        val current = version(currentTag) ?: return null
        val latest = version(latestTag) ?: return null
        val between = releases.mapNotNull { r -> version(r.tag)?.let { it to r } }
            .filter { (v, _) -> compare(v, current) > 0 && compare(v, latest) <= 0 }
            .sortedWith { a, b -> compare(b.first, a.first) }
            .distinctBy { it.second.tag }
        if (between.isEmpty()) return null
        return between.joinToString("\n\n") { (v, r) ->
            val notes = r.body.lines().dropWhile { it.isBlank() }
                .let { lines -> if (lines.firstOrNull()?.trim()?.matches(Regex("""Signal v?[\d.]+""")) == true) lines.drop(1) else lines }
                .joinToString("\n").trim()
            "Version ${v.joinToString(".")}\n" + notes.ifEmpty { "No notes for this release." }
        }
    }

    /** "dev" builds never offer updates; otherwise any different newest tag is an update. */
    fun isNewer(currentTag: String, latestTag: String): Boolean =
        currentTag != "dev" && latestTag.startsWith("apk-") && latestTag != currentTag
}

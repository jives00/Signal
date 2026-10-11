package com.signal.update

import kotlinx.coroutines.flow.StateFlow

/**
 * The auto-updater (PLAN §4.3.15 → About & updates), the same approach as Pulse, Quest, Travel and
 * Weather: a release build compares its own apk-* tag with the newest GitHub Release and, on
 * Install, downloads the APK and hands it to Android's package installer.
 */
interface Updater {
    val state: StateFlow<UpdateState>

    /** Quietly does nothing on failure: a check must never get in the way. */
    suspend fun check()
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
        return Release(tag, str(root, "body").orEmpty(), url)
    }

    /** "dev" builds never offer updates; otherwise any different newest tag is an update. */
    fun isNewer(currentTag: String, latestTag: String): Boolean =
        currentTag != "dev" && latestTag.startsWith("apk-") && latestTag != currentTag
}

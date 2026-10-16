package com.signal.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One app release, for Devices & Profiles → Updates. [stable]: not a pre-release (testing). */
@Serializable
data class ReleaseView(val tag: String, val title: String, val publishedAt: Long, val stable: Boolean, val notes: String)

/** The recent releases, and whether a GitHub token is saved (marking needs one). [error] when GitHub couldn't be reached. */
@Serializable
data class ReleasesView(val releases: List<ReleaseView>, val tokenSet: Boolean, val error: String? = null)

/**
 * Release channels (PLAN §4.3.15 → Release channels): app releases are published as testing
 * (GitHub pre-releases), and a profile set to stable is only offered those marked stable. The web
 * admin marks them, through GitHub's API with a token saved in Settings → Companion. The repo is
 * public, so listing works without one.
 */
class Releases(private val http: HttpClient, private val token: () -> String?) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun io.ktor.client.request.HttpRequestBuilder.github() {
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        token()?.let { header("Authorization", "Bearer $it") }
    }

    suspend fun list(): ReleasesView {
        val tokenSet = token() != null
        val response = runCatching { http.get("$API/releases?per_page=$FETCH") { github() } }
            .getOrElse { return ReleasesView(emptyList(), tokenSet, "GitHub couldn't be reached: ${it.message}") }
        if (!response.status.isSuccess()) return ReleasesView(emptyList(), tokenSet, "GitHub answered ${response.status.value}")
        val releases = (json.parseToJsonElement(response.bodyAsText()) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.flag("draft")) return@mapNotNull null
            val tag = o.str("tag_name") ?: return@mapNotNull null
            if (!tag.startsWith("apk-")) return@mapNotNull null
            ReleaseView(
                tag = tag,
                title = o.str("name") ?: tag,
                publishedAt = o.str("published_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                stable = !o.flag("prerelease"),
                notes = o.str("body").orEmpty(),
            )
        }
        // GitHub doesn't list releases in version order (0.2.31 came 10th, 2026-10-16): sorted here.
        val newest = releases.sortedWith { a, b -> compareVersions(b.tag, a.tag) }.take(SHOWN)
        return ReleasesView(newest, tokenSet)
    }

    /** Marks [tag] stable or back to testing. Returns an error for people, or null. */
    suspend fun mark(tag: String, stable: Boolean): String? {
        if (token() == null) return "Save a GitHub token in Settings → Companion first."
        val found = runCatching { http.get("$API/releases/tags/$tag") { github() } }.getOrElse { return "GitHub couldn't be reached: ${it.message}" }
        if (!found.status.isSuccess()) return "No release $tag on GitHub (${found.status.value})."
        val id = (json.parseToJsonElement(found.bodyAsText()) as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull ?: return "GitHub's answer had no release ID."
        // Stable also makes it GitHub's "latest", which app versions before 0.2.30 still read.
        val body = """{"prerelease":${!stable},"make_latest":"${if (stable) "true" else "false"}"}"""
        val updated: HttpResponse = runCatching {
            http.patch("$API/releases/$id") { github(); contentType(ContentType.Application.Json); setBody(body) }
        }.getOrElse { return "GitHub couldn't be reached: ${it.message}" }
        return when {
            updated.status.isSuccess() -> null
            updated.status.value == 401 || updated.status.value == 403 || updated.status.value == 404 ->
                "GitHub refused the change (${updated.status.value}): the token needs write access to $REPO's contents."
            else -> "GitHub answered ${updated.status.value}."
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.content == "true"

    companion object {
        const val REPO = "jives00/Signal"
        private const val API = "https://api.github.com/repos/$REPO"
        /** As many as GitHub gives in one page, so the newest is always among them. */
        private const val FETCH = 100
        private const val SHOWN = 15

        /** "apk-v0.2.31" against "apk-v0.2.9", by number. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(t: String) = t.removePrefix("apk-").removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}

/** POST /api/releases/{tag}: [stable] true marks it stable, false back to testing. */
@Serializable
data class MarkRelease(val stable: Boolean)

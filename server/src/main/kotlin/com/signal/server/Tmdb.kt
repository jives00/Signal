package com.signal.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * TMDB's API (PLAN §4.3.12, §5.8 → TMDB): a client-side rate limit just under TMDB's, and backoff on
 * 429. The key is a v3 API key (sent as a parameter) or a v4 read token (sent as a Bearer header).
 */
class Tmdb(private val http: HttpClient, private val key: () -> String?, private val base: String = "https://api.themoviedb.org/3") {
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Mutex()
    private var lastAt = 0L

    class NoKey : Exception("Add a TMDB API key in Settings → Companion.")

    suspend fun get(path: String, vararg params: Pair<String, String>): JsonObject {
        val k = key() ?: throw NoKey()
        repeat(MAX_TRIES) { attempt ->
            gate.withLock {
                val wait = lastAt + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastAt = System.currentTimeMillis()
            }
            val r = http.get(base + path) {
                if (k.length > 40) header("Authorization", "Bearer $k") else parameter("api_key", k)
                params.forEach { (n, v) -> parameter(n, v) }
            }
            when {
                r.status == HttpStatusCode.TooManyRequests -> delay((r.headers["Retry-After"]?.toLongOrNull() ?: 2L) * 1000 * (attempt + 1))
                r.status == HttpStatusCode.Unauthorized -> error("TMDB refused the API key")
                r.status == HttpStatusCode.NotFound -> return JsonObject(emptyMap())
                r.status.value >= 400 -> error("TMDB answered ${r.status.value}")
                else -> return json.parseToJsonElement(r.bodyAsText()).jsonObject
            }
        }
        error("TMDB kept answering 429")
    }

    companion object {
        /** About 25 requests a second: under TMDB's limit with room for retries. */
        const val MIN_INTERVAL_MS = 40L
        const val MAX_TRIES = 5
    }
}

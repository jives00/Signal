package com.signal.parse

import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.StreamIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Parses Xtream Codes `player_api.php` responses (PLAN §6). Providers are inconsistent about
 * types (numbers arrive as strings, strings as numbers, nulls everywhere), so fields are read
 * leniently from raw JSON instead of through strict data classes.
 */
object XtreamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class AccountInfo(
        val authenticated: Boolean,
        val status: String?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        /** Epoch seconds, or null for no expiry. */
        val expiresAt: Long?,
    )

    fun accountInfo(body: String): AccountInfo {
        val user = json.parseToJsonElement(body).jsonObject["user_info"] as? JsonObject
            ?: return AccountInfo(false, null, null, null, null)
        return AccountInfo(
            authenticated = user.int("auth") == 1,
            status = user.string("status"),
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            expiresAt = user.long("exp_date"),
        )
    }

    /** `get_live_categories`: category ID → name. */
    fun categories(body: String): Map<String, String> =
        json.parseToJsonElement(body).arrayOrEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.string("category_id") ?: return@mapNotNull null
            id to (obj.string("category_name") ?: "Uncategorized")
        }.toMap()

    /** `get_live_streams`, with category IDs resolved to names. */
    fun liveStreams(source: Source, body: String, categories: Map<String, String>): List<ProviderStream> {
        val out = ArrayList<ProviderStream>()
        val seen = HashSet<String>()
        json.parseToJsonElement(body).arrayOrEmpty().forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val streamId = obj.string("stream_id") ?: return@forEach
            val id = StreamIds.xtream(source.id, streamId)
            if (!seen.add(id)) return@forEach
            out += ProviderStream(
                id = id,
                sourceId = source.id,
                providerId = streamId,
                rawName = obj.string("name")?.trim().orEmpty().ifEmpty { "Channel $streamId" },
                groupName = obj.string("category_id")?.let(categories::get) ?: "Uncategorized",
                logoUrl = obj.string("stream_icon")?.takeIf { it.isNotBlank() },
                tvgId = obj.string("epg_channel_id")?.takeIf { it.isNotBlank() },
                providerNumber = obj.int("num"),
                position = out.size,
                url = null,
            )
        }
        return out
    }

    /** `get_vod_streams` (PLAN §4.3.6): movies, with category IDs kept as given. */
    fun vodStreams(source: Source, body: String): List<com.signal.model.VodItem> {
        val out = ArrayList<com.signal.model.VodItem>()
        val seen = HashSet<String>()
        json.parseToJsonElement(body).arrayOrEmpty().forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val streamId = obj.string("stream_id") ?: return@forEach
            val id = com.signal.model.VodIds.movie(source.id, streamId)
            if (!seen.add(id)) return@forEach
            val name = obj.string("name")?.trim().orEmpty().ifEmpty { "Movie $streamId" }
            out += com.signal.model.VodItem(
                id = id,
                sourceId = source.id,
                kind = com.signal.model.VodKind.MOVIE,
                providerId = streamId,
                name = name,
                categoryId = obj.string("category_id") ?: "",
                posterUrl = obj.string("stream_icon")?.takeIf { it.isNotBlank() },
                year = obj.int("year")?.takeIf { it in 1880..2100 } ?: yearIn(name),
                rating = obj.double("rating")?.takeIf { it > 0 },
                addedAt = obj.long("added") ?: 0,
                tmdbId = obj.long("tmdb")?.takeIf { it > 0 } ?: obj.long("tmdb_id")?.takeIf { it > 0 },
                containerExt = obj.string("container_extension")?.takeIf { it.isNotBlank() },
                position = out.size,
            )
        }
        return out
    }

    /** `get_series` (PLAN §4.3.7). */
    fun series(source: Source, body: String): List<com.signal.model.VodItem> {
        val out = ArrayList<com.signal.model.VodItem>()
        val seen = HashSet<String>()
        json.parseToJsonElement(body).arrayOrEmpty().forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val seriesId = obj.string("series_id") ?: return@forEach
            val id = com.signal.model.VodIds.series(source.id, seriesId)
            if (!seen.add(id)) return@forEach
            val name = obj.string("name")?.trim().orEmpty().ifEmpty { "Series $seriesId" }
            out += com.signal.model.VodItem(
                id = id,
                sourceId = source.id,
                kind = com.signal.model.VodKind.SERIES,
                providerId = seriesId,
                name = name,
                categoryId = obj.string("category_id") ?: "",
                posterUrl = obj.string("cover")?.takeIf { it.isNotBlank() },
                year = obj.string("releaseDate")?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 }
                    ?: obj.string("release_date")?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 } ?: yearIn(name),
                rating = obj.double("rating")?.takeIf { it > 0 },
                addedAt = obj.long("last_modified") ?: 0,
                tmdbId = obj.long("tmdb")?.takeIf { it > 0 } ?: obj.long("tmdb_id")?.takeIf { it > 0 },
                plot = obj.string("plot")?.takeIf { it.isNotBlank() },
                position = out.size,
            )
        }
        return out
    }

    private val yearInName = Regex("""[(\[](19\d{2}|20\d{2})[)\]]""")

    /** "Heat (1995)" → 1995: many providers put the year only in the name. */
    fun yearIn(name: String): Int? = yearInName.find(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun JsonObject.double(key: String): Double? = string(key)?.trim()?.toDoubleOrNull()

    private fun JsonElement.arrayOrEmpty(): JsonArray = (this as? JsonArray) ?: runCatching { jsonArray }.getOrNull() ?: JsonArray(emptyList())

    private fun JsonObject.string(key: String): String? = when (val v = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> v.content
        else -> null
    }

    private fun JsonObject.long(key: String): Long? = string(key)?.trim()?.toDoubleOrNull()?.toLong()
    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}

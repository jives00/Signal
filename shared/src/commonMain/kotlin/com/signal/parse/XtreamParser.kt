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
        val out = LiveStreams(source, categories)
        json.parseToJsonElement(body).arrayOrEmpty().forEach { el -> (el as? JsonObject)?.let(out::add) }
        return out.streams
    }

    /**
     * Builds `get_live_streams` one object at a time, so the device can read the list as it downloads
     * ([com.signal.parse.JsonArrayObjects]): 57k channels read whole, as one string and one JSON tree,
     * ran a 192 MB heap out of memory on the Shield (0.2.8).
     */
    class LiveStreams(private val source: Source, private val categories: Map<String, String>) {
        val streams = ArrayList<ProviderStream>()
        private val seen = HashSet<String>()

        fun add(obj: JsonObject) {
            val streamId = obj.string("stream_id") ?: return
            val id = StreamIds.xtream(source.id, streamId)
            if (!seen.add(id)) return
            streams += ProviderStream(
                id = id,
                sourceId = source.id,
                providerId = streamId,
                rawName = obj.string("name")?.trim().orEmpty().ifEmpty { "Channel $streamId" },
                groupName = obj.string("category_id")?.let(categories::get) ?: "Uncategorized",
                logoUrl = obj.string("stream_icon")?.takeIf { it.isNotBlank() },
                tvgId = obj.string("epg_channel_id")?.takeIf { it.isNotBlank() },
                providerNumber = obj.int("num"),
                position = streams.size,
                url = null,
            )
        }
    }

    /** `get_vod_streams` (PLAN §4.3.6): movies, with category IDs kept as given. */
    fun vodStreams(source: Source, body: String): List<com.signal.model.VodItem> {
        val seen = HashSet<String>()
        return json.parseToJsonElement(body).arrayOrEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { movie(source, it, seen.size) }?.takeIf { seen.add(it.id) }
        }
    }

    /** `get_series` (PLAN §4.3.7). */
    fun series(source: Source, body: String): List<com.signal.model.VodItem> {
        val seen = HashSet<String>()
        return json.parseToJsonElement(body).arrayOrEmpty().mapNotNull { el ->
            (el as? JsonObject)?.let { seriesItem(source, it, seen.size) }?.takeIf { seen.add(it.id) }
        }
    }

    /** One element of a streamed list ([JsonArrayObjects]), or null if it isn't an object. */
    fun objectOf(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** One movie from `get_vod_streams`, at [position] in the provider's list. */
    fun movie(source: Source, obj: JsonObject, position: Int): com.signal.model.VodItem? {
        val streamId = obj.string("stream_id") ?: return null
        val name = obj.string("name")?.trim().orEmpty().ifEmpty { "Movie $streamId" }
        return com.signal.model.VodItem(
            id = com.signal.model.VodIds.movie(source.id, streamId),
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
            position = position,
        )
    }

    /** One series from `get_series`, at [position] in the provider's list. */
    fun seriesItem(source: Source, obj: JsonObject, position: Int): com.signal.model.VodItem? {
        val seriesId = obj.string("series_id") ?: return null
        val name = obj.string("name")?.trim().orEmpty().ifEmpty { "Series $seriesId" }
        return com.signal.model.VodItem(
            id = com.signal.model.VodIds.series(source.id, seriesId),
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
            position = position,
        )
    }

    /** `get_vod_info` (PLAN §4.3.8). */
    fun movieDetail(body: String): com.signal.model.MovieDetail {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return com.signal.model.MovieDetail()
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        val data = root["movie_data"] as? JsonObject ?: JsonObject(emptyMap())
        return com.signal.model.MovieDetail(
            plot = info.string("plot")?.takeIf { it.isNotBlank() } ?: info.string("description")?.takeIf { it.isNotBlank() },
            genres = genres(info.string("genre")),
            cast = info.string("cast")?.takeIf { it.isNotBlank() } ?: info.string("actors")?.takeIf { it.isNotBlank() },
            director = info.string("director")?.takeIf { it.isNotBlank() },
            durationSec = info.int("duration_secs")?.takeIf { it > 0 } ?: hms(info.string("duration")),
            releaseDate = info.string("releasedate")?.takeIf { it.isNotBlank() } ?: info.string("release_date")?.takeIf { it.isNotBlank() },
            rating = info.double("rating")?.takeIf { it > 0 },
            backdropUrl = firstUrl(info["backdrop_path"]),
            posterUrl = info.string("movie_image")?.takeIf { it.isNotBlank() } ?: info.string("cover_big")?.takeIf { it.isNotBlank() },
            trailerKey = trailer(info.string("youtube_trailer")),
            tmdbId = info.long("tmdb_id")?.takeIf { it > 0 },
            containerExt = data.string("container_extension")?.takeIf { it.isNotBlank() },
        )
    }

    /** `get_series_info` (PLAN §4.3.9). Episodes come as a map by season or, from some providers, a flat list. */
    fun seriesDetail(source: Source, body: String): com.signal.model.SeriesDetail {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return com.signal.model.SeriesDetail()
        val info = root["info"] as? JsonObject ?: JsonObject(emptyMap())
        val raw: List<JsonElement> = when (val e = root["episodes"]) {
            is JsonObject -> e.values.flatMap { (it as? JsonArray).orEmpty() }
            is JsonArray -> e.flatMap { (it as? JsonArray) ?: listOf(it) }
            else -> emptyList()
        }
        val episodes = raw.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            val epInfo = obj["info"] as? JsonObject ?: JsonObject(emptyMap())
            val season = obj.int("season") ?: return@mapNotNull null
            val number = obj.int("episode_num") ?: return@mapNotNull null
            com.signal.model.Episode(
                id = "${source.id}:e:$id",
                providerId = id,
                season = season,
                episode = number,
                title = obj.string("title")?.trim()?.takeIf { it.isNotBlank() } ?: "Episode $number",
                plot = epInfo.string("plot")?.takeIf { it.isNotBlank() },
                durationSec = epInfo.int("duration_secs")?.takeIf { it > 0 } ?: hms(epInfo.string("duration")),
                stillUrl = epInfo.string("movie_image")?.takeIf { it.isNotBlank() },
                containerExt = obj.string("container_extension")?.takeIf { it.isNotBlank() },
                addedAt = obj.long("added") ?: 0,
            )
        }.distinctBy { it.id }
        return com.signal.model.SeriesDetail(
            plot = info.string("plot")?.takeIf { it.isNotBlank() },
            genres = genres(info.string("genre")),
            cast = info.string("cast")?.takeIf { it.isNotBlank() },
            director = info.string("director")?.takeIf { it.isNotBlank() },
            rating = info.double("rating")?.takeIf { it > 0 },
            backdropUrl = firstUrl(info["backdrop_path"]),
            trailerKey = trailer(info.string("youtube_trailer")),
            tmdbId = info.long("tmdb")?.takeIf { it > 0 } ?: info.long("tmdb_id")?.takeIf { it > 0 },
            episodes = episodes,
        )
    }

    private fun genres(text: String?): List<String> = text.orEmpty().split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** "01:45:30" → seconds. */
    private fun hms(text: String?): Int? {
        val parts = text?.trim()?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
        return parts.fold(0) { acc, p -> acc * 60 + p }.takeIf { it > 0 && parts.size in 2..3 }
    }

    private fun firstUrl(el: JsonElement?): String? = when (el) {
        is JsonArray -> el.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
        is JsonPrimitive -> el.content.takeIf { it.isNotBlank() && el !is JsonNull }
        else -> null
    }

    /** Providers send a bare YouTube ID or a whole URL; keep the ID. */
    private fun trailer(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Regex("""(?:v=|youtu\.be/|embed/)([A-Za-z0-9_-]{6,})""").find(t)?.groupValues?.get(1) ?: t.takeIf { Regex("[A-Za-z0-9_-]{6,}").matches(it) }
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

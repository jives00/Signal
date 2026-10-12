package com.signal.sync

import com.signal.model.EpgKind
import com.signal.model.EpgSource
import com.signal.model.LiveFormat
import com.signal.model.Source
import com.signal.model.SourceKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Building and reading an item's flat fields. */
fun fields(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is JsonElement -> v
            else -> error("Unsupported field value for $k")
        }
    },
)

fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }

/**
 * Sources and EPG sources as items. Shared, because the companion builds the guide from them too.
 * Only what the user set syncs: refresh results (last refresh, errors, expiry) stay on each device.
 */
object SyncFields {
    const val PASSWORD = "password"

    fun source(s: Source, createdAt: Long): JsonObject = fields(
        "name" to s.name,
        "kind" to s.kind.name,
        "url" to s.url,
        "username" to s.username,
        PASSWORD to s.password,
        "user_agent" to s.userAgent,
        "referrer" to s.referrer,
        "enabled" to s.enabled,
        "live_format" to s.liveFormat.name,
        "connection_limit" to s.connectionLimit,
        "created_at" to createdAt,
    )

    fun source(id: String, f: JsonObject): Source = Source(
        id = id,
        name = f.str("name") ?: "Source",
        kind = f.str("kind")?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() } ?: SourceKind.M3U,
        url = f.str("url").orEmpty(),
        username = f.str("username").orEmpty(),
        password = f.str(PASSWORD).orEmpty(),
        userAgent = f.str("user_agent"),
        referrer = f.str("referrer"),
        enabled = f.bool("enabled") ?: true,
        liveFormat = f.str("live_format")?.let { runCatching { LiveFormat.valueOf(it) }.getOrNull() } ?: LiveFormat.TS,
        connectionLimit = f.long("connection_limit")?.toInt() ?: 2,
    )

    fun epgSource(s: EpgSource, createdAt: Long): JsonObject = fields(
        "name" to s.name,
        "kind" to s.kind.name,
        "url" to s.url,
        "source_id" to s.sourceId,
        "enabled" to s.enabled,
        "time_shift_minutes" to s.timeShiftMinutes,
        "position" to s.position,
        "created_at" to createdAt,
    )

    fun epgSource(id: String, f: JsonObject): EpgSource = EpgSource(
        id = id,
        name = f.str("name") ?: "Guide",
        kind = f.str("kind")?.let { runCatching { EpgKind.valueOf(it) }.getOrNull() } ?: EpgKind.XMLTV,
        url = f.str("url").orEmpty(),
        sourceId = f.str("source_id"),
        enabled = f.bool("enabled") ?: true,
        timeShiftMinutes = f.long("time_shift_minutes")?.toInt() ?: 0,
        position = f.str("position").orEmpty(),
    )
}

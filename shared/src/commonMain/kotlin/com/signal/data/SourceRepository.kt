package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.db.user.UserDatabase
import com.signal.demo.DemoData
import com.signal.demo.DemoVod
import com.signal.model.VodCategory
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.match.CharacterCleanup
import com.signal.model.CredentialMasking
import com.signal.model.LiveFormat
import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.parse.M3uParser
import com.signal.parse.XtreamParser
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** Sources and their cached channel lists (PLAN Â§6). Credentials are encrypted at rest (PLAN Â§5.7). */
class SourceRepository(
    private val db: UserDatabase,
    private val http: HttpClient,
    private val secrets: SecretBox,
    private val io: CoroutineDispatcher,
    private val log: Log,
) {
    fun sources(): Flow<List<Source>> = db.sourceQueries.all().asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<Source> = withContext(io) { db.sourceQueries.all().executeAsList().map { it.toModel() } }

    suspend fun byId(id: String): Source? = withContext(io) { db.sourceQueries.byId(id).executeAsOneOrNull()?.toModel() }

    suspend fun count(): Long = withContext(io) { db.sourceQueries.count().executeAsOne() }

    suspend fun save(source: Source) = withContext(io) {
        val existing = db.sourceQueries.byId(source.id).executeAsOneOrNull()
        db.sourceQueries.upsert(
            com.signal.db.user.Source(
                id = source.id,
                name = source.name,
                kind = source.kind.name,
                url = source.url.trim(),
                username = source.username.trim(),
                password_enc = if (source.password.isEmpty()) "" else secrets.encrypt(source.password),
                user_agent = source.userAgent,
                referrer = source.referrer,
                enabled = if (source.enabled) 1 else 0,
                live_format = source.liveFormat.name,
                connection_limit = source.connectionLimit.toLong(),
                expires_at = existing?.expires_at,
                last_refreshed_at = existing?.last_refreshed_at ?: 0,
                last_error = existing?.last_error,
                created_at = existing?.created_at ?: Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun delete(id: String) = withContext(io) {
        db.transaction {
            db.vodQueries.deleteAllForSource(id)
            db.streamQueries.deleteForSource(id)
            db.sourceQueries.delete(id)
        }
    }

    /** Re-downloads and re-parses a source's channel list (PLAN Â§2.1 "Source refresh"). */
    suspend fun refresh(id: String): Result<Int> = withContext(io) {
        val source = byId(id) ?: return@withContext Result.failure(IllegalArgumentException("No source $id"))
        runCatching {
            val loaded = load(source)
            db.transaction {
                db.streamQueries.deleteForSource(source.id)
                val removeEmoji = true
                loaded.streams.forEach { s -> db.streamQueries.insert(s.toRow(removeEmoji)) }
                loaded.vod?.let { vod ->
                    db.vodQueries.deleteAllForSource(source.id)
                    vod.categories.forEach { db.vodQueries.insertCategory(it.toRow()) }
                    vod.items.forEach { db.vodQueries.insertItem(it.toRow()) }
                }
                db.sourceQueries.markRefreshed(
                    Clock.System.now().toEpochMilliseconds(),
                    loaded.expiresAt,
                    (loaded.connectionLimit ?: source.connectionLimit).toLong(),
                    source.id,
                )
            }
            log.info("Refreshed ${source.name}: ${loaded.streams.size} streams" + (loaded.vod?.let { v -> ", ${v.items.count { it.kind == VodKind.MOVIE }} movies, ${v.items.count { it.kind == VodKind.SERIES }} series" } ?: ""))
            loaded.streams.size
        }.onFailure { e ->
            log.warn("Refresh of ${source.name} failed: ${e.message}")
            db.sourceQueries.markFailed(e.message ?: e::class.simpleName, source.id)
        }
    }

    /** Settings â Sources â Test connection: logs in or downloads, without saving anything. */
    suspend fun test(source: Source): Result<String> = withContext(io) {
        runCatching {
            val loaded = load(source)
            buildString {
                append("${loaded.streams.size} channels")
                loaded.connectionLimit?.let { append(" Â· $it connections") }
            }
        }
    }

    private class Loaded(
        val streams: List<ProviderStream>,
        val expiresAt: Long? = null,
        val connectionLimit: Int? = null,
        /** Movies and series (PLAN §4.3.6): null when the source has none or they failed, which keeps the old ones. */
        val vod: Vod? = null,
    )

    private class Vod(val categories: List<VodCategory>, val items: List<VodItem>)

    private suspend fun load(source: Source): Loaded = when (source.kind) {
        SourceKind.DEMO -> Loaded(
            DemoData.streams(source.id),
            vod = Vod(
                DemoVod.categories(source.id, VodKind.MOVIE) + DemoVod.categories(source.id, VodKind.SERIES),
                DemoVod.movies(source.id) + DemoVod.series(source.id),
            ),
        )
        SourceKind.M3U -> Loaded(loadM3u(source))
        SourceKind.XTREAM -> loadXtream(source)
    }

    private suspend fun loadM3u(source: Source): List<ProviderStream> {
        val response = http.get(source.url) { source.headers.forEach { (k, v) -> header(k, v) } }
        response.ensureOk(source)
        // Streamed line by line: a 50k-entry list is never held as one string (PLAN Â§6).
        val parser = M3uParser(source)
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            parser.line(line)
        }
        return parser.result()
    }

    private suspend fun loadXtream(source: Source): Loaded {
        val base = source.url.trimEnd('/') + "/player_api.php"
        suspend fun call(action: String?): String {
            val response = http.get(base) {
                parameter("username", source.username)
                parameter("password", source.password)
                if (action != null) parameter("action", action)
                source.headers.forEach { (k, v) -> header(k, v) }
            }
            response.ensureOk(source)
            return response.bodyAsText()
        }
        val account = XtreamParser.accountInfo(call(null))
        if (!account.authenticated) error("Login failed: check the username and password")
        val categories = XtreamParser.categories(call("get_live_categories"))
        val streams = XtreamParser.liveStreams(source, call("get_live_streams"), categories)
        // Movies and series (PLAN §4.3.6–4.3.7). Not every provider has them, and a failure here
        // mustn't fail the live channels: it keeps what was cached before.
        val vod = runCatching {
            val movieCats = XtreamParser.categories(call("get_vod_categories"))
            val seriesCats = XtreamParser.categories(call("get_series_categories"))
            val movies = XtreamParser.vodStreams(source, call("get_vod_streams"))
            val series = XtreamParser.series(source, call("get_series"))
            fun cats(m: Map<String, String>, kind: VodKind) = m.entries.mapIndexed { i, (id, name) -> VodCategory(source.id, kind, id, name, i) }
            Vod(cats(movieCats, VodKind.MOVIE) + cats(seriesCats, VodKind.SERIES), movies + series)
        }.onFailure { log.warn("Movies and series from ${source.name} failed: ${it.message}") }.getOrNull()
        return Loaded(streams, account.expiresAt, account.maxConnections, vod)
    }

    private fun HttpResponse.ensureOk(source: Source) {
        if (!status.isSuccess()) {
            // The URL is never logged whole: it can embed credentials (PLAN Â§1).
            error("HTTP ${status.value} from ${CredentialMasking.forLog(call.request.url.toString(), source)}")
        }
    }

    private fun com.signal.db.user.Source.toModel() = Source(
        id = id,
        name = name,
        kind = runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.M3U),
        url = url,
        username = username,
        password = if (password_enc.isEmpty()) "" else runCatching { secrets.decrypt(password_enc) }.getOrDefault(""),
        userAgent = user_agent,
        referrer = referrer,
        enabled = enabled == 1L,
        liveFormat = runCatching { LiveFormat.valueOf(live_format) }.getOrDefault(LiveFormat.TS),
        connectionLimit = connection_limit.toInt(),
        lastRefreshedAt = last_refreshed_at,
        lastError = last_error,
    )

    private fun VodCategory.toRow() = com.signal.db.user.Vod_category(sourceId, kind.name, categoryId, CharacterCleanup.clean(name, true).ifEmpty { name }, position.toLong())

    private fun VodItem.toRow() = com.signal.db.user.Vod_item(
        id, sourceId, kind.name, providerId, name, categoryId, posterUrl, year?.toLong(), rating, addedAt, tmdbId, containerExt, plot, url, position.toLong(),
    )

    private fun ProviderStream.toRow(removeEmoji: Boolean) = com.signal.db.user.Stream(
        id = id,
        source_id = sourceId,
        provider_id = providerId,
        raw_name = rawName,
        display_name = CharacterCleanup.clean(rawName, removeEmoji).ifEmpty { rawName },
        group_name = CharacterCleanup.clean(groupName, removeEmoji).ifEmpty { groupName },
        logo_url = logoUrl,
        tvg_id = tvgId,
        provider_number = providerNumber?.toLong(),
        position = position.toLong(),
        url = url,
        headers = if (headers.isEmpty()) null else Json.encodeToString(headers),
    )
}
